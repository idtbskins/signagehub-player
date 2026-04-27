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
    /**
     * Cadence while the screen is *bound* (operator confirmed, kiosk
     * is showing real content). 60 s is the right rate for "is this
     * screen alive" reporting on the admin dashboard.
     */
    private val boundIntervalMs: Long = 60_000L,
    /**
     * Cadence while *waiting for an admin to bind us*. Way shorter so
     * the auto-pair flow lands the screen in <10s instead of the 0–60s
     * window the bound interval would impose. We expect very few
     * unbound devices on a network at any one time, so the QPS hit is
     * bounded.
     */
    private val unboundIntervalMs: Long = 10_000L,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    @Volatile private var bound = false
    private var lastPlayedAssetIdProvider: () -> String? = { null }
    /**
     * Called on the main thread when a heartbeat reveals the device is now
     * bound to a screen. The string is the absolute display URL (already
     * carrying ``?token=...`` on first delivery from the auto-pair flow).
     * MainActivity uses it to ``loadUrl()`` the player view so the screen
     * leaves the 6-digit pairing-code page on its own.
     */
    private var onBoundUrlProvider: (String) -> Unit = { }
    private var onPairCodeProvider: (String) -> Unit = { }

    fun setLastPlayedAssetIdProvider(provider: () -> String?) {
        lastPlayedAssetIdProvider = provider
    }

    fun setOnBoundUrl(callback: (String) -> Unit) {
        onBoundUrlProvider = callback
    }

    fun setOnPairCode(callback: (String) -> Unit) {
        onPairCodeProvider = callback
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
            // Pick the next delay based on the latest known bind state.
            // Keeps the un-bound window tight (sub-10s land time) and
            // backs off to the bound cadence as soon as the operator
            // claims us.
            val next = if (bound) boundIntervalMs else unboundIntervalMs
            mainHandler.postDelayed(this, next)
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
            // self-heal, and the next heartbeat tick will succeed.
            if (result.code == 404) {
                Log.d(TAG, "heartbeat 404 — re-announcing device")
                val announced = AnnouncementClient.announce(
                    serverUrl = serverUrl,
                    deviceId = deviceId,
                    deviceLabel = deviceIdentity.deviceLabel,
                    appVersion = BuildConfig.VERSION_NAME,
                )
                val announcedPairCode = announced.pairCode
                if (announced.ok && !announcedPairCode.isNullOrBlank()) {
                    configStore.pairCode = announcedPairCode
                    mainHandler.post { onPairCodeProvider(announcedPairCode) }
                }
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
            if (result.bound) {
                bound = true
                // The visual pair_code is now obsolete — the screen has
                // been claimed. Wipe it so that, if the operator ever
                // unbinds the device and the next announce mints a new
                // code, we don't accidentally render the stale one.
                if (configStore.pairCode.isNotBlank()) {
                    configStore.pairCode = ""
                }
                if (!redirectUrl.isNullOrBlank()) {
                    mainHandler.post { onBoundUrlProvider(redirectUrl) }
                }
            } else {
                bound = false
                val pairCode = result.pairCode
                if (!pairCode.isNullOrBlank() && configStore.pairCode != pairCode) {
                    configStore.pairCode = pairCode
                    mainHandler.post { onPairCodeProvider(pairCode) }
                }
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
        val pairCode: String?,
    )

    /** POSTs the body, returns status code + (when applicable) parsed bound/redirect_url. */
    private fun postHeartbeat(urlString: String, body: String): HeartbeatResult {
        val target = try { URL(urlString) } catch (e: Exception) {
            Log.w(TAG, "bad URL: $urlString", e)
            return HeartbeatResult(code = -1, bound = false, redirectUrl = null, pairCode = null)
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
                return HeartbeatResult(code = code, bound = false, redirectUrl = null, pairCode = null)
            }
            val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = try { JSONObject(responseBody) } catch (e: Exception) {
                Log.d(TAG, "heartbeat: response not JSON: ${e.message}")
                return HeartbeatResult(code = code, bound = false, redirectUrl = null, pairCode = null)
            }
            HeartbeatResult(
                code = code,
                bound = json.optBoolean("bound", false),
                redirectUrl = json.optString("redirect_url", "").takeIf { it.isNotBlank() },
                pairCode = json.optString("pair_code", "").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Log.d(TAG, "heartbeat failed: ${e.message}")
            HeartbeatResult(code = -1, bound = false, redirectUrl = null, pairCode = null)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }

    companion object {
        private const val TAG = "Heartbeat"
    }
}
