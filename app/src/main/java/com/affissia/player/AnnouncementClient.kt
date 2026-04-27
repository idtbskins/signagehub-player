package com.affissia.player

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Best-effort device announcement to the Affissia backend. POSTs basic
 * device info to `/api/v1/devices/announce` so the operator can see
 * "Bravo TV-43 (192.168.1.45) wants to join" in the admin console and
 * claim it with one click — no 6-digit code typing.
 *
 * Resilience contract:
 * - 200 / 201: server accepted, announcement persisted. Future: response
 *   may carry a pre-issued device token (skipped for v2.0 — backend not
 *   yet implementing this).
 * - 404: backend doesn't have the endpoint yet (v2.0 ships before the
 *   backend half). Treated as a no-op so the player keeps working with
 *   the existing 6-digit code flow.
 * - any other error / timeout: log and move on. Discovery + manual
 *   pairing fallback continue to work.
 *
 * Runs on a background thread (caller's responsibility) — caller wraps in
 * a Thread or uses lifecycle scope. Uses HttpURLConnection to avoid
 * pulling OkHttp into the v2.0 APK; switch later if we need cleaner code.
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
    )

    fun announce(
        serverUrl: String,
        deviceId: String,
        deviceLabel: String,
        appVersion: String,
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
                    // will fail this announce silently and fall back to
                    // manual pair, which is fine for v2.0.
                }
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.d(TAG, "announce -> HTTP $code")
            if (code !in 200..299) {
                return AnnounceResult(ok = false, pairCode = null)
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsedCode = try {
                JSONObject(body).optString("pair_code", "").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.d(TAG, "announce: response not JSON: ${e.message}")
                null
            }
            AnnounceResult(ok = true, pairCode = parsedCode)
        } catch (e: Exception) {
            Log.w(TAG, "announce failed: ${e.message}")
            AnnounceResult(ok = false, pairCode = null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }
}
