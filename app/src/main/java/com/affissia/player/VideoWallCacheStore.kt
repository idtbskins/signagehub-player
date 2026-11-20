package com.affissia.player

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class VideoWallCacheStore internal constructor(
    private val rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_CACHE_FILES,
) {
    constructor(context: Context) : this(
        rootDir = File(context.applicationContext.cacheDir, CACHE_SUBDIR),
    )

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
        // Touch mtime so actively played slices stay protected by LRU eviction.
        try {
            target.setLastModified(System.currentTimeMillis())
        } catch (_: Exception) {
            // no-op
        }
        return VideoWallReadyStatus.ready(assignment, target.absolutePath)
    }

    /**
     * Performs network and disk IO on the caller's thread. Downloads atomically
     * to a `.tmp` file, verifies SHA-256 on the fly, renames into place, and
     * enforces LRU disk budget so low-storage TV boxes never fill up.
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
            try {
                target.setLastModified(System.currentTimeMillis())
            } catch (_: Exception) {
                // no-op
            }
            pruneCache(protectedFile = target)
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

    /**
     * Evicts oldest cached `.asset` files when total disk usage exceeds
     * [maxBytes] or file count exceeds [maxFiles], and removes stale `.tmp`
     * files left behind by interrupted downloads.
     */
    fun pruneCache(protectedFile: File? = null, nowMs: Long = System.currentTimeMillis()): Int {
        if (!rootDir.exists() || !rootDir.isDirectory) return 0
        val children = rootDir.listFiles() ?: return 0
        var deletedCount = 0
        val protectedPath = protectedFile?.absolutePath

        val assetFiles = mutableListOf<File>()
        for (file in children) {
            if (!file.isFile) continue
            if (file.name.endsWith(".tmp")) {
                if (nowMs - file.lastModified() > STALE_TMP_AGE_MS) {
                    if (safeDelete(file)) deletedCount++
                }
                continue
            }
            if (file.name.endsWith(".asset")) {
                assetFiles += file
            }
        }

        assetFiles.sortBy { it.lastModified() }
        var totalBytes = assetFiles.sumOf { it.length() }
        var remainingFiles = assetFiles.size

        for (file in assetFiles) {
            if (totalBytes <= maxBytes && remainingFiles <= maxFiles) {
                break
            }
            if (protectedPath != null && file.absolutePath == protectedPath) {
                continue
            }
            val size = file.length()
            if (safeDelete(file)) {
                totalBytes -= size
                remainingFiles -= 1
                deletedCount += 1
            }
        }
        return deletedCount
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
        const val DEFAULT_MAX_CACHE_BYTES = 500L * 1024L * 1024L
        const val DEFAULT_MAX_CACHE_FILES = 12
        private const val STALE_TMP_AGE_MS = 10L * 60L * 1000L
    }
}
