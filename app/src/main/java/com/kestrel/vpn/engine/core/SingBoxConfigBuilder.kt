package com.kestrel.vpn.engine.core

import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.TransportType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Targets sing-box 1.11+: uses route-rule actions (sniff / hijack-dns) instead of the legacy
 * inbound "sniff" field and the special "dns" outbound (both removed in 1.13).
 */
object SingBoxConfigBuilder {

    const val TUN_ADDRESS_V4 = "172.19.0.1/30"
    const val TUN_ADDRESS_V6 = "fdfe:dcba:9876::1/126"

    // Encrypted (DoH on an IP literal => no SNI) resolver for proxy-server / .ir domains outside
    // the tunnel, so the ISP can't read or tamper with those lookups.
    private const val DIRECT_DNS = "https://8.8.8.8/dns-query"
    private const val REMOTE_DNS = "https://1.1.1.1/dns-query"

    // Suffix match covers the domain itself and all subdomains ("ir" = every .ir site).
    internal val IRAN_DIRECT_DOMAIN_SUFFIXES = listOf(
        "ir", "digikala.com", "aparat.com", "filimo.com", "telewebion.com",
        "zarinpal.com", "torob.com", "tebyan.net", "eitaa.com", "bale.ai"
    )

    private fun JsonObjectBuilder.putIranSuffixes() {
        putJsonArray("domain_suffix") {
            IRAN_DIRECT_DOMAIN_SUFFIXES.forEach { add(JsonPrimitive(it)) }
        }
    }

