package com.kestrel.vpn.engine.scoring
import com.kestrel.vpn.domain.model.ServerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class ServerScorerTest {
    private val scorer = ServerScorer()
    @Test
    fun `fast low-jitter server scores as EXCELLENT`() {
        val result = scorer.scoreSuccess(
            canonicalKey = "k1",
            networkKey = "wifi",
            pingMs = 120,
            jitterMs = 15,
            speedKbps = 12_000,
            previous = null,
            nowMs = 1_000L
        )
        assertEquals(ServerTier.EXCELLENT, result.tier)
        assertTrue(result.score >= 75.0)
        assertEquals(0, result.consecutiveFailures)
        assertTrue(result.testedWithSpeed)
    }
    @Test
    fun `moderate latency server scores as GOOD and very slow scores as SLOW`() {
        val good = scorer.scoreSuccess(
            canonicalKey = "k2",
            networkKey = "wifi",
            pingMs = 600,
            jitterMs = 80,
            speedKbps = null,
            previous = null,
            nowMs = 1_000L
        )
        val slow = scorer.scoreSuccess(
            canonicalKey = "k3",
            networkKey = "wifi",
            pingMs = 1_350,
            jitterMs = 300,
            speedKbps = 200,
            previous = null,
            nowMs = 1_000L
        )
        assertEquals(ServerTier.GOOD, good.tier)
        assertEquals(ServerTier.SLOW, slow.tier)
        assertTrue(good.score > slow.score)
    }
    @Test
    fun `dead configs get gradual cooldown 15m, 1h, 6h, then 24h`() {
        val t0 = 10_000L
        val fail1 = scorer.scoreFailure("k1", "wifi", null, t0)
        val fail2 = scorer.scoreFailure("k1", "wifi", fail1, t0)
        val fail3 = scorer.scoreFailure("k1", "wifi", fail2, t0)
        val fail4 = scorer.scoreFailure("k1", "wifi", fail3, t0)
        val fail5 = scorer.scoreFailure("k1", "wifi", fail4, t0)
        assertEquals(ServerTier.DEAD, fail1.tier)
        assertEquals(t0 + ServerScorer.COOLDOWN_15M_MS, fail1.cooldownUntilMs)
        assertEquals(t0 + ServerScorer.COOLDOWN_1H_MS, fail2.cooldownUntilMs)
        assertEquals(t0 + ServerScorer.COOLDOWN_6H_MS, fail3.cooldownUntilMs)
        assertEquals(t0 + ServerScorer.COOLDOWN_24H_MS, fail4.cooldownUntilMs)
        assertEquals(t0 + ServerScorer.COOLDOWN_24H_MS, fail5.cooldownUntilMs)
        assertTrue(fail3.emaSuccessRate < fail1.emaSuccessRate)
    }
    @Test
    fun `refining a result with speed does not count a second success in the EMA`() {
        val ping = scorer.scoreSuccess("k1", "wifi", 200, 20, null, null, 1_000L)
        val withSpeed = scorer.scoreSuccess(
            canonicalKey = "k1",
            networkKey = "wifi",
            pingMs = 200,
            jitterMs = 20,
            speedKbps = 5_000,
            previous = ping,
            nowMs = 2_000L,
            updateEma = false
        )
        assertEquals(ping.emaSuccessRate, withSpeed.emaSuccessRate, 1e-9)
        assertTrue(withSpeed.testedWithSpeed)
    }


    @Test
    fun `weights sum to one so scores stay on a 0-100 scale`() {
        val sum = ServerScorer.WEIGHT_PING + ServerScorer.WEIGHT_SPEED +
            ServerScorer.WEIGHT_JITTER + ServerScorer.WEIGHT_EMA
        assertEquals(1.0, sum, 1e-9)
    }

    @Test
    fun `normalisers clamp to their documented ranges`() {
        assertEquals(100.0, scorer.normalizePing(10), 1e-9)
        assertEquals(100.0, scorer.normalizePing(50), 1e-9)
        assertEquals(0.0, scorer.normalizePing(1_500), 1e-9)
        assertEquals(0.0, scorer.normalizePing(9_999), 1e-9)
        assertEquals(100.0, scorer.normalizeJitter(0), 1e-9)
        assertEquals(0.0, scorer.normalizeJitter(400), 1e-9)
        assertEquals(0.0, scorer.normalizeSpeedKbps(100), 1e-9)
        assertEquals(100.0, scorer.normalizeSpeedKbps(25_000), 1e-9)
        assertEquals(100.0, scorer.normalizeSpeedKbps(500_000), 1e-9)
    }

    @Test
    fun `lower ping never lowers the score when everything else is equal`() {
        var previousScore = Double.MAX_VALUE
        for (ping in listOf(60, 150, 300, 600, 900, 1_300)) {
            val r = scorer.scoreSuccess("k", "wifi", ping, 20, 3_000, null, 1_000L)
            assertTrue("score must not rise as ping grows (ping=$ping)", r.score <= previousScore)
            previousScore = r.score
        }
    }

    @Test
    fun `a measured slow download blocks EXCELLENT even with a great ping`() {
        val r = scorer.scoreSuccess("k", "wifi", 100, 10, 1_000, null, 1_000L)
        assertEquals(ServerTier.GOOD, r.tier)
    }

    @Test
    fun `a success after failures clears the failure streak`() {
        val failed = scorer.scoreFailure("k", "wifi", null, 1_000L)
        val recovered = scorer.scoreSuccess("k", "wifi", 200, 20, null, failed, 2_000L)
        assertEquals(0, recovered.consecutiveFailures)
        assertTrue(recovered.emaSuccessRate > failed.emaSuccessRate)
    }
}
