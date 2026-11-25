package com.kestrel.vpn.engine.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class Socks5AuthSocketFactoryTest {

    private fun fakeProxy(server: ServerSocket, acceptAuth: Boolean, seen: MutableList<String>) = thread {
        server.accept().use { s ->
            val i = s.getInputStream(); val o = s.getOutputStream()
            i.readNBytes(3); o.write(byteArrayOf(5, 2)); o.flush()
            val ver = i.read(); val ul = i.read(); val u = String(i.readNBytes(ul))
            val pl = i.read(); val p = String(i.readNBytes(pl))
            seen += "$ver:$u:$p"
            o.write(byteArrayOf(1, if (acceptAuth) 0 else 1)); o.flush()
            if (!acceptAuth) return@use
            i.readNBytes(4); val hl = i.read(); seen += String(i.readNBytes(hl)); i.readNBytes(2)
            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
        }
    }

    @Test
    fun `sends credentials and domain name, never resolves locally`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val seen = mutableListOf<String>()
        val t = fakeProxy(server, true, seen)
        val sock = Socks5AuthSocketFactory(server.localPort, "user", "pass").createSocket()
        sock.connect(InetSocketAddress.createUnresolved("example.test", 443), 3000)
        t.join(3000); sock.close(); server.close()
        assertEquals("1:user:pass", seen[0])
        assertEquals("example.test", seen[1])
    }

    @Test
    fun `rejected authentication fails the connect`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val t = fakeProxy(server, false, mutableListOf())
        val sock = Socks5AuthSocketFactory(server.localPort, "u", "bad").createSocket()
        var failed = false
        try { sock.connect(InetSocketAddress.createUnresolved("example.test", 443), 3000) }
        catch (e: java.io.IOException) { failed = true }
        t.join(3000); server.close()
        assertTrue(failed)
    }
}
