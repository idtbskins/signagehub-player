package com.affissia.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Periodic heartbeat — POSTs basic device state to the Affissia backend
 * every 60 seconds so the operator console can show "online / offline /
 * stale" status per screen and the time of the last seen ping.
 *
 * Endpoint (backend ships in the next pass):
 *   POST /api/v1/devices/<device_id>/heartbeat
 *   { "device_id", "version", "current_url", "last_played_asset_id" }
 *
 * Resilience:
 *  - 404: backend doesn't have the endpoint yet → silent fail, retry
 *    next minute. Player keeps playing normally.
 *  - 4xx/5xx/timeout: log + retry next interval.
 *
 * Lifecycle:
 *  - Start from Activity.onResume()
 *  - Stop from Activity.onPause()
 *  - Survives screen orientation changes; doesn't need a foreground
 *    service since the player Activity is always in foreground (kiosk).
 */
class HeartbeatScheduler(
    private val deviceIdentity: DeviceIdentity,
    private val configStore: ConfigStore,
    private val intervalMs: Long = 60_000L,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastPlayedAssetIdProvider: () -> String? = { null }
    /**
     * Called on the main thread when a heartbeat reveals the device is now
     * bound to a screen. The string is the absolute display URL (already
     * carrying ``?token=...`` on first delivery from the auto-pair flow).
     * MainActivity uses it to ``loadUrl()`` the player view so the screen
     * leaves the 6-digit pairing-code page on its own.
     */
    private var onBoundUrlProvider: (String) -> Unit = { }

    fun setLastPlayedAssetIdProvider(provider: () -> String?) {
        lastPlayedAssetIdProvider = provider
    }

    fun setOnBoundUrl(callback: (String) -> Unit) {
        onBoundUrlProvider = callback
    }

    fun start() {
        if (running) return
        running = true
        mainHandler.post(beatRunnable)
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(beatRunnable)
    }

    private val beatRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            postBeat()
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    private fun postBeat() {
        val serverUrl = configStore.serverUrl.trimEnd('/')
        if (serverUrl.isBlank()) return
        val deviceId = deviceIdentity.deviceId
        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("version", BuildConfig.VERSION_NAME)
            put("current_url", "$serverUrl/display/$deviceId")
            put("last_played_asset_id", lastPlayedAssetIdProvider() ?: JSONObject.NULL)
        }.toString()

        Thread {
            val result = postHeartbeat(
                "$serverUrl/api/v1/devices/$deviceId/heartbeat",
                payload,
            )
            // 404 means the backend has no record of this device — most
            // likely because the original announce in SetupActivity ran
            // before the backend endpoint existed (v2.0.x devices). The
            // backend now exists, so re-announce on the same thread to
            // self-heal, and the next heartbeat tick (60 s later) will
            // succeed.
            if (result.code == 404) {
                Log.d(TAG, "heartbeat 404 — re-announcing device")
                AnnouncementClient.announce(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    deviceLabel = deviceIdentity.deviceLabel,
                    appVersion = BuildConfig.VERSION_NAME,
                )
                return@Thread
            }
            // Auto-pair handshake: when an admin binds this device through
            // /screens/pair, the next heartbeat returns
            //   { "bound": true, "redirect_url": ".../display/<sid>?token=..." }
            // The query string carries the long-term display token *once*
            // (the server NULLs the column right after sending). We hand
            // the URL straight to MainActivity, which calls webView.loadUrl
            // and the in-page display.js takes the token from there.
            val redirectUrl = result.redirectUrl
            if (result.bound && !redirectUrl.isNullOrBlank()) {
                mainHandler.post { onBoundUrlProvider(redirectUrl) }
            }
        }.apply {
            isDaemon = true
            name = "heartbeat-post"
            start()
        }
    }

    private data class HeartbeatResult(
        val code: Int,
        val bound: Boolean,
        val redirectUrl: String?,
    )

    /** POSTs the body, returns status code + (when applicable) parsed bound/redirect_url. */
    private fun postHeartbeat(urlString: String, body: String): HeartbeatResult {
        val target = try { URL(urlString) } catch (e: Exception) {
            Log.w(TAG, "bad URL: $urlString", e)
            return HeartbeatResult(code = -1, bound = false, redirectUrl = null)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 4_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "heartbeat -> HTTP $code")
                return HeartbeatResult(code = code, bound = false, redirectUrl = null)
            }
            val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = try { JSONObject(responseBody) } catch (e: Exception) {
                Log.d(TAG, "heartbeat: response not JSON: ${e.message}")
                return HeartbeatResult(code = code, bound = false, redirectUrl = null)
            }
            HeartbeatResult(
                code = code,
                bound = json.optBoolean("bound", false),
                redirectUrl = json.optString("redirect_url", "").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Log.d(TAG, "heartbeat failed: ${e.message}")
            HeartbeatResult(code = -1, bound = false, redirectUrl = null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }

    companion object {
        private const val TAG = "Heartbeat"
    }
}
