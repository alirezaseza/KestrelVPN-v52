package com.kestrel.vpn.engine.selection

import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.ServerTestResult
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ServerWithResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSelectorTest {
    private fun server(key: String, ping: Int, speed: Int?, tier: ServerTier = ServerTier.GOOD, score: Double = 60.0) =
        ServerWithResult(
            ProxyConfig(
                canonicalKey = key, protocol = Protocol.TROJAN, host = "$key.example.com",
                port = 443, credential = "pw", security = SecurityType.TLS
            ),
            ServerTestResult(
                canonicalKey = key, networkKey = "wifi", tier = tier, pingMs = ping, jitterMs = 10,
                speedKbps = speed, score = score, emaSuccessRate = 0.9, consecutiveFailures = 0,
                lastTestedAtMs = 0L, cooldownUntilMs = 0L, testedWithSpeed = speed != null
            )
        )

    @Test
    fun `server below 0_05 Mbps is never picked even if every server looks good`() {
        val all = listOf(server("a", 100, 30), server("b", 120, 20), server("c", 90, null))
        assertNull(ServerSelector.choose(all))
    }

    @Test
    fun `fast server above the minimum wins`() {
        val pick = ServerSelector.choose(
            listOf(server("a", 100, 30, score = 90.0), server("b", 200, 800, score = 55.0))
        )
        assertEquals("b", pick?.server?.config?.canonicalKey)
    }

    @Test
    fun `clearly faster server stands out among similar ones`() {
        val pick = ServerSelector.choose(
            listOf(
                server("a", 300, 1_000, score = 50.0),
                server("b", 310, 1_100, score = 51.0),
                server("c", 305, 1_050, score = 52.0),
                server("star", 120, 6_000, ServerTier.EXCELLENT, 90.0)
            )
        )
        assertEquals("star", pick?.server?.config?.canonicalKey)
        assertTrue(pick!!.standsOut)
    }

    @Test
    fun `similar servers do not stand out`() {
        val pick = ServerSelector.choose(
            listOf(server("a", 300, 1_000, score = 50.0), server("b", 310, 1_100, score = 51.0), server("c", 305, 1_050, score = 52.0))
        )
        assertFalse(pick!!.standsOut)
    }
}
