package com.kestrel.vpn.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    @Test
    fun `redacts ips uuids and long secrets`() {
        val out = DiagnosticsLog.redact(
            "to 185.10.20.30 id 123e4567-e89b-12d3-a456-426614174000 key abcdefghijklmnopqrstuvwxyz0123456789ABCD v6 2001:db8::1"
        )
        assertFalse(out.contains("185.10.20.30"))
        assertFalse(out.contains("123e4567"))
        assertFalse(out.contains("abcdefghijklmnop"))
        assertTrue(out.contains("<ip>") && out.contains("<uuid>") && out.contains("<secret>"))
    }

    @Test
    fun `ring buffer keeps newest entries`() {
        DiagnosticsLog.clear()
        repeat(350) { DiagnosticsLog.add("t", "line $it") }
        val dump = DiagnosticsLog.dump()
        assertTrue(dump.contains("line 349"))
        assertFalse(dump.contains("line 0\n") || dump.endsWith("line 0"))
    }
}
