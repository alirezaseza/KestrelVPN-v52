package com.kestrel.vpn.data.remote
object SubscriptionConfig {
    const val DEFAULT_SUBSCRIPTION_URL =
    "https://raw.githubusercontent.com/alirezaseza/Public/main/sub/all.txt"
    // true: the TOP of sub/all.txt is the newest config. Flip to false if the newest are at the bottom.
    const val FILE_LISTS_NEWEST_FIRST = true
    const val MAX_SUBSCRIPTION_BYTES = 16L * 1024L * 1024L
    const val AUTO_REFRESH_MIN_INTERVAL_MS = 30L * 60L * 1000L
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 15L
    const val CALL_TIMEOUT_SECONDS = 20L
    private val githubRawRegex = Regex(
        """^https://raw.githubusercontent.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$"""
    )
    fun buildCandidateUrls(primaryUrl: String): List<String> {
        val trimmed = primaryUrl.trim()
        if (trimmed.isEmpty()) return emptyList()
        val urls = mutableListOf(trimmed)
        val match = githubRawRegex.matchEntire(trimmed)
        if (match != null) {
            val (owner, repo, branch, path) = match.destructured
            urls += "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
            urls += "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path"
        }
        return urls.distinct()
    }
}
