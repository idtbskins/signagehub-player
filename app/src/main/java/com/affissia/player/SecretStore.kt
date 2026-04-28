package com.affissia.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device storage for the long-lived ``device_secret``.
 *
 * Server hardening (PR #2 / PR #4d on the backend) issues a 32-byte
 * random secret on the announce that follows a tenant adopting the
 * device. The secret travels back to the Player exactly once, so we
 * MUST persist it locally — losing it forces the operator through
 * the on-screen 4-digit re-pair flow again.
 *
 * Storage backend is AndroidX EncryptedSharedPreferences (AES-GCM via
 * the Android Keystore master key). The blob lives in a separate
 * SharedPreferences file from ConfigStore so a routine "Clear cache"
 * by the operator doesn't wipe it.
 *
 * Initialisation can fail on highly customised ROMs that strip
 * Keystore. In that case ``isAvailable`` returns false and callers
 * fall back to running without a secret (the device works as a
 * NULL-tenant-pool guest until a fresh re-pair).
 */
class SecretStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val backing: SharedPreferences? = openEncryptedPrefs(appContext)

    val isAvailable: Boolean
        get() = backing != null

    /** Returns the persisted secret, or null when the device has not
     *  yet been adopted into a tenant (or the secret was revoked and
     *  cleared by the heartbeat handler). */
    var secret: String?
        get() = backing?.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val store = backing ?: return
            val edits = store.edit()
            if (value.isNullOrBlank()) {
                edits.remove(KEY_SECRET).remove(KEY_ISSUED_AT)
            } else {
                edits.putString(KEY_SECRET, value)
                edits.putLong(KEY_ISSUED_AT, System.currentTimeMillis())
            }
            edits.apply()
        }

    /** Wall-clock millis when the current secret was first stored,
     *  or 0 when no secret is held. Useful for logs / Settings page. */
    val issuedAtMillis: Long
        get() = backing?.getLong(KEY_ISSUED_AT, 0L) ?: 0L

    /** Wipe the secret; called from the factory-reset flow and from
     *  the heartbeat handler when the server returns 403 with
     *  ``secret_revoked``. */
    fun clear() {
        backing?.edit()?.remove(KEY_SECRET)?.remove(KEY_ISSUED_AT)?.apply()
    }

    companion object {
        private const val TAG = "SecretStore"
        private const val PREFS = "affissia_player_secret"
        private const val KEY_SECRET = "device_secret"
        private const val KEY_ISSUED_AT = "secret_issued_at"

        private fun openEncryptedPrefs(context: Context): SharedPreferences? {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                // Highly-customised ROMs strip Keystore; treat the
                // store as unavailable so the rest of the Player
                // still boots. The device becomes a NULL-pool guest
                // until the operator re-pairs.
                Log.w(TAG, "EncryptedSharedPreferences unavailable: ${e.message}")
                null
            }
        }
    }
}
