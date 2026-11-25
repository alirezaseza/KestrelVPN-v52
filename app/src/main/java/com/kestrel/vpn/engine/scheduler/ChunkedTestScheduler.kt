package com.kestrel.vpn.engine.scheduler
import com.kestrel.vpn.domain.core.CoreUnavailableException
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.ScanProgress
import com.kestrel.vpn.domain.model.ServerTestResult
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.TestMode
import com.kestrel.vpn.domain.model.TestSpeedMode
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.PeriodicConnectivityGate
import com.kestrel.vpn.engine.probe.PingProbeOutcome
import com.kestrel.vpn.engine.probe.ProxyProber
import com.kestrel.vpn.engine.scoring.ServerScorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
data class QuickScanRequest(
    val networkKey: String,
    val testMode: TestMode,
    val speedPayloadKb: Int = 300,
    val timeBoxMs: Long = 22_000L,
    val speedTimeBoxMs: Long = 12_000L,
    val maxConfigsInChunk: Int = 80,
    val topKForSpeed: Int = 15,
    val earlyStopExcellentTarget: Int = 5,
    // > 0: stop the chunk as soon as this many EXCELLENT/GOOD servers were found (fast-connect mode).
    val stopAfterUsableTarget: Int = 0,
    // > 0: during the speed stage, stop testing further servers once one reaches this download speed (kbps).
    val speedStopKbps: Int = 0,
    val speedMode: TestSpeedMode = TestSpeedMode.MEDIUM
)
sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Running(
        val testedInChunk: Int,
        val totalTestedOverall: Int,
        val totalConfigs: Int,
        val excellentFound: Int,
        val currentConcurrency: Int,
        val phaseLabel: String
    ) : ScanUiState
    data class PausedOffline(
        val testedInChunk: Int,
        val totalTestedOverall: Int,
        val totalConfigs: Int
    ) : ScanUiState
    data class Completed(
        val testedInChunk: Int,
        val totalTestedOverall: Int,
        val totalConfigs: Int,
        val excellentFound: Int,
        val stoppedEarly: Boolean,
        val hasMoreToTest: Boolean
    ) : ScanUiState
    data class Error(val message: String) : ScanUiState
}
@Singleton
class ChunkedTestScheduler @Inject constructor(
    private val repository: ConfigRepository,
    private val prober: ProxyProber,
    private val scorer: ServerScorer,
    private val connectivityChecker: ConnectivityChecker,
    private val resourceMonitor: ResourceConstraintProvider
) {
    private val sessionMutex = Mutex()
    private val sessionClaimedKeys = HashSet<String>()
    private var activeSessionNetworkKey: String? = null
    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    suspend fun resetSession() {
        sessionMutex.withLock {
            sessionClaimedKeys.clear()
            activeSessionNetworkKey = null
        }
    }
    internal suspend fun tryClaimKey(canonicalKey: String): Boolean = sessionMutex.withLock {
        sessionClaimedKeys.add(canonicalKey)
    }
    internal suspend fun releaseClaimedKey(canonicalKey: String) {
        sessionMutex.withLock {
            sessionClaimedKeys.remove(canonicalKey)
        }
    }
    suspend fun runQuickScan(
        request: QuickScanRequest,
        clockMs: () -> Long = { System.currentTimeMillis() }
    ): ScanUiState {
        sessionMutex.withLock {
            if (activeSessionNetworkKey != request.networkKey) {
                sessionClaimedKeys.clear()
                activeSessionNetworkKey = request.networkKey
            }
        }
        val totalActive = repository.observeActiveCount().first()
        if (totalActive == 0) {
            val done = ScanUiState.Completed(
                testedInChunk = 0,
                totalTestedOverall = 0,
                totalConfigs = 0,
                excellentFound = 0,
                stoppedEarly = false,
                hasMoreToTest = false
            )
            _state.value = done
            return done
        }
        val priorProgress = repository.getScanProgress(request.networkKey)
        val baseTestedCount = priorProgress?.testedCount ?: 0
        val nowStart = clockMs()
        val deadlineMs = nowStart + request.timeBoxMs
        val staleBeforeMs = nowStart - ServerScorer.ALIVE_RESULT_TTL_MS
        val rawCandidates = repository.getCandidateBatchForTesting(
            networkKey = request.networkKey,
            nowMs = nowStart,
            staleBeforeMs = staleBeforeMs,
            limit = request.maxConfigsInChunk * 2
        )
        val claimedQueue = ArrayDeque<ProxyConfig>(request.maxConfigsInChunk)
        for (candidate in rawCandidates) {
            if (claimedQueue.size >= request.maxConfigsInChunk) break
            if (tryClaimKey(candidate.canonicalKey)) {
                claimedQueue.addLast(candidate)
            }
        }
        if (claimedQueue.isEmpty()) {
            val done = ScanUiState.Completed(
                testedInChunk = 0,
                totalTestedOverall = baseTestedCount,
                totalConfigs = totalActive,
                excellentFound = priorProgress?.excellentCount ?: 0,
                stoppedEarly = false,
                hasMoreToTest = false
            )
            _state.value = done
            return done
        }
        // Floor of 4: dead servers are no longer reported as congestion, so the floor only guards
        // against genuine overload and keeps scans from crawling.
        val pingWorkers = request.speedMode.pingWorkers
        val aimd = AdaptiveConcurrencyController(
            initialConcurrency = pingWorkers,
            minConcurrency = minOf(3, pingWorkers),
            maxConcurrency = pingWorkers
        )
        val testedInChunk = AtomicInteger(0)
        val excellentInChunk = AtomicInteger(0)
        val usableInChunk = AtomicInteger(0)
        val stage1Survivors = mutableListOf<Pair<ProxyConfig, ServerTestResult>>()
        val survivorsMutex = Mutex()
        val queueMutex = Mutex()
        val stopRequested = AtomicBoolean(false)
        // A failed probe is only recorded when the phone itself is online (see the gate); otherwise: wait, re-test.
        val gate = PeriodicConnectivityGate(connectivityChecker)
        var stoppedEarly = false
        try {
            com.kestrel.vpn.util.DiagnosticsLog.add("scan", "chunk start n=${claimedQueue.size} mode=${request.testMode}")
            _state.value = ScanUiState.Running(
                testedInChunk = 0,
                totalTestedOverall = baseTestedCount.coerceAtMost(totalActive),
                totalConfigs = totalActive,
                excellentFound = 0,
                currentConcurrency = aimd.currentLimit(resourceMonitor.isConstrained()),
                phaseLabel = "ping"
            )
            // Worker pool instead of lock-step waves: one slow server no longer stalls its whole wave,
            // and no new probe is started once the deadline has passed.
            coroutineScope {
                repeat(pingWorkers) { workerIndex ->
                    launch {
                        while (true) {
                            if (stopRequested.get() || clockMs() >= deadlineMs) break
                            if (request.stopAfterUsableTarget > 0 && usableInChunk.get() >= request.stopAfterUsableTarget) {
                                stoppedEarly = true
                                stopRequested.set(true)
                                break
                            }
                            if (excellentInChunk.get() >= request.earlyStopExcellentTarget) {
                                stoppedEarly = true
                                stopRequested.set(true)
                                break
                            }
                            if (workerIndex >= aimd.currentLimit(resourceMonitor.isConstrained())) {
                                // Worker is parked by the concurrency controller.
                                if (queueMutex.withLock { claimedQueue.isEmpty() }) break
                                delay(WORKER_PARK_MS)
                                continue
                            }
                            val next = queueMutex.withLock { claimedQueue.removeFirstOrNull() } ?: break
                            gate.awaitOnline()
                            run {
                                com.kestrel.vpn.util.DiagnosticsLog.add("scan", "ping ${next.canonicalKey.takeLast(10)}")
                                val epoch = gate.epoch()
                                val probe = try {
                                    prober.runPingStage(next, samples = request.speedMode.pingSamples)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: CoreUnavailableException) {
                                    throw e
                                } catch (t: Throwable) {
                                    // One broken config must never take the whole scan (or the app) down.
                                    PingProbeOutcome(
                                        alive = false,
                                        pingMs = null,
                                        jitterMs = null,
                                        timedOut = false,
                                        failureReason = t.message
                                    )
                                }
                                val probeFailed = !(probe.alive && probe.pingMs != null)
                                val phoneWasOffline = probeFailed && !gate.confirmServerFault(epoch) {
                                    _state.value = ScanUiState.PausedOffline(
                                        testedInChunk = testedInChunk.get(),
                                        totalTestedOverall = (baseTestedCount + testedInChunk.get()).coerceAtMost(totalActive),
                                        totalConfigs = totalActive
                                    )
                                }
                                if (phoneWasOffline) {
                                    // Not the server's fault: save nothing and test it again once the internet is back.
                                    queueMutex.withLock { claimedQueue.addFirst(next) }
                                } else {
                                val prev = repository.getTestResult(next.canonicalKey, request.networkKey)
                                val evalTime = clockMs()
                                val result = if (probe.alive && probe.pingMs != null) {
                                    scorer.scoreSuccess(
                                        canonicalKey = next.canonicalKey,
                                        networkKey = request.networkKey,
                                        pingMs = probe.pingMs,
                                        jitterMs = probe.jitterMs ?: 0,
                                        speedKbps = null,
                                        previous = prev,
                                        nowMs = evalTime
                                    )
                                } else {
                                    scorer.scoreFailure(
                                        canonicalKey = next.canonicalKey,
                                        networkKey = request.networkKey,
                                        previous = prev,
                                        nowMs = evalTime
                                    )
                                }
                                repository.saveTestResult(result)
                                aimd.recordOutcome(
                                    timedOut = probe.timedOut,
                                    deviceConstrained = resourceMonitor.isConstrained()
                                )
                                val tested = testedInChunk.incrementAndGet()
                                if (result.tier == ServerTier.EXCELLENT) {
                                    excellentInChunk.incrementAndGet()
                                }
                                if (result.tier == ServerTier.EXCELLENT || result.tier == ServerTier.GOOD) {
                                    usableInChunk.incrementAndGet()
                                }
                                if (result.tier != ServerTier.DEAD) {
                                    survivorsMutex.withLock {
                                        stage1Survivors += (next to result)
                                    }
                                }
                                _state.value = ScanUiState.Running(
                                    testedInChunk = tested,
                                    totalTestedOverall = (baseTestedCount + tested).coerceAtMost(totalActive),
                                    totalConfigs = totalActive,
                                    excellentFound = excellentInChunk.get(),
                                    currentConcurrency = aimd.currentLimit(resourceMonitor.isConstrained()),
                                    phaseLabel = "ping"
                                )
                                }
                            }
                        }
                    }
                }
            }
            // Anything not started (deadline, early stop) goes back to the pool so later scans can claim it.
            while (true) {
                val leftover = claimedQueue.removeFirstOrNull() ?: break
                releaseClaimedKey(leftover.canonicalKey)
            }
            if (request.testMode == TestMode.PING_AND_SPEED && stage1Survivors.isNotEmpty()) {
                val topK = stage1Survivors
                    .sortedByDescending { it.second.score }
                    .take(request.topKForSpeed)
                val speedQueue = ArrayDeque(topK)
                val speedQueueMutex = Mutex()
                val speedDeadlineMs = clockMs() + request.speedTimeBoxMs
                _state.value = ScanUiState.Running(
                    testedInChunk = testedInChunk.get(),
                    totalTestedOverall = (baseTestedCount + testedInChunk.get()).coerceAtMost(totalActive),
                    totalConfigs = totalActive,
                    excellentFound = excellentInChunk.get(),
                    currentConcurrency = request.speedMode.speedWorkers,
                    phaseLabel = "speed"
                )
                coroutineScope {
                    repeat(request.speedMode.speedWorkers) {
                        launch {
                            while (clockMs() < speedDeadlineMs) {
                                val (config, pingResult) = speedQueueMutex.withLock { speedQueue.removeFirstOrNull() } ?: break
                                gate.awaitOnline()
                                com.kestrel.vpn.util.DiagnosticsLog.add("scan", "speed ${config.canonicalKey.takeLast(10)}")
                                val speedOutcome = try {
                                    prober.runSpeedStage(config, request.speedPayloadKb)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: CoreUnavailableException) {
                                    throw e
                                } catch (t: Throwable) {
                                    com.kestrel.vpn.engine.probe.SpeedProbeOutcome(
                                        success = false,
                                        speedKbps = null,
                                        timedOut = false,
                                        failureReason = t.message
                                    )
                                }
                                if (speedOutcome.success && speedOutcome.speedKbps != null && pingResult.pingMs != null) {
                                    // The ping stage already counted this probe in the EMA: don't count it twice.
                                    val updated = scorer.scoreSuccess(
                                        canonicalKey = config.canonicalKey,
                                        networkKey = request.networkKey,
                                        pingMs = pingResult.pingMs,
                                        jitterMs = pingResult.jitterMs ?: 0,
                                        speedKbps = speedOutcome.speedKbps,
                                        previous = pingResult,
                                        nowMs = clockMs(),
                                        updateEma = false
                                    )
                                    repository.saveTestResult(updated)
                                    if (request.speedStopKbps > 0 && speedOutcome.speedKbps >= request.speedStopKbps) {
                                        speedQueueMutex.withLock { speedQueue.clear() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // COUNT query: this used to load every alive server (with its config) just to count two tiers.
            val tierCounts = repository.countServersByTier(request.networkKey)
            val totalExcellent = tierCounts[ServerTier.EXCELLENT] ?: 0
            val totalGood = tierCounts[ServerTier.GOOD] ?: 0
            val updatedTestedCount = (baseTestedCount + testedInChunk.get()).coerceAtMost(totalActive)
            repository.saveScanProgress(
                ScanProgress(
                    networkKey = request.networkKey,
                    totalConfigs = totalActive,
                    testedCount = updatedTestedCount,
                    excellentCount = totalExcellent,
                    goodCount = totalGood,
                    lastScanAtMs = clockMs(),
                    cursorOffset = updatedTestedCount
                )
            )
            val done = ScanUiState.Completed(
                testedInChunk = testedInChunk.get(),
                totalTestedOverall = updatedTestedCount,
                totalConfigs = totalActive,
                excellentFound = totalExcellent,
                stoppedEarly = stoppedEarly,
                hasMoreToTest = updatedTestedCount < totalActive
            )
            _state.value = done
            return done
        } catch (ce: CancellationException) {
            while (claimedQueue.isNotEmpty()) {
                releaseClaimedKey(claimedQueue.removeFirst().canonicalKey)
            }
            _state.value = ScanUiState.Idle
            throw ce
        } catch (e: CoreUnavailableException) {
            val err = ScanUiState.Error(e.message ?: "Proxy core unavailable")
            _state.value = err
            return err
        } catch (t: Throwable) {
            val err = ScanUiState.Error(t.message ?: "Scan failed")
            _state.value = err
            return err
        }
    }
    companion object {
        private const val WORKER_PARK_MS = 150L
    }
}
