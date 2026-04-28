package com.affissia.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Persistent per-install device identity.
 *
 * Strategy (PR #5, decision 1A): the on-the-wire ``device_id`` is
 * SHA-256 of ``Settings.Secure.ANDROID_ID`` mixed with a salt derived
 * from the application package. ANDROID_ID is provided by the Android
 * platform and stays stable across app reinstalls and most factory
 * resets, so a Player whose owner clears its cache or runs the
 * in-app "factory reset" flow keeps the same ``device_id`` and the
 * server side recognises it as the same device — its tenant binding
 * survives.
 *
 * Backward compatibility: devices already in the field on v2.0.x
 * have a randomly-generated UUID in SharedPreferences. Don't rotate
 * those — that would force every existing tenant to re-pair every
 * Player. We only derive a fresh ANDROID_ID-based id for rows that
 * have no stored id yet (= brand-new installs after PR #5 lands).
 *
 * Edge case: a tiny minority of custom Android ROMs reset
 * ANDROID_ID on factory reset. Those devices arrive at the server as
 * "new" rows; the merchant has to re-pair them via the on-screen
 * 4-digit code + ``/pair-by-code`` (or auto-pair list) — same path as
 * any genuinely new device. Correct, just one re-pair.
 */
class DeviceIdentity(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val derived = deriveStableDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, derived).apply()
            return derived
        }

    /** Human-friendly model label; sent to the server so the operator
     *  can recognize the device in the pending list ("Bravo TV-43"
     *  instead of an opaque hash). */
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

    /**
     * Drop the cached device_id. Only called by the in-app factory
     * reset flow; on the next access ``deviceId`` re-derives from
     * ANDROID_ID. On a normal phone/tablet the new id matches the
     * old one (ANDROID_ID is stable) — cumulative effect: secret is
     * reset, device_id is stable, server rebinds the same row.
     */
    fun clearCachedDeviceId() {
        prefs.edit().remove(KEY_DEVICE_ID).apply()
    }

    private fun deriveStableDeviceId(): String {
        val androidId = readAndroidId()
        // Mix in the package name so two apps signed with the same
        // build can never derive identical ids on the same device.
        // Plain hex of the digest — "dev_" prefix matches the legacy
        // UUID format so server-side routes that rely on the prefix
        // don't need to change.
        val source = "${appContext.packageName}|$androidId"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            if (b < 0x10) hex.append('0')
            hex.append(b.toString(16))
        }
        return "dev_$hex"
    }

    @SuppressLint("HardwareIds")
    private fun readAndroidId(): String {
        return try {
            Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ANDROID_ID,
            ) ?: FALLBACK_ANDROID_ID
        } catch (e: SecurityException) {
            // Some highly-locked-down devices refuse to expose
            // ANDROID_ID. Fall back to a stable per-install constant
            // so we still get a deterministic id (cost: factory
            // reset on these devices = new id, just like the
            // pre-PR-#5 random UUID code path).
            FALLBACK_ANDROID_ID
        }
    }

    companion object {
        private const val PREFS = "affissia_player_identity"
        private const val KEY_DEVICE_ID = "device_id"
        // Used only when ANDROID_ID is unreadable. The hash mixes in
        // the package name anyway, so this string never matters for
        // cross-device collisions.
        private const val FALLBACK_ANDROID_ID = "android_id_unavailable"
    }
}
