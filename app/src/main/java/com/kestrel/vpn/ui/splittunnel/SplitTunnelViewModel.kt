package com.kestrel.vpn.ui.splittunnel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kestrel.vpn.data.local.SplitMode
import com.kestrel.vpn.data.local.UserPreferences
import com.kestrel.vpn.data.local.UserPreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppEntry(val label: String, val packageName: String)

@HiltViewModel
class SplitTunnelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: UserPreferencesDataStore
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesDataStore.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val entries = runCatching {
                pm.queryIntentActivities(launcher, 0)
                    .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .filter { it.packageName != context.packageName }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
            _apps.value = entries
            _loading.value = false
        }
    }

    fun setMode(mode: SplitMode) {
        viewModelScope.launch { preferencesDataStore.updateSplitMode(mode) }
    }

    fun toggleApp(packageName: String) {
        viewModelScope.launch { preferencesDataStore.toggleSplitPackage(packageName) }
    }
}
