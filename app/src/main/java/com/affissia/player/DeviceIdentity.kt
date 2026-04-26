package com.affissia.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

/**
 * Persistent per-install device identity. The UUID is generated on first
 * launch, stored in SharedPreferences, and survives app upgrades. It does
 * NOT change when the user clears the server URL or repairs the device,
 * so the server can recognize a returning device after a re-pair.
 *
 * Phase 2 of the player: this id is sent to the server's
 * /api/v1/devices/announce endpoint together with model/screen info so
 * the operator can claim the device from the admin console without typing
 * a 6-digit code. The endpoint is implemented in a follow-up backend
 * commit; until then the announce call tolerates 404 and the user can
 * still pair with the existing 6-digit code flow.
 */
class DeviceIdentity(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
            return fresh
        }

    /** Human-friendly model label; sent to the server so the operator can
     *  recognize the device in the pending list ("Bravo TV-43" instead of
     *  a UUID). */
    val deviceLabel: String
        get() {
            val manufacturer = (Build.MANUFACTURER ?: "").trim()
            val model = (Build.MODEL ?: "").trim()
            return when {
                manufacturer.isBlank() && model.isBlank() -> "Android device"
                model.startsWith(manufacturer, ignoreCase = true) -> model
                manufacturer.isBlank() -> model
                model.isBlank() -> manufacturer
                else -> "$manufacturer $model"
            }
        }

    companion object {
        private const val PREFS = "affissia_player_identity"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
