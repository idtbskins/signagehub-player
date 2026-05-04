package com.affissia.player

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class VideoWallCacheStore(context: Context) {
    private val rootDir: File = File(context.applicationContext.cacheDir, CACHE_SUBDIR)

    fun readyStatusFor(assignment: VideoWallProAssignment): VideoWallReadyStatus {
        val target = cachedFileFor(assignment)
        if (!target.exists()) {
            return VideoWallReadyStatus.notReady(assignment, "cache_miss")
        }
        if (!target.isFile || !target.canRead()) {
            safeDelete(target)
            return VideoWallReadyStatus.notReady(assignment, "cache_unreadable")
        }

        val cachedSha = try {
            sha256Of(target)
        } catch (e: Exception) {
            safeDelete(target)
            return VideoWallReadyStatus.notReady(assignment, "cache_hash_failed")
        }
        if (!cachedSha.equals(assignment.sha256, ignoreCase = true)) {
            safeDelete(target)
            return VideoWallReadyStatus.notReady(assignment, "cache_sha256_mismatch")
        }
        return VideoWallReadyStatus.ready(assignment, target.absolutePath)
    }

    /**
     * Performs network and disk IO on the caller's thread. This class is
     * deliberately not wired into Activity playback; callers can decide
     * whether and when to prefetch.
     */
    fun downloadToCache(
        assignment: VideoWallProAssignment,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): VideoWallReadyStatus {
        readyStatusFor(assignment).takeIf { it.isReady }?.let { return it }
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            return VideoWallReadyStatus.notReady(assignment, "cache_dir_unavailable")
        }

        val target = cachedFileFor(assignment)
        val tmp = File.createTempFile("${assignment.sha256}.", ".tmp", rootDir)
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(assignment.assetUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                doInput = true
                setRequestProperty("Accept", "*/*")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                safeDelete(tmp)
                return VideoWallReadyStatus.notReady(assignment, "download_http_$code")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tmp).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
                output.fd.sync()
            }

            val downloadedSha = digest.hexDigest()
            if (!downloadedSha.equals(assignment.sha256, ignoreCase = true)) {
                safeDelete(tmp)
                safeDelete(target)
                return VideoWallReadyStatus.notReady(assignment, "download_sha256_mismatch")
            }

            safeDelete(target)
            if (!tmp.renameTo(target)) {
                safeDelete(tmp)
                return VideoWallReadyStatus.notReady(assignment, "cache_rename_failed")
            }
            VideoWallReadyStatus.ready(assignment, target.absolutePath)
        } catch (e: Exception) {
            safeDelete(tmp)
            VideoWallReadyStatus.notReady(assignment, "download_failed")
        } finally {
            try { connection?.disconnect() } catch (e: Exception) { /* no-op */ }
        }
    }

    fun cachedFileFor(assignment: VideoWallProAssignment): File {
        return File(rootDir, "${assignment.sha256.lowercase(Locale.US)}.asset")
    }

    fun clear(assignment: VideoWallProAssignment): Boolean {
        return safeDelete(cachedFileFor(assignment))
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.hexDigest()
    }

    private fun MessageDigest.hexDigest(): String {
        return digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }

    private fun safeDelete(file: File): Boolean {
        return try {
            !file.exists() || file.delete()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val CACHE_SUBDIR = "video_wall_pro"
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000
    }
}
