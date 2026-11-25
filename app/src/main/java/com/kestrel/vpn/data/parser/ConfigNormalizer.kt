package com.kestrel.vpn.data.parser
import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.TransportType
import java.security.MessageDigest
import java.util.Locale
data class RawParsedConfig(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val credential: String,
    val method: String = "",
    val alterId: Int = 0,
    val flow: String = "",
    val security: SecurityType = SecurityType.NONE,
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
    val transport: TransportType = TransportType.TCP,
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val grpcMultiMode: Boolean = false,
    val remark: String = "",
    val rawUri: String = ""
)
object ConfigNormalizer {
    fun normalize(raw: RawParsedConfig): ProxyConfig? {
        val host = normalizeHost(raw.host) ?: return null
        if (raw.port !in 1..65535) return null
        val credential = raw.credential.trim()
        if (credential.isEmpty()) return null
        val method = when (raw.protocol) {
            Protocol.SHADOWSOCKS -> raw.method.trim().lowercase(Locale.ROOT).ifEmpty { return null }
            Protocol.VMESS -> raw.method.trim().lowercase(Locale.ROOT).ifEmpty { "auto" }
            Protocol.VLESS, Protocol.TROJAN -> ""
        }
        val alterId = if (raw.protocol == Protocol.VMESS) raw.alterId.coerceAtLeast(0) else 0
        val flow = if (raw.protocol == Protocol.VLESS) normalizeFlow(raw.flow) else ""
        val security = raw.security
        val sni = if (security != SecurityType.NONE) {
            normalizeHost(raw.sni).orEmpty()
        } else {
            ""
        }
        val alpn = if (security != SecurityType.NONE) {
            val normalized = normalizeAlpn(raw.alpn)
            // WebSocket/HTTPUpgrade cannot run over h2: offering h2 makes the server pick it and the upgrade fails.
            if (raw.transport == TransportType.WS || raw.transport == TransportType.HTTPUPGRADE) {
                if (normalized.isBlank()) "" else "http/1.1"
            } else normalized
        } else {
            ""
        }
        val fingerprint = if (security != SecurityType.NONE) {
            raw.fingerprint.trim().lowercase(Locale.ROOT)
        } else {
            ""
        }
        val allowInsecure = security == SecurityType.TLS && raw.allowInsecure
        val realityPublicKey = if (security == SecurityType.REALITY) {
            raw.realityPublicKey.trim().ifEmpty { return null }
        } else {
            ""
        }
        val realityShortId = if (security == SecurityType.REALITY) {
            raw.realityShortId.trim().lowercase(Locale.ROOT)
        } else {
            ""
        }
        val realitySpiderX = if (security == SecurityType.REALITY) {
            raw.realitySpiderX.trim()
        } else {
            ""
        }
        val transport = raw.transport
        val path = when (transport) {
            TransportType.WS, TransportType.HTTPUPGRADE, TransportType.HTTP -> normalizePath(raw.path)
            TransportType.TCP, TransportType.GRPC -> ""
        }
        val hostHeader = when (transport) {
            TransportType.WS, TransportType.HTTPUPGRADE, TransportType.HTTP ->
            normalizeHost(raw.hostHeader).orEmpty()
            TransportType.TCP, TransportType.GRPC -> ""
        }
        val serviceName = when (transport) {
            TransportType.GRPC -> raw.serviceName.trim().removePrefix("/")
            TransportType.TCP, TransportType.WS, TransportType.HTTPUPGRADE, TransportType.HTTP -> ""
        }
        val grpcMultiMode = transport == TransportType.GRPC && raw.grpcMultiMode
        val canonicalKey = computeCanonicalKey(
            protocol = raw.protocol,
            host = host,
            port = raw.port,
            credential = credential,
            method = method,
            alterId = alterId,
            flow = flow,
            security = security,
            sni = sni,
            alpn = alpn,
            fingerprint = fingerprint,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            transport = transport,
            path = path,
            hostHeader = hostHeader,
            serviceName = serviceName,
            grpcMultiMode = grpcMultiMode
        )
        return ProxyConfig(
            canonicalKey = canonicalKey,
            protocol = raw.protocol,
            host = host,
            port = raw.port,
            credential = credential,
            method = method,
            alterId = alterId,
            flow = flow,
            security = security,
            sni = sni,
            alpn = alpn,
            fingerprint = fingerprint,
            allowInsecure = allowInsecure,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            realitySpiderX = realitySpiderX,
            transport = transport,
            path = path,
            hostHeader = hostHeader,
            serviceName = serviceName,
            grpcMultiMode = grpcMultiMode,
            remark = raw.remark.trim().take(128),
            rawUri = raw.rawUri.trim()
        )
    }
    fun computeCanonicalKey(
        protocol: Protocol,
        host: String,
        port: Int,
        credential: String,
        method: String,
        alterId: Int,
        flow: String,
        security: SecurityType,
        sni: String,
        alpn: String,
        fingerprint: String,
        realityPublicKey: String,
        realityShortId: String,
        transport: TransportType,
        path: String,
        hostHeader: String,
        serviceName: String,
        grpcMultiMode: Boolean
    ): String {
        val canonicalPayload = buildString {
            append("p=").append(protocol.wireName)
            append("|h=").append(host)
            append("|pt=").append(port)
            append("|c=").append(credential)
            append("|m=").append(method)
            append("|aid=").append(alterId)
            append("|fl=").append(flow)
            append("|sec=").append(security.wireName)
            append("|sni=").append(sni)
            append("|alpn=").append(alpn)
            append("|fp=").append(fingerprint)
            append("|pbk=").append(realityPublicKey)
            append("|sid=").append(realityShortId)
            append("|tr=").append(transport.wireName)
            append("|path=").append(path)
            append("|hh=").append(hostHeader)
            append("|sn=").append(serviceName)
            append("|gm=").append(if (grpcMultiMode) "1" else "0")
        }
        return sha256Hex(canonicalPayload)
    }
    internal fun normalizeHost(input: String?): String? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val unbracketed = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
        val normalized = unbracketed.trimEnd('.').lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null
        if (normalized.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) {
            return null
        }
        return normalized
    }
    internal fun normalizeFlow(input: String?): String {
        val f = input?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return if (f.startsWith("xtls-rprx-vision")) "xtls-rprx-vision" else f
    }
    internal fun normalizeAlpn(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input.split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(",")
    }
    internal fun normalizePath(input: String?): String {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return "/"
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }
    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val hexChars = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hexChars[i * 2] = digits[v ushr 4]
            hexChars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hexChars)
    }
}
