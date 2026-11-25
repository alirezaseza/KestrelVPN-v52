package com.kestrel.vpn.ui.settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.vpn.data.local.AppThemeMode
import com.kestrel.vpn.data.local.PreConnectCheck
import com.kestrel.vpn.data.local.TrafficHistoryStore
import com.kestrel.vpn.data.local.UserPreferences
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.data.remote.SubscriptionConfig
import com.kestrel.vpn.domain.model.TestMode
import com.kestrel.vpn.domain.model.TestSpeedMode
import com.kestrel.vpn.domain.repository.ConfigRepository
import com.kestrel.vpn.engine.scheduler.ChunkedTestScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesDataStore: UserPreferencesDataStore,
    private val repository: ConfigRepository,
    private val scheduler: ChunkedTestScheduler,
    private val trafficHistory: TrafficHistoryStore,
    private val huntRequests: com.kestrel.vpn.engine.hunt.HuntRequests,
    @javax.inject.Named("tunnel") private val httpClient: okhttp3.OkHttpClient
) : ViewModel() {
    private val _exitIp = MutableStateFlow<String?>(null)
    val exitIp: StateFlow<String?> = _exitIp.asStateFlow()
    private val _checkingExitIp = MutableStateFlow(false)
    val checkingExitIp: StateFlow<Boolean> = _checkingExitIp.asStateFlow()
    /** User-initiated only: asks a third-party echo service which IP it sees (documented in the privacy policy). */
    fun checkExitIp() {
        if (_checkingExitIp.value) return
        _checkingExitIp.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ip = runCatching {
                val c = httpClient.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
                c.newCall(okhttp3.Request.Builder().url("https://api.ipify.org").build()).execute().use { it.body?.string() }
            }.getOrNull()
            _exitIp.value = ip?.trim()?.takeIf { it.length in 3..45 } ?: "—"
            _checkingExitIp.value = false
        }
    }
    val usage = trafficHistory.usage
    fun startHunt() = huntRequests.request()
    val preferences: StateFlow<UserPreferences> = preferencesDataStore.preferencesFlow
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences()
    )
    private val _urlValidationError = MutableStateFlow(false)
    val urlValidationError: StateFlow<Boolean> = _urlValidationError.asStateFlow()
    fun setLanguageTag(tag: String) {
        viewModelScope.launch {
            preferencesDataStore.updateLanguageTag(tag)
            val locales = if (tag.isBlank()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { preferencesDataStore.updateThemeMode(mode) }
    }
    fun setMobileTestMode(mode: TestMode) {
        viewModelScope.launch {
            preferencesDataStore.updateTestModes(mobile = mode, wifi = preferences.value.wifiTestMode)
        }
    }
    fun setWifiTestMode(mode: TestMode) {
        viewModelScope.launch {
            preferencesDataStore.updateTestModes(mobile = preferences.value.mobileTestMode, wifi = mode)
        }
    }
    fun setTestSpeedMode(mode: TestSpeedMode) {
        viewModelScope.launch { preferencesDataStore.updateTestSpeedMode(mode) }
    }
    fun setPreConnectCheck(mode: PreConnectCheck) {
        viewModelScope.launch { preferencesDataStore.updatePreConnectCheck(mode) }
    }
    fun setMinSpeedKbps(kbps: Int) {
        viewModelScope.launch { preferencesDataStore.updateMinSpeedKbps(kbps) }
    }
    fun setFingerprintOverride(fp: String) {
        viewModelScope.launch { preferencesDataStore.updateFingerprintOverride(fp) }
    }
    fun setMinSpeedEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.updateMinSpeedEnabled(enabled) }
    }
    fun setDataSaver(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.updateDataSaver(enabled) }
    }
    fun setBypassIran(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.updateBypassIran(enabled) }
    }
    fun setReduceEffects(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.updateReduceEffects(enabled) }
    }
    fun setAllowInsecureTls(enabled: Boolean) {
        viewModelScope.launch { preferencesDataStore.updateAllowInsecureTls(enabled) }
    }
    fun setSpeedPayloadKb(kb: Int) {
        viewModelScope.launch { preferencesDataStore.updateSpeedPayloadKb(kb) }
    }
    fun saveSubscriptionUrl(url: String) {
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps) {
            _urlValidationError.value = true
            return
        }
        _urlValidationError.value = false
        viewModelScope.launch {
            preferencesDataStore.updateSubscriptionUrl(trimmed)
            repository.refreshSubscription(force = true)
        }
    }
    fun clearUrlValidationError() {
        _urlValidationError.value = false
    }
    fun resetSubscriptionUrl() {
        saveSubscriptionUrl(SubscriptionConfig.DEFAULT_SUBSCRIPTION_URL)
    }
    fun clearTestCache() {
        viewModelScope.launch {
            repository.clearTestCache(networkKey = null)
            scheduler.resetSession()
        }
    }
}
