package ca.psiphon.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class PsiphonComms(private val stateListener: PsiphonStateListener) {

    interface PsiphonStateListener {
        fun onStateUpdated(state: PsiphonState)
    }

    private val clientCallback = object : IPsiphonClientCallback.Stub() {
        override fun onStateUpdated(stateBundle: Bundle) {
            val state = PsiphonState.fromBundle(stateBundle)
            stateListener.onStateUpdated(state)
        }
    }

    private var psiphonService: IPsiphonService? = null
    private val isServiceBound = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(true)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "PsiphonVpnService connected")
            psiphonService = IPsiphonService.Stub.asInterface(service)

            try {
                psiphonService?.registerClient(clientCallback)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to register client: $e")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "PsiphonVpnService disconnected unexpectedly")
            psiphonService = null
            stateListener.onStateUpdated(PsiphonState.stopped())
            isServiceBound.set(false)
        }
    }

    fun start(context: Context) {
        isStopped.set(false)
        bindService(context)
    }

    fun stop(context: Context) {
        isStopped.set(true)
        stateListener.onStateUpdated(PsiphonState.unknown())

        psiphonService?.let { service ->
            try {
                service.unregisterClient(clientCallback)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to unregister client: $e")
            }
        }

        if (isServiceBound.compareAndSet(true, false)) {
            Log.d(TAG, "Unbinding from PsiphonVpnService")
            context.unbindService(serviceConnection)
        }
        psiphonService = null
    }

    private fun bindService(context: Context) {
        if (isServiceBound.compareAndSet(false, true)) {
            val intent = Intent(context, PsiphonVpnService::class.java)
            val result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!result) {
                isServiceBound.set(false)
                stateListener.onStateUpdated(PsiphonState.stopped())
            }
        }
    }

    companion object {
        private val TAG = PsiphonComms::class.java.simpleName

        fun startPsiphon(context: Context, params: PsiphonServiceParameters? = null) {
            val intent = Intent(context, PsiphonVpnService::class.java).apply {
                action = PsiphonVpnService.INTENT_ACTION_START_PSIPHON
            }
            params?.putIntoIntent(intent)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopPsiphon(context: Context) {
            val intent = Intent(context, PsiphonVpnService::class.java).apply {
                action = PsiphonVpnService.INTENT_ACTION_STOP_PSIPHON
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun updatePsiphonParameters(context: Context, params: PsiphonServiceParameters) {
            val intent = Intent(context, PsiphonVpnService::class.java).apply {
                action = PsiphonVpnService.INTENT_ACTION_UPDATE_PSIPHON_PARAMETERS
            }
            params.putIntoIntent(intent)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}