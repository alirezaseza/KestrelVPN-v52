package com.kestrel.vpn.data.parser

import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyLinkParserTest {

    private val parser = ProxyLinkParser()
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    @Test
    fun `parses vless over websocket with tls`() {
        val cfg = parser.parseLine(
            "vless://$uuid@example.com:443?type=ws&security=tls&sni=example.com&path=%2Fws#My%20Server"
        )
        assertNotNull(cfg)
        assertEquals(Protocol.VLESS, cfg!!.protocol)
        assertEquals("example.com", cfg.host)
        assertEquals(443, cfg.port)
        assertEquals(TransportType.WS, cfg.transport)
        assertEquals("/ws", cfg.path)
        assertEquals("My Server", cfg.remark)
    }

    @Test
    fun `parses ipv6 host`() {
        val cfg = parser.parseLine("trojan://secret@[2001:db8::1]:8443?security=tls&sni=a.com#v6")
        assertNotNull(cfg)
        assertEquals("2001:db8::1", cfg!!.host)
        assertEquals(8443, cfg.port)
    }

    @Test
    fun `parses vmess json with numeric port`() {
        val json = """{"v":"2","ps":"vm","add":"1.2.3.4","port":443,"id":"$uuid","aid":0,"net":"ws","host":"h.com","path":"/p","tls":"tls","sni":"h.com"}"""
        val link = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val cfg = parser.parseLine(link)
        assertNotNull(cfg)
        assertEquals(Protocol.VMESS, cfg!!.protocol)
        assertEquals(443, cfg.port)
        assertEquals(TransportType.WS, cfg.transport)
    }

    @Test
    fun `parses shadowsocks sip002 with base64 userinfo`() {
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:pass".toByteArray())
        val cfg = parser.parseLine("ss://$userInfo@5.6.7.8:8388#ss")
        assertNotNull(cfg)
        assertEquals(Protocol.SHADOWSOCKS, cfg!!.protocol)
        assertEquals("aes-256-gcm", cfg.method)
        assertEquals("pass", cfg.credential)
    }

    @Test
    fun `rejects transports the core cannot run`() {
        assertNull(parser.parseLine("vless://$uuid@example.com:443?type=xhttp&security=tls#x"))
        assertNull(parser.parseLine("vless://$uuid@example.com:443?type=kcp&security=tls#x"))
    }

    @Test
    fun `rejects shadowsocks with plugin`() {
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:pass".toByteArray())
        assertNull(parser.parseLine("ss://$userInfo@5.6.7.8:8388?plugin=obfs-local#p"))
    }

    @Test
    fun `rejects invalid ports and garbage`() {
        assertNull(parser.parseLine("trojan://pw@host.com:99999#x"))
        assertNull(parser.parseLine("trojan://pw@host.com#x"))
        assertNull(parser.parseLine("hello world"))
        assertNull(parser.parseLine(""))
    }

    @Test
    fun `plain and base64 subscriptions parse identically and dedupe`() {
        val line = "trojan://pw@host.com:443?security=tls&sni=host.com#one"
        val plain = "$line\n$line\n# comment\nnot a link\n"
        val fromPlain = parser.parseSubscriptionContent(plain)
        assertEquals(1, fromPlain.configs.size)
        assertEquals(1, fromPlain.duplicateCount)
        assertEquals(1, fromPlain.invalidCount)

        val b64 = Base64.getEncoder().encodeToString(plain.toByteArray())
        val fromB64 = parser.parseSubscriptionContent(b64)
        assertEquals(fromPlain.configs.map { it.canonicalKey }, fromB64.configs.map { it.canonicalKey })
    }

    @Test
    fun `empty content yields empty result`() {
        val r = parser.parseSubscriptionContent("   ")
        assertTrue(r.configs.isEmpty())
        assertEquals(0, r.totalLines)
    }

    @Test
    fun `reality link keeps key and lowercases short id and fingerprint`() {
        val cfg = parser.parseLine(
            "vless://$uuid@1.2.3.4:443?type=tcp&security=reality&pbk=PubKeyValue&sid=ABCD&sni=a.com&fp=Chrome#R"
        )
        assertNotNull(cfg)
        assertEquals(SecurityType.REALITY, cfg!!.security)
        assertEquals("PubKeyValue", cfg.realityPublicKey)
        assertEquals("abcd", cfg.realityShortId)
        assertEquals("chrome", cfg.fingerprint)
    }

    @Test
    fun `reality link without a public key is rejected`() {
        assertNull(parser.parseLine("vless://$uuid@1.2.3.4:443?type=tcp&security=reality&sni=a.com"))
    }

    @Test
    fun `allowInsecure is only honoured for plain tls`() {
        val tls = parser.parseLine("vless://$uuid@example.com:443?security=tls&sni=example.com&allowInsecure=1")
        val none = parser.parseLine("vless://$uuid@example.com:443?security=none&allowInsecure=1")
        assertTrue(tls!!.allowInsecure)
        assertEquals(false, none!!.allowInsecure)
    }

    @Test
    fun `scheme matching is case-insensitive`() {
        val cfg = parser.parseLine("VLESS://$uuid@example.com:443?security=tls&sni=example.com")
        assertNotNull(cfg)
        assertEquals(Protocol.VLESS, cfg!!.protocol)
    }

    @Test
    fun `comments and blank lines are ignored and bad lines are counted`() {
        val content = "# header\n\n" +
            "vless://$uuid@example.com:443?security=tls&sni=example.com\n" +
            "vless://$uuid@example.com:443?type=xhttp\n" +
            "https://not-a-proxy.example\n"
        val result = parser.parseSubscriptionContent(content)
        assertEquals(1, result.configs.size)
        assertEquals(3, result.totalLines)
        assertEquals(2, result.invalidCount)
    }

    @Test
    fun `very long remarks are truncated`() {
        val cfg = parser.parseLine(
            "vless://$uuid@example.com:443?security=tls&sni=example.com#" + "a".repeat(300)
        )
        assertEquals(128, cfg!!.remark.length)
    }

    @Test
    fun vlessWsTls_withoutSni_fallsBackToHostHeader() {
        val c = ProxyLinkParser().parseLine(
            "vless://11111111-1111-1111-1111-111111111111@104.16.1.1:443?type=ws&security=tls&host=cdn.example.com&path=/x&alpn=h2,http/1.1#t"
        )!!
        org.junit.Assert.assertEquals("cdn.example.com", c.sni)
        org.junit.Assert.assertEquals("http/1.1", c.alpn)
    }

    @Test
    fun vlessVisionUdp443_flowIsNormalized() {
        val c = ProxyLinkParser().parseLine(
            "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?type=tcp&security=tls&sni=a.com&flow=xtls-rprx-vision-udp443#t"
        )!!
        org.junit.Assert.assertEquals("xtls-rprx-vision", c.flow)
    }
}
