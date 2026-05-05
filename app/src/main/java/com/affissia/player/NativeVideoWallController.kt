package com.affissia.player

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

class NativeVideoWallController(
    context: Context,
    private val playerView: PlayerView,
    private val onNativeActiveChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val cacheStore = VideoWallCacheStore(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var activeSignature: String? = null
    private var activeAssignment: VideoWallProAssignment? = null
    private var serverClockOffsetMs: Long = 0L
    private var startScheduled = false
    private var released = false

    private val syncRunnable = object : Runnable {
        override fun run() {
            syncPlaybackPosition()
            if (!released && player != null && activeAssignment != null) {
                handler.postDelayed(this, SYNC_INTERVAL_MS)
            }
        }
    }

    fun applyPlayerConfig(playerConfigJson: String?): Boolean {
        val assignment = parseNativeAssignment(playerConfigJson) ?: run {
            Log.d(TAG, "native wall disabled or missing in player_config")
            stop()
            return false
        }
        refreshServerClockOffset(assignment)
        val signature = assignment.signatureWithCrop
        if (signature == activeSignature && player != null) {
            activeAssignment = assignment
            Log.d(TAG, "native wall refreshed screen=${assignment.screenId} offset=${serverClockOffsetMs}ms")
            return true
        }

        activeSignature = signature
        activeAssignment = assignment
        Log.d(
            TAG,
            "native wall assignment screen=${assignment.screenId} crop=${assignment.crop} offset=${serverClockOffsetMs}ms",
        )
        onNativeActiveChanged(true)

        Thread {
            val status = cacheStore.downloadToCache(assignment)
            handler.post {
                if (released || activeSignature != signature) {
                    return@post
                }
                if (!status.isReady || status.cachePath.isNullOrBlank()) {
                    Log.w(TAG, "native wall cache not ready: ${status.reason}")
                    stop()
                    return@post
                }
                Log.d(TAG, "native wall cache ready path=${status.cachePath}")
                startCachedVideo(assignment, File(status.cachePath))
            }
        }.apply {
            isDaemon = true
            name = "native-video-wall-cache"
            start()
        }
        return true
    }

    fun stop() {
        activeSignature = null
        activeAssignment = null
        startScheduled = false
        handler.removeCallbacks(syncRunnable)
        player?.release()
        player = null
        playerView.player = null
        resetCropTransform()
        onNativeActiveChanged(false)
    }

    fun release() {
        released = true
        stop()
    }

    private fun startCachedVideo(assignment: VideoWallProAssignment, file: File) {
        handler.removeCallbacks(syncRunnable)
        player?.release()
        startScheduled = false
        resetCropTransform()

        val nextPlayer = ExoPlayer.Builder(appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d(TAG, "native wall playback state=$playbackState duration=${this@apply.duration}")
                    if (playbackState == Player.STATE_READY) {
                        scheduleAlignedStart(this@apply, assignment)
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Log.d(TAG, "native wall video size=${videoSize.width}x${videoSize.height}")
                    applyCropTransform(assignment.crop)
                }
            })
            prepare()
        }
        player = nextPlayer
        playerView.player = nextPlayer
        onNativeActiveChanged(true)
    }

    private fun scheduleAlignedStart(exoPlayer: ExoPlayer, assignment: VideoWallProAssignment) {
        val signature = assignment.signatureWithCrop
        if (startScheduled || activeSignature != signature) {
            return
        }
        val durationMs = exoPlayer.duration
        if (durationMs <= 0 || durationMs == androidx.media3.common.C.TIME_UNSET) {
            handler.postDelayed({
                if (!startScheduled && activeSignature == signature && player === exoPlayer) {
                    scheduleAlignedStart(exoPlayer, assignment)
                }
            }, UNKNOWN_DURATION_RETRY_MS)
            return
        }

        startScheduled = true
        val startServerMs = nextAlignedServerTime(nowServerMs() + START_LEAD_MS, START_QUANTUM_MS)
        val positionMs = positiveModulo(startServerMs - anchorMs(assignment), durationMs)
        exoPlayer.seekTo(positionMs)
        exoPlayer.playbackParameters = PlaybackParameters(1f)

        val delayMs = (startServerMs - nowServerMs()).coerceAtLeast(0L)
        Log.d(TAG, "native wall scheduled start delay=${delayMs}ms position=${positionMs}ms duration=${durationMs}ms")
        handler.postDelayed({
            if (!released && activeSignature == signature && player === exoPlayer) {
                exoPlayer.play()
                startSyncLoop()
            }
        }, delayMs)
    }

    private fun startSyncLoop() {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, SYNC_INTERVAL_MS)
    }

    private fun syncPlaybackPosition() {
        val assignment = activeAssignment ?: return
        val exoPlayer = player ?: return
        val durationMs = exoPlayer.duration
        if (durationMs <= 0 || durationMs == androidx.media3.common.C.TIME_UNSET || !exoPlayer.isPlaying) {
            return
        }
        val targetMs = positiveModulo(nowServerMs() - anchorMs(assignment), durationMs)
        val driftMs = signedDriftMs(targetMs, exoPlayer.currentPosition, durationMs)
        val absDrift = abs(driftMs)
        when {
            absDrift > HARD_SEEK_DRIFT_MS -> {
                Log.d(TAG, "native wall hard sync drift=${driftMs}ms target=$targetMs current=${exoPlayer.currentPosition}")
                exoPlayer.seekTo(targetMs)
                exoPlayer.playbackParameters = PlaybackParameters(1f)
            }
            absDrift > RATE_DRIFT_MS -> {
                val speed = if (driftMs > 0) 1f + RATE_NUDGE else 1f - RATE_NUDGE
                exoPlayer.playbackParameters = PlaybackParameters(speed)
            }
            else -> exoPlayer.playbackParameters = PlaybackParameters(1f)
        }
    }

    private fun applyCropTransform(crop: VideoWallCrop) {
        playerView.post {
            val textureView = findTextureView(playerView) ?: return@post
            val viewWidth = textureView.width.toFloat().takeIf { it > 0f } ?: return@post
            val viewHeight = textureView.height.toFloat().takeIf { it > 0f } ?: return@post
            textureView.setTransform(Matrix())
            textureView.pivotX = 0f
            textureView.pivotY = 0f
            textureView.scaleX = 1f / crop.w
            textureView.scaleY = 1f / crop.h
            textureView.translationX = -(crop.x / crop.w) * viewWidth
            textureView.translationY = -(crop.y / crop.h) * viewHeight
            Log.d(TAG, "native wall crop applied view=${viewWidth}x${viewHeight} crop=$crop")
        }
    }

    private fun resetCropTransform() {
        findTextureView(playerView)?.let { textureView ->
            textureView.setTransform(Matrix())
            textureView.pivotX = 0f
            textureView.pivotY = 0f
            textureView.scaleX = 1f
            textureView.scaleY = 1f
            textureView.translationX = 0f
            textureView.translationY = 0f
        }
    }

    private fun findTextureView(view: View): TextureView? {
        if (view is TextureView) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextureView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun parseNativeAssignment(playerConfigJson: String?): VideoWallProAssignment? {
        if (playerConfigJson.isNullOrBlank()) {
            return null
        }
        return try {
            val config = JSONObject(playerConfigJson)
            val nativeConfig = config.optJSONObject("native_video_wall") ?: return null
            if (!nativeConfig.optBoolean("enabled", false)) {
                return null
            }
            val assignment = nativeConfig.optJSONObject("assignment") ?: return null
            val parsed = VideoWallProManifestParser.parse(assignment.toString()).copy(
                clientClockMidpointMillis = nativeConfig.optLongOrNull("client_clock_midpoint_ms"),
            )
            if (parsed.startAtMillis == null || parsed.serverNowMillis == null) {
                Log.w(TAG, "native wall config missing start_at or server_now")
                return null
            }
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "invalid native wall config", e)
            null
        }
    }

    private fun refreshServerClockOffset(assignment: VideoWallProAssignment) {
        val serverNow = assignment.serverNowMillis ?: return
        val clientAnchor = assignment.clientClockMidpointMillis ?: System.currentTimeMillis()
        serverClockOffsetMs = serverNow - clientAnchor
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun nowServerMs(): Long = System.currentTimeMillis() + serverClockOffsetMs

    private fun anchorMs(assignment: VideoWallProAssignment): Long {
        return assignment.startAtMillis ?: nowServerMs()
    }

    private fun nextAlignedServerTime(minimumServerMs: Long, quantumMs: Long): Long {
        return (ceil(minimumServerMs.toDouble() / quantumMs.toDouble()) * quantumMs).toLong()
    }

    private fun positiveModulo(value: Long, modulus: Long): Long {
        val remainder = value % modulus
        return if (remainder < 0) remainder + modulus else remainder
    }

    private fun signedDriftMs(targetMs: Long, currentMs: Long, durationMs: Long): Long {
        var drift = targetMs - currentMs
        val half = durationMs / 2
        if (drift > half) {
            drift -= durationMs
        } else if (drift < -half) {
            drift += durationMs
        }
        return drift
    }

    private val VideoWallProAssignment.signatureWithCrop: String
        get() = "$assetSignature:${crop.x}:${crop.y}:${crop.w}:${crop.h}"

    companion object {
        private const val TAG = "NativeVideoWall"
        private const val START_LEAD_MS = 900L
        private const val START_QUANTUM_MS = 250L
        private const val UNKNOWN_DURATION_RETRY_MS = 100L
        private const val SYNC_INTERVAL_MS = 500L
        private const val HARD_SEEK_DRIFT_MS = 2_000L
        private const val RATE_DRIFT_MS = 24L
        private const val RATE_NUDGE = 0.015f
    }
}
