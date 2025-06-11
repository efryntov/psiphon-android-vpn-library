package ca.psiphon.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ca.psiphon.PsiphonTunnel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import androidx.core.content.edit
import kotlinx.coroutines.suspendCancellableCoroutine

class PsiphonVpnService : VpnService(), PsiphonTunnel.HostService {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tunnelTaskState = AtomicReference(TunnelTaskState.STOPPED)
    private val isInForeground = AtomicReference(false)

    private val currentPsiphonState = AtomicReference(PsiphonState())

    private val clients = ConcurrentHashMap<IBinder, IPsiphonClientCallback>()

    private val psiphonTunnel by lazy { PsiphonTunnel.newPsiphonTunnel(this) }

    private var tunnelJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val vpnManager = PsiphonVpnManager.getInstance()

    private val binder = object : IPsiphonService.Stub() {
        override fun registerClient(client: IPsiphonClientCallback?) {
            client?.let {
                val clientBinder = it.asBinder()

                if (!clients.containsKey(clientBinder)) {
                    clients[clientBinder] = it

                    // Send complete current state to new client right away
                    val state = currentPsiphonState.get()
                    notifyClient(it) { callback ->
                        callback.onStateUpdated(state.toBundle())
                    }

                    Log.d(TAG, "Client registered, sent complete state")
                } else {
                    Log.d(TAG, "Client already registered, ignoring duplicate registration")
                }
            }
        }

        override fun unregisterClient(client: IPsiphonClientCallback?) {
            client?.let {
                clients.remove(it.asBinder())
                Log.d(TAG, "Client unregistered")
            }
        }
    }

    // Tunnel state enum
    private enum class TunnelTaskState {
        STOPPED, STARTING, RUNNING, STOPPING
    }

    // Helper methods for resource resolution
    private fun getStringResource(resourceName: String, vararg formatArgs: Any): String {
        val resId = resources.getIdentifier(resourceName, "string", packageName)
        if (resId == 0) {
            throw IllegalStateException("String resource '$resourceName' not found.")
        }
        return if (formatArgs.isNotEmpty()) {
            getString(resId, *formatArgs)
        } else {
            getString(resId)
        }
    }

    private fun getDrawableResource(resourceName: String): Int {
        val resId = resources.getIdentifier(resourceName, "drawable", packageName)
        if (resId == 0) {
            throw IllegalStateException("Drawable resource '$resourceName' not found.")
        }
        return resId
    }

    private fun getRawResourceId(resourceName: String): Int {
        val resId = resources.getIdentifier(resourceName, "raw", packageName)
        if (resId == 0) {
            throw IllegalStateException("Raw resource '$resourceName' not found. Add $resourceName to your res/raw.")
        }
        return resId
    }

    private fun getRawResourceContent(resourceName: String): String {
        val resId = getRawResourceId(resourceName)
        return Utils.readRawResourceFileAsString(this, resId)
    }

    override fun onCreate() {

        Log.d(TAG, "PsiphonVpnService created")
        super.onCreate()
        setupNotificationChannel()

        initializeAvailableEgressRegions()
        // TODO: initialize logging or other resources if needed
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) {
            stopForegroundAndService()
            return START_NOT_STICKY
        }

