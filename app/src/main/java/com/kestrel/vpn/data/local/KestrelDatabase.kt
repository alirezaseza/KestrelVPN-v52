package com.kestrel.vpn.data.local
import androidx.room.Database
import androidx.room.RoomDatabase
import com.kestrel.vpn.data.local.dao.HuntStateDao
import com.kestrel.vpn.data.local.dao.ProxyConfigDao
import com.kestrel.vpn.data.local.dao.ScanProgressDao
import com.kestrel.vpn.data.local.dao.TestResultDao
import com.kestrel.vpn.data.local.entity.HuntStateEntity
import com.kestrel.vpn.data.local.entity.ProxyConfigEntity
import com.kestrel.vpn.data.local.entity.ScanProgressEntity
import com.kestrel.vpn.data.local.entity.TestResultEntity
@Database(
    entities = [
        ProxyConfigEntity::class,
        TestResultEntity::class,
        ScanProgressEntity::class,
        HuntStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun proxyConfigDao(): ProxyConfigDao
    abstract fun testResultDao(): TestResultDao
    abstract fun scanProgressDao(): ScanProgressDao
    abstract fun huntStateDao(): HuntStateDao
}
