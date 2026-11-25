package com.kestrel.vpn.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionConfigTest {
    @Test
    fun `github raw url gets jsdelivr mirrors`() {
        val urls = SubscriptionConfig.buildCandidateUrls(
            "https://raw.githubusercontent.com/me/repo/main/sub/all.txt"
        )
        assertEquals("https://raw.githubusercontent.com/me/repo/main/sub/all.txt", urls[0])
        assertTrue(urls.contains("https://cdn.jsdelivr.net/gh/me/repo@main/sub/all.txt"))
        assertTrue(urls.contains("https://fastly.jsdelivr.net/gh/me/repo@main/sub/all.txt"))
    }

    @Test
    fun `refs heads form is normalised for mirrors`() {
        val urls = SubscriptionConfig.buildCandidateUrls(
            "https://raw.githubusercontent.com/me/repo/refs/heads/main/a.txt"
        )
        assertTrue(urls.contains("https://cdn.jsdelivr.net/gh/me/repo@main/a.txt"))
    }

    @Test
    fun `non github url has no mirrors and blank is empty`() {
        assertEquals(1, SubscriptionConfig.buildCandidateUrls("https://example.com/s.txt").size)
        assertTrue(SubscriptionConfig.buildCandidateUrls("  ").isEmpty())
    }


    @Test
    fun `candidate urls never repeat and the primary stays first`() {
        val primary = "https://raw.githubusercontent.com/me/repo/main/a.txt"
        val urls = SubscriptionConfig.buildCandidateUrls("  $primary  ")
        assertEquals(primary, urls.first())
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `size cap is generous enough for big public lists but bounded`() {
        assertTrue(SubscriptionConfig.MAX_SUBSCRIPTION_BYTES in (1L shl 20)..(64L shl 20))
    }
}
