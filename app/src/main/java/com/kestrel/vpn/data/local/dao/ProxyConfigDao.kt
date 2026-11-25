package com.kestrel.vpn.data.local.dao
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import com.kestrel.vpn.data.local.entity.ProxyConfigEntity
import com.kestrel.vpn.data.local.entity.TestResultEntity
import kotlinx.coroutines.flow.Flow
data class ConfigWithResultRow(
    @Embedded val config: ProxyConfigEntity,
    @Embedded(prefix = "tr_") val result: TestResultEntity?
)
@Dao
interface ProxyConfigDao {
    @Upsert
    suspend fun upsertAll(configs: List<ProxyConfigEntity>)
    @Query("UPDATE proxy_configs SET isActive = 0 WHERE syncGeneration != :activeGeneration AND syncGeneration != 9223372036854775807")
    suspend fun deactivateOlderThan(activeGeneration: Long)
    @Query("SELECT COUNT(*) FROM proxy_configs WHERE isActive = 1")
    suspend fun countActive(): Int
    @Query("SELECT COUNT(*) FROM proxy_configs WHERE isActive = 1")
    fun observeActiveCount(): Flow<Int>
    @Query("SELECT * FROM proxy_configs WHERE canonicalKey = :canonicalKey LIMIT 1")
    suspend fun getByKey(canonicalKey: String): ProxyConfigEntity?
    @Query(
        """
SELECT
c.*,
r.canonicalKey AS tr_canonicalKey,
r.networkKey AS tr_networkKey,
r.tier AS tr_tier,
r.pingMs AS tr_pingMs,
r.jitterMs AS tr_jitterMs,
r.speedKbps AS tr_speedKbps,
r.score AS tr_score,
r.emaSuccessRate AS tr_emaSuccessRate,
r.consecutiveFailures AS tr_consecutiveFailures,
r.lastTestedAtMs AS tr_lastTestedAtMs,
r.cooldownUntilMs AS tr_cooldownUntilMs,
r.testedWithSpeed AS tr_testedWithSpeed
FROM proxy_configs c
LEFT JOIN test_results r
ON c.canonicalKey = r.canonicalKey AND r.networkKey = :networkKey
WHERE c.isActive = 1
ORDER BY
CASE WHEN r.score IS NULL THEN -1.0 ELSE r.score END DESC,
c.importedAtMs ASC
"""
    )
    fun observeActiveWithResults(networkKey: String): Flow<List<ConfigWithResultRow>>
    @Query(
        """
SELECT
c.*,
r.canonicalKey AS tr_canonicalKey,
r.networkKey AS tr_networkKey,
r.tier AS tr_tier,
r.pingMs AS tr_pingMs,
r.jitterMs AS tr_jitterMs,
r.speedKbps AS tr_speedKbps,
r.score AS tr_score,
r.emaSuccessRate AS tr_emaSuccessRate,
r.consecutiveFailures AS tr_consecutiveFailures,
r.lastTestedAtMs AS tr_lastTestedAtMs,
r.cooldownUntilMs AS tr_cooldownUntilMs,
r.testedWithSpeed AS tr_testedWithSpeed
FROM proxy_configs c
INNER JOIN test_results r
ON c.canonicalKey = r.canonicalKey AND r.networkKey = :networkKey
WHERE c.isActive = 1
AND r.tier != 'DEAD'
AND r.tier != 'UNTESTED'
ORDER BY r.score DESC
LIMIT :limit
"""
    )
    suspend fun getBestServers(networkKey: String, limit: Int): List<ConfigWithResultRow>
    @Query(
        """
SELECT c.* FROM proxy_configs c
LEFT JOIN test_results r
ON c.canonicalKey = r.canonicalKey AND r.networkKey = :networkKey
WHERE c.isActive = 1
AND (
r.canonicalKey IS NULL
OR (r.cooldownUntilMs <= :nowMs AND r.lastTestedAtMs < :staleBeforeMs)
)
ORDER BY
CASE
WHEN r.canonicalKey IS NULL THEN 0
WHEN r.tier IN ('EXCELLENT', 'GOOD') THEN 1
ELSE 2
END ASC,
CASE WHEN r.canonicalKey IS NULL THEN c.importedAtMs ELSE 0 END DESC,
COALESCE(r.lastTestedAtMs, 0) ASC,
c.canonicalKey ASC
LIMIT :limit
"""
    )
    suspend fun getCandidateBatchForTesting(
        networkKey: String,
        nowMs: Long,
        staleBeforeMs: Long,
        limit: Int
    ): List<ProxyConfigEntity>

