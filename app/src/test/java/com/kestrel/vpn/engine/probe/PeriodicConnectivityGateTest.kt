package com.kestrel.vpn.engine.probe

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PeriodicConnectivityGateTest {
    private fun checker(answers: (Int) -> Boolean, calls: AtomicInteger = AtomicInteger(0)) =
        object : ConnectivityChecker {
            override suspend fun isInternetReachable(): Boolean = answers(calls.incrementAndGet())
        }

    @Test
    fun `failure is the servers fault when the phone is online`() = runTest {
        val gate = PeriodicConnectivityGate(checker { true })
        assertTrue(gate.confirmServerFault())
        assertFalse(gate.isOffline)
    }

    @Test
    fun `failure during an outage is not recorded and returns after the internet is back`() = runTest {
        val calls = AtomicInteger(0)
        // call 1 (verdict) and call 2 (first poll) offline, call 3 online
        val gate = PeriodicConnectivityGate(checker({ it >= 3 }, calls))
        var offlineReported = 0
        assertFalse(gate.confirmServerFault { offlineReported++ })
        assertEquals(1, offlineReported)
        assertEquals(3, calls.get())
        assertFalse(gate.isOffline)
        // the next failure, with the internet back, counts against the server again
        assertTrue(gate.confirmServerFault())
    }

    @Test
    fun `failure after a network switch is not held against the server even when online`() = runTest {
        var epoch = 7L
        val gate = PeriodicConnectivityGate(object : ConnectivityChecker {
            override suspend fun isInternetReachable(): Boolean = true
            override fun networkEpoch(): Long = epoch
        })
        val startEpoch = gate.epoch()
        epoch = 8L // Wi-Fi dropped to mobile while the probe was running
        assertFalse(gate.confirmServerFault(startEpoch))
        // same network all the way: the server is at fault
        assertTrue(gate.confirmServerFault(gate.epoch()))
    }
}
