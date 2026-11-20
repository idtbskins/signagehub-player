package com.affissia.player

import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class VideoWallProAssignment(
    val screenId: String,
    val assetUrl: String,
    val sha256: String,
    val assetId: String? = null,
    val startAtMillis: Long? = null,
    val serverNowMillis: Long? = null,
    val clientClockMidpointMillis: Long? = null,
    val clientClockMidpointElapsedMillis: Long? = null,
    val heartbeatRttMs: Long? = null,
    val tileReady: Boolean = false,
    val sliceJobId: String? = null,
    val syncState: String = SYNC_STATE_GO,
    val syncOffsetMs: Long = 0L,
    val rotationDeg: Int = 0,
    val crop: VideoWallCrop = VideoWallCrop(),
) {
    val assetSignature: String
        get() = "$sha256:${assetUrl.trim()}"

    /**
     * Identifies the local media file + visual geometry. Intentionally excludes
     * [syncState] and [startAtMillis] so transitioning from `staging` -> `go`
     * reuses the already-downloaded, pre-decoded ExoPlayer instance without
     * re-buffering or flashing black.
     */
    val mediaSignature: String
        get() = "$assetSignature:$tileReady:$rotationDeg:${crop.x}:${crop.y}:${crop.w}:${crop.h}"

    val isStaging: Boolean
        get() = syncState.equals(SYNC_STATE_STAGING, ignoreCase = true)

    val isGo: Boolean
        get() = !isStaging

    /**
     * Shared identifier across all screens participating in the same video-wall
     * playback so LAN Master/Slave beacons only synchronize peers of the same wall.
     */
    val wallSyncKey: String
        get() {
            val base = sliceJobId?.takeIf { it.isNotBlank() }
                ?: assetId?.takeIf { it.isNotBlank() }
                ?: sha256
            return "$base:${startAtMillis ?: 0L}"
        }

    companion object {
        const val SYNC_STATE_STAGING = "staging"
        const val SYNC_STATE_GO = "go"
    }
}

data class VideoWallCrop(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
) {
    val isValid: Boolean
        get() = x >= 0f && y >= 0f && w > 0f && h > 0f && x + w <= 1.0001f && y + h <= 1.0001f
}

enum class VideoWallReadyState(val wireValue: String) {
    READY("ready"),
    NOT_READY("not_ready"),
}

data class VideoWallReadyStatus(
    val state: VideoWallReadyState,
    val cachePath: String?,
    val reason: String?,
    val assetSignature: String,
) {
    val isReady: Boolean
        get() = state == VideoWallReadyState.READY

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("state", state.wireValue)
            put("ready", isReady)
            put("cache_path", cachePath ?: JSONObject.NULL)
            put("reason", reason ?: JSONObject.NULL)
            put("asset_signature", assetSignature)
        }
    }

    companion object {
        fun ready(assignment: VideoWallProAssignment, cachePath: String): VideoWallReadyStatus {
            return VideoWallReadyStatus(
                state = VideoWallReadyState.READY,
                cachePath = cachePath,
                reason = null,
                assetSignature = assignment.assetSignature,
            )
        }

        fun notReady(assignment: VideoWallProAssignment, reason: String): VideoWallReadyStatus {
            return VideoWallReadyStatus(
                state = VideoWallReadyState.NOT_READY,
                cachePath = null,
                reason = reason,
                assetSignature = assignment.assetSignature,
            )
        }
    }
}

