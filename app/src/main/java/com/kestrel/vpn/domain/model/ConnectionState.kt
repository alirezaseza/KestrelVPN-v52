package com.kestrel.vpn.domain.model
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILING_OVER,
    DISCONNECTING,
    ERROR
}
data class ConnectionSnapshot(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeServer: ServerWithResult? = null,
    val connectedSinceMs: Long? = null,
    val durationSeconds: Long = 0L,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val isOfflineWhileConnected: Boolean = false,
    val lastNotice: String? = null,
    val errorDetail: String? = null
) {
    val isActiveOrTransitioning: Boolean
    get() = status == ConnectionStatus.CONNECTING ||
    status == ConnectionStatus.CONNECTED ||
    status == ConnectionStatus.FAILING_OVER
}
sealed interface ConnectCommandResult {
    data class Started(val canonicalKey: String) : ConnectCommandResult
    data object PermissionRequired : ConnectCommandResult
    data object NoServersAvailable : ConnectCommandResult
    data class Error(val message: String) : ConnectCommandResult
}
