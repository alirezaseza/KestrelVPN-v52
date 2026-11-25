package com.kestrel.vpn.engine.core

import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderTest {

    private fun vless(
        security: SecurityType = SecurityType.TLS,
        fingerprint: String = "",
        allowInsecure: Boolean = false,
        transport: TransportType = TransportType.TCP
    ) = ProxyConfig(
        canonicalKey = "k",
        protocol = Protocol.VLESS,
        host = "proxy.example.com",
        port = 443,
        credential = "uuid",
        security = security,
        sni = "front.example.com",
        fingerprint = fingerprint,
        allowInsecure = allowInsecure,
        realityPublicKey = if (security == SecurityType.REALITY) "pubkey" else "",
        transport = transport,
        path = "/ws"
    )

    private fun outbound(config: ProxyConfig, insecureAllowed: Boolean = false): JsonObject =
        SingBoxConfigBuilder.buildProxyOutbound(config, "proxy", insecureAllowed)

    @Test
    fun `reality without fingerprint falls back to chrome utls`() {
        val tls = outbound(vless(security = SecurityType.REALITY))["tls"]!!.jsonObject
        val utls = tls["utls"]!!.jsonObject
        assertEquals("chrome", utls["fingerprint"]!!.jsonPrimitive.content)
        assertNotNull(tls["reality"])
    }

    @Test
    fun `plain tls without fingerprint gets no utls block`() {
        val tls = outbound(vless())["tls"]!!.jsonObject
        assertNull(tls["utls"])
    }

    @Test
    fun `insecure flag needs both the link and the user opt-in`() {
        val cfg = vless(allowInsecure = true)
        assertNull(outbound(cfg, insecureAllowed = false)["tls"]!!.jsonObject["insecure"])
        assertTrue(outbound(cfg, insecureAllowed = true)["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content.toBoolean())
        val strictLink = vless(allowInsecure = false)
        assertNull(outbound(strictLink, insecureAllowed = true)["tls"]!!.jsonObject["insecure"])
    }

    @Test
    fun `httpupgrade and h2 transports are emitted`() {
        val upgrade = outbound(vless(transport = TransportType.HTTPUPGRADE))["transport"]!!.jsonObject
        assertEquals("httpupgrade", upgrade["type"]!!.jsonPrimitive.content)
        val h2 = outbound(vless(transport = TransportType.HTTP))["transport"]!!.jsonObject
        assertEquals("http", h2["type"]!!.jsonPrimitive.content)
        assertTrue(h2["host"] is JsonArray)
    }

    @Test
    fun `tun config resolves outbound server domains directly and routes ipv6`() {
        val root = Json.parseToJsonElement(SingBoxConfigBuilder.buildTunConfig(vless(), mtu = 1400)).jsonObject

        val dns = root["dns"]!!.jsonObject
        val rule = dns["rules"]!!.jsonArray.first().jsonObject
        assertEquals("any", rule["outbound"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("dns-direct", rule["server"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", dns["final"]!!.jsonPrimitive.content)

        val directServer = dns["servers"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertFalse("direct DNS must not detour through an empty direct outbound", directServer.containsKey("detour"))

        val tun = root["inbounds"]!!.jsonArray.first().jsonObject
        val addresses = tun["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(addresses.any { it.contains(":") })
        assertEquals("1400", tun["mtu"]!!.jsonPrimitive.content)
    }
}

class SingBoxConfigMigrationTest {
    private val cfg = com.kestrel.vpn.domain.model.ProxyConfig(
        canonicalKey = "k", protocol = com.kestrel.vpn.domain.model.Protocol.TROJAN,
        host = "h.example.com", port = 443, credential = "pw",
        security = com.kestrel.vpn.domain.model.SecurityType.TLS
    )

    @Test
    fun `tun config uses rule actions and no legacy dns outbound or sniff`() {
        val json = SingBoxConfigBuilder.buildTunConfig(cfg, mtu = 1400)
        org.junit.Assert.assertFalse(json.contains("dns-out"))
        org.junit.Assert.assertFalse(json.contains("\"sniff\":true"))
        org.junit.Assert.assertTrue(json.contains("\"action\":\"sniff\""))
        org.junit.Assert.assertTrue(json.contains("\"action\":\"hijack-dns\""))
    }


    private fun tunJson(bypassIran: Boolean): JsonObject =
        Json.parseToJsonElement(
            SingBoxConfigBuilder.buildTunConfig(vless(), mtu = 1400, bypassIran = bypassIran)
        ).jsonObject

    private fun hasSuffixRule(rules: JsonArray): Boolean =
        rules.any { it.jsonObject.containsKey("domain_suffix") }

    @Test
    fun `tun config is dual stack, strict and routes everything else through the proxy`() {
        val root = tunJson(bypassIran = true)
        val tun = root["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals("true", tun["strict_route"]!!.jsonPrimitive.content)
        assertEquals("true", tun["auto_route"]!!.jsonPrimitive.content)
        val addresses = tun["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(addresses.any { it.contains(':') })
        assertTrue(addresses.any { it.contains('.') })
        val route = root["route"]!!.jsonObject
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertEquals("true", route["auto_detect_interface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `iran bypass adds suffix rules to both routing and dns only when enabled`() {
        val on = tunJson(bypassIran = true)
        val off = tunJson(bypassIran = false)
        assertTrue(hasSuffixRule(on["route"]!!.jsonObject["rules"]!!.jsonArray))
        assertTrue(hasSuffixRule(on["dns"]!!.jsonObject["rules"]!!.jsonArray))
        assertFalse(hasSuffixRule(off["route"]!!.jsonObject["rules"]!!.jsonArray))
        assertFalse(hasSuffixRule(off["dns"]!!.jsonObject["rules"]!!.jsonArray))
    }

    @Test
    fun `direct dns server has no detour and proxy server names are resolved directly first`() {
        val dns = tunJson(bypassIran = true)["dns"]!!.jsonObject
        val direct = dns["servers"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "dns-direct" }
        assertNull(direct["detour"])
        val firstRule = dns["rules"]!!.jsonArray[0].jsonObject
        assertEquals("dns-direct", firstRule["server"]!!.jsonPrimitive.content)
        assertEquals("any", firstRule["outbound"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `local socks config listens on loopback only and carries credentials when given`() {
        val withAuth = Json.parseToJsonElement(
            SingBoxConfigBuilder.buildLocalSocksConfig(vless(), 1081, username = "u", password = "p")
        ).jsonObject["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("127.0.0.1", withAuth["listen"]!!.jsonPrimitive.content)
        assertEquals("u", withAuth["users"]!!.jsonArray[0].jsonObject["username"]!!.jsonPrimitive.content)

        val withoutAuth = Json.parseToJsonElement(
            SingBoxConfigBuilder.buildLocalSocksConfig(vless(), 1081)
        ).jsonObject["inbounds"]!!.jsonArray[0].jsonObject
        assertNull(withoutAuth["users"])
    }

    @Test
    fun wsEarlyDataInPath_becomesEarlyDataFields() {
        val (path, ed) = SingBoxConfigBuilder.splitEarlyData("/chat?ed=2048")
        org.junit.Assert.assertEquals("/chat", path)
        org.junit.Assert.assertEquals(2048, ed)
        val (p2, e2) = SingBoxConfigBuilder.splitEarlyData("/chat?x=1&ed=512")
        org.junit.Assert.assertEquals("/chat?x=1", p2)
        org.junit.Assert.assertEquals(512, e2)
        val (p3, e3) = SingBoxConfigBuilder.splitEarlyData("/plain")
        org.junit.Assert.assertEquals("/plain", p3)
        org.junit.Assert.assertEquals(0, e3)
    }

    @Test
    fun ipLiteralDetection() {
        org.junit.Assert.assertTrue(ServerAddressResolver.isIpLiteral("1.2.3.4"))
        org.junit.Assert.assertTrue(ServerAddressResolver.isIpLiteral("2001:db8::1"))
        org.junit.Assert.assertFalse(ServerAddressResolver.isIpLiteral("a.example.com"))
    }
}
