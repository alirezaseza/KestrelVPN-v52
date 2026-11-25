package com.kestrel.vpn.engine.probe
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
data class ActiveNetworkInfo(
    val networkKey: String,
    val isWifi: Boolean,
    val hasSystemNetwork: Boolean
)
@Singleton
class NetworkKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager: ConnectivityManager?
    get() = context.getSystemService(ConnectivityManager::class.java)
    fun currentNetworkInfo(): ActiveNetworkInfo {
        val cm = connectivityManager ?: return ActiveNetworkInfo("unknown", false, false)
        // While our VPN is up, activeNetwork IS the VPN. Look through it to the physical network so
        // the key (and the test cache) stays "wifi"/"mobile:..." instead of flipping to "other".
        val caps = resolvePhysicalCapabilities(cm)
            ?: return ActiveNetworkInfo("offline", false, false)
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val key = when {
            isWifi -> "wifi"
            isCellular -> "mobile:${carrierSlug()}"
            else -> "other"
        }
        return ActiveNetworkInfo(
            networkKey = key,
            isWifi = isWifi,
            hasSystemNetwork = hasInternet
        )
    }
    private val epoch = java.util.concurrent.atomic.AtomicLong(0)
    private val epochWatching = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Counts physical-network changes: a network appears, disappears, or flips validated/not validated.
     * A probe that started in one epoch and failed in another was disturbed by the phone's network,
     * not by the server. The callback is registered on the first call and lives as long as the app.
     */
    fun networkEpoch(): Long {
        if (epochWatching.compareAndSet(false, true)) startEpochWatch()
        return epoch.get()
    }

    private fun startEpochWatch() {
        val cm = connectivityManager ?: return
        val validatedByNetwork = java.util.concurrent.ConcurrentHashMap<Network, Boolean>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                epoch.incrementAndGet()
            }
            override fun onLost(network: Network) {
                validatedByNetwork.remove(network)
                epoch.incrementAndGet()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Signal-strength updates also arrive here: only a change of "validated" counts.
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val before = validatedByNetwork.put(network, validated)
                if (before != null && before != validated) epoch.incrementAndGet()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    fun observeNetworkInfo(): Flow<ActiveNetworkInfo> = callbackFlow {
        val cm = connectivityManager
        trySend(currentNetworkInfo())
        if (cm == null) {
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentNetworkInfo())
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(currentNetworkInfo())
            }
            override fun onLost(network: Network) {
                trySend(currentNetworkInfo())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
    @Suppress("DEPRECATION")
    private fun resolvePhysicalCapabilities(cm: ConnectivityManager): NetworkCapabilities? {
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return activeCaps
        }
        return cm.allNetworks
            .mapNotNull { cm.getNetworkCapabilities(it) }
            .filter {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
            .maxByOrNull { if (it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0 }
    }
    private fun carrierSlug(): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val raw = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm?.simOperatorName?.takeIf { it.isNotBlank() }
            ?: "default"
        return raw.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }
}
