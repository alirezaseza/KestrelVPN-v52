package com.kestrel.vpn.domain.repository
import com.kestrel.vpn.domain.model.HuntState
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.ScanProgress
import com.kestrel.vpn.domain.model.ServerTestResult
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.model.SubscriptionRefreshResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
interface ConfigRepository {
    suspend fun refreshSubscription(force: Boolean): SubscriptionRefreshResult
    /** Adds one or more share links (vless/vmess/trojan/ss, one per line) typed or pasted by the user. */
    suspend fun addManualConfigs(raw: String): com.kestrel.vpn.domain.model.ManualAddResult =
        com.kestrel.vpn.domain.model.ManualAddResult(emptyList(), 0)
    fun observeActiveCount(): Flow<Int>
    fun observeServersWithResults(networkKey: String): Flow<List<ServerWithResult>>
    suspend fun getConfigByKey(canonicalKey: String): ProxyConfig?
    /** All active configs, newest first (the order they were added to the list). */
    suspend fun getActiveConfigsNewestFirst(): List<ProxyConfig> = emptyList()
    suspend fun getBestServers(networkKey: String, limit: Int): List<ServerWithResult>

    /** Live top-N alive servers (best score first). */
    fun observeBestServers(networkKey: String, limit: Int): kotlinx.coroutines.flow.Flow<List<ServerWithResult>> =
        observeServersWithResults(networkKey).map { list ->
            list.filter { it.result?.pingMs != null && it.tier != ServerTier.DEAD }.take(limit)
        }

    /** Number of active servers per tier for this network, without loading the servers themselves. */
    suspend fun countServersByTier(networkKey: String): Map<ServerTier, Int> =
        getBestServers(networkKey, Int.MAX_VALUE).groupingBy { it.tier }.eachCount()
    suspend fun getCandidateBatchForTesting(
        networkKey: String,
        nowMs: Long,
        staleBeforeMs: Long,
        limit: Int
    ): List<ProxyConfig>
    /**
 * Contenders for Hunt Mode: previously alive servers (best first), untested ones, and dead
 * ones whose cooldown has expired. Unlike [getCandidateBatchForTesting] it does not hide
 * servers that were tested a few minutes ago.
 */
    suspend fun getHuntCandidates(networkKey: String, nowMs: Long, limit: Int): List<ProxyConfig> =
    getCandidateBatchForTesting(networkKey, nowMs, nowMs, limit)
    /** The single best alive server for [networkKey], or null. */
    fun observeBestServer(networkKey: String): Flow<ServerWithResult?> =
    observeServersWithResults(networkKey).map { list ->
        list.firstOrNull { it.result != null && it.tier != com.kestrel.vpn.domain.model.ServerTier.DEAD }
    }
    suspend fun getTestResult(canonicalKey: String, networkKey: String): ServerTestResult?
    suspend fun saveTestResult(result: ServerTestResult)
    fun observeScanProgress(networkKey: String): Flow<ScanProgress?>
    suspend fun getScanProgress(networkKey: String): ScanProgress?
    suspend fun saveScanProgress(progress: ScanProgress)
    fun observeHuntState(networkKey: String): Flow<HuntState?>
    suspend fun getHuntState(networkKey: String): HuntState?
    suspend fun saveHuntState(state: HuntState)
    suspend fun clearHuntState(networkKey: String)
    suspend fun clearTestCache(networkKey: String? = null)
}
