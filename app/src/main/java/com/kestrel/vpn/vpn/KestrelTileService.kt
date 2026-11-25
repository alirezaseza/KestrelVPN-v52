package com.kestrel.vpn.vpn
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kestrel.vpn.R
import com.kestrel.vpn.di.ApplicationScope
import com.kestrel.vpn.domain.model.ConnectCommandResult
import com.kestrel.vpn.domain.model.ConnectionStatus
import com.kestrel.vpn.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
@AndroidEntryPoint
class KestrelTileService : TileService() {
    @Inject lateinit var connectionManager: VpnConnectionManager
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    private var listenJob: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        syncTile(connectionManager.state.value.status)
        listenJob?.cancel()
        listenJob = appScope.launch {
            connectionManager.state.collectLatest { snapshot ->
                syncTile(snapshot.status)
            }
        }
    }
    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }
    override fun onClick() {
        super.onClick()
        val status = connectionManager.state.value.status
        if (status == ConnectionStatus.CONNECTED ||
            status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.FAILING_OVER
        ) {
            connectionManager.disconnect()
            return
        }
        appScope.launch {
            when (connectionManager.connectToBest()) {
                is ConnectCommandResult.Started -> syncTile(ConnectionStatus.CONNECTING)
                ConnectCommandResult.PermissionRequired,
                ConnectCommandResult.NoServersAvailable,
                is ConnectCommandResult.Error -> openAppForUserAction()
            }
        }
    }
    private fun syncTile(status: ConnectionStatus) {
        val tile = qsTile ?: return
        when (status) {
            ConnectionStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_state_connected)
                }
            }
            ConnectionStatus.CONNECTING,
            ConnectionStatus.FAILING_OVER -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_state_connecting)
                }
            }
            ConnectionStatus.DISCONNECTING,
            ConnectionStatus.DISCONNECTED,
            ConnectionStatus.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_state_disconnected)
                }
            }
        }
        tile.updateTile()
    }
    @Suppress("DEPRECATION")
    private fun openAppForUserAction() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                200,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }
    companion object {
        fun requestTileSync(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, KestrelTileService::class.java)
                )
            }
        }
    }
}
