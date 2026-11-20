package com.affissia.player

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SliceReadyResult(
    val ok: Boolean,
    val httpCode: Int,
    val startAtMillis: Long? = null,
    val readyCount: Int = 0,
    val totalScreens: Int = 0,
    val error: String? = null,
)

/**
 * Reports verified local download of a server-sliced video-wall segment via
 * `POST /api/v1/devices/<device_id>/slice-ready`.
 *
 * When this screen is the final wall member to report READY, the server
 * immediately responds with a shared future `start_at` timestamp so playback
 * can schedule `Go` without waiting for another heartbeat round-trip.
 */
object SliceReadyClient {
    fun reportReady(
        serverUrl: String,
        deviceId: String,
        deviceSecret: String?,
        jobId: String,
        sha256: String,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): SliceReadyResult {
        val baseUrl = serverUrl.trim().trimEnd('/')
        if (baseUrl.isBlank() || deviceId.isBlank() || jobId.isBlank() || sha256.isBlank()) {
            return SliceReadyResult(ok = false, httpCode = -1, error = "missing_parameters")
        }
        val endpoint = "$baseUrl/api/v1/devices/$deviceId/slice-ready"
        val target = try {
            URL(endpoint)
        } catch (e: Exception) {
            return SliceReadyResult(ok = false, httpCode = -1, error = "invalid_url")
        }

        val body = JSONObject().apply {
            put("job_id", jobId)
            put("sha256", sha256)
        }.toString()

        var conn: HttpURLConnection? = null
        return try {
            conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                if (!deviceSecret.isNullOrBlank()) {
                    setRequestProperty(HEADER_DEVICE_SECRET, deviceSecret)
                }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "slice-ready HTTP $code job=$jobId")
                return SliceReadyResult(ok = false, httpCode = code, error = "http_$code")
            }
            val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseResponse(code, responseText)
        } catch (e: Exception) {
            Log.w(TAG, "slice-ready failed job=$jobId: ${e.message}")
            SliceReadyResult(ok = false, httpCode = -1, error = e.message ?: "network_error")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { /* no-op */ }
        }
    }

    internal fun parseResponse(httpCode: Int, responseBody: String): SliceReadyResult {
        return try {
            val json = JSONObject(responseBody)
            val startAtRaw = json.optString("start_at", "").trim().takeIf {
                it.isNotBlank() && !json.isNull("start_at")
            }
            val startAtMillis = startAtRaw?.let {
                try {
                    VideoWallProManifestParser.parseTimestampMillis(it)
                } catch (_: Exception) {
                    null
                }
            }
            SliceReadyResult(
                ok = json.optBoolean("ok", httpCode in 200..299),
                httpCode = httpCode,
                startAtMillis = startAtMillis,
                readyCount = json.optInt("ready_count", 0),
                totalScreens = json.optInt("total_screens", 0),
            )
        } catch (e: Exception) {
            SliceReadyResult(ok = false, httpCode = httpCode, error = "invalid_json")
        }
    }

    private const val TAG = "SliceReadyClient"
    private const val HEADER_DEVICE_SECRET = "X-Device-Secret"
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
    private const val DEFAULT_READ_TIMEOUT_MS = 5_000
}
