package com.kestrel.vpn.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

class PlatformBridgeTest {

    /** Stand-in for libbox's PlatformInterface (the real one needs the native AAR). */
    interface FakePlatform {
        fun openTun(): Int
        fun usePlatformAutoDetectInterfaceControl(): Boolean
        fun autoDetectInterfaceControl(fd: Int)
        fun usePlatformDefaultInterfaceMonitor(): Boolean
        fun usePlatformInterfaceGetter(): Boolean
        fun startDefaultInterfaceMonitor(listener: Any)
        fun closeDefaultInterfaceMonitor(listener: Any)
        fun getInterfaces(): Any?
        fun brandNewLibboxFlag(): Boolean
        fun brandNewLibboxName(): String
    }

    private fun proxyOf(bridge: PlatformBridge): FakePlatform =
        Proxy.newProxyInstance(
            FakePlatform::class.java.classLoader,
            arrayOf(FakePlatform::class.java),
            bridge
        ) as FakePlatform

    @Test
    fun `openTun hands back the fd and refuses to run without one`() {
        assertEquals(42, proxyOf(PlatformBridge(42, null)).openTun())
        try {
            proxyOf(PlatformBridge(-1, null)).openTun()
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // ok
        }
    }

    @Test
    fun `sockets are protected through the callback and failures are fatal`() {
        val protectedFds = mutableListOf<Int>()
        val ok = proxyOf(PlatformBridge(5, { fd -> protectedFds += fd; true }))
        assertTrue(ok.usePlatformAutoDetectInterfaceControl())
        ok.autoDetectInterfaceControl(17)
        assertEquals(listOf(17), protectedFds)

        val failing = proxyOf(PlatformBridge(5, { false }))
        try {
            failing.autoDetectInterfaceControl(9)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // a socket that could not be protected would loop through the tunnel
        }
    }

    @Test
    fun `no protect callback means platform interface control is off`() {
        assertFalse(proxyOf(PlatformBridge(5, null)).usePlatformAutoDetectInterfaceControl())
    }

    @Test
    fun `object methods behave like a normal instance`() {
        val p = proxyOf(PlatformBridge(5, null))
        assertTrue(p == p)
        assertFalse(p.equals(proxyOf(PlatformBridge(5, null))))
        assertEquals(System.identityHashCode(p), p.hashCode())
        assertTrue(setOf(p).contains(p))
        assertTrue(p.toString().isNotEmpty())
    }

    @Test
    fun `unknown methods get typed defaults and are reported once`() {
        val reported = mutableListOf<String>()
        val p = proxyOf(PlatformBridge(5, null) { reported += it })
        assertFalse(p.brandNewLibboxFlag())
        assertFalse(p.brandNewLibboxFlag())
        assertEquals("", p.brandNewLibboxName())
        assertEquals(listOf("brandNewLibboxFlag", "brandNewLibboxName"), reported)
    }

    private class FakeNetwork : PlatformNetwork {
        var started: Any? = null
        var stopped = false
        val iterator = Any()
        override fun start(listener: Any) { started = listener }
        override fun stop() { stopped = true }
        override fun interfaces(): Any? = iterator
    }

    @Test
    fun `without a network provider the platform monitor and getter stay off`() {
        val p = proxyOf(PlatformBridge(5, null))
        assertFalse(p.usePlatformDefaultInterfaceMonitor())
        assertFalse(p.usePlatformInterfaceGetter())
    }

    @Test
    fun `a network provider turns on the platform monitor and getter and receives the listener`() {
        val net = FakeNetwork()
        val p = proxyOf(PlatformBridge(5, null, net))
        assertTrue(p.usePlatformDefaultInterfaceMonitor())
        assertTrue(p.usePlatformInterfaceGetter())
        val listener = Any()
        p.startDefaultInterfaceMonitor(listener)
        assertTrue(net.started === listener)
        assertTrue(p.getInterfaces() === net.iterator)
        p.closeDefaultInterfaceMonitor(listener)
        assertTrue(net.stopped)
    }

    @Test
    fun `sing-box log lines are forwarded`() {
        val lines = mutableListOf<String>()
        val p = Proxy.newProxyInstance(
            LogPlatform::class.java.classLoader,
            arrayOf(LogPlatform::class.java),
            PlatformBridge(5, null, onLog = { lines += it })
        ) as LogPlatform
        p.writeLog("boom")
        assertEquals(listOf("boom"), lines)
    }

    interface LogPlatform {
        fun writeLog(message: String)
    }
}
