package com.kestrel.vpn.engine.probe

import com.kestrel.vpn.di.IoDispatcher
import com.kestrel.vpn.domain.core.CoreUnavailableException
import com.kestrel.vpn.domain.core.ProxyCore
import com.kestrel.vpn.domain.core.ProxyInstance
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.SecurityType
import com.kestrel.vpn.engine.core.TlsPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

data class PingProbeOutcome(
    val alive: Boolean,
    val pingMs: Int?,
    val jitterMs: Int?,
    /**
     * True only when the proxy was reachable but every HTTP sample timed out.
     * A dead/unreachable server is NOT a timeout, so it must not be mistaken for network congestion.
     */
    val timedOut: Boolean,
    val failureReason: String? = null
)

data class SpeedProbeOutcome(
    val success: Boolean,
    val speedKbps: Int?,
    val timedOut: Boolean,
    val failureReason: String? = null
)

interface ProxyProber {
    suspend fun runPingStage(config: ProxyConfig, samples: Int = 2): PingProbeOutcome
    suspend fun runSpeedStage(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome

    /** Same as [runPingStage] with generous timeouts, for ONE server the user picked (not for bulk scans). */
    suspend fun runPingStagePatient(config: ProxyConfig, samples: Int = 2): PingProbeOutcome =
        runPingStage(config, samples)

    /** Same as [runSpeedStage] with generous timeouts, for ONE server the user picked. */
    suspend fun runSpeedStagePatient(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome =
        runSpeedStage(config, payloadKb)
}

@Singleton
class ProxyProbePipeline @Inject constructor(
    private val proxyCore: ProxyCore,
    @Named("probe") private val baseHttpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val tlsPolicy: TlsPolicy
) : ProxyProber {

    // The native core is not safe to create/destroy from several threads at once: do it one at a time.
    private val coreLock = kotlinx.coroutines.sync.Mutex()

    private class SampleResult(val rttMs: Int?, val timedOut: Boolean)

    override suspend fun runPingStage(config: ProxyConfig, samples: Int): PingProbeOutcome =
        pingStage(config, samples, patient = false)

    override suspend fun runPingStagePatient(config: ProxyConfig, samples: Int): PingProbeOutcome =
        pingStage(config, samples, patient = true)

    override suspend fun runSpeedStagePatient(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome =
        speedStage(config, payloadKb, patient = true)

    private suspend fun pingStage(config: ProxyConfig, samples: Int, patient: Boolean): PingProbeOutcome =
        withContext(ioDispatcher) {
            coroutineContext.ensureActive()

            if (!runStage0Prefilter(config, patient)) {
                if (patient) com.kestrel.vpn.util.DiagnosticsLog.add("probe", "ping: TCP connect to ${config.host}:${config.port} failed (stage 0)")
                return@withContext PingProbeOutcome(
                    alive = false,
                    pingMs = null,
                    jitterMs = null,
                    timedOut = false,
                    failureReason = "Stage 0 TCP/TLS prefilter failed"
                )
            }
            coroutineContext.ensureActive()

            val socksUser = randomCredential()
            val socksPass = randomCredential()
            val firstPort = allocateRandomLocalPort()
                ?: return@withContext PingProbeOutcome(
                    alive = false,
                    pingMs = null,
                    jitterMs = null,
                    timedOut = false,
                    failureReason = "Unable to allocate local SOCKS port"
                )

            val started = try {
                coreLock.withLock { startWithPortRetry(config, firstPort, socksUser, socksPass, patient) }
            } catch (e: CoreUnavailableException) {
                throw e
            } catch (t: Throwable) {
                com.kestrel.vpn.util.DiagnosticsLog.add("probe", "ping: core start failed: ${t.message}")
                return@withContext PingProbeOutcome(
                    alive = false,
                    pingMs = null,
                    jitterMs = null,
                    timedOut = false,
                    failureReason = "Core start failed: ${t.message}"
                )
            }

            val instance = started.instance
            val port = started.port
            val proxiedClient = buildProxiedClient(port, socksUser, socksPass, if (patient) PATIENT_STAGE1_TIMEOUT_MS else STAGE1_TIMEOUT_MS)
            try {
                val budgetEndNs = System.nanoTime() + (if (patient) PATIENT_PING_BUDGET_MS else PING_BUDGET_MS) * 1_000_000L
                val latencies = mutableListOf<Int>()
                var timeouts = 0
                val count = samples.coerceAtLeast(1)
                for (i in 0 until count) {
                    coroutineContext.ensureActive()
                    if (i > 0 && System.nanoTime() > budgetEndNs) break
                    val sample = measureSingle204(proxiedClient, budgetEndNs)
                    if (sample.rttMs != null) {
                        latencies += sample.rttMs
                    } else if (sample.timedOut) {
                        timeouts++
                    }
                }

                if (latencies.isEmpty()) {
                    PingProbeOutcome(
                        alive = false,
                        pingMs = null,
                        jitterMs = null,
                        timedOut = timeouts > 0,
                        failureReason = "Proxy ping failed"
                    )
                } else {
                    val sorted = latencies.sorted()
                    // Lower median: with 2 samples this is the faster one, not the slower one.
                    val median = sorted[(sorted.size - 1) / 2]
                    val jitter = if (sorted.size >= 2) sorted.last() - sorted.first() else 0
                    PingProbeOutcome(
                        alive = true,
                        pingMs = median,
                        jitterMs = jitter,
                        timedOut = false
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { proxiedClient.connectionPool.evictAll() }
                    coreLock.withLock { runCatching { instance.close() } }
                }
            }
        }

    override suspend fun runSpeedStage(config: ProxyConfig, payloadKb: Int): SpeedProbeOutcome =
        speedStage(config, payloadKb, patient = false)

    private suspend fun speedStage(config: ProxyConfig, payloadKb: Int, patient: Boolean): SpeedProbeOutcome =
        withContext(ioDispatcher) {
            coroutineContext.ensureActive()

            val clampedKb = payloadKb.coerceIn(200, 300)
            val targetBytes = clampedKb * 1024L

            val socksUser = randomCredential()
            val socksPass = randomCredential()
            val firstPort = allocateRandomLocalPort()
                ?: return@withContext SpeedProbeOutcome(
                    success = false,
                    speedKbps = null,
                    timedOut = false,
                    failureReason = "Unable to allocate local SOCKS port"
                )

            val started = try {
                coreLock.withLock { startWithPortRetry(config, firstPort, socksUser, socksPass, patient) }
            } catch (e: CoreUnavailableException) {
                throw e
            } catch (t: Throwable) {
                com.kestrel.vpn.util.DiagnosticsLog.add("probe", "speed: core start failed: ${t.message}")
                return@withContext SpeedProbeOutcome(
                    success = false,
                    speedKbps = null,
                    timedOut = false,
                    failureReason = "Core start failed: ${t.message}"
                )
            }

            val instance = started.instance
            val port = started.port
            com.kestrel.vpn.util.DiagnosticsLog.add("probe", "speed core started")
            // The whole download used to be cut after 5 s, so a slow-but-working server never got a speed.
            val proxiedClient = buildProxiedClient(port, socksUser, socksPass, if (patient) PATIENT_STAGE2_TIMEOUT_MS else STAGE2_TIMEOUT_MS)
                .newBuilder().callTimeout(SPEED_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
            try {
                val url = "https://speed.cloudflare.com/__down?bytes=$targetBytes"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "KestrelVPN/1.0")
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "close")
                    .get()
                    .build()

                var totalBytes = 0L
                var measuredBytes = 0L
                var measuredNs = 0L
                proxiedClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SpeedProbeOutcome(
                            success = false,
                            speedKbps = null,
                            timedOut = false,
                            failureReason = "HTTP ${response.code}"
                        )
                    }
                    val stream = response.body?.byteStream()
                        ?: return@withContext SpeedProbeOutcome(
                            success = false,
                            speedKbps = null,
                            timedOut = false,
                            failureReason = "Empty speed payload"
                        )
                    val buf = ByteArray(16 * 1024)
                    // The clock starts at the first received chunk, so TCP/TLS handshakes and
                    // time-to-first-byte are not counted as "slow download".
                    val first = stream.read(buf)
                    if (first > 0) {
                        totalBytes += first
                        val startNs = System.nanoTime()
                        try {
                            while (totalBytes < targetBytes) {
                                coroutineContext.ensureActive()
                                if (System.nanoTime() - startNs >= SPEED_WINDOW_MS * 1_000_000L) break
                                val read = stream.read(buf)
                                if (read == -1) break
                                totalBytes += read
                                measuredBytes += read
                            }
                        } catch (e: IOException) {
                            // A stall after data started flowing still gives a usable (low) speed.
                            if (measuredBytes <= 0L) throw e
                        }
                        measuredNs = System.nanoTime() - startNs
                    }
                }

                if (totalBytes < 32 * 1024L || measuredBytes <= 0L) {
                    SpeedProbeOutcome(
                        success = false,
                        speedKbps = null,
                        timedOut = true,
                        failureReason = "Incomplete download ($totalBytes bytes)"
                    )
                } else {
                    val elapsedMs = (measuredNs / 1_000_000L).coerceAtLeast(10L)
                    val speedKbps = ((measuredBytes * 8L) / elapsedMs).toInt().coerceAtLeast(1)
                    com.kestrel.vpn.util.DiagnosticsLog.add("probe", "speed done kbps=$speedKbps")
                    SpeedProbeOutcome(success = true, speedKbps = speedKbps, timedOut = false)
                }
            } catch (e: IOException) {
                SpeedProbeOutcome(
                    success = false,
                    speedKbps = null,
                    timedOut = e is InterruptedIOException,
                    failureReason = e.message ?: "Speed probe IO error"
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { proxiedClient.connectionPool.evictAll() }
                    coreLock.withLock { runCatching { instance.close() } }
                }
            }
        }

    private fun runStage0Prefilter(config: ProxyConfig, patient: Boolean = false): Boolean {
        val stage0Ms = if (patient) PATIENT_STAGE0_TIMEOUT_MS else STAGE0_TIMEOUT_MS
        // Domain names would be resolved by the system resolver (visible to the ISP). The ping
        // stage resolves them through the core's encrypted DNS instead, so only IP literals are
        // pre-filtered here.
        if (!isIpLiteral(config.host)) return true
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(config.host, config.port),
                    stage0Ms
                )
                socket.soTimeout = stage0Ms
                val skipCertChecks = config.allowInsecure && tlsPolicy.allowInsecure
                if (config.security == SecurityType.TLS && !skipCertChecks) {
                    val sniHost = config.sni.ifBlank { config.host }
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    (sslFactory.createSocket(socket, sniHost, config.port, false) as SSLSocket).use { ssl ->
                        ssl.soTimeout = stage0Ms
                        ssl.startHandshake()
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Every sample uses a brand-new connection ("Connection: close", no pooling) so that all samples
     * include the same handshake cost. That keeps ping comparable between servers and makes
     * max-min a real jitter figure instead of "first request vs. reused keep-alive request".
     */
    private fun measureSingle204(client: OkHttpClient, budgetEndNs: Long): SampleResult {
        var sawTimeout = false
        for (url in STAGE1_ENDPOINTS) {
            if (System.nanoTime() > budgetEndNs) break
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KestrelVPN/1.0")
                .header("Cache-Control", "no-cache")
                .header("Connection", "close")
                .get()
                .build()
            val startNs = System.nanoTime()
            try {
                val ok = client.newCall(request).execute().use { response -> response.code == 204 }
                if (ok) {
                    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
                    return SampleResult(elapsedMs, false)
                }
            } catch (e: InterruptedIOException) {
                sawTimeout = true
                com.kestrel.vpn.util.DiagnosticsLog.add("probe", "ping $url timeout: ${e.message}")
            } catch (e: IOException) {
                // connection refused/reset etc. -> try next endpoint
                com.kestrel.vpn.util.DiagnosticsLog.add("probe", "ping $url error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return SampleResult(null, sawTimeout)
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || IPV4_LITERAL.matches(host)

    private fun randomCredential(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun buildProxiedClient(socksPort: Int, user: String, pass: String, timeoutMs: Long): OkHttpClient =
        baseHttpClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(Socks5AuthSocketFactory(socksPort, user, pass))
            .dns(Socks5AuthSocketFactory.placeholderDns)
            // Private, non-keeping pool: the shared pool would hold dead SOCKS connections for minutes.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private class StartedProbe(val instance: ProxyInstance, val port: Int)

    /**
     * The port is probed and released before sing-box binds it, so another process can grab it in
     * between. A failed start is therefore retried on a fresh port before the server is blamed.
     */
    private fun startWithPortRetry(config: ProxyConfig, firstPort: Int, user: String, pass: String, verbose: Boolean = false): StartedProbe {
        var port = firstPort
        var lastError: Throwable? = null
        repeat(START_ATTEMPTS) { attempt ->
            try {
                return StartedProbe(proxyCore.startLocalSocks(config, port, user, pass, verbose), port)
            } catch (e: CoreUnavailableException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (attempt < START_ATTEMPTS - 1) port = allocateRandomLocalPort() ?: return@repeat
            }
        }
        throw lastError ?: IllegalStateException("probe core did not start")
    }

    private fun allocateRandomLocalPort(): Int? = runCatching {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.reuseAddress = true
            socket.localPort
        }
    }.getOrNull()

    companion object {
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        // 1.5 s was too aggressive on lossy mobile networks and turned transient losses into DEAD.
        const val START_ATTEMPTS = 3
        const val STAGE0_TIMEOUT_MS = 3_000
        const val STAGE1_TIMEOUT_MS = 4_000L
        const val STAGE2_TIMEOUT_MS = 5_000L
        // Speed test: hard limit for the whole call, and how long the download is measured.
        const val SPEED_CALL_TIMEOUT_MS = 15_000L
        const val SPEED_WINDOW_MS = 4_000L

        // Upper bound for all HTTP samples of one ping stage (keeps scan time-boxes honest).
        const val PING_BUDGET_MS = 10_000L

        // Single picked server: a slow-but-working Reality/far server must not be rejected by tight timeouts.
        const val PATIENT_STAGE0_TIMEOUT_MS = 6_000
        const val PATIENT_STAGE1_TIMEOUT_MS = 10_000L
        const val PATIENT_STAGE2_TIMEOUT_MS = 10_000L
        const val PATIENT_PING_BUDGET_MS = 25_000L

        private val STAGE1_ENDPOINTS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }
}
