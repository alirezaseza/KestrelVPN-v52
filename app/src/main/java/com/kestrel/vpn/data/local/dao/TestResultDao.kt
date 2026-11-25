package com.kestrel.vpn.data.local.dao
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kestrel.vpn.data.local.entity.TestResultEntity
@Dao
interface TestResultDao {
    @Upsert
    suspend fun upsert(result: TestResultEntity)
    @Query(
        """
SELECT * FROM test_results
WHERE canonicalKey = :canonicalKey AND networkKey = :networkKey
LIMIT 1
"""
    )
    suspend fun get(canonicalKey: String, networkKey: String): TestResultEntity?
    @Query(
        """
SELECT COUNT(*) FROM test_results r
INNER JOIN proxy_configs c ON c.canonicalKey = r.canonicalKey
WHERE r.networkKey = :networkKey AND c.isActive = 1
"""
    )
    suspend fun countTestedForNetwork(networkKey: String): Int
    @Query(
        """
SELECT COUNT(*) FROM test_results r
INNER JOIN proxy_configs c ON c.canonicalKey = r.canonicalKey
WHERE r.networkKey = :networkKey AND c.isActive = 1 AND r.tier = :tier
"""
    )
    suspend fun countByTierForNetwork(networkKey: String, tier: String): Int
    @Query("DELETE FROM test_results WHERE networkKey = :networkKey")
    suspend fun deleteByNetwork(networkKey: String)
    @Query("DELETE FROM test_results")
    suspend fun deleteAll()
    @Query("DELETE FROM test_results WHERE canonicalKey NOT IN (SELECT canonicalKey FROM proxy_configs)")
    suspend fun deleteOrphans()
}
