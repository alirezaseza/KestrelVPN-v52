package com.kestrel.vpn.data.repository
import androidx.room.withTransaction
import com.kestrel.vpn.data.local.KestrelDatabase
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.data.local.dao.HuntStateDao
import com.kestrel.vpn.data.local.dao.ProxyConfigDao
import com.kestrel.vpn.data.local.dao.ScanProgressDao
import com.kestrel.vpn.data.local.dao.TestResultDao
import com.kestrel.vpn.data.local.entity.HuntStateEntity
import com.kestrel.vpn.data.local.entity.ProxyConfigEntity
import com.kestrel.vpn.data.local.entity.ScanProgressEntity
import com.kestrel.vpn.data.local.entity.TestResultEntity
import com.kestrel.vpn.data.parser.ProxyLinkParser
import com.kestrel.vpn.data.remote.SubscriptionConfig
import com.kestrel.vpn.data.remote.SubscriptionFetchOutcome
import com.kestrel.vpn.data.remote.SubscriptionFetcher
import com.kestrel.vpn.di.DefaultDispatcher
import com.kestrel.vpn.di.IoDispatcher
import com.kestrel.vpn.domain.model.HuntState
import com.kestrel.vpn.domain.model.ProxyConfig
import com.kestrel.vpn.domain.model.ScanProgress
import com.kestrel.vpn.domain.model.ServerTestResult
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.domain.model.SubscriptionRefreshResult
import com.kestrel.vpn.domain.repository.ConfigRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class ConfigRepositoryImpl @Inject constructor(
    private val database: KestrelDatabase,
    private val proxyConfigDao: ProxyConfigDao,
    private val testResultDao: TestResultDao,
    private val scanProgressDao: ScanProgressDao,
    private val huntStateDao: HuntStateDao,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val subscriptionFetcher: SubscriptionFetcher,
    private val parser: ProxyLinkParser,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ConfigRepository {
    private val refreshMutex = Mutex()
    override suspend fun refreshSubscription(force: Boolean): SubscriptionRefreshResult =
    refreshMutex.withLock {
        val nowMs = System.currentTimeMillis()
        val prefs = preferencesDataStore.current()
        val activeCount = withContext(ioDispatcher) { proxyConfigDao.countActive() }
        if (!force && activeCount > 0 && prefs.lastSuccessfulFetchMs > 0L) {
            val nextAllowed = prefs.lastSuccessfulFetchMs + SubscriptionConfig.AUTO_REFRESH_MIN_INTERVAL_MS
            if (nowMs < nextAllowed) {
                return@withLock SubscriptionRefreshResult.SkippedThrottled(
                    activeConfigs = activeCount,
                    nextAllowedAtMs = nextAllowed
                )
            }
        }
        val outcome = subscriptionFetcher.fetch(
            primaryUrl = prefs.subscriptionUrl,
            etag = if (force) null else prefs.lastEtag,
            lastModified = if (force) null else prefs.lastModified
        )
        when (outcome) {
            is SubscriptionFetchOutcome.NotModified -> {
                preferencesDataStore.updateFetchMetadata(
                    etag = prefs.lastEtag,
                    lastModified = prefs.lastModified,
                    fetchedAtMs = nowMs
                )
                SubscriptionRefreshResult.NotModified(
                    activeConfigs = activeCount,
                    sourceUrl = outcome.sourceUrl
                )
            }
            is SubscriptionFetchOutcome.Success -> {
                val parsed = withContext(defaultDispatcher) {
                    parser.parseSubscriptionContent(outcome.body)
                }
                if (parsed.configs.isEmpty()) {
                    return@withLock if (activeCount > 0) {
                        SubscriptionRefreshResult.CachedOffline(
                            activeConfigs = activeCount,
                            reason = "Subscription returned no supported configs; kept cached list"
                        )
                    } else {
                        SubscriptionRefreshResult.Error("Subscription contained no supported configs")
                    }
                }
                // Keep the original import time of configs we already know (it is used for stable ordering).
                val knownImportTimes = withContext(ioDispatcher) {
                    proxyConfigDao.getImportedAtMap().associate { it.canonicalKey to it.importedAtMs }
                }
                val lastIndex = parsed.configs.size - 1
                val entities = parsed.configs.mapIndexed { index, config ->
                    // New configs get a time that follows the file order, so "newest" is well defined.
                    val rank = if (SubscriptionConfig.FILE_LISTS_NEWEST_FIRST) index else lastIndex - index
                    ProxyConfigEntity.fromDomain(
                        config = config,
                        syncGeneration = nowMs,
                        importedAtMs = knownImportTimes[config.canonicalKey] ?: (nowMs - rank)
                    )
                }
                withContext(ioDispatcher) {
                    database.withTransaction {
                        entities.chunked(UPSERT_CHUNK_SIZE).forEach { chunk ->
                            proxyConfigDao.upsertAll(chunk)
                        }
                        proxyConfigDao.deactivateOlderThan(nowMs)
                        // Configs that disappeared from the list a week ago are gone for good, with their results.
                        proxyConfigDao.deleteInactiveOlderThan(nowMs - INACTIVE_RETENTION_MS)
                        testResultDao.deleteOrphans()
                    }
                }
                preferencesDataStore.updateFetchMetadata(
                    etag = outcome.etag,
                    lastModified = outcome.lastModified,
                    fetchedAtMs = nowMs
                )
                SubscriptionRefreshResult.Updated(
                    totalParsedLines = parsed.totalLines,
                    uniqueValidConfigs = parsed.configs.size,
                    duplicateCount = parsed.duplicateCount,
                    invalidCount = parsed.invalidCount,
                    sourceUrl = outcome.sourceUrl
                )
            }
            is SubscriptionFetchOutcome.Failure -> {
                if (activeCount > 0) {
                    SubscriptionRefreshResult.CachedOffline(
                        activeConfigs = activeCount,
                        reason = outcome.reason
                    )
                } else {
                    SubscriptionRefreshResult.Error(outcome.reason)
                }
            }
        }
    }
    override suspend fun addManualConfigs(raw: String): com.kestrel.vpn.domain.model.ManualAddResult {
        val parsed = withContext(defaultDispatcher) { parser.parseSubscriptionContent(raw) }
        if (parsed.configs.isEmpty()) {
            return com.kestrel.vpn.domain.model.ManualAddResult(emptyList(), parsed.invalidCount.coerceAtLeast(1))
        }
        val now = System.currentTimeMillis()
        val entities = parsed.configs.map {
            ProxyConfigEntity.fromDomain(
                config = it,
                syncGeneration = com.kestrel.vpn.data.local.entity.MANUAL_SYNC_GENERATION,
                importedAtMs = now
            )
        }
        withContext(ioDispatcher) { proxyConfigDao.upsertAll(entities) }
        return com.kestrel.vpn.domain.model.ManualAddResult(
            addedKeys = parsed.configs.map { it.canonicalKey },
            invalidCount = parsed.invalidCount
        )
    }
    override fun observeActiveCount(): Flow<Int> = proxyConfigDao.observeActiveCount()
    override fun observeServersWithResults(networkKey: String): Flow<List<ServerWithResult>> =
    proxyConfigDao.observeActiveWithResults(networkKey).map { rows ->
        rows.map { row ->
            ServerWithResult(
                config = row.config.toDomain(),
                result = row.result?.toDomain()
            )
        }
    }.flowOn(ioDispatcher) // mapping thousands of rows must not run on the main thread
    override fun observeBestServers(networkKey: String, limit: Int): Flow<List<ServerWithResult>> =
    proxyConfigDao.observeBestServers(networkKey, limit).map { rows ->
        rows.map { row -> ServerWithResult(config = row.config.toDomain(), result = row.result?.toDomain()) }
    }.flowOn(ioDispatcher)
    override suspend fun countServersByTier(networkKey: String): Map<ServerTier, Int> =
    withContext(ioDispatcher) {
        proxyConfigDao.countByTier(networkKey).associate { row ->
            (runCatching { ServerTier.valueOf(row.tier) }.getOrDefault(ServerTier.UNTESTED)) to row.cnt
        }
    }
    override fun observeBestServer(networkKey: String): Flow<ServerWithResult?> =
    proxyConfigDao.observeBestServer(networkKey).map { row ->
        row?.let {
            ServerWithResult(
                config = it.config.toDomain(),
                result = it.result?.toDomain()
            )
        }
    }
    override suspend fun getHuntCandidates(
        networkKey: String,
        nowMs: Long,
        limit: Int
    ): List<ProxyConfig> = withContext(ioDispatcher) {
        proxyConfigDao.getHuntCandidates(networkKey, nowMs, limit).map { it.toDomain() }
    }
    override suspend fun getConfigByKey(canonicalKey: String): ProxyConfig? =
    withContext(ioDispatcher) {
        proxyConfigDao.getByKey(canonicalKey)?.toDomain()
    }
    override suspend fun getActiveConfigsNewestFirst(): List<ProxyConfig> =
    withContext(ioDispatcher) {
        proxyConfigDao.getActiveNewestFirst().map { it.toDomain() }
    }
    override suspend fun getBestServers(networkKey: String, limit: Int): List<ServerWithResult> =
    withContext(ioDispatcher) {
        proxyConfigDao.getBestServers(networkKey, limit).map { row ->
            ServerWithResult(
                config = row.config.toDomain(),
                result = row.result?.toDomain()
            )
        }
    }
    override suspend fun getCandidateBatchForTesting(
        networkKey: String,
        nowMs: Long,
        staleBeforeMs: Long,
        limit: Int
    ): List<ProxyConfig> = withContext(ioDispatcher) {
        proxyConfigDao.getCandidateBatchForTesting(
            networkKey = networkKey,
            nowMs = nowMs,
            staleBeforeMs = staleBeforeMs,
            limit = limit
        ).map { it.toDomain() }
    }
    override suspend fun getTestResult(
        canonicalKey: String,
        networkKey: String
    ): ServerTestResult? = withContext(ioDispatcher) {
        testResultDao.get(canonicalKey, networkKey)?.toDomain()
    }
    override suspend fun saveTestResult(result: ServerTestResult) {
        withContext(ioDispatcher) {
            testResultDao.upsert(TestResultEntity.fromDomain(result))
        }
    }
    override fun observeScanProgress(networkKey: String): Flow<ScanProgress?> =
    scanProgressDao.observe(networkKey).map { it?.toDomain() }
    override suspend fun getScanProgress(networkKey: String): ScanProgress? =
    withContext(ioDispatcher) {
        scanProgressDao.get(networkKey)?.toDomain()
    }
    override suspend fun saveScanProgress(progress: ScanProgress) {
        withContext(ioDispatcher) {
            scanProgressDao.upsert(ScanProgressEntity.fromDomain(progress))
        }
    }
    override fun observeHuntState(networkKey: String): Flow<HuntState?> =
    huntStateDao.observe(networkKey).map { it?.toDomain(json) }
    override suspend fun getHuntState(networkKey: String): HuntState? =
    withContext(ioDispatcher) {
        huntStateDao.get(networkKey)?.toDomain(json)
    }
    override suspend fun saveHuntState(state: HuntState) {
        withContext(ioDispatcher) {
            huntStateDao.upsert(HuntStateEntity.fromDomain(state, json))
        }
    }
    override suspend fun clearHuntState(networkKey: String) {
        withContext(ioDispatcher) {
            huntStateDao.deleteByNetwork(networkKey)
        }
    }
    override suspend fun clearTestCache(networkKey: String?) {
        withContext(ioDispatcher) {
            database.withTransaction {
                if (networkKey == null) {
                    testResultDao.deleteAll()
                    scanProgressDao.deleteAll()
                    huntStateDao.deleteAll()
                } else {
                    testResultDao.deleteByNetwork(networkKey)
                    scanProgressDao.deleteByNetwork(networkKey)
                    huntStateDao.deleteByNetwork(networkKey)
                }
            }
        }
    }
    companion object {
        private const val UPSERT_CHUNK_SIZE = 500
        private const val INACTIVE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
