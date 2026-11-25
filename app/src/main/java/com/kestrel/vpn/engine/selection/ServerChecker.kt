package com.kestrel.vpn.engine.selection

import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.engine.probe.ConnectivityChecker
import com.kestrel.vpn.engine.probe.ProxyProber
import com.kestrel.vpn.engine.scoring.ServerScorer
import com.kestrel.vpn.domain.repository.ConfigRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ServerCheckOutcome {
    data class Online(val pingMs: Int, val speedKbps: Int) : ServerCheckOutcome
    /** No ping through the server. */
    data object NoPing : ServerCheckOutcome
    /** Ping works but the download is under 0.05 Mbit/s. */
    data class TooSlow(val speedKbps: Int?) : ServerCheckOutcome
    /** The phone itself has no internet (Google did not answer): not the server's fault. */
    data object DeviceOffline : ServerCheckOutcome
}

/**
 * Tests ONE server the user picked. It is "connected" only when it answers a ping AND downloads
 * faster than 0.05 Mbit/s ([ServerSelector.MIN_DOWNLOAD_KBPS]). A failure caused by the phone being
 * offline is reported as [ServerCheckOutcome.DeviceOffline] and never punishes the server.
 */
@Singleton
class ServerChecker @Inject constructor(
    private val repository: ConfigRepository,
    private val prober: ProxyProber,
    private val scorer: ServerScorer,
    private val connectivityChecker: ConnectivityChecker,
    private val tlsPolicy: com.kestrel.vpn.engine.core.TlsPolicy
) {
    /**
     * @param checkSpeed false = ping only: the server is accepted as soon as it answers a ping.
     * @param enforceMinSpeed false = a measured speed is saved/shown but never rejects the server.
     */
    suspend fun check(
        config: ProxyConfig,
        networkKey: String,
        speedPayloadKb: Int,
        checkSpeed: Boolean = true,
        enforceMinSpeed: Boolean = true,
        minSpeedKbps: Int = ServerSelector.MIN_DOWNLOAD_KBPS
    ): ServerCheckOutcome {
        val key = config.canonicalKey
        com.kestrel.vpn.util.DiagnosticsLog.add("check", "testing ${config.protocol} ${config.host}:${config.port} sec=${config.security} tr=${config.transport}")
        com.kestrel.vpn.engine.core.SingBoxConfigBuilder.describeReality(config, tlsPolicy.fingerprintOverride)?.let {
            com.kestrel.vpn.util.DiagnosticsLog.add("check", it)
        }
        if (!connectivityChecker.isInternetReachable()) return ServerCheckOutcome.DeviceOffline

        var ping = prober.runPingStagePatient(config, samples = 2)
        if (!ping.alive || ping.pingMs == null) {
            delay(300)
            ping = prober.runPingStagePatient(config, samples = 2)
        }
        val prev = repository.getTestResult(key, networkKey)
        val pingMs = ping.pingMs
        if (!ping.alive || pingMs == null) {
            com.kestrel.vpn.util.DiagnosticsLog.add("check", "NO PING: ${ping.failureReason}")
            if (!connectivityChecker.isInternetReachable()) return ServerCheckOutcome.DeviceOffline
            repository.saveTestResult(scorer.scoreFailure(key, networkKey, prev, System.currentTimeMillis()))
            return ServerCheckOutcome.NoPing
        }
        val afterPing = scorer.scoreSuccess(
            canonicalKey = key, networkKey = networkKey, pingMs = pingMs,
            jitterMs = ping.jitterMs ?: 0, speedKbps = null, previous = prev,
            nowMs = System.currentTimeMillis()
        )
        repository.saveTestResult(afterPing)

        if (!checkSpeed) return ServerCheckOutcome.Online(pingMs, 0)

        val speed = prober.runSpeedStagePatient(config, speedPayloadKb)
        val kbps = speed.speedKbps
        if (kbps != null) {
            repository.saveTestResult(
                scorer.scoreSuccess(
                    canonicalKey = key, networkKey = networkKey, pingMs = pingMs,
                    jitterMs = ping.jitterMs ?: 0, speedKbps = kbps, previous = afterPing,
                    nowMs = System.currentTimeMillis(), updateEma = false
                )
            )
        }
        if (!enforceMinSpeed) return ServerCheckOutcome.Online(pingMs, kbps ?: 0)
        if (!speed.success || kbps == null || kbps < minSpeedKbps) {
            com.kestrel.vpn.util.DiagnosticsLog.add("check", "TOO SLOW: kbps=$kbps success=${speed.success} reason=${speed.failureReason}")
            if (!connectivityChecker.isInternetReachable()) return ServerCheckOutcome.DeviceOffline
            // A server cannot be faster than the phone's own connection: if that is below the minimum too,
            // "too slow" says nothing about the server.
            val phoneKbps = connectivityChecker.deviceDownloadKbps()
            com.kestrel.vpn.util.DiagnosticsLog.add("check", "phone internet: down=${phoneKbps ?: "n/a"} kbps")
            if (phoneKbps != null && phoneKbps < minSpeedKbps) return ServerCheckOutcome.Online(pingMs, kbps ?: 0)
            return ServerCheckOutcome.TooSlow(kbps)
        }
        return ServerCheckOutcome.Online(pingMs, kbps)
    }
}
