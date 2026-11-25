package com.kestrel.vpn.engine.hunt
import com.kestrel.vpn.domain.model.HuntPhase
import com.kestrel.vpn.domain.model.HuntState
import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.PingProbeOutcome
import com.kestrel.vpn.engine.probe.ProxyProber
import com.kestrel.vpn.engine.probe.SpeedProbeOutcome
import com.kestrel.vpn.engine.scheduler.InMemoryConfigRepo
import com.kestrel.vpn.engine.scheduler.ResourceConstraintProvider
import com.kestrel.vpn.engine.scoring.ServerScorer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
class HuntModeOrchestratorTest {
    private fun makeConfigs(count: Int): List<ProxyConfig> = (1..count).map { i ->
        ProxyConfig(
            canonicalKey = "node-$i",
            protocol = Protocol.TROJAN,
            host = "node$i.example.org",
            port = 443,
            credential = "pass-$i"
        )
    }
    @Test
    fun `3-round successive halving crowns fastest and most stable server`() = runTest {
        val configs = makeConfigs(20)
        val repo = InMemoryConfigRepo(configs)
        val finalsSampleCounts = ConcurrentHashMap<String, Int>()
        val prober = object : ProxyProber {
            override suspend fun runPingStage(config: ProxyConfig, samples: Int): PingProbeOutcome {
                if (samples == 3) {
                    finalsSampleCounts[config.canonicalKey] = samples
                }
                val id = config.canonicalKey.substringAfter("-").toInt()
                return if (id == 7) {
                    PingProbeOutcome(alive = true, pingMs = 65, jitterMs = 5, timedOut = false)
                } else {
                    PingProbeOutcome(alive = true, pingMs = 180 + id * 20, jitterMs = 40, timedOut = false)
                }
            }
            override suspend fun runSpeedStage(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome {
                val id = config.canonicalKey.substringAfter("-").toInt()
                val speed = if (id == 7) 22_000 else 3_000 + id * 100
                return SpeedProbeOutcome(success = true, speedKbps = speed, timedOut = false)
            }
        }
        val orchestrator = HuntModeOrchestrator(
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
        val finalProgress = orchestrator.runOrResumeHunt(
            HuntConfig(
                networkKey = "wifi",
                round1SampleSize = 20,
                round1KeepRatio = 0.30,
                finalsCount = 5,
                finalsSamplesPerServer = 3
            ),
            runToCompletion = true
        )
        assertEquals(HuntPhase.COMPLETED, finalProgress.phase)
        assertNotNull(finalProgress.winner)
        assertEquals("node-7", finalProgress.winner!!.config.canonicalKey)
        assertEquals(5, finalsSampleCounts.size)
        assertTrue(finalsSampleCounts.values.all { it == 3 })
    }
    @Test
    fun `resumes mid-round without retesting already tested contenders`() = runTest {
        val configs = makeConfigs(6)
        val repo = InMemoryConfigRepo(configs)
        val scorer = ServerScorer()
        configs.forEachIndexed { idx, cfg ->
            repo.saveTestResult(
                scorer.scoreSuccess(
                    canonicalKey = cfg.canonicalKey,
                    networkKey = "wifi",
                    pingMs = 100 + idx * 30,
                    jitterMs = 10,
                    speedKbps = null,
                    previous = null,
                    nowMs = 1_000L
                )
            )
        }
        repo.saveHuntState(
            HuntState(
                networkKey = "wifi",
                phase = HuntPhase.ROUND_2_SPEED,
                contenderKeys = listOf("node-1", "node-2", "node-3", "node-4"),
                testedInRoundKeys = listOf("node-1", "node-2"),
                winnerKey = null,
                roundTotal = 4,
                updatedAtMs = 2_000L
            )
        )
        val speedCalls = ConcurrentHashMap<String, AtomicInteger>()
        val prober = object : ProxyProber {
            override suspend fun runPingStage(config: ProxyConfig, samples: Int): PingProbeOutcome =
            PingProbeOutcome(alive = true, pingMs = 120, jitterMs = 10, timedOut = false)
            override suspend fun runSpeedStage(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome {
                speedCalls.computeIfAbsent(config.canonicalKey) { AtomicInteger(0) }.incrementAndGet()
                return SpeedProbeOutcome(success = true, speedKbps = 10_000, timedOut = false)
            }
        }
        val orchestrator = HuntModeOrchestrator(
            repository = repo,
            prober = prober,
            scorer = scorer,
            connectivityChecker = object : ConnectivityChecker {
                override suspend fun isInternetReachable(): Boolean = true
            },
            resourceMonitor = object : ResourceConstraintProvider {
                override fun isConstrained(): Boolean = false
            }
        )
        val progress = orchestrator.runOrResumeHunt(
            HuntConfig(networkKey = "wifi", finalsCount = 2),
            runToCompletion = true
        )
        assertEquals(HuntPhase.COMPLETED, progress.phase)
        assertEquals(null, speedCalls["node-1"])
        assertEquals(null, speedCalls["node-2"])
        assertEquals(1, speedCalls["node-3"]?.get())
        assertEquals(1, speedCalls["node-4"]?.get())
    }
}
