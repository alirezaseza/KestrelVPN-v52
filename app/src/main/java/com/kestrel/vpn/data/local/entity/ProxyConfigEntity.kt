package com.kestrel.vpn.data.local.entity
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kestrel.vpn.domain.model.Protocol
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.TransportType
@Entity(
    tableName = "proxy_configs",
    indices = [
        Index(value = ["isActive"]),
        Index(value = ["syncGeneration"])
    ]
)
data class ProxyConfigEntity(
    @PrimaryKey
    val canonicalKey: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val credential: String,
    val method: String,
    val alterId: Int,
    val flow: String,
    val security: String,
    val sni: String,
    val alpn: String,
    val fingerprint: String,
    val allowInsecure: Boolean,
    val realityPublicKey: String,
    val realityShortId: String,
    val realitySpiderX: String,
    val transport: String,
    val path: String,
    val hostHeader: String,
    val serviceName: String,
    val grpcMultiMode: Boolean,
    val remark: String,
    val rawUri: String,
    val isActive: Boolean,
    val syncGeneration: Long,
    val importedAtMs: Long
) {
    fun toDomain(): ProxyConfig = ProxyConfig(
        canonicalKey = canonicalKey,
        protocol = Protocol.fromWireName(protocol) ?: Protocol.VLESS,
        host = host,
        port = port,
        credential = credential,
        method = method,
        alterId = alterId,
        flow = flow,
        security = SecurityType.fromWireName(security) ?: SecurityType.NONE,
        sni = sni,
        alpn = alpn,
        fingerprint = fingerprint,
        allowInsecure = allowInsecure,
        realityPublicKey = realityPublicKey,
        realityShortId = realityShortId,
        realitySpiderX = realitySpiderX,
        transport = TransportType.fromWireName(transport) ?: TransportType.TCP,
        path = path,
        hostHeader = hostHeader,
        serviceName = serviceName,
        grpcMultiMode = grpcMultiMode,
        remark = remark,
        rawUri = rawUri
    )
    companion object {
        fun fromDomain(
            config: ProxyConfig,
            syncGeneration: Long,
            importedAtMs: Long
        ): ProxyConfigEntity = ProxyConfigEntity(
            canonicalKey = config.canonicalKey,
            protocol = config.protocol.wireName,
            host = config.host,
            port = config.port,
            credential = config.credential,
            method = config.method,
            alterId = config.alterId,
            flow = config.flow,
            security = config.security.wireName,
            sni = config.sni,
            alpn = config.alpn,
            fingerprint = config.fingerprint,
            allowInsecure = config.allowInsecure,
            realityPublicKey = config.realityPublicKey,
            realityShortId = config.realityShortId,
            realitySpiderX = config.realitySpiderX,
            transport = config.transport.wireName,
            path = config.path,
            hostHeader = config.hostHeader,
            serviceName = config.serviceName,
            grpcMultiMode = config.grpcMultiMode,
            remark = config.remark,
            rawUri = config.rawUri,
            isActive = true,
            syncGeneration = syncGeneration,
            importedAtMs = importedAtMs
        )
    }
}

/** Configs added by hand carry this generation so a subscription refresh never deactivates them. */
const val MANUAL_SYNC_GENERATION: Long = Long.MAX_VALUE
