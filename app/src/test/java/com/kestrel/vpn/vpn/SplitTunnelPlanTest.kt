package com.kestrel.vpn.vpn

import com.kestrel.vpn.data.local.SplitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelPlanTest {
    private val own = "com.kestrel.vpn"

    @Test
    fun `off mode touches nothing`() {
        val plan = SplitTunnelPlan.from(SplitMode.OFF, setOf("a.b", "c.d"), own)
        assertTrue(plan.allowed.isEmpty())
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `exclude mode never excludes this app`() {
        val plan = SplitTunnelPlan.from(SplitMode.EXCLUDE, setOf("z.app", own, "a.app"), own)
        assertEquals(listOf("a.app", "z.app"), plan.disallowed)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test
    fun `include mode always keeps this app inside the tunnel`() {
        val plan = SplitTunnelPlan.from(SplitMode.INCLUDE, setOf("a.app"), own)
        assertTrue(own in plan.allowed)
        assertTrue("a.app" in plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `include mode with an empty selection means everything is tunnelled`() {
        val plan = SplitTunnelPlan.from(SplitMode.INCLUDE, emptySet(), own)
        assertTrue(plan.allowed.isEmpty())
    }

    @Test
    fun `blank package names are dropped and output is deterministic`() {
        val plan = SplitTunnelPlan.from(SplitMode.EXCLUDE, setOf(" ", "", " b.app ", "a.app"), own)
        assertEquals(listOf("a.app", "b.app"), plan.disallowed)
        assertFalse(plan.disallowed.any { it.isBlank() })
    }
}
