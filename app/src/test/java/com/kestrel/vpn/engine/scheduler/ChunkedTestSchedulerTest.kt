package com.kestrel.vpn.engine.scheduler
import com.kestrel.vpn.domain.model.HuntState
import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.ScanProgress
import com.kestrel.vpn.domain.model.ServerTestResult
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.model.SubscriptionRefreshResult
import com.kestrel.vpn.domain.model.TestMode
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.PingProbeOutcome
import com.kestrel.vpn.engine.probe.ProxyProber
import com.kestrel.vpn.engine.probe.SpeedProbeOutcome
import com.kestrel.vpn.engine.scoring.ServerScorer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
class ChunkedTestSchedulerTest {
    private fun fakeConfig(index: Int): ProxyConfig = ProxyConfig(
        canonicalKey = "key-$index",
        protocol = Protocol.VLESS,
        host = "node$index.example.com",
        port = 443,
        credential = "uuid-$index"
    )
    @Test
    fun `never queues or tests the same canonical key twice in a session`() = runTest {
        val configs = (1..20).map { fakeConfig(it) }
        val repo = InMemoryConfigRepo(configs, returnDuplicatesOnQuery = true)
        val pingCounts = ConcurrentHashMap<String, AtomicInteger>()
        val prober = object : ProxyProber {
            override suspend fun runPingStage(config: ProxyConfig, samples: Int): PingProbeOutcome {
                pingCounts.computeIfAbsent(config.canonicalKey) { AtomicInteger(0) }.incrementAndGet()
                return PingProbeOutcome(alive = true, pingMs = 450, jitterMs = 30, timedOut = false)
            }
            override suspend fun runSpeedStage(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome =
            SpeedProbeOutcome(success = true, speedKbps = 5_000, timedOut = false)
        }
        val scheduler = ChunkedTestScheduler(
            repository = repo,
            prober = prober,
            scorer = ServerScorer(),
            connectivityChecker = object : ConnectivityChecker {
                override suspend fun isInternetReachable(): Boolean = true
            },
            resourceMonitor = object : ResourceConstraintProvider {
                override fun isConstrained(): Boolean = false
            }
        )
        scheduler.runQuickScan(
            QuickScanRequest(
                networkKey = "wifi",
                testMode = TestMode.PING_ONLY,
                maxConfigsInChunk = 12,
                earlyStopExcellentTarget = 50
            )
        )
        scheduler.runQuickScan(
            QuickScanRequest(
                networkKey = "wifi",
                testMode = TestMode.PING_ONLY,
                maxConfigsInChunk = 12,
                earlyStopExcellentTarget = 50
            )
        )
        assertEquals(20, pingCounts.size)
        for ((key, count) in pingCounts) {
            assertEquals("Canonical key $key was tested more than once!", 1, count.get())
        }
    }
    @Test
    fun `stage 2 speed test runs only on top K ping survivors and honors early stop`() = runTest {
        val configs = (1..30).map { fakeConfig(it) }
        val repo = InMemoryConfigRepo(configs)
        val speedTestedKeys = ConcurrentHashMap.newKeySet<String>()
        val prober = object : ProxyProber {
            override suspend fun runPingStage(config: ProxyConfig, samples: Int): PingProbeOutcome {
                val id = config.canonicalKey.substringAfter("-").toInt()
                return PingProbeOutcome(alive = true, pingMs = 100 + id * 10, jitterMs = 10, timedOut = false)
            }
            override suspend fun runSpeedStage(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome {
                speedTestedKeys.add(config.canonicalKey)
                return SpeedProbeOutcome(success = true, speedKbps = 8_000, timedOut = false)
            }
        }
        val scheduler = ChunkedTestScheduler(
            repository = repo,
            prober = prober,
            scorer = ServerScorer(),
            connectivityChecker = object : ConnectivityChecker {
                override suspend fun isInternetReachable(): Boolean = true
            },
            resourceMonitor = object : ResourceConstraintProvider {
                override fun isConstrained(): Boolean = false
            }
        )
        val result = scheduler.runQuickScan(
            QuickScanRequest(
                networkKey = "wifi",
                testMode = TestMode.PING_AND_SPEED,
                maxConfigsInChunk = 30,
                topKForSpeed = 5,
                earlyStopExcellentTarget = 100
            )
        )
        assertTrue(result is ScanUiState.Completed)
        assertEquals(5, speedTestedKeys.size)
    }
    @Test
    fun `a probe that fails while the phone is offline is not saved as DEAD and is tested again`() = runTest {
        val configs = (1..4).map { fakeConfig(it) }
        val repo = InMemoryConfigRepo(configs)
        val pings = ConcurrentHashMap<String, AtomicInteger>()
        val prober = object : ProxyProber {
            override suspend fun runPingStage(config: ProxyConfig, samples: Int): PingProbeOutcome {
                val n = pings.computeIfAbsent(config.canonicalKey) { AtomicInteger(0) }.incrementAndGet()
                // key-1 fails on its first try only, because the phone lost its internet at that moment
                return if (config.canonicalKey == "key-1" && n == 1) {
                    PingProbeOutcome(alive = false, pingMs = null, jitterMs = null, timedOut = false)
                } else {
                    PingProbeOutcome(alive = true, pingMs = 200, jitterMs = 10, timedOut = false)
                }
            }
            override suspend fun runSpeedStage(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome =
            SpeedProbeOutcome(success = true, speedKbps = 5_000, timedOut = false)
        }
        val checks = AtomicInteger(0)
        val scheduler = ChunkedTestScheduler(
            repository = repo,
            prober = prober,
            scorer = ServerScorer(),
            // offline for the verdict check and the first poll, online from the third check on
            connectivityChecker = object : ConnectivityChecker {
                override suspend fun isInternetReachable(): Boolean = checks.incrementAndGet() >= 3
            },
            resourceMonitor = object : ResourceConstraintProvider {
                override fun isConstrained(): Boolean = false
            }
        )
        scheduler.runQuickScan(
            QuickScanRequest(
                networkKey = "wifi",
                testMode = TestMode.PING_ONLY,
                maxConfigsInChunk = 4,
                earlyStopExcellentTarget = 100
            )
        )
        val result = repo.getTestResult("key-1", "wifi")
        assertEquals(2, pings["key-1"]!!.get())
        assertTrue("outage must not leave a DEAD record", result != null && result.consecutiveFailures == 0)
        assertTrue(result!!.tier != com.kestrel.vpn.domain.model.ServerTier.DEAD)
    }

    @Test
    fun `AIMD reduces concurrency on timeout spikes and increases when stable`() {
        val aimd = AdaptiveConcurrencyController(
            initialConcurrency = 8,
            minConcurrency = 2,
            maxConcurrency = 12,
            windowSize = 8,
            stableStreakForIncrease = 10
        )
        repeat(4) { aimd.recordOutcome(timedOut = true, deviceConstrained = false) }
        repeat(4) { aimd.recordOutcome(timedOut = false, deviceConstrained = false) }
        assertEquals(4, aimd.currentLimit(deviceConstrained = false))
        repeat(10) { aimd.recordOutcome(timedOut = false, deviceConstrained = false) }
        assertEquals(5, aimd.currentLimit(deviceConstrained = false))
        assertTrue(aimd.currentLimit(deviceConstrained = true) <= 4)
    }
}
internal class InMemoryConfigRepo(
    private val configs: List<ProxyConfig>,
    private val returnDuplicatesOnQuery: Boolean = false
) : ConfigRepository {
    private val results = ConcurrentHashMap<Pair<String, String>, ServerTestResult>()
    private val progressMap = ConcurrentHashMap<String, ScanProgress>()
    private val huntMap = ConcurrentHashMap<String, HuntState>()
    override suspend fun refreshSubscription(force: Boolean): SubscriptionRefreshResult =
    SubscriptionRefreshResult.NotModified(configs.size, "mem")
    override fun observeActiveCount(): Flow<Int> = MutableStateFlow(configs.size)
    override fun observeServersWithResults(networkKey: String): Flow<List<ServerWithResult>> =
    flowOf(configs.map { ServerWithResult(it, results[it.canonicalKey to networkKey]) })
    override suspend fun getConfigByKey(canonicalKey: String): ProxyConfig? =
    configs.firstOrNull { it.canonicalKey == canonicalKey }
    override suspend fun getBestServers(networkKey: String, limit: Int): List<ServerWithResult> =
    configs.mapNotNull { cfg ->
        val r = results[cfg.canonicalKey to networkKey]
        if (r != null) ServerWithResult(cfg, r) else null
    }.sortedByDescending { it.score }.take(limit)
    override suspend fun getCandidateBatchForTesting(
        networkKey: String,
        nowMs: Long,
        staleBeforeMs: Long,
        limit: Int
    ): List<ProxyConfig> {
        if (returnDuplicatesOnQuery) {
            return (configs + configs).take(limit)
        }
        return configs.filter { cfg ->
            val r = results[cfg.canonicalKey to networkKey]
            r == null || (r.cooldownUntilMs <= nowMs && r.lastTestedAtMs < staleBeforeMs)
        }.take(limit)
    }
    override suspend fun getTestResult(canonicalKey: String, networkKey: String): ServerTestResult? =
    results[canonicalKey to networkKey]
    override suspend fun saveTestResult(result: ServerTestResult) {
        results[result.canonicalKey to result.networkKey] = result
    }
    override fun observeScanProgress(networkKey: String): Flow<ScanProgress?> =
    flowOf(progressMap[networkKey])
    override suspend fun getScanProgress(networkKey: String): ScanProgress? =
    progressMap[networkKey]
    override suspend fun saveScanProgress(progress: ScanProgress) {
        progressMap[progress.networkKey] = progress
    }
    override fun observeHuntState(networkKey: String): Flow<HuntState?> =
    flowOf(huntMap[networkKey])
    override suspend fun getHuntState(networkKey: String): HuntState? =
    huntMap[networkKey]
    override suspend fun saveHuntState(state: HuntState) {
        huntMap[state.networkKey] = state
    }
    override suspend fun clearHuntState(networkKey: String) {
        huntMap.remove(networkKey)
    }
    override suspend fun clearTestCache(networkKey: String?) {
        if (networkKey == null) {
            results.clear()
            progressMap.clear()
            huntMap.clear()
        } else {
            results.keys.removeIf { it.second == networkKey }
            progressMap.remove(networkKey)
            huntMap.remove(networkKey)
        }
    }
}

class ChunkedTestSchedulerTimeBoxTest {
    @org.junit.Test
    fun `no new probes start after the deadline and unstarted keys are released`() = kotlinx.coroutines.test.runTest {
        val configs = (1..40).map {
            com.kestrel.vpn.domain.model.ProxyConfig(
                canonicalKey = "tb-$it",
                protocol = com.kestrel.vpn.domain.model.Protocol.VLESS,
                host = "n$it.example.com",
                port = 443,
                credential = "u$it"
            )
        }
        val repo = InMemoryConfigRepo(configs)
        var now = 0L
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val prober = object : com.kestrel.vpn.engine.probe.ProxyProber {
            override suspend fun runPingStage(
                config: com.kestrel.vpn.domain.model.ProxyConfig,
                samples: Int
            ): com.kestrel.vpn.engine.probe.PingProbeOutcome {
                started.incrementAndGet()
                now += 10_000L // every probe "takes" 10 s of the fake clock
                return com.kestrel.vpn.engine.probe.PingProbeOutcome(alive = true, pingMs = 300, jitterMs = 10, timedOut = false)
            }
            override suspend fun runSpeedStage(
                config: com.kestrel.vpn.domain.model.ProxyConfig,
                payloadKb: Int
            ) = com.kestrel.vpn.engine.probe.SpeedProbeOutcome(success = true, speedKbps = 4_000, timedOut = false)
        }
        val scheduler = ChunkedTestScheduler(
            repository = repo,
            prober = prober,
            scorer = com.kestrel.vpn.engine.scoring.ServerScorer(),
            connectivityChecker = object : com.kestrel.vpn.engine.probe.ConnectivityChecker {
                override suspend fun isInternetReachable() = true
            },
            resourceMonitor = object : ResourceConstraintProvider {
                override fun isConstrained() = false
            }
        )
        scheduler.runQuickScan(
            QuickScanRequest(
                networkKey = "wifi",
                testMode = com.kestrel.vpn.domain.model.TestMode.PING_ONLY,
                timeBoxMs = 30_000L,
                maxConfigsInChunk = 40,
                earlyStopExcellentTarget = 100
            ),
            clockMs = { now }
        )
        // 30 s box / 10 s per probe: the box closes after ~3 probes plus the ones already in flight.
        org.junit.Assert.assertTrue("started=${started.get()}", started.get() < 40)
        // Keys that were never started can be claimed again by the next scan.
        val second = scheduler.runQuickScan(
            QuickScanRequest(
                networkKey = "wifi",
                testMode = com.kestrel.vpn.domain.model.TestMode.PING_ONLY,
                timeBoxMs = 30_000L,
                maxConfigsInChunk = 40,
                earlyStopExcellentTarget = 100
            ),
            clockMs = { now }
        )
        org.junit.Assert.assertTrue(second is ScanUiState.Completed)
        org.junit.Assert.assertTrue((second as ScanUiState.Completed).testedInChunk > 0)
    }
}
