package com.kestrel.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthTrackerTest {
    private val failed = TunnelHealthOutcome.TUNNEL_FAILED_INTERNET_OK

    @Test
    fun `failover only after the configured number of consecutive tunnel failures`() {
        val t = TunnelHealthTracker(3)
        assertFalse(t.record(failed).shouldFailover)
        assertFalse(t.record(failed).shouldFailover)
        assertTrue(t.record(failed).shouldFailover)
    }

    @Test
    fun `a healthy probe resets the streak`() {
        val t = TunnelHealthTracker(3)
        t.record(failed)
        t.record(failed)
        assertFalse(t.record(TunnelHealthOutcome.HEALTHY).shouldFailover)
        assertFalse(t.record(failed).shouldFailover)
        assertFalse(t.record(failed).shouldFailover)
        assertTrue(t.record(failed).shouldFailover)
    }

    @Test
    fun `device offline is reported but never blamed on the server`() {
        val t = TunnelHealthTracker(2)
        t.record(failed)
        val v = t.record(TunnelHealthOutcome.DEVICE_OFFLINE)
        assertTrue(v.offlineWhileConnected)
        assertFalse(v.shouldFailover)
        // the offline period cleared the earlier failure
        assertFalse(t.record(failed).shouldFailover)
    }

    @Test
    fun `healthy and failed verdicts are never marked offline`() {
        val t = TunnelHealthTracker(5)
        assertFalse(t.record(TunnelHealthOutcome.HEALTHY).offlineWhileConnected)
        assertFalse(t.record(failed).offlineWhileConnected)
    }
}
