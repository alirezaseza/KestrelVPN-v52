package com.kestrel.vpn.vpn
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.kestrel.vpn.data.local.ServerAliasStore
import com.kestrel.vpn.domain.model.ConnectCommandResult
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ConnectionSnapshot
import com.kestrel.vpn.domain.model.ConnectionStatus
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.probe.NetworkKeyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class VpnConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ConfigRepository,
    private val networkKeyProvider: NetworkKeyProvider,
    private val aliasStore: ServerAliasStore
) {
    private val _state = MutableStateFlow(ConnectionSnapshot())
    val state: StateFlow<ConnectionSnapshot> = _state.asStateFlow()
    fun isVpnPermissionGranted(): Boolean = VpnService.prepare(context) == null
    fun createVpnPermissionIntent(): Intent? = VpnService.prepare(context)
    suspend fun connectToBest(): ConnectCommandResult {
        if (!isVpnPermissionGranted()) {
            return ConnectCommandResult.PermissionRequired
        }
        val networkKey = networkKeyProvider.currentNetworkInfo().networkKey
        val lastGood = aliasStore.lastGoodKey()?.takeIf { key ->
            val tier = repository.getTestResult(key, networkKey)?.tier
            repository.getConfigByKey(key) != null && tier != ServerTier.DEAD
        }
        if (lastGood != null) return connectToServer(lastGood)
        val best = repository.getBestServers(networkKey, limit = 1).firstOrNull()
            ?: return ConnectCommandResult.NoServersAvailable
        return connectToServer(best.config.canonicalKey)
    }
    suspend fun connectToServer(canonicalKey: String): ConnectCommandResult {
        if (!isVpnPermissionGranted()) {
            return ConnectCommandResult.PermissionRequired
        }
        val config = repository.getConfigByKey(canonicalKey)
            ?: return ConnectCommandResult.NoServersAvailable
        val networkKey = networkKeyProvider.currentNetworkInfo().networkKey
        val result = repository.getTestResult(canonicalKey, networkKey)
        _state.update {
            ConnectionSnapshot(
                status = ConnectionStatus.CONNECTING,
                activeServer = ServerWithResult(config, result)
            )
        }
        KestrelTileService.requestTileSync(context)
        val intent = Intent(context, KestrelVpnService::class.java).apply {
            action = KestrelVpnService.ACTION_CONNECT
            putExtra(KestrelVpnService.EXTRA_CANONICAL_KEY, canonicalKey)
        }
        return runCatching {
            ContextCompat.startForegroundService(context, intent)
            ConnectCommandResult.Started(canonicalKey)
        }.getOrElse { throwable ->
            _state.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    errorDetail = throwable.message
                )
            }
            KestrelTileService.requestTileSync(context)
            ConnectCommandResult.Error(throwable.message ?: "Unable to start VPN service")
        }
    }
    fun disconnect() {
        val current = _state.value.status
        if (current == ConnectionStatus.DISCONNECTED) return
        _state.update { it.copy(status = ConnectionStatus.DISCONNECTING) }
        val intent = Intent(context, KestrelVpnService::class.java).apply {
            action = KestrelVpnService.ACTION_DISCONNECT
        }
        runCatching { context.startService(intent) }
        KestrelTileService.requestTileSync(context)
    }
    internal fun onServiceConnecting(server: ServerWithResult, isFailover: Boolean) {
        _state.update {
            it.copy(
                status = if (isFailover) ConnectionStatus.FAILING_OVER else ConnectionStatus.CONNECTING,
                activeServer = server,
                isOfflineWhileConnected = false,
                errorDetail = null
            )
        }
        KestrelTileService.requestTileSync(context)
    }
    internal fun onServiceConnected(server: ServerWithResult, notice: String? = null) {
        val now = System.currentTimeMillis()
        _state.update {
            ConnectionSnapshot(
                status = ConnectionStatus.CONNECTED,
                activeServer = server,
                connectedSinceMs = now,
                durationSeconds = 0L,
                isOfflineWhileConnected = false,
                lastNotice = notice
            )
        }
        KestrelTileService.requestTileSync(context)
    }
    internal fun onStatsTick(
        durationSeconds: Long,
        rxBytesPerSec: Long,
        txBytesPerSec: Long,
        totalRxBytes: Long,
        totalTxBytes: Long
    ) {
        if (durationSeconds == STABLE_AFTER_SECONDS) {
            _state.value.activeServer?.config?.canonicalKey?.let { aliasStore.setLastGood(it) }
        }
        _state.update { current ->
            if (current.status != ConnectionStatus.CONNECTED) return@update current
            current.copy(
                durationSeconds = durationSeconds,
                rxBytesPerSec = rxBytesPerSec,
                txBytesPerSec = txBytesPerSec,
                totalRxBytes = totalRxBytes,
                totalTxBytes = totalTxBytes
            )
        }
    }
    internal fun onOfflineWhileConnectedChanged(isOffline: Boolean) {
        _state.update { current ->
            if (current.status != ConnectionStatus.CONNECTED) return@update current
            current.copy(isOfflineWhileConnected = isOffline)
        }
    }
    internal fun onServiceDisconnected(notice: String? = null, errorDetail: String? = null) {
        _state.value = ConnectionSnapshot(
            status = if (errorDetail != null) ConnectionStatus.ERROR else ConnectionStatus.DISCONNECTED,
            lastNotice = notice,
            errorDetail = errorDetail
        )
        KestrelTileService.requestTileSync(context)
    }
    private companion object {
        const val STABLE_AFTER_SECONDS = 8L
    }
}
