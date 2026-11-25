package com.kestrel.vpn.vpn
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.kestrel.vpn.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
enum class TunnelHealthOutcome {
    HEALTHY,
    TUNNEL_FAILED_INTERNET_OK,
    DEVICE_OFFLINE
}
@Singleton
class TunnelHealthProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("tunnel") private val tunnelClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun checkHealth(): TunnelHealthOutcome = withContext(ioDispatcher) {
        if (probeThroughTunnel()) {
            return@withContext TunnelHealthOutcome.HEALTHY
        }
        val physicalOnline = isUnderlyingPhysicalNetworkOnline()
        if (physicalOnline) {
            TunnelHealthOutcome.TUNNEL_FAILED_INTERNET_OK
        } else {
            TunnelHealthOutcome.DEVICE_OFFLINE
        }
    }
    private fun probeThroughTunnel(): Boolean {
        for (url in TUNNEL_ENDPOINTS) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KestrelVPN/1.0")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            val ok = runCatching {
                tunnelClient.newCall(request).execute().use { response ->
                    response.code == 204
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
    /**
 * Leak-safe: asks Android whether the physical network is validated instead of sending
 * our own HTTP requests over it (that would expose the real IP to the probe endpoints
 * while the user believes they are protected).
 */
    private fun isUnderlyingPhysicalNetworkOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = findPhysicalNetwork(cm) ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Suppress("DEPRECATION")
    private fun findPhysicalNetwork(cm: ConnectivityManager): Network? {
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }
    companion object {
        private val TUNNEL_ENDPOINTS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }
}
