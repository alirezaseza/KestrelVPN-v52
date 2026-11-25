package com.kestrel.vpn.data.local.dao
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kestrel.vpn.data.local.entity.ScanProgressEntity
import kotlinx.coroutines.flow.Flow
@Dao
interface ScanProgressDao {
    @Upsert
    suspend fun upsert(progress: ScanProgressEntity)
    @Query("SELECT * FROM scan_progress WHERE networkKey = :networkKey LIMIT 1")
    suspend fun get(networkKey: String): ScanProgressEntity?
    @Query("SELECT * FROM scan_progress WHERE networkKey = :networkKey LIMIT 1")
    fun observe(networkKey: String): Flow<ScanProgressEntity?>
    @Query("DELETE FROM scan_progress WHERE networkKey = :networkKey")
    suspend fun deleteByNetwork(networkKey: String)
    @Query("DELETE FROM scan_progress")
    suspend fun deleteAll()
}
