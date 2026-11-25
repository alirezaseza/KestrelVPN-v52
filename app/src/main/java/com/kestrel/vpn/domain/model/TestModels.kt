package com.kestrel.vpn.domain.model
enum class ServerTier {
    EXCELLENT,
    GOOD,
    SLOW,
    DEAD,
    UNTESTED
}
enum class TestMode {
    PING_ONLY,
    PING_AND_SPEED
}
/**
 * How aggressively servers are tested.
 * More parallel probes and fewer samples = faster scans, but less accurate numbers.
 */
enum class TestSpeedMode(
    val pingWorkers: Int,
    val pingSamples: Int,
    val speedWorkers: Int,
    val huntSpeedWorkers: Int,
    val chunkSize: Int
) {
    SLOW(pingWorkers = 4, pingSamples = 3, speedWorkers = 1, huntSpeedWorkers = 2, chunkSize = 16),
    MEDIUM(pingWorkers = 8, pingSamples = 2, speedWorkers = 2, huntSpeedWorkers = 3, chunkSize = 24),
    FAST(pingWorkers = 12, pingSamples = 1, speedWorkers = 3, huntSpeedWorkers = 4, chunkSize = 40)
}
enum class HuntPhase {
    IDLE,
    ROUND_1_PING,
    ROUND_2_SPEED,
    ROUND_3_FINALS,
    COMPLETED
}
data class ServerTestResult(
    val canonicalKey: String,
    val networkKey: String,
    val tier: ServerTier,
    val pingMs: Int?,
    val jitterMs: Int?,
    val speedKbps: Int?,
    val score: Double,
    val emaSuccessRate: Double,
    val consecutiveFailures: Int,
    val lastTestedAtMs: Long,
    val cooldownUntilMs: Long,
    val testedWithSpeed: Boolean
)
data class ServerWithResult(
    val config: ProxyConfig,
    val result: ServerTestResult?
) {
    val tier: ServerTier get() = result?.tier ?: ServerTier.UNTESTED
    val score: Double get() = result?.score ?: -1.0
}
data class ScanProgress(
    val networkKey: String,
    val totalConfigs: Int,
    val testedCount: Int,
    val excellentCount: Int,
    val goodCount: Int,
    val lastScanAtMs: Long,
    val cursorOffset: Int
)
data class HuntState(
    val networkKey: String,
    val phase: HuntPhase,
    val contenderKeys: List<String>,
    val testedInRoundKeys: List<String>,
    val winnerKey: String?,
    val roundTotal: Int,
    val updatedAtMs: Long
)
