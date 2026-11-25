package com.kestrel.vpn.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class DayUsage(val dayIndex: Long, val rxBytes: Long, val txBytes: Long) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/** Per-day VPN traffic totals (last 35 days), kept on-device only. */
@Singleton
class TrafficHistoryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("kestrel_traffic", Context.MODE_PRIVATE)
    private val lock = Any()
    private var pendingRx = 0L
    private var pendingTx = 0L
    private var lastFlushMs = 0L
    private val _usage = MutableStateFlow(readAll())
    val usage: StateFlow<List<DayUsage>> = _usage.asStateFlow()

    fun add(rxBytes: Long, txBytes: Long) {
        if (rxBytes <= 0L && txBytes <= 0L) return
        synchronized(lock) {
            pendingRx += rxBytes.coerceAtLeast(0L)
            pendingTx += txBytes.coerceAtLeast(0L)
            if (System.currentTimeMillis() - lastFlushMs >= FLUSH_INTERVAL_MS) flushLocked()
        }
    }

    fun flush() = synchronized(lock) { flushLocked() }

    private fun flushLocked() {
        lastFlushMs = System.currentTimeMillis()
        if (pendingRx == 0L && pendingTx == 0L) return
        val today = todayIndex()
        val days = readAll().associateBy { it.dayIndex }.toMutableMap()
        val current = days[today]
        days[today] = DayUsage(
            dayIndex = today,
            rxBytes = (current?.rxBytes ?: 0L) + pendingRx,
            txBytes = (current?.txBytes ?: 0L) + pendingTx
        )
        pendingRx = 0L
        pendingTx = 0L
        val kept = days.values.filter { it.dayIndex > today - RETENTION_DAYS }.sortedBy { it.dayIndex }
        prefs.edit()
            .putString(KEY_DAYS, kept.joinToString(";") { "${it.dayIndex}=${it.rxBytes},${it.txBytes}" })
            .apply()
        _usage.value = kept
    }

    private fun readAll(): List<DayUsage> {
        val raw = prefs.getString(KEY_DAYS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val (day, values) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val (rx, tx) = values.split(',', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val dayIndex = day.toLongOrNull() ?: return@mapNotNull null
            DayUsage(dayIndex, rx.toLongOrNull() ?: 0L, tx.toLongOrNull() ?: 0L)
        }
    }

    companion object {
        private const val KEY_DAYS = "days"
        private const val FLUSH_INTERVAL_MS = 10_000L
        private const val RETENTION_DAYS = 35L
        private const val DAY_MS = 86_400_000L

        /** Local-calendar day number (days since epoch in the device time zone). */
        fun todayIndex(nowMs: Long = System.currentTimeMillis()): Long =
            (nowMs + TimeZone.getDefault().getOffset(nowMs)) / DAY_MS
    }
}
