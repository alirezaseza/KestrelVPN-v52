package com.kestrel.vpn.data.local
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kestrel.vpn.data.remote.SubscriptionConfig
import com.kestrel.vpn.domain.model.TestMode
import com.kestrel.vpn.domain.model.TestSpeedMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
private val Context.kestrelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kestrel_preferences"
)
enum class SplitMode {
    OFF, EXCLUDE, INCLUDE
}
/** What to test on a server the user taps, before connecting to it. AUTO follows the Wi-Fi / mobile / data-saver settings. */
enum class PreConnectCheck {
    AUTO, PING_ONLY, PING_AND_SPEED
}
/** uTLS fingerprints sing-box understands. "" means: keep what the link says. */
val FINGERPRINT_CHOICES = setOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized")
enum class AppThemeMode {
    SYSTEM, LIGHT, DARK
}
data class UserPreferences(
    val subscriptionUrl: String = SubscriptionConfig.DEFAULT_SUBSCRIPTION_URL,
    val lastEtag: String? = null,
    val lastModified: String? = null,
    val lastSuccessfulFetchMs: Long = 0L,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val languageTag: String = "",
    val mobileTestMode: TestMode = TestMode.PING_AND_SPEED,
    val wifiTestMode: TestMode = TestMode.PING_AND_SPEED,
    val dataSaver: Boolean = false,
    val testSpeedMode: TestSpeedMode = TestSpeedMode.MEDIUM,
    val speedPayloadKb: Int = 300,
    val onboardingCompleted: Boolean = false,
    val publicNoticeAcknowledged: Boolean = false,
    val allowInsecureTls: Boolean = false,
    val bypassIran: Boolean = true,
    val reduceEffects: Boolean = false,
    val splitMode: SplitMode = SplitMode.OFF,
    val splitPackages: Set<String> = emptySet(),
    val favoriteKeys: Set<String> = emptySet(),
    val preConnectCheck: PreConnectCheck = PreConnectCheck.AUTO,
    /** When true a server must download faster than 0.05 Mbit/s to be used. When false there is no minimum speed. */
    val minSpeedEnabled: Boolean = true,
    /** Minimum download speed in kilobit/s used when [minSpeedEnabled] (default 50 = 0.05 Mbit/s). */
    val minSpeedKbps: Int = 50,
    /** uTLS fingerprint forced on every TLS/REALITY server. Empty = use the one in the link. */
    val fingerprintOverride: String = ""
)
@Singleton
class UserPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SUBSCRIPTION_URL = stringPreferencesKey("subscription_url")
        val LAST_ETAG = stringPreferencesKey("last_etag")
        val LAST_MODIFIED = stringPreferencesKey("last_modified")
        val LAST_FETCH_MS = longPreferencesKey("last_successful_fetch_ms")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val MOBILE_TEST_MODE = stringPreferencesKey("mobile_test_mode")
        val WIFI_TEST_MODE = stringPreferencesKey("wifi_test_mode")
        val DATA_SAVER = booleanPreferencesKey("data_saver")
        val TEST_SPEED_MODE = stringPreferencesKey("test_speed_mode")
        val SPEED_PAYLOAD_KB = intPreferencesKey("speed_payload_kb")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PUBLIC_NOTICE_ACK = booleanPreferencesKey("public_notice_acknowledged")
        val ALLOW_INSECURE_TLS = booleanPreferencesKey("allow_insecure_tls")
        val BYPASS_IRAN = booleanPreferencesKey("bypass_iran")
        val REDUCE_EFFECTS = booleanPreferencesKey("reduce_effects")
        val SPLIT_MODE = stringPreferencesKey("split_mode")
        val SPLIT_PACKAGES = stringSetPreferencesKey("split_packages")
        val FAVORITE_KEYS = stringSetPreferencesKey("favorite_keys")
        val PRE_CONNECT_CHECK = stringPreferencesKey("pre_connect_check")
        val MIN_SPEED_ENABLED = booleanPreferencesKey("min_speed_enabled")
        val MIN_SPEED_KBPS = intPreferencesKey("min_speed_kbps")
        val FINGERPRINT_OVERRIDE = stringPreferencesKey("fingerprint_override")
    }
    val preferencesFlow: Flow<UserPreferences> = context.kestrelDataStore.data.map { prefs ->
        UserPreferences(
            subscriptionUrl = prefs[Keys.SUBSCRIPTION_URL]
                ?.takeIf { it.isNotBlank() }
                ?: SubscriptionConfig.DEFAULT_SUBSCRIPTION_URL,
            lastEtag = prefs[Keys.LAST_ETAG],
            lastModified = prefs[Keys.LAST_MODIFIED],
            lastSuccessfulFetchMs = prefs[Keys.LAST_FETCH_MS] ?: 0L,
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
                ?: AppThemeMode.SYSTEM,
            languageTag = prefs[Keys.LANGUAGE_TAG] ?: "",
            mobileTestMode = prefs[Keys.MOBILE_TEST_MODE]
                ?.let { runCatching { TestMode.valueOf(it) }.getOrNull() }
                ?: TestMode.PING_AND_SPEED,
            wifiTestMode = prefs[Keys.WIFI_TEST_MODE]
                ?.let { runCatching { TestMode.valueOf(it) }.getOrNull() }
                ?: TestMode.PING_AND_SPEED,
            dataSaver = prefs[Keys.DATA_SAVER] ?: false,
            testSpeedMode = prefs[Keys.TEST_SPEED_MODE]
                ?.let { runCatching { TestSpeedMode.valueOf(it) }.getOrNull() }
                ?: TestSpeedMode.MEDIUM,
            speedPayloadKb = (prefs[Keys.SPEED_PAYLOAD_KB] ?: 300).coerceIn(200, 300),
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            publicNoticeAcknowledged = prefs[Keys.PUBLIC_NOTICE_ACK] ?: false,
            allowInsecureTls = prefs[Keys.ALLOW_INSECURE_TLS] ?: false,
            bypassIran = prefs[Keys.BYPASS_IRAN] ?: true,
            reduceEffects = prefs[Keys.REDUCE_EFFECTS] ?: false,
            splitMode = prefs[Keys.SPLIT_MODE]
                ?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() }
                ?: SplitMode.OFF,
            splitPackages = prefs[Keys.SPLIT_PACKAGES] ?: emptySet(),
            favoriteKeys = prefs[Keys.FAVORITE_KEYS] ?: emptySet(),
            preConnectCheck = prefs[Keys.PRE_CONNECT_CHECK]
                ?.let { runCatching { PreConnectCheck.valueOf(it) }.getOrNull() }
                ?: PreConnectCheck.AUTO,
            minSpeedEnabled = prefs[Keys.MIN_SPEED_ENABLED] ?: true,
            minSpeedKbps = (prefs[Keys.MIN_SPEED_KBPS] ?: 50).coerceIn(10, 100_000),
            fingerprintOverride = (prefs[Keys.FINGERPRINT_OVERRIDE] ?: "").takeIf { it in FINGERPRINT_CHOICES } ?: ""
        )
    }
    suspend fun current(): UserPreferences = preferencesFlow.first()
    suspend fun updateSubscriptionUrl(url: String) {
        val cleaned = url.trim().ifBlank { SubscriptionConfig.DEFAULT_SUBSCRIPTION_URL }
        context.kestrelDataStore.edit { prefs ->
            prefs[Keys.SUBSCRIPTION_URL] = cleaned
            prefs.remove(Keys.LAST_ETAG)
            prefs.remove(Keys.LAST_MODIFIED)
            prefs[Keys.LAST_FETCH_MS] = 0L
        }
    }
    suspend fun updateFetchMetadata(
        etag: String?,
        lastModified: String?,
        fetchedAtMs: Long
    ) {
        context.kestrelDataStore.edit { prefs ->
            if (!etag.isNullOrBlank()) prefs[Keys.LAST_ETAG] = etag else prefs.remove(Keys.LAST_ETAG)
            if (!lastModified.isNullOrBlank()) prefs[Keys.LAST_MODIFIED] = lastModified else prefs.remove(Keys.LAST_MODIFIED)
            prefs[Keys.LAST_FETCH_MS] = fetchedAtMs
        }
    }
    suspend fun updateThemeMode(mode: AppThemeMode) {
        context.kestrelDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
    suspend fun updateLanguageTag(tag: String) {
        context.kestrelDataStore.edit { it[Keys.LANGUAGE_TAG] = tag }
    }
    suspend fun updateTestModes(mobile: TestMode, wifi: TestMode) {
        context.kestrelDataStore.edit {
            it[Keys.MOBILE_TEST_MODE] = mobile.name
            it[Keys.WIFI_TEST_MODE] = wifi.name
        }
    }
    suspend fun updateTestSpeedMode(mode: TestSpeedMode) {
        context.kestrelDataStore.edit { it[Keys.TEST_SPEED_MODE] = mode.name }
    }
    suspend fun updateDataSaver(enabled: Boolean) {
        context.kestrelDataStore.edit { it[Keys.DATA_SAVER] = enabled }
    }
    suspend fun updateSpeedPayloadKb(kb: Int) {
        context.kestrelDataStore.edit { it[Keys.SPEED_PAYLOAD_KB] = kb.coerceIn(200, 300) }
    }
    suspend fun updatePreConnectCheck(mode: PreConnectCheck) {
        context.kestrelDataStore.edit { it[Keys.PRE_CONNECT_CHECK] = mode.name }
    }
    suspend fun updateMinSpeedEnabled(enabled: Boolean) {
        context.kestrelDataStore.edit { it[Keys.MIN_SPEED_ENABLED] = enabled }
    }
    suspend fun updateFingerprintOverride(fp: String) {
        context.kestrelDataStore.edit { it[Keys.FINGERPRINT_OVERRIDE] = if (fp in FINGERPRINT_CHOICES) fp else "" }
    }
    suspend fun updateMinSpeedKbps(kbps: Int) {
        context.kestrelDataStore.edit { it[Keys.MIN_SPEED_KBPS] = kbps.coerceIn(10, 100_000) }
    }
    suspend fun updateAllowInsecureTls(enabled: Boolean) {
        context.kestrelDataStore.edit { it[Keys.ALLOW_INSECURE_TLS] = enabled }
    }
    suspend fun updateReduceEffects(enabled: Boolean) {
        context.kestrelDataStore.edit { it[Keys.REDUCE_EFFECTS] = enabled }
    }
    suspend fun updateBypassIran(enabled: Boolean) {
        context.kestrelDataStore.edit { it[Keys.BYPASS_IRAN] = enabled }
    }
    suspend fun updateSplitMode(mode: SplitMode) {
        context.kestrelDataStore.edit { it[Keys.SPLIT_MODE] = mode.name }
    }
    suspend fun toggleSplitPackage(packageName: String) {
        context.kestrelDataStore.edit { prefs ->
            val current = prefs[Keys.SPLIT_PACKAGES] ?: emptySet()
            prefs[Keys.SPLIT_PACKAGES] = if (packageName in current) current - packageName else current + packageName
        }
    }
    suspend fun toggleFavorite(canonicalKey: String) {
        context.kestrelDataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_KEYS] ?: emptySet()
            prefs[Keys.FAVORITE_KEYS] = if (canonicalKey in current) current - canonicalKey else current + canonicalKey
        }
    }
    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.kestrelDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }
    suspend fun setPublicNoticeAcknowledged(acknowledged: Boolean) {
        context.kestrelDataStore.edit { it[Keys.PUBLIC_NOTICE_ACK] = acknowledged }
    }
}
