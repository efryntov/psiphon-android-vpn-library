package ca.psiphon.library

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import ca.psiphon.Tun2SocksJniLoader
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The PsiphonVpnManager class manages the VPN interface and tun2socks library. It creates the VPN
 * interface, starts tun2socks to route traffic through the VPN interface, and stops tun2socks.
 * The class is a singleton and should be accessed via the getInstance() method.
 */
class PsiphonVpnManager private constructor() {

    companion object {
        val TAG = PsiphonVpnManager::class.java.simpleName
        private const val VPN_INTERFACE_MTU = 1500
        private const val VPN_INTERFACE_IPV4_NETMASK = "255.255.255.0"
        private const val UDPGW_SERVER_PORT = 7300

        // The underlying tun2socks library has global state, so we need to ensure that only one
        // instance of PsiphonVpnManager is created and used at a time
        @Volatile
        private var INSTANCE: PsiphonVpnManager? = null

        fun getInstance(): PsiphonVpnManager {
            return INSTANCE ?: synchronized(PsiphonVpnManager::class.java) {
                INSTANCE ?: PsiphonVpnManager().also { INSTANCE = it }
            }
        }

        // Log messages from tun2socks, called from native tun2socks code
        @JvmStatic
        fun logTun2Socks(level: String, channel: String, msg: String) {
            val logMsg = "tun2socks: $level($channel): $msg"

            // These are the levels as defined in the native code
            // static char *level_names[] = { NULL, "ERROR", "WARNING", "NOTICE", "INFO", "DEBUG" };
            when (level) {
                "ERROR" -> Log.d(TAG, logMsg)
                "WARNING" -> Log.d(TAG, logMsg)
                "NOTICE" -> Log.d(TAG, logMsg)
                "INFO" -> Log.d(TAG, logMsg)
                "DEBUG" -> Log.d(TAG, logMsg)
                else -> Log.d(TAG, logMsg)
            }
        }

        // Initialize the tun2socks logger with the class name and method name
        // This is called once when the class is loaded
        // The logTun2Socks method is called from the native tun2socks code to log messages
        init {
            Tun2SocksJniLoader.initializeLogger(PsiphonVpnManager::class.java.name, "logTun2Socks")
        }
    }

    private var privateAddress: PrivateAddress? = null
    private val tunFd = AtomicReference<ParcelFileDescriptor?>()
    private val isRoutingThroughTunnel = AtomicBoolean(false)
    private var tun2SocksThread: Thread? = null

    // Helper class to pick and store a private address for the VPN interface
    private data class PrivateAddress(
        val ipAddress: String,
        val subnet: String,
        val prefixLength: Int,
        val router: String
    )

    @Throws(IllegalStateException::class)
    private fun selectPrivateAddress(): PrivateAddress {
        // Select one of 10.0.0.1, 172.16.0.1, 192.168.0.1, or 169.254.1.1 depending on
        // which private address range isn't in use.

        val candidates = mutableMapOf<String, PrivateAddress>().apply {
            put("10", PrivateAddress("10.0.0.1", "10.0.0.0", 8, "10.0.0.2"))
            put("172", PrivateAddress("172.16.0.1", "172.16.0.0", 12, "172.16.0.2"))
            put("192", PrivateAddress("192.168.0.1", "192.168.0.0", 16, "192.168.0.2"))
            put("169", PrivateAddress("169.254.1.1", "169.254.1.0", 24, "169.254.1.2"))
        }

        val netInterfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: SocketException) {
            throw IllegalStateException("Error getting network interfaces: $e")
        } ?: throw IllegalStateException("No network interfaces found")

        netInterfaces.asSequence().forEach { netInterface ->
            netInterface.inetAddresses.asSequence().forEach { inetAddress ->
                if (inetAddress is Inet4Address) {
                    val ipAddress = inetAddress.hostAddress ?: return@forEach

                    when {
                        ipAddress.startsWith("10.") -> candidates.remove("10")
                        ipAddress.length >= 6 &&
                                ipAddress.substring(0, 6) >= "172.16" &&
                                ipAddress.substring(0, 6) <= "172.31" -> candidates.remove("172")
                        ipAddress.startsWith("192.168") -> candidates.remove("192")
                    }
                }
            }
        }

