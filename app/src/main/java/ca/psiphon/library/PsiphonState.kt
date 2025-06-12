package ca.psiphon.library

import android.os.Bundle
import org.json.JSONObject

data class PsiphonState(
    val tunnelEvent: TunnelEvent? = TunnelEvent.STOPPED,
    val isWaitingForNetwork: Boolean = false,
    val clientRegion: String? = null,
    val connectedServerRegion: String? = null,
    val totalBytesSent: Long = 0,
    val totalBytesReceived: Long = 0,
    val upstreamRateLimit: Long? = null,
    val downstreamRateLimit: Long? = null,
    val availableEgressRegions: List<String>? = null,
    val socksProxyPort: Int? = null,
    val httpProxyPort: Int? = null,
    val applicationParameters: JSONObject? = null,

    // Error information - code + generic data
    val lastErrorCode: ErrorCode? = null,
    val lastErrorData: String? = null, // Generic data - port numbers, exception messages, etc.
    val lastErrorTimestamp: Long? = null
) {
    companion object {
        fun fromBundle(bundle: Bundle): PsiphonState {
            return PsiphonState(
                tunnelEvent = bundle.getString("tunnelEvent")?.let { TunnelEvent.valueOf(it) },
                isWaitingForNetwork = bundle.getBoolean("isWaitingForNetwork", false),
                clientRegion = bundle.getString("clientRegion"),
                connectedServerRegion = bundle.getString("connectedServerRegion"),
                totalBytesSent = bundle.getLong("totalBytesSent", 0),
                totalBytesReceived = bundle.getLong("totalBytesReceived", 0),
                upstreamRateLimit = if (bundle.containsKey("upstreamRateLimit")) bundle.getLong("upstreamRateLimit") else null,
                downstreamRateLimit = if (bundle.containsKey("downstreamRateLimit")) bundle.getLong("downstreamRateLimit") else null,
                availableEgressRegions = bundle.getStringArrayList("availableEgressRegions"),
                socksProxyPort = if (bundle.containsKey("socksProxyPort")) bundle.getInt("socksProxyPort") else null,
                httpProxyPort = if (bundle.containsKey("httpProxyPort")) bundle.getInt("httpProxyPort") else null,
                applicationParameters = bundle.getString("applicationParameters")?.let { JSONObject(it) },
                lastErrorCode = bundle.getString("lastErrorCode")?.let { ErrorCode.valueOf(it) },
                lastErrorData = bundle.getString("lastErrorData"),
                lastErrorTimestamp = if (bundle.containsKey("lastErrorTimestamp")) bundle.getLong("lastErrorTimestamp") else null
            )
        }

        fun stopped() = PsiphonState(tunnelEvent = TunnelEvent.STOPPED)
        fun unknown() = PsiphonState(tunnelEvent = null)

        // Generic error setter
        fun setError(
            currentState: PsiphonState,
            errorCode: ErrorCode,
            errorData: String? = null
        ) = currentState.copy(
            lastErrorCode = errorCode,
            lastErrorData = errorData,
            lastErrorTimestamp = System.currentTimeMillis()
        )
    }

    fun toBundle(): Bundle = Bundle().apply {
        tunnelEvent?.let { putString("tunnelEvent", it.name) }
        putBoolean("isWaitingForNetwork", isWaitingForNetwork)
        clientRegion?.let { putString("clientRegion", it) }
        connectedServerRegion?.let { putString("connectedServerRegion", it) }
        putLong("totalBytesSent", totalBytesSent)
        putLong("totalBytesReceived", totalBytesReceived)
        upstreamRateLimit?.let { putLong("upstreamRateLimit", it) }
        downstreamRateLimit?.let { putLong("downstreamRateLimit", it) }
        applicationParameters?.let { putString("applicationParameters", it.toString()) }
        availableEgressRegions?.let { putStringArrayList("availableEgressRegions", ArrayList(it)) }
        socksProxyPort?.let { putInt("socksProxyPort", it) }
        httpProxyPort?.let { putInt("httpProxyPort", it) }
        lastErrorCode?.let { putString("lastErrorCode", it.name) }
        lastErrorData?.let { putString("lastErrorData", it) }
        lastErrorTimestamp?.let { putLong("lastErrorTimestamp", it) }
    }

    val hasError: Boolean get() = lastErrorCode != null

    fun clearError(): PsiphonState = copy(
        lastErrorCode = null,
        lastErrorData = null,
        lastErrorTimestamp = null
    )
}

enum class ErrorCode {
    SOCKS_PORT_IN_USE,
    HTTP_PORT_IN_USE,
    VPN_REVOKED,
    SOCKS_PORT_NOT_SET,
    TUNNEL_RESTART_FAILED,
    TUNNEL_START_FAILED,
    UNEXPECTED_ERROR,
}

enum class TunnelEvent {
    STOPPED, CONNECTING, CONNECTED, STOPPING
}