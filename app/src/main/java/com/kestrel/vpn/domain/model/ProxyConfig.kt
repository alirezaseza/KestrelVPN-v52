package com.kestrel.vpn.domain.model
enum class Protocol(val wireName: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    SHADOWSOCKS("ss");
    companion object {
        fun fromWireName(value: String): Protocol? =
        entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) }
    }
}
enum class TransportType(val wireName: String) {
    TCP("tcp"),
    WS("ws"),
    GRPC("grpc"),
    HTTPUPGRADE("httpupgrade"),
    HTTP("h2");
    companion object {
        fun fromWireName(value: String?): TransportType? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "", "tcp", "raw", "none" -> TCP
                "ws", "websocket" -> WS
                "grpc" -> GRPC
                "httpupgrade" -> HTTPUPGRADE
                "h2", "http" -> HTTP
                else -> null
            }
        }
    }
}
enum class SecurityType(val wireName: String) {
    NONE("none"),
    TLS("tls"),
    REALITY("reality");
    companion object {
        fun fromWireName(value: String?): SecurityType? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "", "none" -> NONE
                "tls" -> TLS
                "reality" -> REALITY
                else -> null
            }
        }
    }
}
data class ProxyConfig(
    val canonicalKey: String,
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
