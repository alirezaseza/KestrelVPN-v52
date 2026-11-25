package com.kestrel.vpn.data.local.dao
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kestrel.vpn.data.local.entity.HuntStateEntity
import kotlinx.coroutines.flow.Flow
@Dao
interface HuntStateDao {
    @Upsert
    suspend fun upsert(state: HuntStateEntity)
    @Query("SELECT * FROM hunt_state WHERE networkKey = :networkKey LIMIT 1")
    suspend fun get(networkKey: String): HuntStateEntity?
    @Query("SELECT * FROM hunt_state WHERE networkKey = :networkKey LIMIT 1")
    fun observe(networkKey: String): Flow<HuntStateEntity?>
    @Query("DELETE FROM hunt_state WHERE networkKey = :networkKey")
    suspend fun deleteByNetwork(networkKey: String)
    @Query("DELETE FROM hunt_state")
    suspend fun deleteAll()
}
