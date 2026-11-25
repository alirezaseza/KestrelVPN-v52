package com.kestrel.vpn.engine.selection

import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ServerWithResult

/**
 * Picks the server to auto-connect to.
 *
 * - A server is only eligible when it is alive AND its measured download speed is above
 *   [MIN_DOWNLOAD_KBPS] (0.05 Mbit/s). This holds even when every tested server looks good.
 * - Among eligible servers the best score wins. [Pick.standsOut] tells whether the winner is clearly
 *   different from the rest (much faster or much lower latency), which lets the search stop early.
 */
object ServerSelector {
    /** 0.05 Mbit/s = 50 kilobit/s (speedKbps is in kilobits per second). */
    const val MIN_DOWNLOAD_KBPS = 50
    const val STANDOUT_SPEED_FACTOR = 1.5
    const val STANDOUT_PING_FACTOR = 0.6
    const val MIN_COMPARED = 3

    data class Pick(val server: ServerWithResult, val standsOut: Boolean, val comparedCount: Int)

    fun isEligible(s: ServerWithResult, minKbps: Int = MIN_DOWNLOAD_KBPS): Boolean {
        val speed = s.result?.speedKbps ?: return false
        return s.tier != ServerTier.DEAD && s.tier != ServerTier.UNTESTED && speed >= minKbps
    }

    fun choose(candidates: List<ServerWithResult>, minKbps: Int = MIN_DOWNLOAD_KBPS): Pick? {
        val eligible = candidates.filter { isEligible(it, minKbps) }.sortedByDescending { it.score }
        val top = eligible.firstOrNull() ?: return null
        val others = candidates.filter {
            it.config.canonicalKey != top.config.canonicalKey &&
                it.tier != ServerTier.DEAD && it.tier != ServerTier.UNTESTED &&
                it.result?.speedKbps != null
        }
        val compared = others.size + 1
        if (others.isEmpty()) return Pick(top, standsOut = false, comparedCount = compared)
        val medianSpeed = median(others.mapNotNull { it.result?.speedKbps })
        val medianPing = median(others.mapNotNull { it.result?.pingMs })
        val topSpeed = top.result?.speedKbps ?: 0
        val topPing = top.result?.pingMs ?: Int.MAX_VALUE
        val fasterByFar = medianSpeed > 0 && topSpeed >= medianSpeed * STANDOUT_SPEED_FACTOR
        val lowerLatency = medianPing > 0 && topPing <= medianPing * STANDOUT_PING_FACTOR
        return Pick(top, standsOut = fasterByFar || lowerLatency, comparedCount = compared)
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return s[s.size / 2]
    }
}
