package ca.psiphon.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

class PsiphonVpnHelper private constructor(
    activityRef: WeakReference<ComponentActivity>,
    private val applicationContext: Context,
    private val stateListener: PsiphonStateListener
) : DefaultLifecycleObserver {

    interface PsiphonStateListener {
        fun onStateUpdated(state: PsiphonState)
        fun onError(message: String, cause: Throwable? = null)
    }

    private val activityRef = AtomicReference(activityRef)
    private val psiphonComms: PsiphonComms
    private val pendingStartParams = AtomicReference<PsiphonServiceParameters?>()

    private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null

    private val internalStateListener = object : PsiphonComms.PsiphonStateListener {
        override fun onStateUpdated(state: PsiphonState) {
            stateListener.onStateUpdated(state)
        }
    }

    init {
        psiphonComms = PsiphonComms(internalStateListener)
    }

    companion object {
        private val TAG = PsiphonVpnHelper::class.java.simpleName

        fun create(
            activity: ComponentActivity,
            stateListener: PsiphonStateListener
        ): PsiphonVpnHelper {
            val helper = PsiphonVpnHelper(
                WeakReference(activity),
                activity.applicationContext,
                stateListener
            )

            // Register VPN permission launcher
            helper.vpnPermissionLauncher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                helper.handleVpnPermissionResult(result.resultCode)
            }

            // Observe activity lifecycle for automatic cleanup
            activity.lifecycle.addObserver(helper)

            return helper
        }

        fun hasServiceRunningFlag(context: Context): Boolean {
            return Utils.getServiceRunningFlag(context)
        }
    }

    fun startVpn(params: PsiphonServiceParameters? = null): Boolean? {
        val activity = getActivity() ?: run {
            stateListener.onError("Activity is no longer available")
            return null
        }

        return if (isVpnPermissionGranted()) {
            startVpnInternal(params)
            true
        } else {
            pendingStartParams.set(params)
            requestVpnPermission()
            false
        }
    }

    fun stopVpn() {
        try {
            // Just stop the VPN service - communication stays active
            PsiphonComms.stopPsiphon(applicationContext)
            pendingStartParams.set(null)
            Log.d(TAG, "VPN stop initiated")
        } catch (e: Exception) {
            stateListener.onError("Failed to stop VPN", e)
        }
    }

    fun updateParameters(params: PsiphonServiceParameters) {
        try {
            PsiphonComms.updatePsiphonParameters(applicationContext, params)
            Log.d(TAG, "Parameters updated")
        } catch (e: Exception) {
            stateListener.onError("Failed to update parameters", e)
        }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up PsiphonVpnHelper")

        // Stop communication but not the VPN service itself
        psiphonComms.stop(applicationContext)

        // Clear activity reference
        activityRef.set(WeakReference(null))

        // Clear pending params
        pendingStartParams.set(null)

        // Remove from activity lifecycle if still attached
        getActivity()?.lifecycle?.removeObserver(this)
    }

    // Private methods
    private fun isVpnPermissionGranted(): Boolean {
        val activity = getActivity() ?: return false
        return VpnService.prepare(activity) == null
    }

    private fun getActivity(): ComponentActivity? {
        return activityRef.get()?.get()
    }

    private fun requestVpnPermission() {
        val activity = getActivity() ?: run {
            stateListener.onError("Activity is no longer available")
            return
        }
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            Log.d(TAG, "Requesting VPN permission")
            vpnPermissionLauncher?.launch(intent) ?: run {
                stateListener.onError("Permission launcher not available")
            }
        } else {
            // Permission already granted
            val params = pendingStartParams.getAndSet(null)
            startVpnInternal(params)
        }
    }

    private fun handleVpnPermissionResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "VPN permission granted")
            val params = pendingStartParams.getAndSet(null)
            startVpnInternal(params)
        } else {
            Log.d(TAG, "VPN permission denied")
            pendingStartParams.set(null)
            stateListener.onError("VPN permission denied by user")
        }
    }

    private fun startVpnInternal(params: PsiphonServiceParameters?) {
        try {
            // Just start the VPN service - communication is handled separately
            PsiphonComms.startPsiphon(applicationContext, params)
            Log.d(TAG, "VPN start initiated")
        } catch (e: Exception) {
            stateListener.onError("Failed to start VPN", e)
        }
    }

    // Lifecycle methods

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        psiphonComms.start(applicationContext)
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        psiphonComms.stop(applicationContext)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        Log.d(TAG, "Activity destroyed, cleaning up")
        cleanup()
    }
}