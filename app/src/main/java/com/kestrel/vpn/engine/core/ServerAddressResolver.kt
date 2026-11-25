package com.kestrel.vpn.engine.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.domain.model.TransportType
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves a proxy server's domain with the phone's own resolver, bound to the PHYSICAL network
 * (so it works while another tunnel is up), before sing-box starts. sing-box then dials the IP
 * and no longer depends on the DoH lookup (https://8.8.8.8) that is often blocked.
 * If resolution fails the config is returned unchanged and sing-box resolves it as before.
 * SNI / Host header are pinned to the original domain so TLS and CDN routing keep working.
 */
internal object ServerAddressResolver {
    private const val TTL_MS = 5 * 60_000L
    private const val TIMEOUT_MS = 3_000L
    private class Entry(val ip: String, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "kestrel-resolve").apply { isDaemon = true } }

    fun resolve(context: Context, config: ProxyConfig): ProxyConfig {
        val host = config.host
        if (isIpLiteral(host)) return config
        val ip = lookup(context, host) ?: return config
        return config.copy(
            host = ip,
            sni = if (config.security != SecurityType.NONE) config.sni.ifBlank { host } else config.sni,
            hostHeader = when (config.transport) {
                TransportType.WS, TransportType.HTTPUPGRADE, TransportType.HTTP -> config.hostHeader.ifBlank { host }
                else -> config.hostHeader
            }
        )
    }

    internal fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)

    private fun lookup(context: Context, host: String): String? {
        val now = System.currentTimeMillis()
        cache[host]?.let { if (now - it.atMs < TTL_MS) return it.ip }
        val future = pool.submit<String?> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val physical = cm?.allNetworks?.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            val addrs: Array<InetAddress>? = runCatching { physical?.getAllByName(host) }.getOrNull()
                ?: runCatching { InetAddress.getAllByName(host) }.getOrNull()
            val best = addrs?.firstOrNull { it is Inet4Address } ?: addrs?.firstOrNull()
            best?.hostAddress?.substringBefore('%')
        }
        val ip = try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            future.cancel(true)
            null
        }
        if (ip != null) cache[host] = Entry(ip, now)
        return ip
    }
}
