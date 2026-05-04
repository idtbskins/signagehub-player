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
    val startAtMillis: Long? = null,
    val serverNowMillis: Long? = null,
) {
    val assetSignature: String
        get() = "$sha256:${assetUrl.trim()}"
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

        return VideoWallProAssignment(
            screenId = screenId,
            assetUrl = assetUrl,
            sha256 = sha256,
            startAtMillis = source.optEpochMillis("start_at"),
            serverNowMillis = source.optEpochMillis("server_now"),
        )
    }

    private fun JSONObject.requiredCleanString(name: String): String {
        val value = optString(name, "").trim()
        require(value.isNotBlank()) { "$name is required" }
        return value
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

    private fun parseTimestampMillis(raw: String): Long {
        raw.toLongOrNull()?.let { return it }
        for (format in TIMESTAMP_FORMATS) {
            val position = ParsePosition(0)
            val parsed = synchronized(format) {
                format.parse(raw, position)
            }
            if (parsed != null && position.index == raw.length) {
                return parsed.time
            }
        }
        throw IllegalArgumentException("Unsupported timestamp: $raw")
    }

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

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
