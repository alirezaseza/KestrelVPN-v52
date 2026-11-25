package com.kestrel.vpn.domain.model
sealed interface SubscriptionRefreshResult {
    data class Updated(
        val totalParsedLines: Int,
        val uniqueValidConfigs: Int,
        val duplicateCount: Int,
        val invalidCount: Int,
        val sourceUrl: String
    ) : SubscriptionRefreshResult
    data class NotModified(
        val activeConfigs: Int,
        val sourceUrl: String
    ) : SubscriptionRefreshResult
    data class SkippedThrottled(
        val activeConfigs: Int,
        val nextAllowedAtMs: Long
    ) : SubscriptionRefreshResult
    data class CachedOffline(
        val activeConfigs: Int,
        val reason: String
    ) : SubscriptionRefreshResult
    data class Error(
        val reason: String
    ) : SubscriptionRefreshResult
}

/** Result of adding configs by hand: [addedKeys] are the canonical keys that were stored. */
data class ManualAddResult(
    val addedKeys: List<String>,
    val invalidCount: Int
) {
    val added: Int get() = addedKeys.size
}
