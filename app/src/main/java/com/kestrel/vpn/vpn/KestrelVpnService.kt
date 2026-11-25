package com.kestrel.vpn.vpn
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.coroutines.CoroutineExceptionHandler
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kestrel.vpn.KestrelApp
import com.kestrel.vpn.R
import com.kestrel.vpn.data.local.ServerAliasStore
import com.kestrel.vpn.data.local.TrafficHistoryStore
import com.kestrel.vpn.data.local.UserPreferences
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.di.IoDispatcher
import com.kestrel.vpn.domain.core.CoreUnavailableException
import com.kestrel.vpn.domain.core.ProxyCore
import com.kestrel.vpn.domain.core.ProxyInstance
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.probe.NetworkKeyProvider
import com.kestrel.vpn.engine.scoring.ServerScorer
import com.kestrel.vpn.ui.MainActivity
import com.kestrel.vpn.ui.util.LocaleFormatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kestrel.vpn.util.DiagnosticsLog
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
@AndroidEntryPoint
class KestrelVpnService : VpnService() {
    @Inject lateinit var proxyCore: ProxyCore
    @Inject lateinit var repository: ConfigRepository
    @Inject lateinit var connectionManager: VpnConnectionManager
    @Inject lateinit var healthProbe: TunnelHealthProbe
    @Inject lateinit var networkKeyProvider: NetworkKeyProvider
    @Inject lateinit var scorer: ServerScorer
    @Inject lateinit var preferencesStore: UserPreferencesDataStore
    @Inject lateinit var trafficHistory: TrafficHistoryStore
    @Inject lateinit var aliasStore: ServerAliasStore
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var serviceScope: CoroutineScope
    private val lifecycleMutex = Mutex()
    private val isTornDown = AtomicBoolean(false)
    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var proxyInstance: ProxyInstance? = null
    @Volatile private var coreTunFd: Int = -1
    @Volatile private var activeServer: ServerWithResult? = null
    private var statsJob: Job? = null
    private var healthJob: Job? = null
    private val failedInSessionKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val failoverInProgress = AtomicBoolean(false)
    override fun onCreate() {
        super.onCreate()
        val handler = CoroutineExceptionHandler { _, t ->
            DiagnosticsLog.add("vpn", "uncaught: ${t.javaClass.simpleName}: ${t.message}")
            runCatching {
                stopActiveJobsAndCore()
                isTornDown.set(true)
                connectionManager.onServiceDisconnected(
                    notice = getString(R.string.vpn_notice_start_failed),
                    errorDetail = t.message
                )
                stopForegroundAndSelf()
            }
        }
        serviceScope = CoroutineScope(SupervisorJob() + ioDispatcher + handler)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    teardownInternal(notice = null, errorDetail = null)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val canonicalKey = intent.getStringExtra(EXTRA_CANONICAL_KEY)
                // The service was started with startForegroundService(): startForeground() must run
                // before ANY exit path, otherwise Android throws ForegroundServiceDidNotStartInTimeException.
                promoteToForeground(
                    buildStatusNotification(
                        title = getString(R.string.vpn_notif_connecting),
                        text = getString(R.string.vpn_notif_preparing)
                    )
                )
                if (canonicalKey.isNullOrBlank()) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                isTornDown.set(false)
                serviceScope.launch {
                    failedInSessionKeys.clear()
                    startTunnelForKey(canonicalKey, isFailover = false)
                }
                return START_NOT_STICKY
            }
            else -> {
                promoteToForeground(
                    buildStatusNotification(
                        title = getString(R.string.vpn_notif_connecting),
                        text = getString(R.string.vpn_notif_preparing)
                    )
                )
                // Always-on VPN: the system starts the service with action SERVICE_INTERFACE (or a null
                // intent after a process restart). Connect to the best known server instead of quitting.
                val systemStart = intent == null || intent.action == SERVICE_INTERFACE
                if (!systemStart) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                isTornDown.set(false)
                serviceScope.launch {
                    val networkKey = networkKeyProvider.currentNetworkInfo().networkKey
                    val best = repository.getBestServers(networkKey, limit = 1).firstOrNull()
                    if (best == null) {
                        isTornDown.set(true)
                        connectionManager.onServiceDisconnected(
                            notice = getString(R.string.vpn_notice_no_server),
                            errorDetail = null
                        )
                        stopForegroundAndSelf()
                    } else {
                        failedInSessionKeys.clear()
                        startTunnelForKey(best.config.canonicalKey, isFailover = false)
                    }
                }
                return START_STICKY
            }
        }
    }
    override fun onRevoke() {
        serviceScope.launch {
            teardownInternal(
                notice = getString(R.string.vpn_notice_revoked),
                errorDetail = null
            )
            stopSelf()
        }
        super.onRevoke()
    }
    override fun onDestroy() {
        if (::trafficHistory.isInitialized) trafficHistory.flush()
        val instanceToClose = proxyInstance
        val tunToClose = tunInterface
        val coreFdToCheck = coreTunFd
        proxyInstance = null
        tunInterface = null
        activeServer = null
        runCatching { statsJob?.cancel() }
        runCatching { healthJob?.cancel() }
        if (::serviceScope.isInitialized) {
            serviceScope.cancel()
        }
        if (isTornDown.compareAndSet(false, true) && ::connectionManager.isInitialized) {
            connectionManager.onServiceDisconnected(notice = null, errorDetail = null)
        }
        if (instanceToClose != null || tunToClose != null) {
            Thread({
                    runCatching { instanceToClose?.close() }
                    runCatching {
                        if (coreFdToCheck >= 0 && tunToClose != null && coreFdToCheck != tunToClose.fd &&
                            Os.readlink("/proc/self/fd/${tunToClose.fd}") == Os.readlink("/proc/self/fd/$coreFdToCheck")
                        ) ParcelFileDescriptor.adoptFd(coreFdToCheck).close()
                    }
                    runCatching { tunToClose?.close() }
                }, "kestrel-vpn-cleanup").apply {
                isDaemon = true
                start()
            }
        }
        super.onDestroy()
    }
    private suspend fun startTunnelForKey(
        canonicalKey: String,
        isFailover: Boolean,
        failoverNotice: String? = null
    ) = lifecycleMutex.withLock {
        // On failover the TUN stays open (nobody reads it while the core restarts), so traffic is
        // black-holed instead of leaking out over the physical network. That is the kill-switch.
        stopActiveJobsAndCore(closeTun = !isFailover)
        val config = repository.getConfigByKey(canonicalKey)
        if (config == null) {
            isTornDown.set(true)
            connectionManager.onServiceDisconnected(
                notice = getString(R.string.vpn_notice_no_server),
                errorDetail = "Config $canonicalKey not found"
            )
            stopForegroundAndSelf()
            return@withLock
        }
        val networkKey = networkKeyProvider.currentNetworkInfo().networkKey
        val server = ServerWithResult(config, repository.getTestResult(canonicalKey, networkKey))
        activeServer = server
        connectionManager.onServiceConnecting(server, isFailover)
        updateStatusNotification(
            title = if (isFailover) {
                getString(R.string.vpn_notif_failover)
            } else {
                getString(R.string.vpn_notif_connecting)
            },
            text = serverDisplayLabel(server)
        )
        val prefs = preferencesStore.current()
        val tunPfd = (if (isFailover) tunInterface else null) ?: establishTunInterface(prefs)
        if (tunPfd == null) {
            isTornDown.set(true)
            connectionManager.onServiceDisconnected(
                notice = getString(R.string.vpn_notice_tun_failed),
                errorDetail = "VpnService.Builder.establish() returned null"
            )
            stopForegroundAndSelf()
            return@withLock
        }
        tunInterface = tunPfd
        val instance = try {
            // libbox gets its OWN duplicate of the TUN fd (ownership handed over with detachFd()),
            // while we keep the original. Sharing one fd number would make both sides close it.
            val coreFd = tunPfd.dup().detachFd()
            coreTunFd = coreFd
            try {
                proxyCore.startTunTunnel(
                    config = config,
                    tunFd = coreFd,
                    mtu = TUN_MTU,
                    protectSocket = { fd -> protect(fd) },
                    bypassIran = prefs.bypassIran
                )
            } catch (t: Throwable) {
                runCatching { ParcelFileDescriptor.adoptFd(coreFd).close() }
                throw t
            }
        } catch (e: CoreUnavailableException) {
            failStart(isFailover, getString(R.string.vpn_notice_core_missing), e.message)
            return@withLock
        } catch (t: Throwable) {
            DiagnosticsLog.add("vpn", "start failed: ${t.javaClass.simpleName}: ${t.message}")
            failStart(isFailover, getString(R.string.vpn_notice_start_failed), t.message)
            return@withLock
        }
        proxyInstance = instance
        com.kestrel.vpn.engine.core.ProbeProtector.protect = { fd -> protect(fd) }
        DiagnosticsLog.add("vpn", "connected tier=${server.tier} failover=$isFailover")
        connectionManager.onServiceConnected(server, notice = failoverNotice)
        updateStatusNotification(
            title = getString(R.string.vpn_notif_connected_tier, tierLabel(server.tier)),
            text = serverDisplayLabel(server)
        )
        startStatsLoop(server, instance)
        startHealthMonitorLoop(server)
    }
    /**
 * Start failure. During failover the TUN is KEPT open: closing it would silently send all
 * traffic over the physical network (a leak). The user is told traffic is blocked and can
 * disconnect from the notification / tile, or reconnect.
 */
    private fun failStart(isFailover: Boolean, notice: String, detail: String?) {
        if (isFailover && tunInterface != null) {
            connectionManager.onServiceDisconnected(notice = notice, errorDetail = detail ?: "failover failed")
            postAlertNotification(
                title = getString(R.string.vpn_alert_blocked_title),
                body = getString(R.string.vpn_alert_blocked_body)
            )
            updateStatusNotification(
                title = getString(R.string.vpn_alert_blocked_title),
                text = getString(R.string.vpn_alert_blocked_body)
            )
            return
        }
        closeTunOnly()
        isTornDown.set(true)
        connectionManager.onServiceDisconnected(notice = notice, errorDetail = detail)
        stopForegroundAndSelf()
    }
    private fun establishTunInterface(prefs: UserPreferences): ParcelFileDescriptor? {
        return runCatching {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .addAddress("172.19.0.1", 30)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("0.0.0.0", 0)
                // IPv6 default route as well: without it IPv6 traffic would bypass the tunnel.
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            val plan = SplitTunnelPlan.from(prefs.splitMode, prefs.splitPackages, packageName)
            plan.disallowed.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            plan.allowed.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            builder.establish()
        }.getOrNull()
    }
    private fun startStatsLoop(server: ServerWithResult, instance: ProxyInstance) {
        statsJob?.cancel()
        val connectedAtMs = System.currentTimeMillis()
        var prevRx = 0L
        var prevTx = 0L
        var tickCount = 0
        statsJob = serviceScope.launch {
            while (isActive && instance.isRunning()) {
                delay(1_000L)
                val stats = instance.readTrafficStats()
                val rxRate = (stats.rxBytes - prevRx).coerceAtLeast(0L)
                val txRate = (stats.txBytes - prevTx).coerceAtLeast(0L)
                prevRx = stats.rxBytes
                prevTx = stats.txBytes
                val durationSec = ((System.currentTimeMillis() - connectedAtMs) / 1_000L).coerceAtLeast(0L)
                connectionManager.onStatsTick(
                    durationSeconds = durationSec,
                    rxBytesPerSec = rxRate,
                    txBytesPerSec = txRate,
                    totalRxBytes = stats.rxBytes,
                    totalTxBytes = stats.txBytes
                )
                trafficHistory.add(rxRate, txRate)
                tickCount++
                if (tickCount % 2 == 0) {
                    val speedText =
                    "↓ ${LocaleFormatters.formatLiveRate(rxRate)}  ↑ ${LocaleFormatters.formatLiveRate(txRate)}  •  ${LocaleFormatters.formatDuration(durationSec)}"
                    updateStatusNotification(
                        title = getString(R.string.vpn_notif_connected_tier, tierLabel(server.tier)),
                        text = speedText
                    )
                }
            }
            DiagnosticsLog.add("vpn", "core stopped (flag cleared)")
            // Core died underneath us (crash/OOM): the TUN stays open and black-holes traffic (no leak),
            // but the user must not be left silently disconnected.
            if (isActive && !instance.isRunning() && proxyInstance === instance) {
                healthJob?.cancel()
                // Separate coroutine: failover cancels statsJob (this one) while restarting the tunnel.
                serviceScope.launch { handleAutoFailover(server) }
            }
        }
    }

    private fun startHealthMonitorLoop(server: ServerWithResult) {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            val tracker = TunnelHealthTracker(MAX_CONSECUTIVE_FAILURES)
            // Startup verification: a config can pass the probe yet pass no traffic in TUN mode.
            // Check right after connecting and fail over in seconds instead of ~36 s.
            delay(STARTUP_CHECK_DELAY_MS)
            var startupFailures = 0
            while (isActive) {
                val outcome = try {
                    healthProbe.checkHealth()
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    TunnelHealthOutcome.HEALTHY
                }
                if (outcome != TunnelHealthOutcome.TUNNEL_FAILED_INTERNET_OK) {
                    connectionManager.onOfflineWhileConnectedChanged(outcome == TunnelHealthOutcome.DEVICE_OFFLINE)
                    break
                }
                startupFailures++
                if (startupFailures >= STARTUP_MAX_FAILURES) {
                    DiagnosticsLog.add("vpn", "startup verification failed ($startupFailures)")
                    healthJob = null
                    serviceScope.launch { handleAutoFailover(server) }
                    return@launch
                }
                delay(STARTUP_CHECK_INTERVAL_MS)
            }
            delay(HEALTH_INITIAL_DELAY_MS)
            while (isActive) {
                val verdict = tracker.record(try {
                    healthProbe.checkHealth()
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    TunnelHealthOutcome.HEALTHY
                })
                connectionManager.onOfflineWhileConnectedChanged(verdict.offlineWhileConnected)
                if (verdict.shouldFailover) {
                    healthJob = null
                    serviceScope.launch { handleAutoFailover(server) }
                    return@launch
                }
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }
    private suspend fun handleAutoFailover(failedServer: ServerWithResult) {
        if (!failoverInProgress.compareAndSet(false, true)) return
        try {
            doFailover(failedServer)
        } finally {
            failoverInProgress.set(false)
        }
    }
    private suspend fun doFailover(failedServer: ServerWithResult) {
        DiagnosticsLog.add("vpn", "failover triggered tier=${failedServer.tier}")
        val networkKey = networkKeyProvider.currentNetworkInfo().networkKey
        failedInSessionKeys.add(failedServer.config.canonicalKey)
        if (aliasStore.lastGoodKey() == failedServer.config.canonicalKey) aliasStore.setLastGood(null)
        // A tunnel that failed while the phone had no network is not the server's fault.
        if (networkKeyProvider.currentNetworkInfo().hasSystemNetwork) {
            val prev = repository.getTestResult(failedServer.config.canonicalKey, networkKey)
            repository.saveTestResult(
                scorer.scoreFailure(
                    canonicalKey = failedServer.config.canonicalKey,
                    networkKey = networkKey,
                    previous = prev,
                    nowMs = System.currentTimeMillis()
                )
            )
        }
        val candidates = repository.getBestServers(networkKey, limit = 15)
        val nextServer = FailoverPolicy.next(candidates, failedInSessionKeys)
        if (nextServer == null) {
            postAlertNotification(
                title = getString(R.string.vpn_alert_failover_exhausted_title),
                body = getString(R.string.vpn_alert_failover_exhausted_body)
            )
            teardownInternal(
                notice = getString(R.string.vpn_alert_failover_exhausted_body),
                errorDetail = null
            )
            stopSelf()
            return
        }
        val notice = getString(R.string.vpn_alert_failover_switched_body, tierLabel(nextServer.tier))
        postAlertNotification(
            title = getString(R.string.vpn_alert_failover_switched_title),
            body = notice
        )
        startTunnelForKey(
            canonicalKey = nextServer.config.canonicalKey,
            isFailover = true,
            failoverNotice = notice
        )
    }
    private suspend fun teardownInternal(notice: String?, errorDetail: String?) =
    lifecycleMutex.withLock {
        stopActiveJobsAndCore()
        activeServer = null
        isTornDown.set(true)
        connectionManager.onServiceDisconnected(notice = notice, errorDetail = errorDetail)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
    private fun stopActiveJobsAndCore(closeTun: Boolean = true) {
        statsJob?.cancel()
        statsJob = null
        healthJob?.cancel()
        healthJob = null
        com.kestrel.vpn.engine.core.ProbeProtector.protect = null
        runCatching { proxyInstance?.close() }
        proxyInstance = null
        releaseCoreFd(tunInterface)
        if (closeTun) {
            closeTunOnly()
        }
    }
    /**
     * libbox owns the duplicated TUN fd, but if its close() fails or is partial the fd stays open and
     * Android keeps showing the VPN key icon. Close it ourselves if it is still a TUN descriptor.
     */
    private fun releaseCoreFd(original: ParcelFileDescriptor?) {
        val fd = coreTunFd
        coreTunFd = -1
        if (fd < 0 || original == null) return
        runCatching {
            val mine = Os.readlink("/proc/self/fd/${original.fd}")
            val theirs = Os.readlink("/proc/self/fd/$fd")
            if (fd != original.fd && mine == theirs) {
                ParcelFileDescriptor.adoptFd(fd).close()
            }
        }
    }

    private fun closeTunOnly() {
        runCatching { tunInterface?.close() }
        tunInterface = null
    }
    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun promoteToForeground(notification: Notification) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID_STATUS, notification, fgsType)
        } catch (t: Throwable) {
            DiagnosticsLog.add("vpn", "startForeground failed: ${t.javaClass.simpleName}: ${t.message}")
            isTornDown.set(true)
            connectionManager.onServiceDisconnected(
                notice = getString(R.string.vpn_notice_start_failed),
                errorDetail = t.message
            )
            stopSelf()
        }
    }
    private fun updateStatusNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID_STATUS, buildStatusNotification(title, text))
    }
    private fun buildStatusNotification(title: String, text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KestrelVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, KestrelApp.CHANNEL_VPN_STATUS)
            .setSmallIcon(R.drawable.ic_tile_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .addAction(
            R.drawable.ic_tile_vpn,
            getString(R.string.vpn_action_disconnect),
            disconnectIntent
        )
            .build()
    }
    private fun postAlertNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, KestrelApp.CHANNEL_VPN_ALERTS)
            .setSmallIcon(R.drawable.ic_tile_vpn)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_ALERT, notification)
    }
    private fun tierLabel(tier: ServerTier): String = when (tier) {
        ServerTier.EXCELLENT -> getString(R.string.tier_excellent)
        ServerTier.GOOD -> getString(R.string.tier_good)
        ServerTier.SLOW -> getString(R.string.tier_slow)
        ServerTier.DEAD -> getString(R.string.tier_dead)
        ServerTier.UNTESTED -> getString(R.string.tier_untested)
    }
    private fun serverDisplayLabel(server: ServerWithResult): String =
    aliasStore.nameFor(server.config)
    companion object {
        const val ACTION_CONNECT = "com.kestrel.vpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.kestrel.vpn.action.DISCONNECT"
        const val EXTRA_CANONICAL_KEY = "extra_canonical_key"
        private const val SERVICE_INTERFACE = VpnService.SERVICE_INTERFACE
        // 1500 plus proxy overhead fragments; 1400 leaves room for VLESS/VMess/Trojan + TLS framing.
        private const val TUN_MTU = 1400
        private const val NOTIFICATION_ID_STATUS = 1001
        private const val NOTIFICATION_ID_ALERT = 1002
        private const val STARTUP_CHECK_DELAY_MS = 1_500L
        private const val STARTUP_CHECK_INTERVAL_MS = 2_000L
        private const val STARTUP_MAX_FAILURES = 3
        private const val HEALTH_INITIAL_DELAY_MS = 6_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3 // ~30 s: the health probe is the dead-core detector
    }
}
