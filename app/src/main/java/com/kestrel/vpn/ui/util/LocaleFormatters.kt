package com.kestrel.vpn.ui.util
import java.text.NumberFormat
import java.util.Locale
object LocaleFormatters {
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private const val LRI = "\u2066"
    private const val PDI = "\u2069"
    fun localizeDigits(input: String, locale: Locale = Locale.getDefault()): String {
        if (locale.language != "fa") return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(persianDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
    private fun isolateLtr(value: String): String = "$LRI$value$PDI"
    fun formatNumber(value: Int, locale: Locale = Locale.getDefault()): String {
        val formatted = NumberFormat.getIntegerInstance(locale).format(value)
        return localizeDigits(formatted, locale)
    }
    fun formatDuration(totalSeconds: Long, locale: Locale = Locale.getDefault()): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val raw = if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
        return isolateLtr(localizeDigits(raw, locale))
    }
    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val raw = when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024L -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
        return isolateLtr(localizeDigits(raw, locale))
    }
    fun formatLiveRate(bytesPerSec: Long, locale: Locale = Locale.getDefault()): String {
        val raw = when {
            bytesPerSec >= 1_048_576L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_048_576.0)
            bytesPerSec >= 1_024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1_024.0)
            else -> "$bytesPerSec B/s"
        }
        return isolateLtr(localizeDigits(raw, locale))
    }
    /** Download speed in megaBITS per second (speedKbps is kilobit/s, so 1 Mbit/s = 1000 kbit/s). */
    fun formatApproxSpeedKbps(speedKbps: Int?, locale: Locale = Locale.getDefault()): String? {
        if (speedKbps == null || speedKbps <= 0) return null
        val mbit = speedKbps / 1000.0
        val raw = if (mbit >= 10.0) String.format(Locale.US, "~%.0f Mbit/s", mbit)
        else if (mbit >= 0.1) String.format(Locale.US, "~%.1f Mbit/s", mbit)
        else String.format(Locale.US, "~%.2f Mbit/s", mbit)
        return isolateLtr(localizeDigits(raw, locale))
    }
    /** Plain speed value such as "0.05 Mbit/s" (no "~"), for the minimum-speed setting. */
    fun formatMbit(speedKbps: Int, locale: Locale = Locale.getDefault()): String {
        val mbit = speedKbps / 1000.0
        val raw = if (mbit >= 1.0) String.format(Locale.US, "%.1f Mbit/s", mbit)
        else String.format(Locale.US, "%.2f Mbit/s", mbit)
        return isolateLtr(localizeDigits(raw, locale))
    }
    fun formatPing(pingMs: Int?, locale: Locale = Locale.getDefault()): String {
        if (pingMs == null) return "—"
        return isolateLtr(localizeDigits("$pingMs ms", locale))
    }
    fun formatPayloadMb(payloadKb: Int, locale: Locale = Locale.getDefault()): String {
        val raw = String.format(Locale.US, "%.2f MB", payloadKb / 1000.0)
        return isolateLtr(localizeDigits(raw, locale))
    }
}
