package com.kestrel.vpn.data.parser
import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
data class ParseBatchResult(
    val configs: List<ProxyConfig>,
    val totalLines: Int,
    val duplicateCount: Int,
    val invalidCount: Int
)
@Singleton
class ProxyLinkParser @Inject constructor() {
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val supportedSchemes = listOf("vless://", "vmess://", "trojan://", "ss://")
    fun parseSubscriptionContent(rawContent: String): ParseBatchResult {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) {
            return ParseBatchResult(emptyList(), 0, 0, 0)
        }
        val decodedText = if (containsAnyScheme(trimmed)) {
            trimmed
        } else {
            val decoded = Base64Compat.decodeToString(trimmed)
            if (decoded != null && containsAnyScheme(decoded)) decoded else trimmed
        }
        val lines = decodedText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val dedupMap = LinkedHashMap<String, ProxyConfig>(lines.size)
        var duplicateCount = 0
        var invalidCount = 0
        for (line in lines) {
            val parsed = parseLine(line)
            if (parsed == null) {
                invalidCount++
                continue
            }
            val existing = dedupMap[parsed.canonicalKey]
            if (existing == null) {
                dedupMap[parsed.canonicalKey] = parsed
            } else {
                duplicateCount++
                if (existing.remark.isBlank() && parsed.remark.isNotBlank()) {
                    dedupMap[parsed.canonicalKey] = existing.copy(remark = parsed.remark)
                }
            }
        }
        return ParseBatchResult(
            configs = dedupMap.values.toList(),
            totalLines = lines.size,
            duplicateCount = duplicateCount,
            invalidCount = invalidCount
        )
    }
    fun parseLine(line: String): ProxyConfig? = runCatching {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        when {
            lower.startsWith("vless://") -> parseVlessOrTrojan(trimmed, Protocol.VLESS)
            lower.startsWith("trojan://") -> parseVlessOrTrojan(trimmed, Protocol.TROJAN)
            lower.startsWith("vmess://") -> parseVmess(trimmed)
            lower.startsWith("ss://") -> parseShadowsocks(trimmed)
            else -> null
        }
    }.getOrNull()
    private fun parseVlessOrTrojan(uri: String, protocol: Protocol): ProxyConfig? {
        val withoutScheme = uri.substringAfter("://")
        val (beforeFragment, rawFragment) = splitOnce(withoutScheme, '#')
        val (authority, rawQuery) = splitOnce(beforeFragment, '?')
        val atIndex = authority.lastIndexOf('@')
        if (atIndex <= 0 || atIndex >= authority.length - 1) return null
        val credential = urlDecode(authority.substring(0, atIndex))
        val hostPort = parseHostPort(authority.substring(atIndex + 1)) ?: return null
        val params = parseQueryParams(rawQuery)
        val transport = TransportType.fromWireName(params["type"] ?: params["net"]) ?: return null
        val defaultSecurity = if (protocol == Protocol.TROJAN) SecurityType.TLS else SecurityType.NONE
        val security = if (params["security"].isNullOrBlank()) {
            defaultSecurity
        } else {
            SecurityType.fromWireName(params["security"]) ?: return null
        }
        val hostHeaderParam = params["host"] ?: ""
        val sni = (params["sni"] ?: params["peer"]).orEmpty().ifBlank {
            if (security != SecurityType.NONE) hostHeaderParam else ""
        }
        val alpn = params["alpn"] ?: ""
        val fp = params["fp"] ?: params["fingerprint"] ?: ""
        val allowInsecure = isTruthy(params["allowinsecure"] ?: params["insecure"])
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""
        val spx = params["spx"] ?: ""
        val flow = if (protocol == Protocol.VLESS) params["flow"] ?: "" else ""
        val path = params["path"] ?: ""
        val hostHeader = params["host"] ?: ""
        val serviceName = params["servicename"] ?: params["path"] ?: ""
        val grpcMultiMode = params["mode"].equals("multi", ignoreCase = true)
        return ConfigNormalizer.normalize(
            RawParsedConfig(
                protocol = protocol,
                host = hostPort.first,
                port = hostPort.second,
                credential = credential,
                flow = flow,
                security = security,
                sni = sni,
                alpn = alpn,
                fingerprint = fp,
                allowInsecure = allowInsecure,
                realityPublicKey = pbk,
                realityShortId = sid,
                realitySpiderX = spx,
                transport = transport,
                path = path,
                hostHeader = hostHeader,
                serviceName = serviceName,
                grpcMultiMode = grpcMultiMode,
                remark = urlDecode(rawFragment),
                rawUri = uri
            )
        )
    }
    private fun parseVmess(uri: String): ProxyConfig? {
        val payload = uri.substringAfter("://").substringBefore("#").trim()
        if (payload.isEmpty()) return null
        val jsonString = Base64Compat.decodeToString(payload) ?: return null
        val jsonObj = runCatching {
            lenientJson.parseToJsonElement(jsonString) as? JsonObject
        }.getOrNull() ?: return null
        val host = jsonObj.str("add") ?: return null
        val port = jsonObj.str("port")?.toIntOrNull() ?: return null
        val uuid = jsonObj.str("id") ?: return null
        val alterId = jsonObj.str("aid")?.toIntOrNull() ?: 0
        val method = jsonObj.str("scy") ?: jsonObj.str("security") ?: "auto"
        val transport = TransportType.fromWireName(jsonObj.str("net")) ?: return null
        val tlsRaw = jsonObj.str("tls").orEmpty().lowercase(Locale.ROOT)
        val security = when (tlsRaw) {
            "", "none", "0", "false" -> SecurityType.NONE
            "tls", "1", "true" -> SecurityType.TLS
            "reality" -> SecurityType.REALITY
            else -> return null
        }
        val sni = jsonObj.str("sni").orEmpty().ifBlank {
            if (security != SecurityType.NONE) jsonObj.str("host").orEmpty() else ""
        }
        val alpn = jsonObj.str("alpn").orEmpty()
        val fp = jsonObj.str("fp").orEmpty()
        val allowInsecure = isTruthy(jsonObj.str("allowInsecure") ?: jsonObj.str("insecure"))
        val path = jsonObj.str("path").orEmpty()
        val hostHeader = jsonObj.str("host").orEmpty()
        val serviceName = if (transport == TransportType.GRPC) path else ""
        val grpcMultiMode = jsonObj.str("type").equals("multi", ignoreCase = true)
        val remark = jsonObj.str("ps").orEmpty()
        return ConfigNormalizer.normalize(
            RawParsedConfig(
                protocol = Protocol.VMESS,
                host = host,
                port = port,
                credential = uuid,
                method = method,
                alterId = alterId,
                security = security,
                sni = sni,
                alpn = alpn,
                fingerprint = fp,
                allowInsecure = allowInsecure,
                transport = transport,
                path = path,
                hostHeader = hostHeader,
                serviceName = serviceName,
                grpcMultiMode = grpcMultiMode,
                remark = remark,
                rawUri = uri
            )
        )
    }
    private fun parseShadowsocks(uri: String): ProxyConfig? {
        val withoutScheme = uri.substringAfter("://")
        val (beforeFragment, rawFragment) = splitOnce(withoutScheme, '#')
        val (mainPart, rawQuery) = splitOnce(beforeFragment, '?')
        val remark = urlDecode(rawFragment)
        val params = parseQueryParams(rawQuery)
        if (!params["plugin"].isNullOrBlank()) {
            return null
        }
        val method: String
        val password: String
        val hostPort: Pair<String, Int>
        val atIndex = mainPart.lastIndexOf('@')
        if (atIndex > 0) {
            val userInfoRaw = mainPart.substring(0, atIndex)
            val decodedUserInfo = if (userInfoRaw.contains(':')) {
                urlDecode(userInfoRaw)
            } else {
                Base64Compat.decodeToString(userInfoRaw) ?: return null
            }
            val colonIdx = decodedUserInfo.indexOf(':')
            if (colonIdx <= 0 || colonIdx >= decodedUserInfo.length - 1) return null
            method = decodedUserInfo.substring(0, colonIdx)
            password = decodedUserInfo.substring(colonIdx + 1)
            hostPort = parseHostPort(mainPart.substring(atIndex + 1)) ?: return null
        } else {
            val decoded = Base64Compat.decodeToString(mainPart) ?: return null
            val innerAt = decoded.lastIndexOf('@')
            if (innerAt <= 0 || innerAt >= decoded.length - 1) return null
            val userInfo = decoded.substring(0, innerAt)
            val colonIdx = userInfo.indexOf(':')
            if (colonIdx <= 0 || colonIdx >= userInfo.length - 1) return null
            method = userInfo.substring(0, colonIdx)
            password = userInfo.substring(colonIdx + 1)
            hostPort = parseHostPort(decoded.substring(innerAt + 1)) ?: return null
        }
        return ConfigNormalizer.normalize(
            RawParsedConfig(
                protocol = Protocol.SHADOWSOCKS,
                host = hostPort.first,
                port = hostPort.second,
                credential = password,
                method = method,
                security = SecurityType.NONE,
                transport = TransportType.TCP,
                remark = remark,
                rawUri = uri
            )
        )
    }
    private fun parseHostPort(authority: String): Pair<String, Int>? {
        val cleaned = authority.trim().substringBefore('/')
        if (cleaned.isEmpty()) return null
        val host: String
        val portStr: String
        if (cleaned.startsWith("[")) {
            val closeBracket = cleaned.indexOf(']')
            if (closeBracket <= 1) return null
            host = cleaned.substring(1, closeBracket)
            if (closeBracket + 1 >= cleaned.length || cleaned[closeBracket + 1] != ':') return null
            portStr = cleaned.substring(closeBracket + 2)
        } else {
            val colonIdx = cleaned.lastIndexOf(':')
            if (colonIdx <= 0 || colonIdx >= cleaned.length - 1) return null
            host = cleaned.substring(0, colonIdx)
            portStr = cleaned.substring(colonIdx + 1)
        }
        val port = portStr.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }
    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val result = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            val key = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
            val value = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
            val decodedKey = urlDecode(key).trim().lowercase(Locale.ROOT)
            if (decodedKey.isNotEmpty()) {
                result[decodedKey] = urlDecode(value).trim()
            }
        }
        return result
    }
    private fun splitOnce(value: String, delimiter: Char): Pair<String, String> {
        val idx = value.indexOf(delimiter)
        return if (idx < 0) value to "" else value.substring(0, idx) to value.substring(idx + 1)
    }
    private fun urlDecode(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(value)
    }
    private fun isTruthy(value: String?): Boolean {
        val lower = value?.trim()?.lowercase(Locale.ROOT) ?: return false
        return lower == "1" || lower == "true" || lower == "yes"
    }
    private fun containsAnyScheme(text: String): Boolean {
        val lower = text.take(4096).lowercase(Locale.ROOT)
        return supportedSchemes.any { lower.contains(it) }
    }
    private fun JsonObject.str(key: String): String? {
        val element = this[key] as? JsonPrimitive ?: return null
        return element.contentOrNull?.trim()
    }
}
