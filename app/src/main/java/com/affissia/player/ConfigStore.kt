package com.affissia.player

import android.content.Context
import android.content.SharedPreferences

class ConfigStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = preferences.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_SERVER_URL, normalizeServerUrl(value)).apply()
        }

    var lastLoadedAt: Long
        get() = preferences.getLong(KEY_LAST_LOADED_AT, 0L)
        set(value) {
            preferences.edit().putLong(KEY_LAST_LOADED_AT, value).apply()
        }

    var failedLoadCount: Int
        get() = preferences.getInt(KEY_FAILED_LOAD_COUNT, 0)
        set(value) {
            preferences.edit().putInt(KEY_FAILED_LOAD_COUNT, value).apply()
        }

    var trustSelfSigned: Boolean
        get() = preferences.getBoolean(KEY_TRUST_SELF_SIGNED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_TRUST_SELF_SIGNED, value).apply()
        }

    /**
     * The server-minted 4-digit visual pair code for this device. Set
     * the first time announce returns one and re-read on each subsequent
     * boot so the kiosk can append ``?pair_code=...`` to its loadUrl
     * even after a power cycle. Cleared (set to "") when the screen is
     * known to be bound — the next "unbound" announce will mint a fresh
     * code.
     */
    var pairCode: String
        get() = preferences.getString(KEY_PAIR_CODE, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_PAIR_CODE, value).apply()
        }

    fun normalizeServerUrl(value: String): String {
        return value.trim().trimEnd('/')
    }

    companion object {
        private const val PREFS_NAME = "affissia_player_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LAST_LOADED_AT = "last_loaded_at"
        private const val KEY_FAILED_LOAD_COUNT = "failed_load_count"
        private const val KEY_TRUST_SELF_SIGNED = "trust_self_signed"
        private const val KEY_PAIR_CODE = "pair_code"
    }
}