        return candidates.values.firstOrNull()
            ?: throw IllegalStateException("No private address available")
    }

    // Pick a private address and create the VPN interface
    @Synchronized
    @Throws(IllegalStateException::class)
    fun vpnEstablish(vpnServiceBuilder: VpnService.Builder) {
        privateAddress = selectPrivateAddress()

        val previousLocale = Locale.getDefault()

        try {
            // Workaround for https://code.google.com/p/android/issues/detail?id=61096
            Locale.setDefault(Locale("en"))

            val dnsResolver = privateAddress!!.router

            val tunFd = vpnServiceBuilder
                .setMtu(VPN_INTERFACE_MTU)
                .addAddress(privateAddress!!.ipAddress, privateAddress!!.prefixLength)
                .addRoute("0.0.0.0", 0)
                .addRoute(privateAddress!!.subnet, privateAddress!!.prefixLength)
                .addDnsServer(dnsResolver)
                .establish()
                ?: throw IllegalStateException("Application is no longer prepared or was revoked")

            this.tunFd.set(tunFd)
            isRoutingThroughTunnel.set(false)
        } finally {
            // Restore the original locale
            Locale.setDefault(previousLocale)
        }
    }

    // Stop tun2socks if running and close tun FD
    @Synchronized
    fun vpnTeardown() {
        stopRouteThroughTunnel()
        tunFd.getAndSet(null)?.let { fd ->
            try {
                fd.close()
            } catch (ignored: IOException) {
                // Ignore close errors
            }
        }
        isRoutingThroughTunnel.set(false)
    }

    // Start routing traffic via tunnel by starting tun2socks if it is not running already
    @Synchronized
    fun routeThroughTunnel(socksProxyPort: Int) {
        if (!isRoutingThroughTunnel.compareAndSet(false, true)) {
            return
        }

        val tunFd = this.tunFd.get() ?: return

        if (socksProxyPort <= 0) {
            Log.e(TAG, "routeThroughTunnel: socks proxy port is not set")
            return
        }

        val socksServerAddress = "127.0.0.1:$socksProxyPort"
        val udpgwServerAddress = "127.0.0.1:$UDPGW_SERVER_PORT"

        // We may call routeThroughTunnel and stopRouteThroughTunnel more than once within the same
        // VPN session. Since stopTun2Socks() closes the FD passed to startTun2Socks(), we will use a
        // dup of the original tun FD and close the original only when we call vpnTeardown().
        //
        // Note that ParcelFileDescriptor.dup() may throw an IOException.
        try {
            startTun2Socks(
                tunFd.dup(),
                VPN_INTERFACE_MTU,
                privateAddress!!.router,
                VPN_INTERFACE_IPV4_NETMASK,
                socksServerAddress,
                udpgwServerAddress,
                true
            )
            Log.d(TAG, "Routing through tunnel")
        } catch (e: IOException) {
            Log.e(TAG, "routeThroughTunnel: error duplicating tun FD: $e")
        }
    }

    // Stop routing traffic via tunnel by stopping tun2socks if currently routing through tunnel
    @Synchronized
    fun stopRouteThroughTunnel() {
        if (isRoutingThroughTunnel.compareAndSet(true, false)) {
            stopTun2Socks()
        }
    }

    // Tun2Socks APIs
    private fun startTun2Socks(
        vpnInterfaceFileDescriptor: ParcelFileDescriptor,
        vpnInterfaceMTU: Int,
        vpnIpv4Address: String,
        vpnIpv4NetMask: String,
        socksServerAddress: String,
        udpgwServerAddress: String,
        udpgwTransparentDNS: Boolean
    ) {
        if (tun2SocksThread != null) {
            return
        }

        tun2SocksThread = Thread {
            Tun2SocksJniLoader.runTun2Socks(
                vpnInterfaceFileDescriptor.detachFd(),
                vpnInterfaceMTU,
                vpnIpv4Address,
                vpnIpv4NetMask,
                null, // IPv4 only routing
                socksServerAddress,
                udpgwServerAddress,
                if (udpgwTransparentDNS) 1 else 0
            )
        }
        tun2SocksThread!!.start()
        Log.d(TAG, "tun2socks started")
    }

    private fun stopTun2Socks() {
        tun2SocksThread?.let { thread ->
            try {
                Tun2SocksJniLoader.terminateTun2Socks()
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            tun2SocksThread = null
            Log.d(TAG, "tun2socks stopped")
        }
    }
}