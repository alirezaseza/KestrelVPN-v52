package com.kestrel.vpn.data

import com.kestrel.vpn.data.local.KestrelDatabase
import com.kestrel.vpn.data.local.UserPreferences
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.data.local.dao.HuntStateDao
import com.kestrel.vpn.data.local.dao.ProxyConfigDao
import com.kestrel.vpn.data.local.dao.ScanProgressDao
import com.kestrel.vpn.data.local.dao.TestResultDao
import com.kestrel.vpn.data.parser.ProxyLinkParser
import com.kestrel.vpn.data.remote.SubscriptionFetchOutcome
import com.kestrel.vpn.data.remote.SubscriptionFetcher
import com.kestrel.vpn.data.repository.ConfigRepositoryImpl
import com.kestrel.vpn.domain.model.SubscriptionRefreshResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryImplTest {

    private val configDao = mockk<ProxyConfigDao>(relaxed = true)
    private val prefs = mockk<UserPreferencesDataStore>(relaxed = true)
    private val fetcher = mockk<SubscriptionFetcher>()

    private fun repo() = ConfigRepositoryImpl(
        mockk<KestrelDatabase>(relaxed = true), configDao,
        mockk<TestResultDao>(relaxed = true), mockk<ScanProgressDao>(relaxed = true),
        mockk<HuntStateDao>(relaxed = true), prefs, fetcher, ProxyLinkParser(),
        Json { ignoreUnknownKeys = true }, Dispatchers.Unconfined, Dispatchers.Unconfined
    )

    @Test
    fun `recent successful fetch is throttled`() = runBlocking {
        coEvery { prefs.current() } returns UserPreferences(lastSuccessfulFetchMs = System.currentTimeMillis())
        coEvery { configDao.countActive() } returns 10
        assertTrue(repo().refreshSubscription(force = false) is SubscriptionRefreshResult.SkippedThrottled)
    }

    @Test
    fun `network failure with cache keeps cached list`() = runBlocking {
        coEvery { prefs.current() } returns UserPreferences()
        coEvery { configDao.countActive() } returns 5
        coEvery { fetcher.fetch(any(), any(), any()) } returns SubscriptionFetchOutcome.Failure("down")
        assertTrue(repo().refreshSubscription(force = true) is SubscriptionRefreshResult.CachedOffline)
    }

    @Test
    fun `network failure without cache is an error`() = runBlocking {
        coEvery { prefs.current() } returns UserPreferences()
        coEvery { configDao.countActive() } returns 0
        coEvery { fetcher.fetch(any(), any(), any()) } returns SubscriptionFetchOutcome.Failure("down")
        assertTrue(repo().refreshSubscription(force = true) is SubscriptionRefreshResult.Error)
    }

    @Test
    fun `garbage subscription never wipes cache`() = runBlocking {
        coEvery { prefs.current() } returns UserPreferences()
        coEvery { configDao.countActive() } returns 5
        coEvery { fetcher.fetch(any(), any(), any()) } returns
            SubscriptionFetchOutcome.Success("<html>blocked</html>", null, null, "u")
        assertTrue(repo().refreshSubscription(force = true) is SubscriptionRefreshResult.CachedOffline)
    }
}
