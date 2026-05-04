package com.affissia.player

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DisplayAssignmentClient {
    data class Crop(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )

    data class VideoAssignment(
        val signature: String,
        val url: String,
        val anchorAtMs: Long,
        val serverNowAtMs: Long,
        val crop: Crop,
    )

    fun fetchVideoWallAssignment(
        serverUrl: String,
        screenId: String,
        token: String,
    ): VideoAssignment? {
        val base = serverUrl.trimEnd('/')
        if (base.isBlank() || screenId.isBlank() || token.isBlank()) return null
        val target = try { URL("$base/display/$screenId/current") } catch (_: Exception) {
            return null
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2_000
                readTimeout = 2_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseVideoWallAssignment(base, body)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    fun parseVideoWallAssignment(baseUrl: String, body: String): VideoAssignment? {
        val root = try { JSONObject(body) } catch (_: Exception) {
            return null
        }
        val framing = root.optJSONObject("framing") ?: return null
        if (framing.optString("fit_mode") != "cover-custom") return null
        val cropJson = framing.optJSONObject("crop") ?: return null
        val crop = Crop(
            x = cropJson.optDouble("x", 0.0).toFloat().coerceIn(0f, 0.999f),
            y = cropJson.optDouble("y", 0.0).toFloat().coerceIn(0f, 0.999f),
            w = cropJson.optDouble("w", 1.0).toFloat().coerceIn(0.001f, 1f),
            h = cropJson.optDouble("h", 1.0).toFloat().coerceIn(0.001f, 1f),
        )
        val serverNowAtMs = parseIsoMillis(root.optString("server_now_at", "")) ?: return null
        val mode = root.optString("mode")
        if (mode == "asset") {
            val mime = root.optString("mime_type", "")
            if (!mime.startsWith("video/")) return null
            val assetUrl = absoluteUrl(baseUrl, root.optString("asset_url", "")) ?: return null
            val anchorAtMs = parseIsoMillis(root.optString("asset_anchor_at", "")) ?: return null
            val version = root.optInt("version", 0)
            val assetId = root.optString("asset_id", "")
            return VideoAssignment(
                signature = "asset:$version:$assetId:${root.optString("asset_anchor_at", "")}:$assetUrl:$crop",
                url = assetUrl,
                anchorAtMs = anchorAtMs,
                serverNowAtMs = serverNowAtMs,
                crop = crop,
            )
        }

        if (mode == "playlist") {
            val items = root.optJSONArray("playlist_items") ?: return null
            if (items.length() == 0) return null
            val defaultDuration = root.optLong("default_duration_ms", 10_000L).coerceAtLeast(1L)
            val playlistAnchorAtMs = parseIsoMillis(root.optString("playlist_anchor_at", "")) ?: return null
            val totalDuration = (0 until items.length()).sumOf { index ->
                items.optJSONObject(index)?.optLong("duration_ms", defaultDuration)?.coerceAtLeast(1L)
                    ?: defaultDuration
            }
            if (totalDuration <= 0L) return null
            val elapsed = floorMod(serverNowAtMs - playlistAnchorAtMs, totalDuration)
            val cycleAnchorAtMs = serverNowAtMs - elapsed
            var cursor = 0L
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val duration = item.optLong("duration_ms", defaultDuration).coerceAtLeast(1L)
                val next = cursor + duration
                if (elapsed < next) {
                    val mime = item.optString("mime_type", "")
                    if (!mime.startsWith("video/")) return null
                    val itemUrl = absoluteUrl(baseUrl, item.optString("asset_url", "")) ?: return null
                    val version = root.optInt("version", 0)
                    val playlistId = root.optString("playlist_id", "")
                    return VideoAssignment(
                        signature = "playlist:$version:$playlistId:$index:${root.optString("playlist_anchor_at", "")}:$itemUrl:$crop",
                        url = itemUrl,
                        anchorAtMs = cycleAnchorAtMs + cursor,
                        serverNowAtMs = serverNowAtMs,
                        crop = crop,
                    )
                }
                cursor = next
            }
        }

        return null
    }

    private fun absoluteUrl(baseUrl: String, raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (!raw.startsWith("/")) return "$baseUrl/$raw"
        return "$baseUrl$raw"
    }

    private fun parseIsoMillis(raw: String): Long? {
        val normalized = normalizeUtcTimestamp(raw) ?: return null
        val patterns = if (normalized.contains('.')) {
            listOf("yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss")
        } else {
            listOf("yyyy-MM-dd HH:mm:ss")
        }
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(normalized)?.time
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun normalizeUtcTimestamp(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null
        value = value.replace('T', ' ')
        value = value.removeSuffix("Z")
        value = value.replace(Regex("""[+-]00:?00$"""), "")
        val fraction = Regex("""\.(\d{1,9})$""").find(value)
        if (fraction != null) {
            val millis = fraction.groupValues[1].padEnd(3, '0').take(3)
            value = value.substring(0, fraction.range.first) + ".$millis"
        }
        return value
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val result = value % divisor
        return if (result < 0) result + divisor else result
    }
}
