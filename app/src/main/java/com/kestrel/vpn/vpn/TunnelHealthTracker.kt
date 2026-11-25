package com.kestrel.vpn.vpn

/**
 * Turns a stream of health-probe outcomes into decisions. Extracted from the service so the
 * "how many failures before failover" rule is testable without Android.
 *
 * - A device that is simply offline is not the server's fault: it resets the streak and never fails over.
 * - Only "tunnel failed while the physical network is fine" counts toward failover.
 */
internal class TunnelHealthTracker(private val maxConsecutiveFailures: Int) {

    data class Verdict(val offlineWhileConnected: Boolean, val shouldFailover: Boolean)

    private var failures = 0

    fun record(outcome: TunnelHealthOutcome): Verdict = when (outcome) {
        TunnelHealthOutcome.HEALTHY -> {
            failures = 0
            Verdict(offlineWhileConnected = false, shouldFailover = false)
        }
        TunnelHealthOutcome.DEVICE_OFFLINE -> {
            failures = 0
            Verdict(offlineWhileConnected = true, shouldFailover = false)
        }
        TunnelHealthOutcome.TUNNEL_FAILED_INTERNET_OK -> {
            failures++
            Verdict(offlineWhileConnected = false, shouldFailover = failures >= maxConsecutiveFailures)
        }
    }
}