    fun buildLocalSocksConfig(
        config: ProxyConfig,
        listenPort: Int,
        allowInsecureTls: Boolean = false,
        username: String? = null,
        password: String? = null,
        verbose: Boolean = false,
        fingerprintOverride: String = ""
    ): String {
        val root = buildJsonObject {
            putJsonObject("log") {
                put("disabled", !verbose)
                put("level", if (verbose) "debug" else "error")
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(
                        buildJsonObject {
                            put("tag", "dns-probe")
                            put("address", DIRECT_DNS)
                        }
                    )
                }
                put("strategy", "prefer_ipv4")
            }
            putJsonArray("inbounds") {
                add(
                    buildJsonObject {
                        put("type", "socks")
                        put("tag", "socks-in")
                        put("listen", "127.0.0.1")
                        put("listen_port", listenPort)
                        // Without credentials any app on the phone could use (or detect) this local proxy.
                        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                            putJsonArray("users") {
                                add(
                                    buildJsonObject {
                                        put("username", username)
                                        put("password", password)
                                    }
                                )
                            }
                        }
                    }
                )
            }
            putJsonArray("outbounds") {
                add(buildProxyOutbound(config, tag = "proxy", allowInsecureTls = allowInsecureTls, fingerprintOverride = fingerprintOverride))
            }
            putJsonObject("route") {
                put("final", "proxy")
            }
        }
        return root.toString()
    }

    fun buildTunConfig(
        config: ProxyConfig,
        mtu: Int,
        allowInsecureTls: Boolean = false,
        bypassIran: Boolean = true,
        fingerprintOverride: String = ""
    ): String {
        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
                put("timestamp", true)
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(
                        buildJsonObject {
                            put("tag", "dns-remote")
                            put("address", REMOTE_DNS)
                            put("detour", "proxy")
                        }
                    )
                    // No "detour": a detour to an empty direct outbound is rejected by newer sing-box.
                    add(
                        buildJsonObject {
                            put("tag", "dns-direct")
                            put("address", DIRECT_DNS)
                        }
                    )
                }
                putJsonArray("rules") {
                    // Domains of outbound servers (the proxy itself) must never be resolved
                    // through the proxy, otherwise the tunnel can never come up.
                    add(
                        buildJsonObject {
                            putJsonArray("outbound") { add(JsonPrimitive("any")) }
                            put("server", "dns-direct")
                        }
                    )
                    if (bypassIran) {
                        add(
                            buildJsonObject {
                                putIranSuffixes()
                                put("server", "dns-direct")
                            }
                        )
                    }
                }
                put("final", "dns-remote")
                put("strategy", "prefer_ipv4")
            }
            putJsonArray("inbounds") {
                add(
                    buildJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", "tun0")
                        putJsonArray("address") {
                            add(JsonPrimitive(TUN_ADDRESS_V4))
                            add(JsonPrimitive(TUN_ADDRESS_V6))
                        }
                        put("mtu", mtu)
                        put("auto_route", true)
                        put("strict_route", true)
                        put("stack", "gvisor")
                    }
                )
            }
            putJsonArray("outbounds") {
                add(buildProxyOutbound(config, tag = "proxy", allowInsecureTls = allowInsecureTls, fingerprintOverride = fingerprintOverride))
                add(
                    buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    }
                )
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject { put("action", "sniff") })
                    add(
                        buildJsonObject {
                            put("protocol", "dns")
                            put("action", "hijack-dns")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("ip_is_private", true)
                            put("outbound", "direct")
                        }
                    )
                    if (bypassIran) {
                        add(
                            buildJsonObject {
                                putIranSuffixes()
                                put("outbound", "direct")
                            }
                        )
                    }
                }
                put("final", "proxy")
                put("auto_detect_interface", true)
            }
        }
        return root.toString()
    }

    /** Fingerprint actually sent: the user's override, else the link's, else "chrome" (REALITY needs uTLS). */
    internal fun effectiveFingerprint(config: ProxyConfig, override: String): String =
        override.ifBlank { config.fingerprint }

    /** One log line with the REALITY parameters that are really used (public key shortened, no secrets). */
    fun describeReality(config: ProxyConfig, override: String): String? {
        if (config.security != SecurityType.REALITY) return null
        val fp = effectiveFingerprint(config, override).ifBlank { "chrome(default)" }
        val src = if (override.isNotBlank()) "override" else "link"
        return "reality sni=${config.sni.ifBlank { config.host }} fp=$fp($src) flow=${config.flow.ifBlank { "-" }} " +
            "sid=${config.realityShortId.ifBlank { "-" }} pbk=${config.realityPublicKey.take(6)}..(${config.realityPublicKey.length}) " +
            "spx=${config.realitySpiderX.ifBlank { "-" }}"
    }

    internal fun buildProxyOutbound(
        config: ProxyConfig,
        tag: String,
        allowInsecureTls: Boolean = false,
        fingerprintOverride: String = ""
    ): JsonObject =
        buildJsonObject {
            put("tag", tag)
            put("server", config.host)
            put("server_port", config.port)
            when (config.protocol) {
                Protocol.VLESS -> {
                    put("type", "vless")
                    put("uuid", config.credential)
                    if (config.flow.isNotBlank()) {
                        put("flow", config.flow)
                    }
                    put("packet_encoding", "xudp")
                }
                Protocol.VMESS -> {
                    put("type", "vmess")
                    put("uuid", config.credential)
                    put("security", config.method.ifBlank { "auto" })
                    put("alter_id", config.alterId)
                }
                Protocol.TROJAN -> {
                    put("type", "trojan")
                    put("password", config.credential)
                }
                Protocol.SHADOWSOCKS -> {
                    put("type", "shadowsocks")
                    put("method", config.method)
                    put("password", config.credential)
                }
            }
            if (config.protocol != Protocol.SHADOWSOCKS) {
                appendTlsBlock(config, allowInsecureTls, fingerprintOverride)
                appendTransportBlock(config)
            }
        }

    private fun JsonObjectBuilder.appendTlsBlock(config: ProxyConfig, allowInsecureTls: Boolean, fingerprintOverride: String) {
        if (config.security == SecurityType.NONE) return
        putJsonObject("tls") {
            put("enabled", true)
            val effectiveSni = config.sni.ifBlank { config.host }
            put("server_name", effectiveSni)
            // Certificate checks are only skipped when the user explicitly opted in.
            if (allowInsecureTls && config.allowInsecure && config.security == SecurityType.TLS) {
                put("insecure", true)
            }
            if (config.alpn.isNotBlank()) {
                val alpnList = config.alpn.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (alpnList.isNotEmpty()) {
                    put("alpn", JsonArray(alpnList.map { JsonPrimitive(it) }))
                }
            }
            // Reality requires uTLS in sing-box, so fall back to "chrome" when the link has no fp.
            val effectiveFp = effectiveFingerprint(config, fingerprintOverride)
            if (effectiveFp.isNotBlank() || config.security == SecurityType.REALITY) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", effectiveFp.ifBlank { "chrome" })
                }
            }
            if (config.security == SecurityType.REALITY) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", config.realityPublicKey)
                    if (config.realityShortId.isNotBlank()) {
                        put("short_id", config.realityShortId)
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.appendTransportBlock(config: ProxyConfig) {
        when (config.transport) {
            TransportType.TCP -> Unit
            TransportType.WS -> {
                putJsonObject("transport") {
                    put("type", "ws")
                    val (wsPath, earlyData) = splitEarlyData(config.path.ifBlank { "/" })
                    put("path", wsPath)
                    if (earlyData > 0) {
                        put("max_early_data", earlyData)
                        put("early_data_header_name", "Sec-WebSocket-Protocol")
                    }
                    if (config.hostHeader.isNotBlank()) {
                        putJsonObject("headers") {
                            put("Host", config.hostHeader)
                        }
                    }
                }
            }
            TransportType.GRPC -> {
                putJsonObject("transport") {
                    put("type", "grpc")
                    if (config.serviceName.isNotBlank()) {
                        put("service_name", config.serviceName)
                    }
                }
            }
            TransportType.HTTPUPGRADE -> {
                putJsonObject("transport") {
                    put("type", "httpupgrade")
                    put("path", config.path.ifBlank { "/" })
                    if (config.hostHeader.isNotBlank()) {
                        put("host", config.hostHeader)
                    }
                }
            }
            TransportType.HTTP -> {
                putJsonObject("transport") {
                    put("type", "http")
                    put("path", config.path.ifBlank { "/" })
                    val hostValue = config.hostHeader.ifBlank { config.sni }
                    if (hostValue.isNotBlank()) {
                        putJsonArray("host") { add(JsonPrimitive(hostValue)) }
                    }
                }
            }
        }
    }

    /** Xray-style `/path?ed=2048` -> ("/path", 2048). Other query parameters are kept. */
    internal fun splitEarlyData(path: String): Pair<String, Int> {
        val m = Regex("([?&])ed=(\\d+)").find(path) ?: return path to 0
        val ed = m.groupValues[2].toIntOrNull() ?: 0
        var cleaned = path.removeRange(m.range)
        if (m.groupValues[1] == "?" && cleaned.contains('&') && !cleaned.contains('?')) {
            cleaned = cleaned.replaceFirst('&', '?')
        }
        cleaned = cleaned.trimEnd('?', '&')
        return cleaned.ifEmpty { "/" } to ed
    }
}
