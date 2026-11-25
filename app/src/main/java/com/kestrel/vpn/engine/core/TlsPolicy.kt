package com.kestrel.vpn.engine.core

import com.kestrel.vpn.data.local.UserPreferencesDataStore
import com.kestrel.vpn.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide mirror of the "allow invalid TLS certificates" preference, so that
 * non-suspending code (config builder, probes) can read it. Defaults to the safe value
 * (false) until the preference has been loaded.
 */
@Singleton
class TlsPolicy @Inject constructor(
    preferences: UserPreferencesDataStore,
    @ApplicationScope scope: CoroutineScope
) {
    @Volatile
    var allowInsecure: Boolean = false
        private set

    @Volatile
    var fingerprintOverride: String = ""
        private set

    init {
        scope.launch {
            preferences.preferencesFlow
                .map { it.fingerprintOverride }
                .distinctUntilChanged()
                .collect { fingerprintOverride = it }
        }
        scope.launch {
            preferences.preferencesFlow
                .map { it.allowInsecureTls }
                .distinctUntilChanged()
                .collect { allowInsecure = it }
        }
    }
}
