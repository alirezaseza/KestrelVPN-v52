package com.kestrel.vpn.engine.probe

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a phone outage from being blamed on servers (normal scan and Hunt Mode alike).
 *
 * Every probe that FAILED must pass [confirmServerFault] before the failure is written to the database:
 * - phone online  -> true: the server really did not answer, record the failure;
 * - phone offline -> false: nothing is recorded. The call returns only after the internet is back
 *   (every other worker waits in [awaitOnline] meanwhile) so the caller can simply test the server again.
 *
 * This replaces the old "ping Google every 10 probes and re-test afterwards" scheme, which had already
 * written up to nine wrong DEAD results (cooldown, EMA and failure streak included) before it noticed.
 */
class PeriodicConnectivityGate(private val checker: ConnectivityChecker) {
    private val lock = Mutex()

    @Volatile
    var isOffline: Boolean = false
        private set

    /** Read this just BEFORE a probe starts and pass it to [confirmServerFault] when the probe fails. */
    fun epoch(): Long = checker.networkEpoch()

    /** Call before starting each probe: suspends while an outage is being waited out. */
    suspend fun awaitOnline() {
        while (isOffline) delay(POLL_MS)
    }

    private enum class Verdict { ONLINE, OFFLINE_OWNER, OFFLINE_OTHER }

    /**
     * @param probeStartEpoch [epoch] read before the probe started.
     * @param onOffline called once, by the worker that detected the outage (e.g. to show "disconnected").
     * @return true = the failure is the server's fault; false = the phone was offline or switched networks, do not penalize.
     */
    suspend fun confirmServerFault(
        probeStartEpoch: Long = checker.networkEpoch(),
        onOffline: suspend () -> Unit = {}
    ): Boolean {
        if (isOffline) {
            awaitOnline()
            return false
        }
        // One worker checks and owns the outage; the rest queue behind the lock and reuse its verdict.
        val verdict = lock.withLock {
            when {
                isOffline -> Verdict.OFFLINE_OTHER
                checker.isInternetReachable() -> Verdict.ONLINE
                else -> {
                    isOffline = true
                    Verdict.OFFLINE_OWNER
                }
            }
        }
        when (verdict) {
            // Online now, but the network changed while the probe was running (Wi-Fi <-> mobile, a short drop):
            // the failure may come from that switch, so it is not held against the server.
            Verdict.ONLINE -> return checker.networkEpoch() == probeStartEpoch
            Verdict.OFFLINE_OTHER -> awaitOnline()
            Verdict.OFFLINE_OWNER -> try {
                onOffline()
                while (!checker.isInternetReachable()) delay(POLL_MS)
            } finally {
                isOffline = false
            }
        }
        return false
    }

    companion object {
        private const val POLL_MS = 2_000L
    }
}
