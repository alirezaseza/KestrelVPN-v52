package com.kestrel.vpn.engine.scoring
import com.kestrel.vpn.domain.model.ServerTestResult
import com.kestrel.vpn.domain.model.ServerTier
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
@Singleton
class ServerScorer @Inject constructor() {
    fun scoreSuccess(
        canonicalKey: String,
        networkKey: String,
        pingMs: Int,
        jitterMs: Int,
        speedKbps: Int?,
        previous: ServerTestResult?,
        nowMs: Long,
        // False when this call only refines an already-counted probe (speed stage, finals):
        // the same server must not get several "success" EMA updates for one scan.
        updateEma: Boolean = true
    ): ServerTestResult {
        val prevEma = previous?.emaSuccessRate ?: INITIAL_EMA
        val newEma = if (updateEma) {
            (EMA_ALPHA * 1.0) + ((1.0 - EMA_ALPHA) * prevEma)
        } else {
            prevEma
        }
        val pingScore = normalizePing(pingMs)
        val jitterScore = normalizeJitter(jitterMs)
        val emaScore = (newEma * 100.0).coerceIn(0.0, 100.0)
        // One formula for every result so ping-only and ping+speed servers sort fairly in the same list.
        // An unmeasured speed counts as "average" instead of switching to a different weighting.
        val speedScore = if (speedKbps != null) normalizeSpeedKbps(speedKbps) else UNMEASURED_SPEED_SCORE
        val score = ((WEIGHT_PING * pingScore) +
            (WEIGHT_SPEED * speedScore) +
            (WEIGHT_JITTER * jitterScore) +
            (WEIGHT_EMA * emaScore)).coerceIn(0.0, 100.0)
        val tier = assignTier(score = score, pingMs = pingMs, speedKbps = speedKbps)
        return ServerTestResult(
            canonicalKey = canonicalKey,
            networkKey = networkKey,
            tier = tier,
            pingMs = pingMs,
            jitterMs = jitterMs,
            speedKbps = speedKbps ?: previous?.speedKbps,
            score = score,
            emaSuccessRate = newEma,
            consecutiveFailures = 0,
            lastTestedAtMs = nowMs,
            cooldownUntilMs = nowMs + ALIVE_RESULT_TTL_MS,
            testedWithSpeed = speedKbps != null
        )
    }
    fun scoreFailure(
        canonicalKey: String,
        networkKey: String,
        previous: ServerTestResult?,
        nowMs: Long
    ): ServerTestResult {
        val prevEma = previous?.emaSuccessRate ?: INITIAL_EMA
        val newEma = (1.0 - EMA_ALPHA) * prevEma
        val failures = (previous?.consecutiveFailures ?: 0) + 1
        val cooldownMs = deadCooldownDurationMs(failures)
        return ServerTestResult(
            canonicalKey = canonicalKey,
            networkKey = networkKey,
            tier = ServerTier.DEAD,
            pingMs = null,
            jitterMs = null,
            speedKbps = null,
            score = 0.0,
            emaSuccessRate = newEma,
            consecutiveFailures = failures,
            lastTestedAtMs = nowMs,
            cooldownUntilMs = nowMs + cooldownMs,
            testedWithSpeed = false
        )
    }
    // Gentle ramp: one failed probe on a lossy network must not bench a good server for hours.
    fun deadCooldownDurationMs(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= 1 -> COOLDOWN_15M_MS
        consecutiveFailures == 2 -> COOLDOWN_1H_MS
        consecutiveFailures == 3 -> COOLDOWN_6H_MS
        else -> COOLDOWN_24H_MS
    }
    internal fun normalizePing(pingMs: Int): Double {
        val clamped = pingMs.coerceIn(50, 1500)
        return ((1500.0 - clamped) / (1500.0 - 50.0)) * 100.0
    }
    internal fun normalizeJitter(jitterMs: Int): Double {
        val clamped = jitterMs.coerceIn(0, 400)
        return ((400.0 - clamped) / 400.0) * 100.0
    }
    internal fun normalizeSpeedKbps(speedKbps: Int): Double {
        if (speedKbps <= 100) return 0.0
        val ratio = ln(speedKbps / 100.0) / ln(250.0)
        return (ratio * 100.0).coerceIn(0.0, 100.0)
    }
    private fun assignTier(score: Double, pingMs: Int, speedKbps: Int?): ServerTier {
        val speedOkForExcellent = speedKbps == null || speedKbps >= EXCELLENT_MIN_SPEED_KBPS
        return when {
            score >= EXCELLENT_MIN_SCORE && pingMs <= EXCELLENT_MAX_PING_MS && speedOkForExcellent -> ServerTier.EXCELLENT
            score >= GOOD_MIN_SCORE && pingMs <= GOOD_MAX_PING_MS -> ServerTier.GOOD
            else -> ServerTier.SLOW
        }
    }
    companion object {
        // Composite weights; they must sum to 1.0 (checked in ServerScorerTest).
        const val WEIGHT_PING = 0.40
        const val WEIGHT_SPEED = 0.35
        const val WEIGHT_JITTER = 0.10
        const val WEIGHT_EMA = 0.15

        // Tier boundaries.
        const val EXCELLENT_MIN_SCORE = 75.0
        const val EXCELLENT_MAX_PING_MS = 350
        const val EXCELLENT_MIN_SPEED_KBPS = 1_500
        const val GOOD_MIN_SCORE = 45.0
        const val GOOD_MAX_PING_MS = 900

        const val EMA_ALPHA = 0.35
        const val INITIAL_EMA = 0.70
        const val UNMEASURED_SPEED_SCORE = 50.0
        const val COOLDOWN_15M_MS = 15L * 60L * 1000L
        const val ALIVE_RESULT_TTL_MS = 15L * 60L * 1000L
        const val COOLDOWN_1H_MS = 1L * 60L * 60L * 1000L
        const val COOLDOWN_6H_MS = 6L * 60L * 60L * 1000L
        const val COOLDOWN_24H_MS = 24L * 60L * 60L * 1000L
    }
}
