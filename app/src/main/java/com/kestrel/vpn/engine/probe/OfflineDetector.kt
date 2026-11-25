package com.kestrel.vpn.engine.probe

import com.kestrel.vpn.di.IoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

interface ConnectivityChecker {
    suspend fun isInternetReachable(): Boolean

    /** The phone's OWN download speed in kbit/s, measured directly (no proxy); null when it cannot be measured. */
    suspend fun deviceDownloadKbps(): Int? = null

    /** Changes whenever the phone's physical network changes (see NetworkKeyProvider.networkEpoch). */
    fun networkEpoch(): Long = 0L
}

@Singleton
class OfflineDetector @Inject constructor(
    private val networkKeyProvider: NetworkKeyProvider,
    @Named("probe") private val httpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ConnectivityChecker {

    init {
        // Register the network watcher at app start, well before the first scan reads the epoch.
        networkKeyProvider.networkEpoch()
    }

    override fun networkEpoch(): Long = networkKeyProvider.networkEpoch()

    // Scans, the gate and the Home screen all ask this question; one answer is good for a moment.
    // Only "online" is cached, and only briefly: an outage must never be hidden for long.
    @Volatile
    private var lastOnlineAtNs = 0L

    override suspend fun isInternetReachable(): Boolean {
        val last = lastOnlineAtNs
        if (last != 0L && System.nanoTime() - last < ONLINE_CACHE_NS) return true
        val online = checkReachable()
        lastOnlineAtNs = if (online) System.nanoTime() else 0L
        return online
    }

    override suspend fun deviceDownloadKbps(): Int? = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(SPEED_URL)
            .header("User-Agent", "KestrelVPN/1.0")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        try {
            httpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return@use null
                val stream = response.body?.byteStream() ?: return@use null
                val buf = ByteArray(16 * 1024)
                val first = stream.read(buf)
                if (first <= 0) return@use null
                // Same method as the server speed test: the clock starts at the first byte.
                val startNs = System.nanoTime()
                var bytes = 0L
                while (System.nanoTime() - startNs < SPEED_WINDOW_NS) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    bytes += read
                }
                val ms = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(10L)
                if (bytes <= 0L) null else ((bytes * 8L) / ms).toInt().coerceAtLeast(1)
            }
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun checkReachable(): Boolean = withContext(ioDispatcher) {
        if (!networkKeyProvider.currentNetworkInfo().hasSystemNetwork) {
            return@withContext false
        }
        coroutineScope {
            // Return as soon as ONE endpoint proves connectivity; don't wait for the slowest one.
            val verdict = CompletableDeferred<Boolean>()
            val probes = PROBE_ENDPOINTS.map { url ->
                launch {
                    if (probeSingle(url)) verdict.complete(true)
                }
            }
            launch {
                probes.joinAll()
                verdict.complete(false)
            }
            val online = verdict.await()
            coroutineContext.cancelChildren()
            online
        }
    }

    private suspend fun probeSingle(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "KestrelVPN/1.0")
            .get()
            .build()
        return try {
            httpClient.newCall(request).awaitResponse().use { response ->
                isOnlineResponse(url, response)
            }
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        private const val ONLINE_CACHE_NS = 2_000_000_000L
        private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=262144"
        private const val SPEED_WINDOW_NS = 3_000_000_000L

        val PROBE_ENDPOINTS = listOf(
            "https://www.google.com/generate_204",
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://captive.apple.com/hotspot-detect.html"
        )

        /**
         * A captive portal answers 200/302 with its own page, so only the exact "clean" answer of
         * each endpoint counts as online: 204 for generate_204, and the "Success" page for Apple.
         */
        fun isOnlineResponse(url: String, response: Response): Boolean {
            return if (url.endsWith("generate_204")) {
                response.code == 204
            } else {
                response.code == 200 &&
                    runCatching { response.body?.string().orEmpty().contains("Success") }
                        .getOrDefault(false)
            }
        }
    }
}
