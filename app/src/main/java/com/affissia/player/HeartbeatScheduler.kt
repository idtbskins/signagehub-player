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

    fun setLastPlayedAssetIdProvider(provider: () -> String?) {
        lastPlayedAssetIdProvider = provider
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
            postIgnore("$serverUrl/api/v1/devices/$deviceId/heartbeat", payload)
        }.apply {
            isDaemon = true
            name = "heartbeat-post"
            start()
        }
    }

    private fun postIgnore(urlString: String, body: String) {
        val target = try { URL(urlString) } catch (e: Exception) {
            Log.w(TAG, "bad URL: $urlString", e)
            return
        }
        var conn: HttpURLConnection? = null
        try {
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
            }
        } catch (e: Exception) {
            Log.d(TAG, "heartbeat failed: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }

    companion object {
        private const val TAG = "Heartbeat"
    }
}
