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

sealed class VpnResult<out T> {
    data class Success<out T>(val value: T) : VpnResult<T>()
    data class Failure<out T>(val message: String) : VpnResult<T>()
}

class PsiphonVpnHelper private constructor(
    activityRef: WeakReference<ComponentActivity>,
    private val applicationContext: Context,
    private val stateListener: PsiphonStateListener
) : DefaultLifecycleObserver {

    interface PsiphonStateListener {
        fun onStateUpdated(state: PsiphonState)
    }

    private val activityRef = AtomicReference(activityRef)
    private val psiphonComms: PsiphonComms
    private val pendingStartParams = AtomicReference<PsiphonServiceParameters?>()

    @Volatile
    private var pendingStartCallback: ((VpnResult<String>) -> Unit)? = null

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

    fun startVpn(
        params: PsiphonServiceParameters? = null,
        callback: (VpnResult<String>) -> Unit
    ) {
        val activity = getActivity() ?: run {
            callback(VpnResult.Failure("Activity is no longer available"))
            return
        }

        if (isVpnPermissionGranted()) {
            callback(startVpnInternal(params))
        } else {
            requestVpnPermission(params, callback)
        }
    }

    fun stopVpn(callback: (VpnResult<String>) -> Unit) {
        try {
            // Just stop the VPN service - communication stays active
            PsiphonComms.stopPsiphon(applicationContext)
            Log.d(TAG, "VPN stop initiated")
            callback(VpnResult.Success("VPN stop initiated"))
        } catch (e: Exception) {
            callback(VpnResult.Failure("Failed to stop VPN: ${e.message ?: "Unknown error"}"))
        }
    }

    fun updateParameters(
        params: PsiphonServiceParameters,
        callback: (VpnResult<String>) -> Unit
    ) {
        try {
            PsiphonComms.updatePsiphonParameters(applicationContext, params)
            Log.d(TAG, "Parameters updated")
            callback(VpnResult.Success("Parameters updated"))
        } catch (e: Exception) {
            callback(VpnResult.Failure("Failed to update parameters: ${e.message ?: "Unknown error"}"))
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

        // Clear pending callback
        pendingStartCallback = null

        // Remove from activity lifecycle if still attached
        getActivity()?.lifecycle?.removeObserver(this)
    }

    // Private methods
    private fun isVpnPermissionGranted(): Boolean {
        val activity = getActivity() ?: return false
        return try {
            VpnService.prepare(activity) == null
        } catch (e: Exception) {
            false // If we can't check the permission, assume it's not granted
        }
    }

    private fun getActivity(): ComponentActivity? {
        return activityRef.get()?.get()
    }

    private fun requestVpnPermission(
        params: PsiphonServiceParameters?,
        callback: (VpnResult<String>) -> Unit) {
        val activity = getActivity() ?: run {
            callback(VpnResult.Failure("Activity is no longer available"))
            return
        }
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            // Store the callback and parameters until permission result is received
            pendingStartCallback = callback
            pendingStartParams.set(params)

            Log.d(TAG, "Requesting VPN permission")
            if (vpnPermissionLauncher == null) {
                callback(VpnResult.Failure("VPN permission launcher not initialized"))
                return
            }
            vpnPermissionLauncher!!.launch(intent)
        } else {
            // Permission already granted
            callback(startVpnInternal(params))
        }
    }

    private fun handleVpnPermissionResult(resultCode: Int) {
        val callback = pendingStartCallback
        pendingStartCallback = null

        callback?.let { cb ->
            if (resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "VPN permission granted")
                val params = pendingStartParams.getAndSet(null)
                cb(startVpnInternal(params))
            } else {
                Log.d(TAG, "VPN permission denied")
                pendingStartParams.set(null)
                cb(VpnResult.Failure("VPN permission denied by user"))
            }
        }
    }

    private fun startVpnInternal(params: PsiphonServiceParameters?): VpnResult<String> {
        try {
            // Just start the VPN service - communication is handled separately
            PsiphonComms.startPsiphon(applicationContext, params)
            Log.d(TAG, "VPN start initiated")
            return VpnResult.Success("VPN started successfully")
        } catch (e: Exception) {
            return VpnResult.Failure("Failed to start VPN: ${e.message ?: "Unknown error"}")
        }
    }


    // Lifecycle methods - must implement DefaultLifecycleObserver in full
    override fun onCreate(owner: LifecycleOwner) {
        Log.d(TAG, "Lifecycle: onCreate")
    }

    override fun onResume(owner: LifecycleOwner) {
        Log.d(TAG, "Lifecycle: onResume")
    }

    override fun onPause(owner: LifecycleOwner) {
        Log.d(TAG, "Lifecycle: onPause")
    }

    override fun onStart(owner: LifecycleOwner) {
        psiphonComms.start(applicationContext)
    }

    override fun onStop(owner: LifecycleOwner) {
        psiphonComms.stop(applicationContext)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.d(TAG, "Activity destroyed, cleaning up")
        cleanup()
    }
}