        synchronized(this) {
            // Handle foreground promotion requirement
            if (isInForeground.compareAndSet(false, true)) {
                val notification = getNotificationForAction(intent.action!!)
                startForegroundWithNotification(notification)
            }

            return when (intent.action) {
                INTENT_ACTION_STOP_PSIPHON -> handleStopAction()
                INTENT_ACTION_START_PSIPHON -> handleStartAction(intent)
                INTENT_ACTION_UPDATE_PSIPHON_PARAMETERS -> handleParamsChangedAction(intent)
                else -> {
                    Log.d(TAG, "Unknown action: ${intent.action}")
                    stopForegroundAndService()
                    START_NOT_STICKY
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != null) {
            // Need to use super class behavior for VPN service interface:
            // http://developer.android.com/reference/android/net/VpnService.html#onBind%28android.content.Intent%29
            if (SERVICE_INTERFACE == intent.action) {
                return super.onBind(intent)
            }
        }
        return binder
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(TAG, "VPN revoked by user")

        if (tunnelTaskState.get() != TunnelTaskState.STOPPED &&
            tunnelTaskState.get() != TunnelTaskState.STOPPING) {
            Log.d(TAG, "VPN revoked, stopping tunnel")
            updatePsiphonState { currentState ->
                PsiphonState.setError(currentState, ErrorCode.VPN_REVOKED)
            }
            showErrorNotification(getStringResource("psiphon_notification_error_vpn_revoked_by_user"))
            stopTunnel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelJob?.cancel("Service destroyed")
        serviceScope.cancel()
        isInForeground.set(false)
        cancelNotification(NOTIFICATION_ID_TUNNEL_STATE)
    }

    // ============= Command Handlers =============

    private fun handleStopAction(): Int {
        Log.d(TAG, "Stop action received")
        when (tunnelTaskState.get()) {
            TunnelTaskState.RUNNING, TunnelTaskState.STARTING -> {
                Utils.setServiceRunningFlag(this, false)
                stopTunnel()
            }

            TunnelTaskState.STOPPING -> {
                Log.d(TAG, "Already stopping")
            }

            TunnelTaskState.STOPPED -> {
                stopForegroundAndService()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStartAction(intent: Intent): Int {
        return when (tunnelTaskState.get()) {
            TunnelTaskState.STOPPED -> {
                Log.d(TAG, "Starting tunnel")

                // Get params from intent, or load last stored params if null
                val params = PsiphonServiceParameters.fromIntent(intent)
                    ?: PsiphonServiceParameters.loadStored(applicationContext)

                if (params == null) {
                    Log.d(TAG, "No dynamic parameters available")
                }

                // Store the parameters (whether from intent or loaded)
                params?.store(applicationContext)
                startTunnel()
                START_REDELIVER_INTENT
            }

            TunnelTaskState.RUNNING -> {
                Log.d(TAG, "Tunnel already running, ignoring start request")
                START_NOT_STICKY
            }

            TunnelTaskState.STARTING, TunnelTaskState.STOPPING -> {
                Log.d(TAG, "Tunnel in transition, ignoring start request")
                START_NOT_STICKY
            }
        }
    }

    private fun handleParamsChangedAction(intent: Intent): Int {
        val params = PsiphonServiceParameters.fromIntent(intent)
            ?: throw IllegalStateException("Invalid parameters")

        val paramsUpdated = params.store(applicationContext)
        Log.d(TAG, if (paramsUpdated) "Parameters updated" else "No parameter changes")

        when (tunnelTaskState.get()) {
            TunnelTaskState.STOPPED -> {
                stopForegroundAndService()
            }

            TunnelTaskState.RUNNING -> {
                if (paramsUpdated) {
                    Log.d(TAG, "Restarting tunnel due to parameter changes")
                    restartTunnel()
                }
            }

            else -> { /* ignore transitional states */ }
        }
        return START_NOT_STICKY
    }

    // ============= Tunnel Lifecycle =============

    private fun startTunnel() {
        if (!tunnelTaskState.compareAndSet(TunnelTaskState.STOPPED, TunnelTaskState.STARTING)) {
            Log.d(TAG, "Cannot start tunnel from current state")
            return
        }

        Log.d(TAG, "Starting tunnel")
        Utils.setServiceRunningFlag(this, true)
        cancelErrorNotifications()

        tunnelJob = serviceScope.launch(Dispatchers.IO) {
            try {
                updatePsiphonState {
                    it.clearError().copy(tunnelEvent = TunnelEvent.CONNECTING)
                }

                val embeddedServers = getRawResourceContent("psiphon_embedded_servers")
                val builder = Builder().setSession(getStringResource("app_name"))

                if (Build.VERSION.SDK_INT >= 21) {
                    try {
                        builder.addDisallowedApplication("ca.psiphon.conduit")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.d(TAG, "Disallowed application 'ca.psiphon.conduit' not found")
                    }
                    try {
                        builder.addDisallowedApplication("network.ryve.app")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.d(TAG, "Disallowed application 'network.ryve.app' not found")
                    }
                }

                vpnManager.vpnEstablish(builder)
                psiphonTunnel.setVpnMode(true)

                psiphonTunnel.startTunneling(embeddedServers)
                tunnelTaskState.set(TunnelTaskState.RUNNING)

                Log.d(TAG, "Tunnel started, suspending until cancelled...")

                // Suspend indefinitely until cancelled - this replaces your old latch
                suspendCancellableCoroutine<Nothing> { continuation ->
                    // The coroutine will stay suspended here until cancelled
                    continuation.invokeOnCancellation {
                        Log.d(TAG, "Tunnel coroutine cancelled, proceeding to cleanup")
                    }
                }

            } catch (e: PsiphonTunnel.Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
                updatePsiphonState { currentState ->
                    PsiphonState.setError(currentState, ErrorCode.TUNNEL_START_FAILED, e.message)
                }
                showErrorNotification(getStringResource("psiphon_notification_error_failed_to_start_tunnel"))
            } catch (e: CancellationException) {
                Log.d(TAG, "Tunnel cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected tunnel error", e)
                updatePsiphonState { currentState ->
                    PsiphonState.setError(currentState, ErrorCode.UNEXPECTED_ERROR, e.message)
                }
                showErrorNotification(getStringResource("psiphon_notification_error_failed_to_start_tunnel"))
            } finally {
                // This finally block runs when the coroutine is cancelled
                Log.d(TAG, "Cleaning up tunnel")
                vpnManager.vpnTeardown()
                psiphonTunnel.stop() // This stops the internal tunnel threads

                tunnelTaskState.set(TunnelTaskState.STOPPED)
                // Reset, but preserve error information and available regions
                updatePsiphonState { currentState ->
                    PsiphonState(
                        tunnelEvent = TunnelEvent.STOPPED,
                        availableEgressRegions = currentState.availableEgressRegions,
                        // Preserve error information
                        lastErrorCode = currentState.lastErrorCode,
                        lastErrorData = currentState.lastErrorData,
                        lastErrorTimestamp = currentState.lastErrorTimestamp
                    )
                }
                tunnelJob = null
                stopForegroundAndService()
            }
        }
    }

    private fun stopTunnel() {
        if (!(tunnelTaskState.compareAndSet(TunnelTaskState.RUNNING, TunnelTaskState.STOPPING) ||
                    tunnelTaskState.compareAndSet(TunnelTaskState.STARTING, TunnelTaskState.STOPPING))) {
            Log.d(TAG, "Cannot stop tunnel from current state: ${tunnelTaskState.get()}")
            return
        }

        Log.d(TAG, "Stopping tunnel")

        // Cancel the coroutine - this will trigger the finally block
        mainHandler.post {
            tunnelJob?.cancel("Stop requested")
        }
    }

    private fun restartTunnel() {
        serviceScope.launch {
            try {
                psiphonTunnel.restartPsiphon()
            } catch (e: PsiphonTunnel.Exception) {
                Log.e(TAG, "Failed to restart tunnel", e)
                stopTunnel()
                updatePsiphonState { currentState ->
                    PsiphonState.setError(currentState, ErrorCode.TUNNEL_RESTART_FAILED, e.message)
                }
                showErrorNotification(getStringResource("psiphon_notification_error_failed_to_restart_tunnel"))
            }
        }
    }

    // ============= PsiphonTunnel.HostService Implementation =============

    override fun getContext(): Context = this

    override fun getPsiphonConfig(): String {
        // TODO: apply dynamic parameters to config if needed, like egress region
        // VPN exclude list, etc.
        val configString = getRawResourceContent("psiphon_config")
        val config = JSONObject(configString)

        // Configure Psiphon settings
        config.apply {
            put("DisableTunnels", false)
            put("DisableLocalHTTPProxy", false);
            put("DisableLocalSocksProxy", false);
            put("EmitBytesTransferred", true)

            // Add client version
            val packageInfo = packageManager.getPackageInfo(packageName, 0)

            val versionCodeString = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }

            put("ClientVersion", versionCodeString)

            // Set data directory
            val dataDir = Utils.dataRootDirectory(this@PsiphonVpnService)
            put("DataRootDirectory", dataDir.absolutePath)
        }

        return config.toString()
    }

    override fun bindToDevice(fileDescriptor: Long) {
        if (!protect(fileDescriptor.toInt())) {
            throw RuntimeException("VpnService.protect() failed")
        }
    }

    // Unified method to update state and notify all clients with complete state
    private fun updatePsiphonState(updateFn: (PsiphonState) -> PsiphonState) {
        mainHandler.post {
            val newState = updateFn(currentPsiphonState.get())
            currentPsiphonState.set(newState)
            notifyAllClients { client -> client.onStateUpdated(newState.toBundle()) }
        }
    }

    override fun onConnecting() {
        // Only update state if we are in the RUNNING state
        if (tunnelTaskState.get() == TunnelTaskState.RUNNING) {
            updatePsiphonState { it.copy(tunnelEvent = TunnelEvent.CONNECTING) }
            // Also update foreground notification to indicate connecting state
            updateForegroundNotification(
                getDrawableResource("ic_psiphon_connecting"),
                getStringResource("psiphon_notification_connecting")
            )
        }
    }

    override fun onConnected() {
        updatePsiphonState {
            it.clearError().copy(tunnelEvent = TunnelEvent.CONNECTED)
        }
        // Also update foreground notification to indicate connected state
        updateForegroundNotification(
            getDrawableResource("ic_psiphon_connected"),
            getStringResource("psiphon_notification_connected")
        )

        // route through tunnel once connected
        currentPsiphonState.get().socksProxyPort?.let { port ->
            vpnManager.routeThroughTunnel(port)
        } ?: run {
            Log.e(TAG, "Cannot route through tunnel: SOCKS proxy port is not set")
            stopTunnel()
            updatePsiphonState { currentState ->
                PsiphonState.setError(currentState, ErrorCode.SOCKS_PORT_NOT_SET)
            }

            showErrorNotification(getStringResource("psiphon_notification_error_socks_proxy_port_is_not_set"))
        }
    }

    override fun onExiting() {
        updatePsiphonState { it.copy(tunnelEvent = TunnelEvent.EXITING) }
    }

    override fun onStartedWaitingForNetworkConnectivity() {
        updatePsiphonState { it.copy(isWaitingForNetwork = true) }
        // Update foreground notification to indicate waiting for network
        updateForegroundNotification(
            getDrawableResource("ic_psiphon_waiting_for_network"),
            getStringResource("psiphon_notification_waiting_for_network")
        )
    }

    override fun onStoppedWaitingForNetworkConnectivity() {
        updatePsiphonState { it.copy(isWaitingForNetwork = false) }
        // Update foreground notification to indicate no longer waiting for network, switch to connecting state
        updateForegroundNotification(
            getDrawableResource("ic_psiphon_connecting"),
            getStringResource("psiphon_notification_connecting")
        )
    }

    override fun onClientRegion(region: String) {
        updatePsiphonState { it.copy(clientRegion = region) }
    }

    override fun onConnectedServerRegion(region: String) {
        updatePsiphonState { it.copy(connectedServerRegion = region) }
    }

    override fun onBytesTransferred(sent: Long, received: Long) {
        updatePsiphonState {
            it.copy(
                totalBytesSent = it.totalBytesSent + sent,
                totalBytesReceived = it.totalBytesReceived + received
            )
        }
    }

    override fun onApplicationParameters(parameters: Any) {
        if (parameters !is JSONObject) {
            Log.e(
                TAG,
                "Invalid parameter type. Expected JSONObject, got: ${parameters.javaClass.name}"
            )
            return
        }

        val params = parameters as JSONObject
        updatePsiphonState { it.copy(applicationParameters = params) }
    }

    override fun onTrafficRateLimits(upstreamBytesPerSecond: Long, downstreamBytesPerSecond: Long) {
        updatePsiphonState {
            it.copy(
                upstreamRateLimit = upstreamBytesPerSecond,
                downstreamRateLimit = downstreamBytesPerSecond
            )
        }
    }

    override fun onAvailableEgressRegions(regions: List<String>) {
        // Store to preferences
        val prefs = getSharedPreferences("psiphon_state", Context.MODE_PRIVATE)
        prefs.edit { putStringSet("available_egress_regions", regions.toSet()) }

        updatePsiphonState { it.copy(availableEgressRegions = regions) }
    }

    override fun onSocksProxyPortInUse(port: Int) {
        Log.e(TAG, "SOCKS proxy port $port already in use")
        stopTunnel()
        updatePsiphonState { currentState ->
            PsiphonState.setError(currentState, ErrorCode.SOCKS_PORT_IN_USE, port.toString())
        }
        showErrorNotification(
            getStringResource("psiphon_notification_error_socks_proxy_port_already_in_use", port)
        )
    }

    override fun onHttpProxyPortInUse(port: Int) {
        Log.e(TAG, "HTTP proxy port $port already in use")
        stopTunnel()
        updatePsiphonState { currentState ->
            PsiphonState.setError(currentState, ErrorCode.HTTP_PORT_IN_USE, port.toString())
        }
        showErrorNotification(
            getStringResource("psiphon_notification_error_http_proxy_port_already_in_use", port)
        )
    }

    override fun onListeningSocksProxyPort(port: Int) {
        updatePsiphonState { it.copy(socksProxyPort = port) }
    }

    override fun onListeningHttpProxyPort(port: Int) {
        updatePsiphonState { it.copy(httpProxyPort = port) }
    }
    // ============= Notification Management =============

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager?.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getStringResource("app_name"),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getStringResource("psiphon_service_channel_description")
                }
                notificationManager?.createNotificationChannel(channel)
            }
        }
    }

