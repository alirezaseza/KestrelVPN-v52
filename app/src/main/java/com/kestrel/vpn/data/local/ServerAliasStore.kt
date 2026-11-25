package com.kestrel.vpn.data.local

import android.content.Context
import com.kestrel.vpn.domain.model.ProxyConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Friendly, stable replacement names for servers (the raw remarks are long and messy). */
object ServerNaming {
    private val words = listOf(
        "Falcon", "Hawk", "Eagle", "Osprey", "Harrier", "Merlin", "Peregrine", "Condor",
        "Swift", "Raven", "Heron", "Kite", "Owl", "Phoenix", "Sparrow", "Lark"
    )

    fun defaultName(canonicalKey: String): String {
        val h = canonicalKey.hashCode() and 0x7fffffff
        return "${words[h % words.size]} ${(h / words.size) % 900 + 100}"
    }
}

/**
 * Keeps user-chosen server names and the last server that connected fine.
 * Plain SharedPreferences: no database migration needed.
 */
@Singleton
class ServerAliasStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("kestrel_server_names", Context.MODE_PRIVATE)
    private val _aliases = MutableStateFlow(load())
    val aliases: StateFlow<Map<String, String>> = _aliases.asStateFlow()

    fun nameFor(config: ProxyConfig): String = nameFor(config.canonicalKey)

    fun nameFor(canonicalKey: String): String =
        _aliases.value[canonicalKey] ?: ServerNaming.defaultName(canonicalKey)

    /** Blank name resets to the generated one. */
    fun rename(canonicalKey: String, name: String) {
        val trimmed = name.trim().take(MAX_NAME)
        val next = _aliases.value.toMutableMap()
        if (trimmed.isEmpty()) next.remove(canonicalKey) else next[canonicalKey] = trimmed
        _aliases.value = next
        prefs.edit().putString(KEY_ALIASES, JSONObject(next as Map<*, *>).toString()).apply()
    }

    private val _lastGood = MutableStateFlow(prefs.getString(KEY_LAST_GOOD, null))
    /** Last server that stayed connected; the Connect button re-checks it first. */
    val lastGood: StateFlow<String?> = _lastGood.asStateFlow()

    fun lastGoodKey(): String? = _lastGood.value

    fun setLastGood(canonicalKey: String?) {
        _lastGood.value = canonicalKey
        prefs.edit().apply {
            if (canonicalKey == null) remove(KEY_LAST_GOOD) else putString(KEY_LAST_GOOD, canonicalKey)
        }.apply()
    }

    private fun load(): Map<String, String> {
        val raw = prefs.getString(KEY_ALIASES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = HashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.getString(k)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private companion object {
        const val KEY_ALIASES = "aliases"
        const val KEY_LAST_GOOD = "last_good_key"
        const val MAX_NAME = 40
    }
}
