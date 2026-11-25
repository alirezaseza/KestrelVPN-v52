package com.kestrel.vpn.vpn

import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.ServerWithResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailoverPolicyTest {
    private fun s(key: String) = ServerWithResult(
        ProxyConfig(
            canonicalKey = key, protocol = Protocol.TROJAN, host = "$key.example.com",
            port = 443, credential = "pw", security = SecurityType.TLS
        ),
        null
    )

    @Test
    fun `skips servers that already failed this session`() {
        val next = FailoverPolicy.next(listOf(s("a"), s("b"), s("c")), setOf("a", "b"))
        assertEquals("c", next?.config?.canonicalKey)
    }

    @Test
    fun `returns null when every candidate failed`() {
        assertNull(FailoverPolicy.next(listOf(s("a")), setOf("a")))
    }

    @Test
    fun `keeps best-first order`() {
        assertEquals("a", FailoverPolicy.next(listOf(s("a"), s("b")), emptySet())?.config?.canonicalKey)
    }
}
