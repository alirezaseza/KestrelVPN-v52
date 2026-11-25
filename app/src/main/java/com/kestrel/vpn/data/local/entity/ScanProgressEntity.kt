package com.kestrel.vpn.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kestrel.vpn.domain.model.ScanProgress
@Entity(tableName = "scan_progress")
data class ScanProgressEntity(
    @PrimaryKey
    val networkKey: String,
    val totalConfigs: Int,
    val testedCount: Int,
    val excellentCount: Int,
    val goodCount: Int,
    val lastScanAtMs: Long,
    val cursorOffset: Int
) {
    fun toDomain(): ScanProgress = ScanProgress(
        networkKey = networkKey,
        totalConfigs = totalConfigs,
        testedCount = testedCount,
        excellentCount = excellentCount,
        goodCount = goodCount,
        lastScanAtMs = lastScanAtMs,
        cursorOffset = cursorOffset
    )
    companion object {
        fun fromDomain(progress: ScanProgress): ScanProgressEntity = ScanProgressEntity(
            networkKey = progress.networkKey,
            totalConfigs = progress.totalConfigs,
            testedCount = progress.testedCount,
            excellentCount = progress.excellentCount,
            goodCount = progress.goodCount,
            lastScanAtMs = progress.lastScanAtMs,
            cursorOffset = progress.cursorOffset
        )
    }
}
