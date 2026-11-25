package com.kestrel.vpn.engine.scheduler
class AdaptiveConcurrencyController(
    initialConcurrency: Int = 8,
    private val minConcurrency: Int = 2,
    private val maxConcurrency: Int = 12,
    private val windowSize: Int = 8,
    private val stableStreakForIncrease: Int = 10
) {
    private val lock = Any()
    private var current: Int = initialConcurrency.coerceIn(minConcurrency, maxConcurrency)
    private val recentTimeouts = ArrayDeque<Boolean>(windowSize)
    private var stableStreak = 0
    fun currentLimit(deviceConstrained: Boolean): Int = synchronized(lock) {
        if (deviceConstrained) {
            current = minOf(current, minOf(4, maxConcurrency)).coerceAtLeast(minConcurrency)
        }
        current
    }
    fun recordOutcome(timedOut: Boolean, deviceConstrained: Boolean): Int = synchronized(lock) {
        if (recentTimeouts.size == windowSize) {
            recentTimeouts.removeFirst()
        }
        recentTimeouts.addLast(timedOut)
        if (deviceConstrained) {
            stableStreak = 0
            current = (current / 2).coerceAtLeast(minConcurrency)
            return@synchronized current
        }
        val timeoutCount = recentTimeouts.count { it }
        val timeoutSpike = recentTimeouts.size >= windowSize &&
        (timeoutCount.toDouble() / recentTimeouts.size) >= 0.40
        if (timeoutSpike) {
            stableStreak = 0
            recentTimeouts.clear()
            current = (current / 2).coerceAtLeast(minConcurrency)
        } else if (!timedOut) {
            stableStreak++
            if (stableStreak >= stableStreakForIncrease) {
                stableStreak = 0
                current = (current + 1).coerceAtMost(maxConcurrency)
            }
        } else {
            stableStreak = 0
        }
        current
    }
}
