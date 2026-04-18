package com.signagehub.player

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

    fun normalizeServerUrl(value: String): String {
        return value.trim().trimEnd('/')
    }

    companion object {
        private const val PREFS_NAME = "signagehub_player_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LAST_LOADED_AT = "last_loaded_at"
        private const val KEY_FAILED_LOAD_COUNT = "failed_load_count"
        private const val KEY_TRUST_SELF_SIGNED = "trust_self_signed"
    }
}
