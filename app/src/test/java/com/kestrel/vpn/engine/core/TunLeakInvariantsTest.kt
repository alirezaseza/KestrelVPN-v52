package com.kestrel.vpn.engine.core

import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guards for the properties that keep the TUN config leak-free. */
class TunLeakInvariantsTest {

    private fun config() = ProxyConfig(
        canonicalKey = "k",
        protocol = Protocol.VLESS,
        host = "proxy.example.com",
        port = 443,
        credential = "uuid",
        security = SecurityType.TLS,
        sni = "front.example.com"
    )

    private fun root(bypassIran: Boolean = true): JsonObject =
        Json.parseToJsonElement(
            SingBoxConfigBuilder.buildTunConfig(config(), mtu = 1400, bypassIran = bypassIran)
        ).jsonObject

    @Test
    fun `no plain-text DNS server is configured`() {
        val servers = root()["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        assertTrue(servers.isNotEmpty())
        servers.forEach {
            val addr = it["address"]!!.jsonPrimitive.content
            assertTrue("DNS must be encrypted: $addr", addr.startsWith("https://") || addr.startsWith("tls://"))
        }
    }

    @Test
    fun `default DNS goes through the proxy`() {
        val dns = root()["dns"]!!.jsonObject
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)
        val remote = dns["servers"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "dns-remote" }
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tun is strict-route with ipv4 and ipv6 and final route is the proxy`() {
        val r = root()
        val tun = r["inbounds"]!!.jsonArray.first().jsonObject
        assertTrue(tun["strict_route"]!!.jsonPrimitive.boolean)
        assertTrue(tun["auto_route"]!!.jsonPrimitive.boolean)
        val addrs = tun["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(addrs.any { it.contains(":") })
        assertTrue(addrs.any { it.contains(".") })
        val route = r["route"]!!.jsonObject
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `only private ranges and the iran list may leave directly`() {
        val rules = root()["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val direct = rules.filter { it["outbound"]?.jsonPrimitive?.content == "direct" }
        direct.forEach {
            assertTrue(
                "unexpected direct rule: $it",
                it.containsKey("ip_is_private") || it.containsKey("domain_suffix")
            )
        }
    }

    @Test
    fun `disabling iran bypass removes every domain-based direct rule`() {
        val r = root(bypassIran = false)
        val routeRules = r["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertFalse(routeRules.any { it.containsKey("domain_suffix") })
        val dnsRules = r["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertFalse(dnsRules.any { it.containsKey("domain_suffix") })
    }
}
