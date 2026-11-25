package com.kestrel.vpn.engine.hunt
import com.kestrel.vpn.domain.core.CoreUnavailableException
import com.kestrel.vpn.domain.model.HuntPhase
import com.kestrel.vpn.domain.model.HuntState
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.PeriodicConnectivityGate
import com.kestrel.vpn.engine.probe.PingProbeOutcome
import com.kestrel.vpn.engine.probe.SpeedProbeOutcome
import com.kestrel.vpn.engine.probe.ProxyProber
import com.kestrel.vpn.engine.scheduler.AdaptiveConcurrencyController
import com.kestrel.vpn.engine.scheduler.ResourceConstraintProvider
import com.kestrel.vpn.engine.scoring.ServerScorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.kestrel.vpn.domain.model.TestSpeedMode
import kotlin.math.ceil
data class HuntConfig(
    val networkKey: String,
    val speedPayloadKb: Int = 300,
    val round1SampleSize: Int = 10_000,
    val round1KeepRatio: Double = 0.30,
    val finalsCount: Int = 5,
    val finalsSamplesPerServer: Int = 3,
    val chunkTimeBoxMs: Long = 25_000L,
    val speedMode: TestSpeedMode = TestSpeedMode.MEDIUM
)
data class HuntUiProgress(
    val phase: HuntPhase,
    val roundNumber: Int,
    val totalRounds: Int = 3,
    val contendersLeft: Int,
    val testedInRound: Int,
    val roundTotal: Int,
    val isPausedOffline: Boolean = false,
    val isRunning: Boolean = false,
    val winner: ServerWithResult? = null,
    val errorMessage: String? = null
)
@Singleton
class HuntModeOrchestrator @Inject constructor(
    private val repository: ConfigRepository,
    private val prober: ProxyProber,
    private val scorer: ServerScorer,
    private val connectivityChecker: ConnectivityChecker,
    private val resourceMonitor: ResourceConstraintProvider
) {
    private val _progress = MutableStateFlow(
        HuntUiProgress(
            phase = HuntPhase.IDLE,
            roundNumber = 0,
            contendersLeft = 0,
            testedInRound = 0,
            roundTotal = 0
        )
    )
    val progress: StateFlow<HuntUiProgress> = _progress.asStateFlow()
    suspend fun resetHunt(networkKey: String) {
        repository.clearHuntState(networkKey)
        _progress.value = HuntUiProgress(
            phase = HuntPhase.IDLE,
            roundNumber = 0,
            contendersLeft = 0,
            testedInRound = 0,
            roundTotal = 0
        )
    }
    suspend fun runOrResumeHunt(
        config: HuntConfig,
        runToCompletion: Boolean = true,
        clockMs: () -> Long = { System.currentTimeMillis() }
    ): HuntUiProgress {
        val startMs = clockMs()
        val deadlineMs = if (runToCompletion) Long.MAX_VALUE else startMs + config.chunkTimeBoxMs
        try {
            var state = repository.getHuntState(config.networkKey)
            if (state == null || state.phase == HuntPhase.IDLE || state.phase == HuntPhase.COMPLETED) {
                // Hunt wants the best contenders, including servers a Quick Scan just found alive
                // (they sit in their 15-minute "fresh" window and are hidden from normal scan batches).
                val initialCandidates = repository.getHuntCandidates(
                    networkKey = config.networkKey,
                    nowMs = startMs,
                    limit = config.round1SampleSize
                ).distinctBy { it.canonicalKey }
                if (initialCandidates.isEmpty()) {
                    val empty = HuntUiProgress(
                        phase = HuntPhase.IDLE,
                        roundNumber = 0,
                        contendersLeft = 0,
                        testedInRound = 0,
                        roundTotal = 0,
                        errorMessage = "No servers available to hunt"
                    )
                    _progress.value = empty
                    return empty
                }
                val keys = initialCandidates.map { it.canonicalKey }
                state = HuntState(
                    networkKey = config.networkKey,
                    phase = HuntPhase.ROUND_1_PING,
                    contenderKeys = keys,
                    testedInRoundKeys = emptyList(),
                    winnerKey = null,
                    roundTotal = keys.size,
                    updatedAtMs = startMs
                )
                repository.saveHuntState(state)
            }
            if (state.phase == HuntPhase.ROUND_1_PING) {
                state = executeRound1(state, config, deadlineMs, clockMs)
                if (state.phase == HuntPhase.ROUND_1_PING) {
                    return emitProgress(state, isRunning = false)
                }
            }
            if (state.phase == HuntPhase.ROUND_2_SPEED) {
                state = executeRound2(state, config, deadlineMs, clockMs)
                if (state.phase == HuntPhase.ROUND_2_SPEED) {
                    return emitProgress(state, isRunning = false)
                }
            }
            if (state.phase == HuntPhase.ROUND_3_FINALS) {
                state = executeRound3(state, config, deadlineMs, clockMs)
            }
            return emitProgress(state, isRunning = false)
        } catch (ce: CancellationException) {
            _progress.value = _progress.value.copy(isRunning = false)
            throw ce
        } catch (e: CoreUnavailableException) {
            val err = _progress.value.copy(isRunning = false, errorMessage = e.message)
            _progress.value = err
            return err
        } catch (t: Throwable) {
            val err = _progress.value.copy(isRunning = false, errorMessage = t.message ?: "Hunt failed")
            _progress.value = err
            return err
        }
    }
    private suspend fun executeRound1(
        initialState: HuntState,
        config: HuntConfig,
        deadlineMs: Long,
        clockMs: () -> Long
    ): HuntState {
        var state = initialState
        val testedSet = state.testedInRoundKeys.toMutableSet()
        val remaining = ArrayDeque(state.contenderKeys.filterNot { it in testedSet })
        val pingWorkers = config.speedMode.pingWorkers
        val aimd = AdaptiveConcurrencyController(
            initialConcurrency = pingWorkers,
            minConcurrency = minOf(3, pingWorkers),
            maxConcurrency = pingWorkers
        )
        val gate = PeriodicConnectivityGate(connectivityChecker)
        state = runPool(
            state = state,
            remaining = remaining,
            testedSet = testedSet,
            workers = pingWorkers,
            allowedWorkers = { aimd.currentLimit(resourceMonitor.isConstrained()) },
            deadlineMs = deadlineMs,
            clockMs = clockMs,
            gate = gate
        ) { key, onOffline ->
            val proxy = repository.getConfigByKey(key) ?: return@runPool true
            val epoch = gate.epoch()
            val outcome = try {
                prober.runPingStage(proxy, samples = config.speedMode.pingSamples)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: CoreUnavailableException) {
                throw e
            } catch (t: Throwable) {
                PingProbeOutcome(false, null, null, false, t.message)
            }
            if (!(outcome.alive && outcome.pingMs != null) && !gate.confirmServerFault(epoch, onOffline)) {
                // The phone was offline or switched networks: do not penalize the server, test it again.
                return@runPool false
            }
            val prev = repository.getTestResult(key, config.networkKey)
            val result = if (outcome.alive && outcome.pingMs != null) {
                scorer.scoreSuccess(
                    canonicalKey = key,
                    networkKey = config.networkKey,
                    pingMs = outcome.pingMs,
                    jitterMs = outcome.jitterMs ?: 0,
                    speedKbps = null,
                    previous = prev,
                    nowMs = clockMs()
                )
            } else {
                scorer.scoreFailure(key, config.networkKey, prev, clockMs())
            }
            repository.saveTestResult(result)
            aimd.recordOutcome(outcome.timedOut, resourceMonitor.isConstrained())
            true
        }
        if (remaining.isNotEmpty()) {
            return state
        }
        val aliveRanked = state.contenderKeys.mapNotNull { key ->
            val res = repository.getTestResult(key, config.networkKey)
            if (res != null && res.tier != ServerTier.DEAD) key to res.score else null
        }.sortedByDescending { it.second }
        if (aliveRanked.isEmpty()) {
            val finishedEmpty = state.copy(
                phase = HuntPhase.COMPLETED,
                contenderKeys = emptyList(),
                testedInRoundKeys = emptyList(),
                winnerKey = null,
                roundTotal = 0,
                updatedAtMs = clockMs()
            )
            repository.saveHuntState(finishedEmpty)
            return finishedEmpty
        }
        val keepCount = ceil(aliveRanked.size * config.round1KeepRatio)
            .toInt()
            .coerceIn(minOf(config.finalsCount, aliveRanked.size), minOf(36, aliveRanked.size))
        val survivors = aliveRanked.take(keepCount).map { it.first }
        val nextState = state.copy(
            phase = HuntPhase.ROUND_2_SPEED,
            contenderKeys = survivors,
            testedInRoundKeys = emptyList(),
            roundTotal = survivors.size,
            updatedAtMs = clockMs()
        )
        repository.saveHuntState(nextState)
        return nextState
    }
    private suspend fun executeRound2(
        initialState: HuntState,
        config: HuntConfig,
        deadlineMs: Long,
        clockMs: () -> Long
    ): HuntState {
        var state = initialState
        val testedSet = state.testedInRoundKeys.toMutableSet()
        val remaining = ArrayDeque(state.contenderKeys.filterNot { it in testedSet })
        val speedWorkers = config.speedMode.huntSpeedWorkers
        val gate = PeriodicConnectivityGate(connectivityChecker)
        state = runPool(
            state = state,
            remaining = remaining,
            testedSet = testedSet,
            workers = speedWorkers,
            allowedWorkers = { speedWorkers },
            deadlineMs = deadlineMs,
            clockMs = clockMs,
            gate = gate
        ) { key, onOffline ->
            val proxy = repository.getConfigByKey(key) ?: return@runPool true
            val prev = repository.getTestResult(key, config.networkKey)
            val epoch = gate.epoch()
            val speed = try {
                prober.runSpeedStage(proxy, config.speedPayloadKb)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: CoreUnavailableException) {
                throw e
            } catch (t: Throwable) {
                SpeedProbeOutcome(false, null, false, t.message)
            }
            // A failed download is not saved, but with no internet it says nothing: measure this one again.
            if (!speed.success && !gate.confirmServerFault(epoch, onOffline)) return@runPool false
            if (speed.success && speed.speedKbps != null && prev?.pingMs != null) {
                repository.saveTestResult(
                    scorer.scoreSuccess(
                        canonicalKey = key,
                        networkKey = config.networkKey,
                        pingMs = prev.pingMs,
                        jitterMs = prev.jitterMs ?: 0,
                        speedKbps = speed.speedKbps,
                        previous = prev,
                        nowMs = clockMs(),
                        updateEma = false
                    )
                )
            }
            true
        }
        if (remaining.isNotEmpty()) {
            return state
        }
        val finalists = state.contenderKeys.mapNotNull { key ->
            val res = repository.getTestResult(key, config.networkKey)
            if (res != null && res.tier != ServerTier.DEAD) key to res.score else null
        }.sortedByDescending { it.second }
            .take(config.finalsCount)
            .map { it.first }
        val nextState = state.copy(
            phase = HuntPhase.ROUND_3_FINALS,
            contenderKeys = finalists,
            testedInRoundKeys = emptyList(),
            roundTotal = finalists.size,
            updatedAtMs = clockMs()
        )
        repository.saveHuntState(nextState)
        return nextState
    }
    private suspend fun executeRound3(
        initialState: HuntState,
        config: HuntConfig,
        deadlineMs: Long,
        clockMs: () -> Long
    ): HuntState {
        var state = initialState
        val testedSet = state.testedInRoundKeys.toMutableSet()
        val remaining = ArrayDeque(state.contenderKeys.filterNot { it in testedSet })
        val gate = PeriodicConnectivityGate(connectivityChecker)
        while (remaining.isNotEmpty() && clockMs() < deadlineMs) {
            gate.awaitOnline()
            val key = remaining.removeFirst()
            emitProgress(state, isRunning = true)
            val proxy = repository.getConfigByKey(key)
            if (proxy != null) {
                val prev = repository.getTestResult(key, config.networkKey)
                val epoch = gate.epoch()
                val probe = try {
                    prober.runPingStage(proxy, samples = config.finalsSamplesPerServer)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: CoreUnavailableException) {
                    throw e
                } catch (t: Throwable) {
                    PingProbeOutcome(false, null, null, false, t.message)
                }
                if (!(probe.alive && probe.pingMs != null) &&
                    !gate.confirmServerFault(epoch) { emitProgress(state, isRunning = true, pausedOffline = true) }
                ) {
                    remaining.addFirst(key) // phone was offline: nothing recorded, test this one again
                    continue
                }
                val updated = if (probe.alive && probe.pingMs != null) {
                    scorer.scoreSuccess(
                        canonicalKey = key,
                        networkKey = config.networkKey,
                        pingMs = probe.pingMs,
                        jitterMs = probe.jitterMs ?: 0,
                        speedKbps = prev?.speedKbps,
                        previous = prev,
                        nowMs = clockMs(),
                        updateEma = false
                    )
                } else {
                    scorer.scoreFailure(key, config.networkKey, prev, clockMs())
                }
                repository.saveTestResult(updated)
            }
            testedSet.add(key)
            state = state.copy(
                testedInRoundKeys = testedSet.toList(),
                updatedAtMs = clockMs()
            )
            repository.saveHuntState(state)
        }
        if (remaining.isNotEmpty()) {
            return state
        }
        val winnerKey = state.contenderKeys.mapNotNull { key ->
            val res = repository.getTestResult(key, config.networkKey)
            if (res != null && res.tier != ServerTier.DEAD) key to res.score else null
        }.maxByOrNull { it.second }?.first
        val completed = state.copy(
            phase = HuntPhase.COMPLETED,
            winnerKey = winnerKey,
            updatedAtMs = clockMs()
        )
        repository.saveHuntState(completed)
        return completed
    }
    private suspend fun emitProgress(
        state: HuntState,
        isRunning: Boolean,
        pausedOffline: Boolean = false
    ): HuntUiProgress {
        val roundNum = when (state.phase) {
            HuntPhase.IDLE -> 0
            HuntPhase.ROUND_1_PING -> 1
            HuntPhase.ROUND_2_SPEED -> 2
            HuntPhase.ROUND_3_FINALS -> 3
            HuntPhase.COMPLETED -> 3
        }
        val winner = state.winnerKey?.let { key ->
            val cfg = repository.getConfigByKey(key)
            val res = repository.getTestResult(key, state.networkKey)
            if (cfg != null) ServerWithResult(cfg, res) else null
        }
        val ui = HuntUiProgress(
            phase = state.phase,
            roundNumber = roundNum,
            contendersLeft = (state.contenderKeys.size - state.testedInRoundKeys.size).coerceAtLeast(0),
            testedInRound = state.testedInRoundKeys.size,
            roundTotal = state.roundTotal,
            isPausedOffline = pausedOffline,
            isRunning = isRunning,
            winner = winner
        )
        _progress.value = ui
        return ui
    }

    /**
     * Worker pool shared by the Hunt rounds: [workers] probes run in parallel and a slow server only
     * holds back its own worker (the old lock-step waves made every worker wait for the slowest one).
     * [work] returns false when the phone was offline or switched networks (nothing was recorded):
     * the key then goes back to the front of the queue. Progress is saved every few keys and at the
     * end, also when the hunt is cancelled, so a resumed hunt never repeats finished work.
     */
    private suspend fun runPool(
        state: HuntState,
        remaining: ArrayDeque<String>,
        testedSet: MutableSet<String>,
        workers: Int,
        allowedWorkers: () -> Int,
        deadlineMs: Long,
        clockMs: () -> Long,
        gate: PeriodicConnectivityGate,
        work: suspend (key: String, onOffline: suspend () -> Unit) -> Boolean
    ): HuntState {
        val lock = Mutex()
        var current = state
        var unsaved = 0
        // Small rounds are saved on every key; the 10k-key ping round only every few keys.
        val saveEvery = if (state.roundTotal <= SMALL_ROUND) 1 else SAVE_EVERY
        // Call with [lock] held.
        suspend fun persist() {
            current = current.copy(testedInRoundKeys = testedSet.toList(), updatedAtMs = clockMs())
            repository.saveHuntState(current)
            unsaved = 0
        }
        try {
            emitProgress(current, isRunning = true)
            coroutineScope {
                repeat(workers.coerceAtLeast(1)) { index ->
                    launch {
                        while (true) {
                            if (clockMs() >= deadlineMs) break
                            if (index >= allowedWorkers()) {
                                // Parked by the concurrency controller.
                                if (lock.withLock { remaining.isEmpty() }) break
                                delay(WORKER_PARK_MS)
                                continue
                            }
                            val key = lock.withLock { remaining.removeFirstOrNull() } ?: break
                            gate.awaitOnline()
                            val done = work(key) {
                                emitProgress(current, isRunning = true, pausedOffline = true)
                            }
                            val toShow = lock.withLock {
                                if (done) {
                                    testedSet.add(key)
                                    unsaved++
                                    if (unsaved >= saveEvery) {
                                        persist()
                                        current
                                    } else null
                                } else {
                                    remaining.addFirst(key)
                                    null
                                }
                            }
                            if (toShow != null) emitProgress(toShow, isRunning = true)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { lock.withLock { if (unsaved > 0) persist() } }
        }
        return current
    }

    private companion object {
        const val WORKER_PARK_MS = 150L
        const val SAVE_EVERY = 8
        const val SMALL_ROUND = 100
    }
}
