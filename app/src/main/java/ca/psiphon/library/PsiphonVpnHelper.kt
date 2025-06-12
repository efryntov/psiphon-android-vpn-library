package ca.psiphon.library

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

sealed class VpnResult<out T> {
    data class Success<out T>(val value: T) : VpnResult<T>()
    data class Failure<out T>(val message: String) : VpnResult<T>()
    data class NeedsPermission<out T>(val intent: Intent) : VpnResult<T>()
}

class PsiphonVpnHelper private constructor(
    private val contextRef: WeakReference<Context>,
    private val stateListener: PsiphonStateListener
) : DefaultLifecycleObserver {

    interface PsiphonStateListener {
        fun onStateUpdated(state: PsiphonState)
    }

    private var lifecycleOwner: LifecycleOwner? = null
    private val psiphonComms: PsiphonComms

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
            context: Context,
            lifecycleOwner: LifecycleOwner,
            stateListener: PsiphonStateListener
        ): PsiphonVpnHelper {
            val helper = PsiphonVpnHelper(
                WeakReference(context),
                stateListener
            )

            helper.lifecycleOwner = lifecycleOwner
            // Auto-bind to lifecycle
            lifecycleOwner.lifecycle.addObserver(helper)

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
        val context = getContext() ?: run {
            callback(VpnResult.Failure("Context is no longer available"))
            return
        }

        // Check if VPN permission is needed
        val intent = VpnService.prepare(context)
        if (intent != null) {
            // Need permission - hand off to plugin layer
            callback(VpnResult.NeedsPermission(intent))
        } else {
            // Permission already granted - start immediately
            callback(startVpnInternal(params))
        }
    }

    fun stopVpn(callback: (VpnResult<String>) -> Unit) {
        val context = getContext() ?: run {
            callback(VpnResult.Failure("Context is no longer available"))
            return
        }

        try {
            PsiphonComms.stopPsiphon(context)
            Log.d(TAG, "VPN stop initiated")
            callback(VpnResult.Success("VPN stopped successfully"))
        } catch (e: Exception) {
            callback(VpnResult.Failure("Failed to stop VPN: ${e.message ?: "Unknown error"}"))
        }
    }

    fun updateParameters(
        params: PsiphonServiceParameters,
        callback: (VpnResult<String>) -> Unit
    ) {
        val context = getContext() ?: run {
            callback(VpnResult.Failure("Context is no longer available"))
            return
        }

        try {
            PsiphonComms.updatePsiphonParameters(context, params)
            Log.d(TAG, "Parameters updated")
            callback(VpnResult.Success("Parameters updated successfully"))
        } catch (e: Exception) {
            callback(VpnResult.Failure("Failed to update parameters: ${e.message ?: "Unknown error"}"))
        }
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up PsiphonVpnHelper")

        lifecycleOwner?.lifecycle?.removeObserver(this)
        lifecycleOwner = null

        val context = getContext()
        if (context != null) {
            psiphonComms.stop(context)
        }
        contextRef.clear()
    }

    // Private methods
    private fun getContext(): Context? {
        return contextRef.get()
    }

    private fun startVpnInternal(params: PsiphonServiceParameters?): VpnResult<String> {
        val context = getContext() ?: return VpnResult.Failure("Context is no longer available")

        return try {
            PsiphonComms.startPsiphon(context, params)
            Log.d(TAG, "VPN start initiated")
            VpnResult.Success("VPN started successfully")
        } catch (e: Exception) {
            VpnResult.Failure("Failed to start VPN: ${e.message ?: "Unknown error"}")
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
        Log.d(TAG, "Lifecycle: onStart - starting comms")
        val context = getContext()
        if (context != null) {
            psiphonComms.start(context)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "Lifecycle: onStop - stopping comms")
        val context = getContext()
        if (context != null) {
            psiphonComms.stop(context)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.d(TAG, "Lifecycle: onDestroy - cleaning up")
        cleanup()
    }
}