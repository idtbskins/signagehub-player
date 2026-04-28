package com.affissia.player

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Best-effort device announcement to the Affissia backend. POSTs basic
 * device info plus the tenant invite code to `/api/v1/devices/announce`
 * so the operator sees only Players intended for their merchant before
 * binding the physical screen with the short visual pair code.
 *
 * Resilience contract:
 * - 200 / 201: server accepted, announcement persisted. The response may
 *   carry a visual pair code and, after adoption, the one-shot
 *   device_secret.
 * - 404: backend doesn't have the endpoint yet. Treated as a no-op so
 *   installers can still troubleshoot the server URL.
 * - any other error / timeout: log and move on. Discovery + manual
 *   pairing fallback continue to work.
 *
 * Runs on a background thread (caller's responsibility) — caller wraps in
 * a Thread or uses lifecycle scope. Uses HttpURLConnection to avoid
 * pulling OkHttp into the APK; switch later if we need cleaner code.
 */
object AnnouncementClient {
    private const val TAG = "Announcement"
    private const val ENDPOINT = "/api/v1/devices/announce"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * POSTs to /api/v1/devices/announce. Returns the parsed result so
     * MainActivity / SetupActivity can persist the visual ``pair_code``
     * the backend mints for this device. Never throws.
     */
    data class AnnounceResult(
        val ok: Boolean,
        val pairCode: String?,
        /** Set exactly once on the announce that follows merchant
         *  adoption — the server hands back the long-lived
         *  ``device_secret`` plaintext, callers MUST persist it via
         *  SecretStore before the response object goes out of scope.
         *  Subsequent announces of the same device do NOT re-emit
         *  the secret. */
        val deviceSecret: String? = null,
    )

    fun announce(
        serverUrl: String,
        deviceId: String,
        deviceLabel: String,
        appVersion: String,
        inviteCode: String? = null,
        displaySize: Pair<Int, Int>? = null,
    ): AnnounceResult {
        val cleanedBase = serverUrl.trimEnd('/')
        val target = try { URL("$cleanedBase$ENDPOINT") } catch (e: Exception) {
            Log.w(TAG, "bad URL: $cleanedBase", e)
            return AnnounceResult(ok = false, pairCode = null)
        }

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("device_label", deviceLabel)
            put("app_version", appVersion)
            inviteCode?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("invite_code", it)
            }
            displaySize?.let { (width, height) ->
                if (width > 0 && height > 0) {
                    put("display_width", width)
                    put("display_height", height)
                }
            }
            put("client", "affissia-player-android")
        }.toString()

        var conn: HttpURLConnection? = null
        return try {
            conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                if (this is HttpsURLConnection) {
                    // Self-signed certs are tolerated in v1 (see
                    // SignageWebClient onReceivedSslError). For the
                    // announce call we use the JVM's default trust
                    // manager — admin LAN setups with self-signed certs
                    // will fail this announce silently and leave the
                    // setup screen available for manual troubleshooting.
                }
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.d(TAG, "announce -> HTTP $code")
            if (code !in 200..299) {
                return AnnounceResult(ok = false, pairCode = null)
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val (parsedCode, parsedSecret) = try {
                val obj = JSONObject(body)
                Pair(
                    obj.optString("pair_code", "").takeIf { it.isNotBlank() },
                    obj.optString("device_secret", "").takeIf { it.isNotBlank() },
                )
            } catch (e: Exception) {
                Log.d(TAG, "announce: response not JSON: ${e.message}")
                Pair(null, null)
            }
            AnnounceResult(ok = true, pairCode = parsedCode, deviceSecret = parsedSecret)
        } catch (e: Exception) {
            Log.w(TAG, "announce failed: ${e.message}")
            AnnounceResult(ok = false, pairCode = null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }
}
