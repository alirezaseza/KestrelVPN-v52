package com.kestrel.vpn.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kestrel.vpn.data.local.entity.ProxyConfigEntity
import com.kestrel.vpn.data.local.entity.TestResultEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyConfigDaoTest {
    private lateinit var db: KestrelDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), KestrelDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun cfg(key: String, active: Boolean = true, gen: Long = 1L) = ProxyConfigEntity(
        canonicalKey = key, protocol = "vless", host = "$key.example.com", port = 443, credential = "id",
        method = "", alterId = 0, flow = "", security = "tls", sni = "", alpn = "", fingerprint = "",
        allowInsecure = false, realityPublicKey = "", realityShortId = "", realitySpiderX = "",
        transport = "tcp", path = "", hostHeader = "", serviceName = "", grpcMultiMode = false,
        remark = "", rawUri = "", isActive = active, syncGeneration = gen, importedAtMs = 1L
    )

    private fun res(key: String, tier: String, score: Double) = TestResultEntity(
        canonicalKey = key, networkKey = "wifi", tier = tier, pingMs = 100, jitterMs = 5,
        speedKbps = null, score = score, emaSuccessRate = 0.7, consecutiveFailures = 0,
        lastTestedAtMs = 1L, cooldownUntilMs = 1L, testedWithSpeed = false
    )

    @Test
    fun `best servers are ordered by score and skip dead and inactive`() = runBlocking {
        db.proxyConfigDao().upsertAll(listOf(cfg("a"), cfg("b"), cfg("c"), cfg("d", active = false)))
        listOf(res("a", "GOOD", 60.0), res("b", "EXCELLENT", 90.0), res("c", "DEAD", 0.0), res("d", "EXCELLENT", 99.0))
            .forEach { db.testResultDao().upsert(it) }
        val best = db.proxyConfigDao().getBestServers("wifi", 10).map { it.config.canonicalKey }
        assertEquals(listOf("b", "a"), best)
    }

    @Test
    fun `deactivateOlderThan hides configs missing from the new sync`() = runBlocking {
        db.proxyConfigDao().upsertAll(listOf(cfg("old", gen = 1L), cfg("new", gen = 2L)))
        db.proxyConfigDao().deactivateOlderThan(2L)
        assertEquals(1, db.proxyConfigDao().countActive())
    }

    @Test
    fun `deleting a config cascades to its results and orphans are cleaned`() = runBlocking {
        db.proxyConfigDao().upsertAll(listOf(cfg("x", active = false, gen = 1L)))
        db.testResultDao().upsert(res("x", "GOOD", 50.0))
        db.proxyConfigDao().deleteInactiveOlderThan(10L)
        assertNull(db.testResultDao().get("x", "wifi"))
    }
}
