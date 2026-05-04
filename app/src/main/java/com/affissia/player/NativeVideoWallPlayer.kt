package com.affissia.player

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

@OptIn(UnstableApi::class)
class NativeVideoWallPlayer(
    context: Context,
    private val textureView: TextureView,
    private val webView: View,
    private val configStore: ConfigStore,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val player = ExoPlayer.Builder(appContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        setVideoTextureView(textureView)
    }
    private var running = false
    private var activeSignature = ""
    private var serverClockOffsetMs = 0L
    private var serverClockSamples = 0
    private var currentAssignment: DisplayAssignmentClient.VideoAssignment? = null
    private var startRunnable: Runnable? = null

    init {
        textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            currentAssignment?.let { applyCrop(it.crop) }
        }
    }

    fun start() {
        if (running) return
        running = true
        mainHandler.post(pollRunnable)
        mainHandler.post(syncRunnable)
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(syncRunnable)
        clearStartTimer()
        player.pause()
    }

    fun release() {
        stop()
        player.release()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            pollAssignment()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            syncPlayback(false)
            mainHandler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private fun pollAssignment() {
        val serverUrl = configStore.serverUrl
        val screenId = configStore.displayScreenId
        val token = configStore.displayToken
        if (serverUrl.isBlank() || screenId.isBlank() || token.isBlank()) {
            hideNative()
            return
        }
        Thread {
            val assignment = DisplayAssignmentClient.fetchVideoWallAssignment(
                serverUrl = serverUrl,
                screenId = screenId,
                token = token,
            )
            mainHandler.post {
                if (!running) return@post
                if (assignment == null) {
                    hideNative()
                } else {
                    showAssignment(assignment)
                }
            }
        }.apply {
            isDaemon = true
            name = "native-video-wall-poll"
            start()
        }
    }

    private fun showAssignment(assignment: DisplayAssignmentClient.VideoAssignment) {
        updateServerClock(assignment.serverNowAtMs)
        currentAssignment = assignment
        textureView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        applyCrop(assignment.crop)

        if (activeSignature != assignment.signature) {
            activeSignature = assignment.signature
            clearStartTimer()
            player.setMediaItem(MediaItem.fromUri(assignment.url))
            player.prepare()
            player.playWhenReady = false
            scheduleStart(assignment)
            return
        }

        syncPlayback(false)
    }

    private fun hideNative() {
        clearStartTimer()
        activeSignature = ""
        currentAssignment = null
        player.stop()
        textureView.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun scheduleStart(assignment: DisplayAssignmentClient.VideoAssignment) {
        val delay = assignment.anchorAtMs - serverNowMs()
        if (delay > START_TIMER_FLOOR_MS) {
            player.seekTo(0)
            startRunnable = Runnable {
                startRunnable = null
                startPlaybackNow(true)
            }
            mainHandler.postAtTime(startRunnable!!, SystemClock.uptimeMillis() + delay)
            return
        }
        startPlaybackNow(true)
    }

    private fun startPlaybackNow(force: Boolean) {
        syncPlayback(force)
        player.play()
    }

    private fun syncPlayback(force: Boolean) {
        val assignment = currentAssignment ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val elapsed = serverNowMs() - assignment.anchorAtMs
        if (elapsed < 0) {
            player.seekTo(0)
            player.playbackParameters = PlaybackParameters(1f)
            return
        }
        val target = floorMod(elapsed, duration)
        val current = player.currentPosition
        val drift = signedDrift(target, current, duration)
        val absDrift = abs(drift)
        if (force || absDrift > HARD_SEEK_DRIFT_MS) {
            player.seekTo(target)
            player.playbackParameters = PlaybackParameters(1f)
            return
        }
        if (absDrift > SOFT_DRIFT_MS) {
            player.playbackParameters = PlaybackParameters(if (drift > 0) 1.04f else 0.96f)
        } else {
            player.playbackParameters = PlaybackParameters(1f)
        }
    }

    private fun applyCrop(crop: DisplayAssignmentClient.Crop) {
        val width = textureView.width.toFloat().takeIf { it > 0f } ?: return
        val height = textureView.height.toFloat().takeIf { it > 0f } ?: return
        val matrix = Matrix().apply {
            setScale(1f / crop.w, 1f / crop.h)
            postTranslate(-(width * crop.x / crop.w), -(height * crop.y / crop.h))
        }
        textureView.setTransform(matrix)
    }

    private fun updateServerClock(serverNowAtMs: Long) {
        val sample = serverNowAtMs - System.currentTimeMillis()
        if (serverClockSamples == 0) {
            serverClockOffsetMs = sample
        } else {
            serverClockOffsetMs = ((serverClockOffsetMs * 7) + sample) / 8
        }
        serverClockSamples += 1
    }

    private fun serverNowMs(): Long {
        return System.currentTimeMillis() + serverClockOffsetMs
    }

    private fun clearStartTimer() {
        startRunnable?.let(mainHandler::removeCallbacks)
        startRunnable = null
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val result = value % divisor
        return if (result < 0) result + divisor else result
    }

    private fun signedDrift(target: Long, current: Long, duration: Long): Long {
        var drift = target - current
        val half = duration / 2
        if (drift > half) drift -= duration
        if (drift < -half) drift += duration
        return drift
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val SYNC_INTERVAL_MS = 120L
        private const val START_TIMER_FLOOR_MS = 30L
        private const val HARD_SEEK_DRIFT_MS = 25L
        private const val SOFT_DRIFT_MS = 6L
    }
}
