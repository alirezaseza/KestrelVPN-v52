package com.kestrel.vpn.data.local.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.kestrel.vpn.domain.model.ServerTestResult
import com.kestrel.vpn.domain.model.ServerTier
@Entity(
    tableName = "test_results",
    primaryKeys = ["canonicalKey", "networkKey"],
    foreignKeys = [
        ForeignKey(
            entity = ProxyConfigEntity::class,
            parentColumns = ["canonicalKey"],
            childColumns = ["canonicalKey"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["networkKey"]),
        Index(value = ["networkKey", "score"]),
        Index(value = ["networkKey", "cooldownUntilMs"]),
        Index(value = ["networkKey", "lastTestedAtMs"])
    ]
)
data class TestResultEntity(
    val canonicalKey: String,
    val networkKey: String,
    val tier: String,
    val pingMs: Int?,
    val jitterMs: Int?,
    val speedKbps: Int?,
    val score: Double,
    val emaSuccessRate: Double,
    val consecutiveFailures: Int,
    val lastTestedAtMs: Long,
    val cooldownUntilMs: Long,
    val testedWithSpeed: Boolean
) {
    fun toDomain(): ServerTestResult = ServerTestResult(
        canonicalKey = canonicalKey,
        networkKey = networkKey,
        tier = runCatching { ServerTier.valueOf(tier) }.getOrDefault(ServerTier.UNTESTED),
        pingMs = pingMs,
        jitterMs = jitterMs,
        speedKbps = speedKbps,
        score = score,
        emaSuccessRate = emaSuccessRate,
        consecutiveFailures = consecutiveFailures,
        lastTestedAtMs = lastTestedAtMs,
        cooldownUntilMs = cooldownUntilMs,
        testedWithSpeed = testedWithSpeed
    )
    companion object {
        fun fromDomain(result: ServerTestResult): TestResultEntity = TestResultEntity(
            canonicalKey = result.canonicalKey,
            networkKey = result.networkKey,
            tier = result.tier.name,
            pingMs = result.pingMs,
            jitterMs = result.jitterMs,
            speedKbps = result.speedKbps,
            score = result.score,
            emaSuccessRate = result.emaSuccessRate,
            consecutiveFailures = result.consecutiveFailures,
            lastTestedAtMs = result.lastTestedAtMs,
            cooldownUntilMs = result.cooldownUntilMs,
            testedWithSpeed = result.testedWithSpeed
        )
    }
}
