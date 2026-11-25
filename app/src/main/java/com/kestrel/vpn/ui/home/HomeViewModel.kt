package com.kestrel.vpn.ui.home
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.vpn.R
import com.kestrel.vpn.data.local.ServerAliasStore
import com.kestrel.vpn.data.local.ServerNaming
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.engine.hunt.BestServerRequests
import com.kestrel.vpn.engine.hunt.HuntRequests
import com.kestrel.vpn.engine.hunt.ServerTestRequests
import com.kestrel.vpn.data.local.PreConnectCheck
import com.kestrel.vpn.engine.selection.ServerCheckOutcome
import com.kestrel.vpn.engine.selection.ServerChecker
import com.kestrel.vpn.engine.selection.SmartConnectEngine
import com.kestrel.vpn.engine.selection.SmartConnectOutcome
import com.kestrel.vpn.domain.model.ConnectCommandResult
import com.kestrel.vpn.domain.model.ConnectionSnapshot
import com.kestrel.vpn.domain.model.ConnectionStatus
import com.kestrel.vpn.domain.model.HuntPhase
import com.kestrel.vpn.domain.model.ScanProgress
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.model.SubscriptionRefreshResult
import com.kestrel.vpn.domain.model.TestMode
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.hunt.HuntConfig
import com.kestrel.vpn.engine.hunt.HuntModeOrchestrator
import com.kestrel.vpn.engine.hunt.HuntUiProgress
import com.kestrel.vpn.engine.probe.ActiveNetworkInfo
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.NetworkKeyProvider
import com.kestrel.vpn.engine.scheduler.ChunkedTestScheduler
import com.kestrel.vpn.engine.scheduler.QuickScanRequest
import com.kestrel.vpn.engine.scheduler.ScanUiState
import com.kestrel.vpn.vpn.SubscriptionRefreshWorker
import com.kestrel.vpn.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
enum class PendingTestAction {
    HUNT_MODE
}
data class HomeUiState(
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val scanState: ScanUiState = ScanUiState.Idle,
    val huntProgress: HuntUiProgress = HuntUiProgress(
        phase = HuntPhase.IDLE,
        roundNumber = 0,
        contendersLeft = 0,
        testedInRound = 0,
        roundTotal = 0
    ),
    val scanProgress: ScanProgress? = null,
    val totalConfigs: Int = 0,
    val bestServer: ServerWithResult? = null,
    val radarServers: List<ServerWithResult> = emptyList(),
    val isRefreshingSubscription: Boolean = false,
    val isDeviceOffline: Boolean = false,
    val showHuntSheet: Boolean = false,
    val isSearching: Boolean = false,
    val isTestingServer: Boolean = false,
    val redFlash: Boolean = false,
    val aliases: Map<String, String> = emptyMap(),
    val pendingDisconnectForTest: PendingTestAction? = null,
    @StringRes val friendlyErrorRes: Int? = null,
    val serviceNoticeText: String? = null,
    val errorDetail: String? = null,
    val lastGoodKey: String? = null
) {
    val isRed: Boolean get() = isDeviceOffline || redFlash
    fun nameOfKey(key: String): String =
        aliases[key] ?: ServerNaming.defaultName(key)

    fun nameOf(config: ProxyConfig): String =
        aliases[config.canonicalKey] ?: ServerNaming.defaultName(config.canonicalKey)
}
sealed interface HomeEffect {
    data object RequestVpnPermission : HomeEffect
}
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ConfigRepository,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val connectionManager: VpnConnectionManager,
    private val testScheduler: ChunkedTestScheduler,
    private val huntOrchestrator: HuntModeOrchestrator,
    private val networkKeyProvider: NetworkKeyProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val smartConnect: SmartConnectEngine,
    private val aliasStore: ServerAliasStore,
    private val huntRequests: HuntRequests,
    private val bestServerRequests: BestServerRequests,
    private val serverTestRequests: ServerTestRequests,
    private val serverChecker: ServerChecker
) : ViewModel() {
    private val _localState = MutableStateFlow(LocalHomeFlags())
    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 2)
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()
    private var scanJob: Job? = null
    private var huntJob: Job? = null
    private var pendingConnectAfterPermissionKey: String? = null
    private val networkFlow: StateFlow<ActiveNetworkInfo> =
    networkKeyProvider.observeNetworkInfo()
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = networkKeyProvider.currentNetworkInfo()
    )
    private val scanProgressFlow = networkFlow.flatMapLatest { net ->
        repository.observeScanProgress(net.networkKey)
    }
    private val bestServerFlow = networkFlow.flatMapLatest { net ->
        repository.observeBestServer(net.networkKey)
    }
    private val radarFlow = networkFlow.flatMapLatest { net ->
        repository.observeBestServers(net.networkKey, limit = 14)
    }
    val uiState: StateFlow<HomeUiState> = combine(
        connectionManager.state,
        testScheduler.state,
        huntOrchestrator.progress,
        scanProgressFlow,
        repository.observeActiveCount(),
        bestServerFlow,
        _localState,
        radarFlow,
        aliasStore.aliases,
        aliasStore.lastGood
    ) { values ->
        val conn = values[0] as ConnectionSnapshot
        val scan = values[1] as ScanUiState
        val hunt = values[2] as HuntUiProgress
        val progress = values[3] as ScanProgress?
        val total = values[4] as Int
        val best = values[5] as ServerWithResult?
        val local = values[6] as LocalHomeFlags
        @Suppress("UNCHECKED_CAST")
        val radar = values[7] as List<ServerWithResult>
        @Suppress("UNCHECKED_CAST")
        val aliases = values[8] as Map<String, String>
        val lastGood = values[9] as String?
        HomeUiState(
            connection = conn,
            scanState = scan,
            huntProgress = hunt,
            scanProgress = progress,
            totalConfigs = total,
            bestServer = best,
            radarServers = radar,
            isRefreshingSubscription = local.isRefreshing,
            isDeviceOffline = local.isOffline || conn.isOfflineWhileConnected,
            showHuntSheet = local.showHuntSheet,
            isSearching = local.isSearching,
            isTestingServer = local.isTestingServer,
            redFlash = local.redFlash,
            aliases = aliases,
            pendingDisconnectForTest = local.pendingDisconnectForTest,
            friendlyErrorRes = local.friendlyErrorRes,
            serviceNoticeText = if (local.dismissedNotice == conn.lastNotice) null else conn.lastNotice,
            errorDetail = local.errorDetail ?: conn.errorDetail,
            lastGoodKey = lastGood
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )
    init {
        SubscriptionRefreshWorker.schedulePeriodicRefresh(appContext)
        refreshSubscription(force = false)
        observeConnectivityChanges()
        viewModelScope.launch { huntRequests.events.collect { onHuntModeClicked() } }
        viewModelScope.launch { bestServerRequests.events.collect { onBestServerRequested() } }
        viewModelScope.launch { serverTestRequests.events.collect { onServerTestRequested(it) } }
    }
    /** Re-checks real internet reachability while the Home screen is visible. Two misses in a row = offline. */
    suspend fun watchConnectivity() {
        var misses = 0
        while (true) {
            val online = networkKeyProvider.currentNetworkInfo().hasSystemNetwork &&
                connectivityChecker.isInternetReachable()
            if (online) {
                misses = 0
                _localState.update { if (it.isOffline) it.copy(isOffline = false) else it }
            } else {
                misses++
                if (misses >= 2) {
                    _localState.update { if (it.isOffline) it else it.copy(isOffline = true) }
                }
            }
            delay(if (online) 10_000L else 4_000L)
        }
    }
    private fun observeConnectivityChanges() {
        viewModelScope.launch {
            networkFlow.collect { info ->
                val online = if (!info.hasSystemNetwork) false else connectivityChecker.isInternetReachable()
                _localState.update { it.copy(isOffline = !online) }
            }
        }
    }
    fun refreshSubscription(force: Boolean) {
        viewModelScope.launch {
            _localState.update { it.copy(isRefreshing = true) }
            when (val res = repository.refreshSubscription(force = force)) {
                is SubscriptionRefreshResult.Updated,
                is SubscriptionRefreshResult.NotModified,
                is SubscriptionRefreshResult.SkippedThrottled -> {
                    _localState.update { it.copy(isRefreshing = false, friendlyErrorRes = null, errorDetail = null) }
                }
                is SubscriptionRefreshResult.CachedOffline -> {
                    _localState.update { it.copy(isRefreshing = false) }
                }
                is SubscriptionRefreshResult.Error -> {
                    _localState.update {
                        it.copy(
                            isRefreshing = false,
                            friendlyErrorRes = R.string.error_subscription_load,
                            errorDetail = res.reason
                        )
                    }
                }
            }
        }
    }
    @Volatile private var lastPowerTapMs = 0L
    fun onPowerButtonClicked() {
        val now = System.currentTimeMillis()
        if (now - lastPowerTapMs < 800L) return   // ignore double taps: never start two searches at once
        lastPowerTapMs = now
        com.kestrel.vpn.util.DiagnosticsLog.add("ui", "power button status=${connectionManager.state.value.status}")
        if (_localState.value.isSearching) {
            scanJob?.cancel()
            _localState.update { it.copy(isSearching = false) }
            return
        }
        val status = connectionManager.state.value.status
        if (status == ConnectionStatus.CONNECTED ||
            status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.FAILING_OVER
        ) {
            connectionManager.disconnect()
            return
        }
        cancelActiveTests()
        scanJob = viewModelScope.launch { smartConnectAndStart() }
    }
    /**
     * "Best server" (from the Servers screen): benchmark here on Home. Look for 10 s for a server above
     * 0.05 Mbit/s; if none, keep going until one reaches it. Never reuses the previous server, it wants the best.
     */
    private fun onBestServerRequested() {
        if (_localState.value.isSearching) return
        cancelActiveTests()
        scanJob = viewModelScope.launch { smartConnectAndStart(reusePrevious = false) }
    }
    /**
     * Connect button: re-benchmark the server that worked last time; if it is gone, search for the
     * server that stands out (download speed must be above 0.05 Mbit/s), then connect.
     */
    private suspend fun smartConnectAndStart(reusePrevious: Boolean = true) {
        if (!connectionManager.isVpnPermissionGranted()) {
            pendingConnectAfterPermissionKey = null
            _effects.tryEmit(HomeEffect.RequestVpnPermission)
            return
        }
        val prefs = preferencesDataStore.current()
        val net = networkKeyProvider.currentNetworkInfo()
        _localState.update { it.copy(isSearching = true, friendlyErrorRes = null, errorDetail = null) }
        // isSearching stays true until the connect command has been handed over, so the UI never flashes back
        // to "not protected" (blue) between the end of the search and the start of the connection.
        try {
            val outcome = smartConnect.findServer(
                net.networkKey, prefs.speedPayloadKb, reusePrevious, prefs.testSpeedMode,
                minSpeedKbps = if (prefs.minSpeedEnabled) prefs.minSpeedKbps else 0
            )
            handleSmartOutcome(outcome)
        } finally {
            _localState.update { it.copy(isSearching = false) }
        }
    }

    private var flashJob: Job? = null

    private suspend fun connectKey(key: String) {
        when (val res = connectionManager.connectToServer(key)) {
            is ConnectCommandResult.Started -> clearError()
            ConnectCommandResult.PermissionRequired -> {
                pendingConnectAfterPermissionKey = key
                _effects.tryEmit(HomeEffect.RequestVpnPermission)
            }
            ConnectCommandResult.NoServersAvailable -> _localState.update {
                it.copy(friendlyErrorRes = R.string.error_connect_failed, errorDetail = "Server no longer available")
            }
            is ConnectCommandResult.Error -> _localState.update {
                it.copy(friendlyErrorRes = R.string.error_connect_failed, errorDetail = res.message)
            }
        }
    }

    /** Soft red for a few seconds (the accent colour cross-fades), then back to normal. */
    private fun failServer(@StringRes messageRes: Int) {
        flashJob?.cancel()
        _localState.update { it.copy(redFlash = true, friendlyErrorRes = messageRes, errorDetail = null) }
        flashJob = viewModelScope.launch {
            delay(4_000L)
            _localState.update {
                it.copy(redFlash = false, friendlyErrorRes = if (it.friendlyErrorRes == messageRes) null else it.friendlyErrorRes)
            }
        }
    }

    /** A server was picked on the Servers screen: test it HERE (ping + download > 0.05 Mbit/s), connect or red. */
    private fun onServerTestRequested(key: String) {
        cancelActiveTests()
        flashJob?.cancel()
        scanJob = viewModelScope.launch {
            _localState.update {
                it.copy(isSearching = true, isTestingServer = true, redFlash = false, friendlyErrorRes = null, errorDetail = null)
            }
            try {
                val config = repository.getConfigByKey(key) ?: return@launch
                val net = networkKeyProvider.currentNetworkInfo()
                val prefs = preferencesDataStore.current()
                val checkSpeed = when (prefs.preConnectCheck) {
                    PreConnectCheck.PING_ONLY -> false
                    PreConnectCheck.PING_AND_SPEED -> true
                    PreConnectCheck.AUTO -> !prefs.dataSaver &&
                        (if (net.isWifi) prefs.wifiTestMode else prefs.mobileTestMode) == TestMode.PING_AND_SPEED
                }
                when (serverChecker.check(config, net.networkKey, prefs.speedPayloadKb, checkSpeed, prefs.minSpeedEnabled, prefs.minSpeedKbps)) {
                    is ServerCheckOutcome.Online -> {
                        if (!connectionManager.isVpnPermissionGranted()) {
                            pendingConnectAfterPermissionKey = key
                            _effects.tryEmit(HomeEffect.RequestVpnPermission)
                        } else {
                            connectKey(key)
                        }
                    }
                    ServerCheckOutcome.NoPing -> failServer(R.string.home_server_no_ping)
                    is ServerCheckOutcome.TooSlow -> failServer(R.string.home_server_slow)
                    ServerCheckOutcome.DeviceOffline -> _localState.update { it.copy(isOffline = true) }
                }
            } finally {
                _localState.update { it.copy(isSearching = false, isTestingServer = false) }
            }
        }
    }

    private suspend fun handleSmartOutcome(outcome: SmartConnectOutcome) {
        when (outcome) {
            is SmartConnectOutcome.Found -> connectKey(outcome.canonicalKey)
            SmartConnectOutcome.NoneFound -> _localState.update {
                it.copy(friendlyErrorRes = R.string.error_connect_failed, errorDetail = "No fast server found in the quick search. Use Hunt Mode (Settings) to test every server.")
            }
            is SmartConnectOutcome.Error -> _localState.update {
                it.copy(friendlyErrorRes = R.string.error_scan_failed, errorDetail = outcome.message)
            }
        }
    }
    fun onVpnPermissionResult(granted: Boolean) {
        if (!granted) {
            _localState.update {
                it.copy(
                    friendlyErrorRes = R.string.error_vpn_permission_denied,
                    errorDetail = null
                )
            }
            return
        }
        viewModelScope.launch {
            val target = pendingConnectAfterPermissionKey
            pendingConnectAfterPermissionKey = null
            if (target != null) {
                connectionManager.connectToServer(target)
            } else {
                smartConnectAndStart()
            }
        }
    }
    fun onHuntModeClicked() {
        if (connectionManager.state.value.isActiveOrTransitioning) {
            _localState.update { it.copy(pendingDisconnectForTest = PendingTestAction.HUNT_MODE) }
            return
        }
        _localState.update { it.copy(showHuntSheet = true) }
        startHuntInternal(resetFirst = false)
    }
    fun confirmDisconnectAndRunTest() {
        val pending = _localState.value.pendingDisconnectForTest ?: return
        _localState.update { it.copy(pendingDisconnectForTest = null) }
        connectionManager.disconnect()
        viewModelScope.launch {
            withTimeoutOrNull(3_000L) {
                connectionManager.state.first {
                    it.status == ConnectionStatus.DISCONNECTED || it.status == ConnectionStatus.ERROR
                }
            }
            when (pending) {
                PendingTestAction.HUNT_MODE -> {
                    _localState.update { it.copy(showHuntSheet = true) }
                    startHuntInternal(resetFirst = false)
                }
            }
        }
    }
    fun dismissDisconnectPrompt() {
        _localState.update { it.copy(pendingDisconnectForTest = null) }
    }
    fun dismissHuntSheet() {
        _localState.update { it.copy(showHuntSheet = false) }
    }
    fun cancelHunt() {
        huntJob?.cancel()
        _localState.update { it.copy(showHuntSheet = false) }
    }
    fun restartHunt() {
        startHuntInternal(resetFirst = true)
    }
    fun connectToHuntWinner() {
        val winnerKey = huntOrchestrator.progress.value.winner?.config?.canonicalKey ?: return
        _localState.update { it.copy(showHuntSheet = false) }
        cancelActiveTests()
        viewModelScope.launch {
            when (connectionManager.connectToServer(winnerKey)) {
                is ConnectCommandResult.Started -> clearError()
                ConnectCommandResult.PermissionRequired -> {
                    pendingConnectAfterPermissionKey = winnerKey
                    _effects.tryEmit(HomeEffect.RequestVpnPermission)
                }
                else -> Unit
            }
        }
    }
    fun clearError() {
        val currentNotice = connectionManager.state.value.lastNotice
        _localState.update {
            it.copy(
                friendlyErrorRes = null,
                errorDetail = null,
                dismissedNotice = currentNotice
            )
        }
    }
    private fun startHuntInternal(resetFirst: Boolean) {
        scanJob?.cancel()
        huntJob?.cancel()
        huntJob = viewModelScope.launch {
            val prefs = preferencesDataStore.current()
            val net = networkKeyProvider.currentNetworkInfo()
            if (resetFirst) {
                huntOrchestrator.resetHunt(net.networkKey)
            }
            huntOrchestrator.runOrResumeHunt(
                HuntConfig(
                    networkKey = net.networkKey,
                    speedPayloadKb = prefs.speedPayloadKb,
                    speedMode = prefs.testSpeedMode
                ),
                runToCompletion = true
            )
        }
    }
    private fun cancelActiveTests() {
        scanJob?.cancel()
        huntJob?.cancel()
    }
    private data class LocalHomeFlags(
        val isRefreshing: Boolean = false,
        val isOffline: Boolean = false,
        val showHuntSheet: Boolean = false,
        val isSearching: Boolean = false,
        val isTestingServer: Boolean = false,
        val redFlash: Boolean = false,
        val pendingDisconnectForTest: PendingTestAction? = null,
        @StringRes val friendlyErrorRes: Int? = null,
        val dismissedNotice: String? = null,
        val errorDetail: String? = null
    )
}
