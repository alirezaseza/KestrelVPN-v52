package com.kestrel.vpn.ui.servers
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.domain.model.ConnectCommandResult
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.NetworkKeyProvider
import com.kestrel.vpn.engine.hunt.BestServerRequests
import com.kestrel.vpn.engine.hunt.ServerTestRequests
import com.kestrel.vpn.domain.model.ManualAddResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.kestrel.vpn.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
sealed interface ServerListEffect {
    data object RequestVpnPermission : ServerListEffect
    data object ConnectedNavigateBack : ServerListEffect
}
enum class ServerNotice { NONE, NO_PING, TOO_SLOW, DEVICE_OFFLINE, NO_BEST }

data class ServerListUiState(
    val testingKey: String? = null,
    val failedKey: String? = null,
    val notice: ServerNotice = ServerNotice.NONE,
    val deviceOffline: Boolean = false,
    /** Short red flash for a failed test; fades back on its own. */
    val redFlash: Boolean = false,
    val addedCount: Int = 0
) {
    val isRed: Boolean get() = deviceOffline || redFlash
    val isBusy: Boolean get() = testingKey != null
}

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val repository: ConfigRepository,
    private val networkKeyProvider: NetworkKeyProvider,
    private val connectionManager: VpnConnectionManager,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val aliasStore: com.kestrel.vpn.data.local.ServerAliasStore,
    private val serverTestRequests: ServerTestRequests,
    private val bestServerRequests: BestServerRequests,
    private val connectivityChecker: ConnectivityChecker
) : ViewModel() {
    private val _ui = MutableStateFlow(ServerListUiState())
    val ui: StateFlow<ServerListUiState> = _ui.asStateFlow()
    private var workJob: Job? = null
    private var flashJob: Job? = null

    private val _effects = MutableSharedFlow<ServerListEffect>(extraBufferCapacity = 2)
    val effects: SharedFlow<ServerListEffect> = _effects.asSharedFlow()
    private var pendingKey: String? = null
    val favorites: StateFlow<Set<String>> = preferencesDataStore.preferencesFlow
        .map { it.favoriteKeys }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet()
    )
    // Favorites first; the repository order (best score first) is kept inside each group.
    val servers: StateFlow<List<ServerWithResult>> =
    combine(
        networkKeyProvider.observeNetworkInfo()
            .flatMapLatest { net -> repository.observeServersWithResults(net.networkKey) },
        favorites
    ) { list, favs -> list.sortedByDescending { it.config.canonicalKey in favs } }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
    val aliases: StateFlow<Map<String, String>> = aliasStore.aliases
    fun rename(canonicalKey: String, name: String) = aliasStore.rename(canonicalKey, name)
    fun toggleFavorite(canonicalKey: String) {
        viewModelScope.launch { preferencesDataStore.toggleFavorite(canonicalKey) }
    }
    /** Re-checks real internet (Google) while the screen is visible; two misses in a row = offline (red). */
    suspend fun watchConnectivity() {
        var misses = 0
        while (true) {
            val online = networkKeyProvider.currentNetworkInfo().hasSystemNetwork &&
                connectivityChecker.isInternetReachable()
            if (online) {
                misses = 0
                _ui.update { if (it.deviceOffline) it.copy(deviceOffline = false, notice = if (it.notice == ServerNotice.DEVICE_OFFLINE) ServerNotice.NONE else it.notice) else it }
            } else {
                misses++
                if (misses >= 2) _ui.update { if (it.deviceOffline) it else it.copy(deviceOffline = true) }
            }
            delay(if (online) 10_000L else 4_000L)
        }
    }

    private fun flashRed() {
        flashJob?.cancel()
        _ui.update { it.copy(redFlash = true) }
        flashJob = viewModelScope.launch {
            delay(3_500L)
            _ui.update { it.copy(redFlash = false) }
        }
    }

    private fun fail(notice: ServerNotice, failedKey: String? = null) {
        _ui.update {
            it.copy(
                notice = notice,
                failedKey = failedKey,
                deviceOffline = it.deviceOffline || notice == ServerNotice.DEVICE_OFFLINE
            )
        }
        flashRed()
    }

    /** Tapping a server: go back to Home, which tests it there (ping + download) and connects or turns red. */
    fun onServerClicked(canonicalKey: String) {
        serverTestRequests.request(canonicalKey)
        _effects.tryEmit(ServerListEffect.ConnectedNavigateBack)
    }

    /** "Best server": go back to Home, which runs the benchmark and connects. */
    fun onBestServerClicked() {
        if (_ui.value.isBusy) return
        bestServerRequests.request()
        _effects.tryEmit(ServerListEffect.ConnectedNavigateBack)
    }

    /** Adds configs typed/pasted by the user and pins them (favorite) so they show in the short list. */
    suspend fun addManual(raw: String): ManualAddResult {
        val result = repository.addManualConfigs(raw)
        if (result.added > 0) {
            val favs = favorites.value
            result.addedKeys.filter { it !in favs }.forEach { preferencesDataStore.toggleFavorite(it) }
            _ui.update { it.copy(addedCount = result.added, notice = ServerNotice.NONE, failedKey = null) }
        }
        return result
    }

    fun consumeAdded() = _ui.update { it.copy(addedCount = 0) }

    fun dismissNotice() = _ui.update { it.copy(notice = ServerNotice.NONE, failedKey = null) }

    fun connectTo(canonicalKey: String) {
        viewModelScope.launch {
            when (connectionManager.connectToServer(canonicalKey)) {
                is ConnectCommandResult.Started -> {
                    pendingKey = null
                    _effects.tryEmit(ServerListEffect.ConnectedNavigateBack)
                }
                ConnectCommandResult.PermissionRequired -> {
                    pendingKey = canonicalKey
                    _effects.tryEmit(ServerListEffect.RequestVpnPermission)
                }
                else -> Unit
            }
        }
    }
    fun onVpnPermissionResult(granted: Boolean) {
        val target = pendingKey
        pendingKey = null
        if (granted && target != null) {
            connectTo(target)
        }
    }
}
