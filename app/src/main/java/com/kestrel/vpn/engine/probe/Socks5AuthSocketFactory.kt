package com.kestrel.vpn.engine.probe

import okhttp3.Dns
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * OkHttp socket factory that speaks SOCKS5 with username/password to the local probe proxy.
 * The platform SOCKS support cannot carry per-connection credentials, and an unauthenticated
 * localhost proxy can be found and abused by any other app on the phone.
 *
 * Use together with [placeholderDns] and Proxy.NO_PROXY so host names are resolved by the proxy,
 * never by the device (no DNS leak, no poisoned answers).
 */
internal class Socks5AuthSocketFactory(
    private val proxyPort: Int,
    private val username: String,
    private val password: String
) : SocketFactory() {

    override fun createSocket(): Socket = Socks5Socket()
    override fun createSocket(host: String?, port: Int): Socket = unsupported()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Use the unconnected socket")

    private inner class Socks5Socket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported endpoint")
            val host = target.hostString
            super.connect(InetSocketAddress("127.0.0.1", proxyPort), timeout)
            val previousTimeout = soTimeout
            try {
                soTimeout = if (timeout > 0) timeout else DEFAULT_HANDSHAKE_TIMEOUT_MS
                handshake(host, target.port)
                soTimeout = previousTimeout
            } catch (t: Throwable) {
                runCatching { close() }
                throw if (t is IOException) t else IOException(t)
            }
        }

        private fun handshake(host: String, port: Int) {
            val out = getOutputStream()
            val input = getInputStream()
            out.write(byteArrayOf(5, 1, 2))
            out.flush()
            val method = input.readExactly(2)
            if (method[0] != 5.toByte() || method[1] != 2.toByte()) throw IOException("SOCKS auth method rejected")

            val user = username.toByteArray(Charsets.UTF_8)
            val pass = password.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
            out.flush()
            val auth = input.readExactly(2)
            if (auth[1] != 0.toByte()) throw IOException("SOCKS authentication failed")

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            if (hostBytes.isEmpty() || hostBytes.size > 255) throw IOException("Invalid target host")
            out.write(
                byteArrayOf(5, 1, 0, 3, hostBytes.size.toByte()) + hostBytes +
                    byteArrayOf((port shr 8).toByte(), port.toByte())
            )
            out.flush()
            val reply = input.readExactly(4)
            if (reply[1] != 0.toByte()) throw IOException("SOCKS connect failed (code ${reply[1].toInt() and 0xFF})")
            when (reply[3].toInt()) {
                1 -> input.readExactly(4 + 2)
                4 -> input.readExactly(16 + 2)
                3 -> input.readExactly((input.readExactly(1)[0].toInt() and 0xFF) + 2)
                else -> throw IOException("Unexpected SOCKS address type")
            }
        }
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(buffer, offset, count - offset)
            if (read < 0) throw IOException("SOCKS proxy closed the connection")
            offset += read
        }
        return buffer
    }

    companion object {
        private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000

        /** Returns a placeholder address that keeps the host name; the proxy does the real lookup. */
        val placeholderDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        }
    }
}
