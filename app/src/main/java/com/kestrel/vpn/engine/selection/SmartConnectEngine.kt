package com.kestrel.vpn.engine.selection

import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.model.TestMode
import com.kestrel.vpn.domain.model.TestSpeedMode
import com.kestrel.vpn.data.local.ServerAliasStore
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.probe.ProxyProber
import com.kestrel.vpn.engine.scheduler.ChunkedTestScheduler
import com.kestrel.vpn.engine.scheduler.QuickScanRequest
import com.kestrel.vpn.engine.scheduler.ScanUiState
import com.kestrel.vpn.engine.scoring.ServerScorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SmartConnectOutcome {
    data class Found(val canonicalKey: String, val reusedPrevious: Boolean) : SmartConnectOutcome
    data object NoneFound : SmartConnectOutcome
    data class Error(val message: String) : SmartConnectOutcome
}

/**
 * "Connect" button brain:
 * 1. Re-benchmark the server that worked last time; if it still works, use it.
 * 2. Otherwise search: speed-test the best known servers, scan more in short chunks, and pick the
 *    server that stands out (or, when all look alike, the best one with download > 0.05 Mbit/s).
 */
@Singleton
class SmartConnectEngine @Inject constructor(
    private val repository: ConfigRepository,
    private val prober: ProxyProber,
    private val scorer: ServerScorer,
    private val scheduler: ChunkedTestScheduler,
    private val aliasStore: ServerAliasStore,
    private val connectivityChecker: com.kestrel.vpn.engine.probe.ConnectivityChecker
) {
    suspend fun findServer(
        networkKey: String,
        speedPayloadKb: Int,
        reusePrevious: Boolean = true,
        speedMode: TestSpeedMode = TestSpeedMode.MEDIUM,
        minSpeedKbps: Int = ServerSelector.MIN_DOWNLOAD_KBPS
    ): SmartConnectOutcome {
        try {
            val previousKey = aliasStore.lastGoodKey()
            com.kestrel.vpn.util.DiagnosticsLog.add("smart", "find start lastGood=${previousKey != null}")
            if (reusePrevious && previousKey != null) {
                val cfg = repository.getConfigByKey(previousKey)
                if (cfg != null && benchmark(cfg, networkKey, speedPayloadKb, minSpeedKbps)) {
                    return SmartConnectOutcome.Found(previousKey, reusedPrevious = true)
                }
            }
            // Quick search in short chunks (never a full Hunt). Phase 1: for a few seconds look for a server that
            // clearly stands out. Phase 2: if none did, keep going only until some server reaches the minimum
            // download speed (0.05 Mbit/s), then connect. Ends early only when the list is exhausted or the user cancels.
            val start = System.currentTimeMillis()
            while (true) {
                com.kestrel.vpn.util.DiagnosticsLog.add("smart", "round")
                speedTestTopWithoutSpeed(networkKey, speedPayloadKb, minSpeedKbps)
                val inWindow = System.currentTimeMillis() - start < PREFER_WINDOW_MS
                val known = ServerSelector.choose(repository.getBestServers(networkKey, limit = 40), minSpeedKbps)
                if (known != null) {
                    val clear = known.standsOut && known.comparedCount >= ServerSelector.MIN_COMPARED
                    if (clear || !inWindow) {
                        return SmartConnectOutcome.Found(known.server.config.canonicalKey, reusedPrevious = false)
                    }
                }
                val res = scheduler.runQuickScan(
                    QuickScanRequest(
                        networkKey = networkKey,
                        testMode = TestMode.PING_AND_SPEED,
                        speedPayloadKb = speedPayloadKb,
                        timeBoxMs = 8_000L,
                        speedTimeBoxMs = 12_000L,
                        maxConfigsInChunk = speedMode.chunkSize,
                        topKForSpeed = 3,
                        earlyStopExcellentTarget = 2,
                        stopAfterUsableTarget = 3,
                        speedStopKbps = minSpeedKbps.coerceAtLeast(1),
                        speedMode = speedMode
                    )
                )
                if (res is ScanUiState.Error) return SmartConnectOutcome.Error(res.message)
                val exhausted = res is ScanUiState.Completed && res.testedInChunk == 0
                val pick = ServerSelector.choose(repository.getBestServers(networkKey, limit = 40), minSpeedKbps)
                if (pick != null) {
                    val stillComparing = System.currentTimeMillis() - start < PREFER_WINDOW_MS && !pick.standsOut && !exhausted
                    if (!stillComparing) {
                        return SmartConnectOutcome.Found(pick.server.config.canonicalKey, reusedPrevious = false)
                    }
                }
                if (exhausted) return SmartConnectOutcome.NoneFound
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return SmartConnectOutcome.Error(t.message ?: "Server search failed")
        }
    }

    /**
     * Quick health check of the previously good server: ping (one retry), then a short download.
     * Forgiving on purpose: a one-off glitch must not throw away a server that worked a minute ago.
     * Returns true only when it is alive and delivers a usable download speed.
     */
    private suspend fun benchmark(config: ProxyConfig, networkKey: String, speedPayloadKb: Int, minSpeedKbps: Int): Boolean {
        val key = config.canonicalKey
        com.kestrel.vpn.util.DiagnosticsLog.add("smart", "benchmark last-good ${key.takeLast(10)}")
        var ping = prober.runPingStage(config, samples = 2)
        if (!ping.alive || ping.pingMs == null) {
            delay(400)
            ping = prober.runPingStage(config, samples = 2)
        }
        val prev = repository.getTestResult(key, networkKey)
        if (!ping.alive || ping.pingMs == null) {
            // No internet on the phone = no verdict: keep the server's record and "last good" untouched.
            if (!connectivityChecker.isInternetReachable()) return false
            repository.saveTestResult(scorer.scoreFailure(key, networkKey, prev, System.currentTimeMillis()))
            aliasStore.setLastGood(null)
            return false
        }
        val afterPing = scorer.scoreSuccess(
            canonicalKey = key,
            networkKey = networkKey,
            pingMs = ping.pingMs,
            jitterMs = ping.jitterMs ?: 0,
            speedKbps = null,
            previous = prev,
            nowMs = System.currentTimeMillis()
        )
        repository.saveTestResult(afterPing)
        val speed = prober.runSpeedStage(config, speedPayloadKb)
        val kbps = speed.speedKbps
        if (!speed.success || kbps == null || kbps < minSpeedKbps) return false
        repository.saveTestResult(
            scorer.scoreSuccess(
                canonicalKey = key,
                networkKey = networkKey,
                pingMs = ping.pingMs,
                jitterMs = ping.jitterMs ?: 0,
                speedKbps = kbps,
                previous = afterPing,
                nowMs = System.currentTimeMillis(),
                updateEma = false
            )
        )
        return true
    }

    /** Servers that only have a ping result get a speed test, one at a time, stopping at the first fast one. */
    private suspend fun speedTestTopWithoutSpeed(networkKey: String, speedPayloadKb: Int, minSpeedKbps: Int) {
        val targets: List<ServerWithResult> = repository.getBestServers(networkKey, limit = 12)
            .filter { it.result?.speedKbps == null && it.result?.pingMs != null }
            .take(TOP_FOR_SPEED)
        for (s in targets) {
            val outcome = prober.runSpeedStage(s.config, speedPayloadKb)
            val prev = s.result
            val kbps = outcome.speedKbps
            if (outcome.success && kbps != null && prev?.pingMs != null) {
                repository.saveTestResult(
                    scorer.scoreSuccess(
                        canonicalKey = s.config.canonicalKey,
                        networkKey = networkKey,
                        pingMs = prev.pingMs,
                        jitterMs = prev.jitterMs ?: 0,
                        speedKbps = kbps,
                        previous = prev,
                        nowMs = System.currentTimeMillis(),
                        updateEma = false
                    )
                )
                if (kbps >= minSpeedKbps) return
            }
        }
    }

    private companion object {
        // How long to look for a clearly better server before settling for the first one that is fast enough.
        const val PREFER_WINDOW_MS = 10_000L
        const val TOP_FOR_SPEED = 3
    }
}