    private fun getNotificationForAction(action: String): Notification {
        val (iconId, text) = when (action) {
            INTENT_ACTION_START_PSIPHON -> getDrawableResource("ic_psiphon_connecting") to getStringResource("psiphon_notification_connecting")
            INTENT_ACTION_UPDATE_PSIPHON_PARAMETERS -> getDrawableResource("ic_psiphon_processing_action") to getStringResource("psiphon_notification_updating_parameters")
            INTENT_ACTION_STOP_PSIPHON -> getDrawableResource("ic_psiphon_processing_action") to getStringResource("psiphon_notification_tunnel_stopping")
            else -> getDrawableResource("ic_psiphon_processing_action") to "Unknown action"
        }

        return buildNotification(iconId, text)
    }


    private fun buildNotification(iconId: Int, text: String): Notification {
        val stopIntent = Intent(this, PsiphonVpnService::class.java).apply {
            action = INTENT_ACTION_STOP_PSIPHON
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = NotificationCompat.Action.Builder(
            getDrawableResource("ic_psiphon_stop_service"),
            getStringResource("psiphon_service_stop_label_text"),
            stopPendingIntent
        ).build()

        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val mainPendingIntent = mainIntent?.let { intent ->
            PendingIntent.getActivity(
                this@PsiphonVpnService, 1, intent, PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(iconId)
            .setContentTitle(getStringResource("app_name"))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun updateForegroundNotification(iconId: Int, text: String) {
        mainHandler.post {
            val notification = buildNotification(iconId, text)
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID_TUNNEL_STATE, notification)
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= 34) { // API 34 = Android 14
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        isInForeground.set(true)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID_TUNNEL_STATE,
            notification,
            serviceType
        )
    }

    private fun stopForegroundAndService() {
        ServiceCompat.stopForeground(this@PsiphonVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isInForeground.set(false)
        stopSelf()
    }

    private fun showErrorNotification(message: String) {
        mainHandler.post {
            val mainIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val mainPendingIntent = mainIntent?.let { intent ->
                PendingIntent.getActivity(
                    this@PsiphonVpnService, 1, intent, PendingIntent.FLAG_IMMUTABLE
                )
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(getDrawableResource("ic_psiphon_error"))
                .setContentTitle(getStringResource("app_name"))
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainPendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager?.notify(NOTIFICATION_ID_ERROR, notification)
        }
    }

    private fun cancelNotification(notificationId: Int) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(notificationId)
    }

    private fun cancelErrorNotifications() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.apply {
            cancel(NOTIFICATION_ID_ERROR)
        }
    }

    // ============= Client Notification =============

    private fun notifyAllClients(action: (IPsiphonClientCallback) -> Unit) {
        val clientsCopy = clients.values.toList() // Copy to avoid concurrent modification
        clientsCopy.forEach { client ->
            notifyClient(client, action)
        }
    }

    private inline fun notifyClient(
        client: IPsiphonClientCallback,
        action: (IPsiphonClientCallback) -> Unit
    ) {
        try {
            action(client)
        } catch (e: RemoteException) {
            if (e is DeadObjectException) {
                clients.remove(client.asBinder())
            } else {
                Log.e(TAG, "Failed to notify client", e)
            }
        }
    }

    private fun initializeAvailableEgressRegions() {
        // Initialize egress regions from embedded servers if needed
        val prefs = getSharedPreferences("psiphon_state", MODE_PRIVATE)

        if (!prefs.contains("available_egress_regions")) {
            try {
                val embeddedServers = getRawResourceContent("psiphon_embedded_servers")
                val regions = Utils.egressRegionsFromEmbeddedServers(embeddedServers)
                prefs.edit { putStringSet("available_egress_regions", regions.toSet()) }
                Log.d(TAG, "Initialized ${regions.size} egress regions from embedded servers")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse egress regions from embedded data", e)
                // Save empty set to prevent future parsing attempts
                prefs.edit { putStringSet("available_egress_regions", emptySet()) }
            }
        }

        // Load saved regions into state
        val savedRegions = prefs.getStringSet("available_egress_regions", null)?.toList()
        if (savedRegions != null) {
            currentPsiphonState.set(PsiphonState(availableEgressRegions = savedRegions))
        }
    }

    companion object {
        // from java class name
        private val TAG = PsiphonVpnService::class.java.simpleName

        // Notification channel
        private const val NOTIFICATION_CHANNEL_ID = "PsiphonServiceChannel"
        // Notification IDs
        private const val NOTIFICATION_ID_TUNNEL_STATE = 1001
        private const val NOTIFICATION_ID_ERROR = 1002


        // Intent actions
        const val INTENT_ACTION_STOP_PSIPHON = "ca.psiphon.library.StopPsiphon"
        const val INTENT_ACTION_START_PSIPHON = "ca.psiphon.library.StartPsiphon"
        const val INTENT_ACTION_UPDATE_PSIPHON_PARAMETERS = "ca.psiphon.library.UpdatePsiphonParameters"
    }
}