    @Query(
        """
SELECT c.* FROM proxy_configs c
LEFT JOIN test_results r
ON c.canonicalKey = r.canonicalKey AND r.networkKey = :networkKey
WHERE c.isActive = 1
AND (
r.canonicalKey IS NULL
OR r.tier != 'DEAD'
OR r.cooldownUntilMs <= :nowMs
)
ORDER BY
CASE
WHEN r.canonicalKey IS NOT NULL AND r.tier != 'DEAD' THEN 0
WHEN r.canonicalKey IS NULL THEN 1
ELSE 2
END ASC,
COALESCE(r.score, 0.0) DESC,
c.importedAtMs DESC,
c.canonicalKey ASC
LIMIT :limit
"""
    )
    suspend fun getHuntCandidates(networkKey: String, nowMs: Long, limit: Int): List<ProxyConfigEntity>

    @Query(
        """
SELECT
c.*,
r.canonicalKey AS tr_canonicalKey,
r.networkKey AS tr_networkKey,
r.tier AS tr_tier,
r.pingMs AS tr_pingMs,
r.jitterMs AS tr_jitterMs,
r.speedKbps AS tr_speedKbps,
r.score AS tr_score,
r.emaSuccessRate AS tr_emaSuccessRate,
r.consecutiveFailures AS tr_consecutiveFailures,
r.lastTestedAtMs AS tr_lastTestedAtMs,
r.cooldownUntilMs AS tr_cooldownUntilMs,
r.testedWithSpeed AS tr_testedWithSpeed
FROM proxy_configs c
INNER JOIN test_results r
ON c.canonicalKey = r.canonicalKey AND r.networkKey = :networkKey
WHERE c.isActive = 1
AND r.tier != 'DEAD'
AND r.tier != 'UNTESTED'
ORDER BY r.score DESC
LIMIT 1
"""
    )
    fun observeBestServer(networkKey: String): Flow<ConfigWithResultRow?>

    /** Top-N alive servers as a live list (the Home radar shows 14; it used to observe ALL servers). */
    @Query(
        """
SELECT
c.*,
r.canonicalKey AS tr_canonicalKey,
r.networkKey AS tr_networkKey,
r.tier AS tr_tier,
r.pingMs AS tr_pingMs,
r.jitterMs AS tr_jitterMs,
r.speedKbps AS tr_speedKbps,
r.score AS tr_score,
r.emaSuccessRate AS tr_emaSuccessRate,
r.consecutiveFailures AS tr_consecutiveFailures,
r.lastTestedAtMs AS tr_lastTestedAtMs,
r.cooldownUntilMs AS tr_cooldownUntilMs,
r.testedWithSpeed AS tr_testedWithSpeed
FROM proxy_configs c
INNER JOIN test_results r
ON c.canonicalKey = r.canonicalKey AND r.networkKey = :networkKey
WHERE c.isActive = 1
AND r.tier != 'DEAD'
AND r.tier != 'UNTESTED'
ORDER BY r.score DESC
LIMIT :limit
"""
    )
    fun observeBestServers(networkKey: String, limit: Int): Flow<List<ConfigWithResultRow>>

    @Query(
        """
SELECT r.tier AS tier, COUNT(*) AS cnt
FROM test_results r
INNER JOIN proxy_configs c ON c.canonicalKey = r.canonicalKey
WHERE c.isActive = 1 AND r.networkKey = :networkKey
GROUP BY r.tier
"""
    )
    suspend fun countByTier(networkKey: String): List<TierCountRow>

    /** Every active config, newest import first (used by the sequential "Best server" search). */
    @Query("SELECT * FROM proxy_configs WHERE isActive = 1 ORDER BY importedAtMs DESC, canonicalKey ASC")
    suspend fun getActiveNewestFirst(): List<ProxyConfigEntity>

    @Query("SELECT canonicalKey, importedAtMs FROM proxy_configs")
    suspend fun getImportedAtMap(): List<ImportedAtRow>

    @Query("DELETE FROM proxy_configs WHERE isActive = 0 AND syncGeneration < :cutoffGeneration")
    suspend fun deleteInactiveOlderThan(cutoffGeneration: Long)
}

data class TierCountRow(
    val tier: String,
    val cnt: Int
)

data class ImportedAtRow(
    val canonicalKey: String,
    val importedAtMs: Long
)