object VideoWallProManifestParser {
    fun parse(jsonString: String): VideoWallProAssignment {
        val root = JSONObject(jsonString)
        val source = root.optJSONObject("assignment")
            ?: root.optJSONObject("manifest")
            ?: root

        val screenId = source.requiredCleanString("screen_id")
        val assetUrl = source.requiredCleanString("asset_url")
        val sha256 = source.requiredCleanString("sha256").lowercase(Locale.US)
        require(SHA256_PATTERN.matches(sha256)) { "sha256 must be 64 hex characters" }

        val rawSyncState = source.optCleanString("sync_state")
            ?: VideoWallProAssignment.SYNC_STATE_GO
        val normalizedSyncState = when (rawSyncState.lowercase(Locale.US)) {
            VideoWallProAssignment.SYNC_STATE_STAGING -> VideoWallProAssignment.SYNC_STATE_STAGING
            else -> VideoWallProAssignment.SYNC_STATE_GO
        }

        val rawRotation = source.optInt("rotation_deg", 0)
        val rotationDeg = if (rawRotation in VALID_ROTATIONS) rawRotation else 0

        return VideoWallProAssignment(
            screenId = screenId,
            assetUrl = assetUrl,
            sha256 = sha256,
            assetId = source.optCleanString("asset_id"),
            startAtMillis = source.optEpochMillis("start_at"),
            serverNowMillis = source.optEpochMillis("server_now"),
            tileReady = source.optBoolean("tile_ready", false),
            sliceJobId = source.optCleanString("slice_job_id"),
            syncState = normalizedSyncState,
            syncOffsetMs = source.optLongOrZero("sync_offset_ms"),
            rotationDeg = rotationDeg,
            crop = source.optVideoWallCrop(),
        )
    }

    /**
     * Parses epoch milliseconds or ISO-8601 timestamps (including Python
     * microsecond strings such as `2026-11-20T12:00:05.123456+00:00` or
     * SQLite-style `2026-11-20 12:00:05.123`).
     */
    fun parseTimestampMillis(raw: String): Long {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { return it }

        val normalized = normalizeIsoTimestamp(trimmed)
        for (format in TIMESTAMP_FORMATS) {
            val position = ParsePosition(0)
            val parsed = synchronized(format) {
                format.parse(normalized, position)
            }
            if (parsed != null && position.index == normalized.length) {
                return parsed.time
            }
        }
        throw IllegalArgumentException("Unsupported timestamp: $raw")
    }

    private fun normalizeIsoTimestamp(raw: String): String {
        var value = raw
        if (value.length >= 19 && value[10] == ' ') {
            value = value.substring(0, 10) + "T" + value.substring(11)
        }
        val match = FRACTIONAL_SECONDS_PATTERN.matchEntire(value)
        if (match != null) {
            val prefix = match.groupValues[1]
            val frac = match.groupValues[2]
            val tz = match.groupValues[3].ifEmpty { "Z" }
            val millis = when {
                frac.length == 3 -> frac
                frac.length > 3 -> frac.substring(0, 3)
                else -> frac.padEnd(3, '0')
            }
            return "$prefix.$millis$tz"
        }
        if (NO_TZ_DATETIME_PATTERN.matches(value)) {
            return "${value}Z"
        }
        return value
    }

    private fun JSONObject.requiredCleanString(name: String): String {
        val value = optString(name, "").trim()
        require(value.isNotBlank()) { "$name is required" }
        return value
    }

    private fun JSONObject.optCleanString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name, "").trim().takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optLongOrZero(name: String): Long {
        if (!has(name) || isNull(name)) return 0L
        return when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun JSONObject.optEpochMillis(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name) ?: return null
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().takeIf { it.isNotBlank() }?.let { parseTimestampMillis(it) }
            else -> throw IllegalArgumentException("$name must be an epoch number or ISO-8601 string")
        }
    }

    private fun JSONObject.optVideoWallCrop(): VideoWallCrop {
        val crop = optJSONObject("framing")?.optJSONObject("crop")
            ?: optJSONObject("crop")
            ?: return VideoWallCrop()
        return VideoWallCrop(
            x = crop.optDouble("x", 0.0).toFloat(),
            y = crop.optDouble("y", 0.0).toFloat(),
            w = crop.optDouble("w", 1.0).toFloat(),
            h = crop.optDouble("h", 1.0).toFloat(),
        ).takeIf { it.isValid } ?: VideoWallCrop()
    }

    private val VALID_ROTATIONS = setOf(0, 90, 180, 270)
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val FRACTIONAL_SECONDS_PATTERN =
        Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\.(\\d+)([Zz]|[+-]\\d{2}:?\\d{2})?$")
    private val NO_TZ_DATETIME_PATTERN =
        Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$")

    private val TIMESTAMP_FORMATS: List<SimpleDateFormat> = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    ).map { pattern ->
        SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
