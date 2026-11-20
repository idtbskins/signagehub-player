package com.affissia.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Over-the-air updater for the Affissia Player.
 *
 * Workflow:
 * 1. Periodic poll of `GET /api/v1/player/latest?current=<version>&device_id=<id>`
 *    on the configured server. The server returns either:
 *      { "version": "2.2.0", "url": "https://.../app-release.apk", "min_version": "2.0.0" }
 *    or 404 / 204 if no update is available.
 * 2. If the returned version is strictly newer than the running app, download
 *    the APK atomically via `.tmp` -> `.apk`, clean up older cached APKs, and
 *    trigger Android's PackageInstaller via FileProvider.
 * 3. Debounces repeat installer prompts for the same target version for
 *    [PROMPT_COOLDOWN_MS] so unattended signage screens are not interrupted
 *    every hour by a confirmation modal.
 */
class OtaChecker(private val context: Context) {
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastPromptedVersion: String? = null
    @Volatile private var lastPromptedElapsedMs: Long = 0L

    fun checkForUpdate(serverUrl: String, currentVersion: String, deviceId: String? = null) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        Thread {
            try {
                runCheck(serverUrl, currentVersion, deviceId)
            } finally {
                inFlight.set(false)
            }
        }.apply {
            isDaemon = true
            name = "ota-check"
            start()
        }
    }

    private fun runCheck(serverUrl: String, currentVersion: String, deviceId: String?) {
        val baseUrl = serverUrl.trimEnd('/')
        val target = try {
            URL("$baseUrl/api/v1/player/latest?${latestQuery(currentVersion, deviceId)}")
        } catch (e: Exception) {
            Log.w(TAG, "bad URL: $baseUrl", e)
            return
        }

        val response = fetchJson(target) ?: return
        val latest = response.optString("version", "").takeIf { it.isNotBlank() } ?: return
        val downloadUrl = response.optString("url", "").takeIf { it.isNotBlank() } ?: return

        if (!isNewer(latest, currentVersion)) {
            Log.d(TAG, "ota: server=$latest current=$currentVersion -> already up to date")
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastPromptedVersion == latest && (nowElapsed - lastPromptedElapsedMs) < PROMPT_COOLDOWN_MS) {
            Log.d(TAG, "ota: skipping repeat installer prompt for $latest during cooldown")
            return
        }

        Log.i(TAG, "ota: downloading $latest from $downloadUrl")
        val apkFile = downloadApk(downloadUrl, latest) ?: return
        Log.i(TAG, "ota: download complete (${apkFile.length()} bytes), launching installer")
        lastPromptedVersion = latest
        lastPromptedElapsedMs = SystemClock.elapsedRealtime()
        launchInstaller(apkFile)
    }

    private fun fetchJson(url: URL): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NO_CONTENT) return null
            if (code !in 200..299) {
                Log.d(TAG, "ota check -> HTTP $code (no update)")
                return null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "ota check failed: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }

    private fun downloadApk(downloadUrl: String, version: String): File? {
        val target = try { URL(downloadUrl) } catch (e: Exception) {
            Log.w(TAG, "bad download URL: $downloadUrl", e)
            return null
        }
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "$APK_PREFIX$safeVersion.apk")
        val tmpFile = File(context.cacheDir, "$APK_PREFIX$safeVersion.apk.tmp")
        var conn: HttpURLConnection? = null
        return try {
            conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "download HTTP ${conn.responseCode}")
                return null
            }
            FileOutputStream(tmpFile).use { output ->
                conn.inputStream.use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            if (tmpFile.length() <= 0L) {
                tmpFile.delete()
                return null
            }
            if (outFile.exists()) {
                outFile.delete()
            }
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.delete()
                return null
            }
            cleanupOldApks(keepFile = outFile)
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            try { tmpFile.delete() } catch (_: Exception) { /* no-op */ }
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }

    private fun cleanupOldApks(keepFile: File) {
        val files = context.cacheDir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (file.absolutePath == keepFile.absolutePath) continue
            if (file.name.startsWith(APK_PREFIX) && (file.name.endsWith(".apk") || file.name.endsWith(".apk.tmp"))) {
                try { file.delete() } catch (_: Exception) { /* no-op */ }
            }
        }
    }

    private fun launchInstaller(apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val authority = context.packageName + ".fileprovider"
        val uri: Uri = try {
            FileProvider.getUriForFile(context, authority, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider failed (check authority $authority + xml)", e)
            return
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "launchInstaller failed", e)
        }
    }

    private fun latestQuery(currentVersion: String, deviceId: String?): String {
        val params = mutableListOf("current" to currentVersion)
        if (!deviceId.isNullOrBlank()) {
            params += "device_id" to deviceId
        }
        return params.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    companion object {
        private const val TAG = "OtaChecker"
        private const val APK_PREFIX = "affissia-player-"
        private const val PROMPT_COOLDOWN_MS = 6L * 60L * 60L * 1000L

        /**
         * Semver compare matching backend `_semver_tuple`. Strips optional
         * leading `v`/`V`, `-prerelease`, and `+build` suffixes.
         */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = parseVersionParts(candidate)
            val b = parseVersionParts(current)
            if (a.isEmpty() || b.isEmpty()) return false
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai > bi
            }
            return false
        }

        private fun parseVersionParts(raw: String): List<Int> {
            val cleaned = raw.trim().trimStart('v', 'V')
            if (cleaned.isBlank()) return emptyList()
            val core = cleaned.substringBefore('-').substringBefore('+')
            val parts = core.split(".")
            val parsed = parts.map { it.toIntOrNull() ?: return emptyList() }
            return parsed
        }
    }
}
