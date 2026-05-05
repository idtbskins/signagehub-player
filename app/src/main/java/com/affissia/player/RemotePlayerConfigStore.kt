package com.affissia.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class RemotePlayerConfigStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveFromHeartbeat(playerConfigJson: String?, featuresJson: String?) {
        if (playerConfigJson == null && featuresJson == null) return

        val editor = preferences.edit()
            .putLong(KEY_UPDATED_AT_MS, System.currentTimeMillis())
        playerConfigJson?.let { editor.putString(KEY_PLAYER_CONFIG_JSON, it) }
        featuresJson?.let { editor.putString(KEY_FEATURES_JSON, it) }
        editor.apply()

        Log.i(
            TAG,
            "heartbeat remote config stored: player_config=${playerConfigJson != null}, features=${featuresJson != null}",
        )
    }

    val playerConfigJson: String?
        get() = preferences.getString(KEY_PLAYER_CONFIG_JSON, null)

    val updatedAtMs: Long
        get() = preferences.getLong(KEY_UPDATED_AT_MS, 0L)

    companion object {
        private const val TAG = "RemotePlayerConfig"
        private const val PREFS_NAME = "affissia_player_remote_config"
        private const val KEY_PLAYER_CONFIG_JSON = "player_config_json"
        private const val KEY_FEATURES_JSON = "features_json"
        private const val KEY_UPDATED_AT_MS = "updated_at_ms"
    }
}
