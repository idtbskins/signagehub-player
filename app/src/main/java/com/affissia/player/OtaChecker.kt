package com.affissia.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updater for the Affissia Player.
 *
 * Workflow:
 * 1. Periodic poll of `GET /api/v1/player/latest?current=<version>` on
 *    the configured server. The server returns either:
 *      { "version": "2.1.0", "url": "https://.../app-release.apk", "min_version": "2.0.0" }
 *    or 404 / 204 if no update is available.
 * 2. If the returned version is newer than the running app, download the
 *    APK to internal storage and trigger Android's PackageInstaller via
 *    an Intent — the user sees a single confirmation prompt and the app
 *    is replaced. Without device-owner mode this prompt is unavoidable
 *    on stock Android.
 * 3. Errors are logged and swallowed — the next scheduled poll tries
 *    again. The player keeps running normally if the update path fails.
 *
 * The check is intentionally cheap (one HTTP HEAD-equivalent JSON
 * response, no big payload unless an update is actually available) so
 * we can run it on launch + every hour without battery concerns.
 */
class OtaChecker(private val context: Context) {

    fun checkForUpdate(serverUrl: String, currentVersion: String) {
        Thread {
            runCheck(serverUrl, currentVersion)
        }.apply {
            isDaemon = true
            name = "ota-check"
            start()
        }
    }

    private fun runCheck(serverUrl: String, currentVersion: String) {
        val baseUrl = serverUrl.trimEnd('/')
        val target = try {
            URL("$baseUrl/api/v1/player/latest?current=$currentVersion")
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

        Log.i(TAG, "ota: downloading $latest from $downloadUrl")
        val apkFile = downloadApk(downloadUrl, latest) ?: return
        Log.i(TAG, "ota: download complete (${apkFile.length()} bytes), launching installer")
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
        val outFile = File(context.cacheDir, "affissia-player-$version.apk")
        var conn: HttpURLConnection? = null
        return try {
            conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 60_000
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "download HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            try { outFile.delete() } catch (e2: Exception) { /* no-op */ }
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* no-op */ }
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

    /**
     * "X.Y.Z" semver compare. Returns true when `candidate` strictly
     * higher than `current`. Non-numeric segments (e.g. "2.0.0-rc1")
     * are tolerated by stripping after the first "-".
     */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.substringBefore('-').split(".").mapNotNull { it.toIntOrNull() }
        val b = current.substringBefore('-').split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai > bi
        }
        return false
    }

    companion object {
        private const val TAG = "OtaChecker"
    }
}
