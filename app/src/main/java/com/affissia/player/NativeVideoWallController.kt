package com.affissia.player

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class NativeVideoWallController(
    context: Context,
    private val playerView: PlayerView,
    private val configStore: ConfigStore,
    private val deviceIdentity: DeviceIdentity,
    private val secretStore: SecretStore,
    private val onNativeActiveChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val cacheStore = VideoWallCacheStore(appContext)
    private val clockSync = WallClockSync()
    private val lanWallSync = LanWallSyncManager()
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var activeMediaSignature: String? = null
    private var downloadInFlightSignature: String? = null
    private var activeAssignment: VideoWallProAssignment? = null
    private var playerPrepared = false
    private var scheduledGoKey: String? = null
    private var readyReportedJobKey: String? = null
    private var readyReportInFlight = false
    private var lastHardSeekElapsedMs: Long = -1L
    private var lastAppliedSpeed: Float = 1.0f
    private var lastMediaErrorSignature: String? = null
    private var pendingStartRunnable: Runnable? = null
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
            lastMediaErrorSignature = null
            stop()
            return false
        }

        refreshClockSync(assignment)

        val signature = assignment.mediaSignature
        if (signature == lastMediaErrorSignature) {
            Log.w(TAG, "skipping native wall after fatal playback error for signature=$signature")
            return false
        }

        if (signature == activeMediaSignature && (player != null || downloadInFlightSignature == signature)) {
            activeAssignment = assignment
            val exoPlayer = player
            if (exoPlayer == null) {
                Log.d(
                    TAG,
                    "native wall download still in flight screen=${assignment.screenId} state=${assignment.syncState}",
                )
                return true
            }
            if (assignment.isStaging) {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                }
                if (playerPrepared) {
                    reportSliceReadyIfNeeded(assignment)
                }
            } else if (playerPrepared && assignment.startAtMillis != null) {
                val goKey = buildGoKey(assignment)
                if (scheduledGoKey != goKey) {
                    scheduleAlignedStart(exoPlayer, assignment)
                }
            }
            Log.d(
                TAG,
                "native wall refreshed screen=${assignment.screenId} state=${assignment.syncState} tileReady=${assignment.tileReady} offset=${clockSync.offsetMs}ms rtt=${clockSync.bestRttMs}ms",
            )
            return true
        }

        // New media assignment (or initial start)
        cancelScheduledStart()
        handler.removeCallbacks(syncRunnable)
        lanWallSync.stop()
        player?.release()
        player = null
        playerView.player = null

        activeMediaSignature = signature
        downloadInFlightSignature = signature
        activeAssignment = assignment
        playerPrepared = false
        scheduledGoKey = null
        readyReportedJobKey = null
        readyReportInFlight = false
        lastHardSeekElapsedMs = -1L
        lastAppliedSpeed = 1.0f

        Log.d(
            TAG,
            "native wall assignment screen=${assignment.screenId} state=${assignment.syncState} tileReady=${assignment.tileReady} rot=${assignment.rotationDeg} syncOffset=${assignment.syncOffsetMs}ms job=${assignment.sliceJobId ?: "-"}",
        )
        onNativeActiveChanged(true)

        Thread {
            val status = cacheStore.downloadToCache(assignment)
            handler.post {
                if (downloadInFlightSignature == signature) {
                    downloadInFlightSignature = null
                }
                if (released || activeMediaSignature != signature) {
                    return@post
                }
                if (!status.isReady || status.cachePath.isNullOrBlank()) {
                    Log.w(TAG, "native wall cache not ready: ${status.reason}")
                    stop()
                    return@post
                }
                Log.d(TAG, "native wall cache ready path=${status.cachePath}")
                val freshest = activeAssignment ?: assignment
                prepareCachedVideo(freshest, File(status.cachePath))
            }
        }.apply {
            isDaemon = true
            name = "native-video-wall-cache"
            start()
        }
        return true
    }

    fun stop() {
        cancelScheduledStart()
        activeMediaSignature = null
        downloadInFlightSignature = null
        activeAssignment = null
        playerPrepared = false
        scheduledGoKey = null
        readyReportedJobKey = null
        readyReportInFlight = false
        lastHardSeekElapsedMs = -1L
        lastAppliedSpeed = 1.0f
        handler.removeCallbacks(syncRunnable)
        lanWallSync.stop()
        player?.release()
        player = null
        playerView.player = null
        resetSurfaceTransform()
        onNativeActiveChanged(false)
    }

    fun release() {
        released = true
        stop()
    }

    private fun prepareCachedVideo(assignment: VideoWallProAssignment, file: File) {
        cancelScheduledStart()
        handler.removeCallbacks(syncRunnable)
        lanWallSync.stop()
        player?.release()
        playerPrepared = false
        scheduledGoKey = null
        lastHardSeekElapsedMs = -1L
        lastAppliedSpeed = 1.0f
        resetSurfaceTransform()

        playerView.resizeMode = if (assignment.tileReady && assignment.rotationDeg == 0) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

        val mediaSignature = assignment.mediaSignature
        val nextPlayer = ExoPlayer.Builder(appContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d(TAG, "native wall playback state=$playbackState duration=${this@apply.duration}")
                    if (playbackState == Player.STATE_READY && !playerPrepared) {
                        playerPrepared = true
                        val current = activeAssignment ?: assignment
                        if (activeMediaSignature != mediaSignature || player !== this@apply) {
                            return
                        }
                        if (current.isStaging) {
                            // Hold cleanly at frame 0 and report READY to the server.
                            playWhenReady = false
                            if (currentPosition > 50L) {
                                seekTo(0L)
                            }
                            reportSliceReadyIfNeeded(current)
                        } else if (current.startAtMillis != null) {
                            scheduleAlignedStart(this@apply, current)
                        }
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Log.d(TAG, "native wall video size=${videoSize.width}x${videoSize.height}")
                    applySurfaceTransform(activeAssignment ?: assignment)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "native wall ExoPlayer error; falling back to WebView", error)
                    lastMediaErrorSignature = mediaSignature
                    val failedAssignment = activeAssignment ?: assignment
                    Thread {
                        cacheStore.clear(failedAssignment)
                    }.apply {
                        isDaemon = true
                        name = "native-wall-cache-clear"
                        start()
                    }
                    stop()
                }
            })
            prepare()
        }
        player = nextPlayer
        playerView.player = nextPlayer
        onNativeActiveChanged(true)
    }

    private fun reportSliceReadyIfNeeded(assignment: VideoWallProAssignment) {
        val jobId = assignment.sliceJobId?.takeIf { it.isNotBlank() } ?: return
        val jobKey = "$jobId:${assignment.sha256}"
        if (readyReportedJobKey == jobKey || readyReportInFlight) {
            return
        }
        val serverUrl = configStore.serverUrl.trim()
        if (serverUrl.isBlank()) return

        readyReportInFlight = true
        val mediaSignature = assignment.mediaSignature
        val deviceId = deviceIdentity.deviceId
        val deviceSecret = secretStore.secret

        Thread {
            val result = SliceReadyClient.reportReady(
                serverUrl = serverUrl,
                deviceId = deviceId,
                deviceSecret = deviceSecret,
                jobId = jobId,
                sha256 = assignment.sha256,
            )
            handler.post {
                readyReportInFlight = false
                if (released || activeMediaSignature != mediaSignature) {
                    return@post
                }
                if (result.ok) {
                    readyReportedJobKey = jobKey
                    Log.i(
                        TAG,
                        "slice-ready ack job=$jobId ready=${result.readyCount}/${result.totalScreens} startAt=${result.startAtMillis}",
                    )
                    val immediateStartAt = result.startAtMillis
                    val exoPlayer = player
                    if (immediateStartAt != null && exoPlayer != null && playerPrepared) {
                        val upgraded = (activeAssignment ?: assignment).copy(
                            syncState = VideoWallProAssignment.SYNC_STATE_GO,
                            startAtMillis = immediateStartAt,
                        )
                        activeAssignment = upgraded
                        scheduleAlignedStart(exoPlayer, upgraded)
                    }
                } else {
                    Log.w(TAG, "slice-ready failed job=$jobId code=${result.httpCode} err=${result.error}")
                }
            }
        }.apply {
            isDaemon = true
            name = "native-wall-slice-ready"
            start()
        }
    }

    private fun scheduleAlignedStart(exoPlayer: ExoPlayer, assignment: VideoWallProAssignment) {
        val signature = assignment.mediaSignature
        val startAtMs = assignment.startAtMillis ?: return
        val goKey = buildGoKey(assignment)
        if (scheduledGoKey == goKey || activeMediaSignature != signature || player !== exoPlayer) {
            return
        }

        val durationMs = exoPlayer.duration
        if (durationMs <= 0L || durationMs == C.TIME_UNSET) {
            handler.postDelayed({
                if (!released && activeMediaSignature == signature && player === exoPlayer) {
                    scheduleAlignedStart(exoPlayer, activeAssignment ?: assignment)
                }
            }, UNKNOWN_DURATION_RETRY_MS)
            return
        }

        cancelScheduledStart()
        scheduledGoKey = goKey

        val plan = WallDriftController.computeStartPlan(
            nowServerMs = nowServerMs(),
            startAtMs = startAtMs,
            syncOffsetMs = assignment.syncOffsetMs,
            durationMs = durationMs,
        )

        // Avoid redundant decoder-flushing seek when already parked at frame 0.
        if (plan.positionMs > 0L || exoPlayer.currentPosition > 50L) {
            exoPlayer.seekTo(plan.positionMs)
        }
        applyPlaybackSpeed(exoPlayer, 1.0f)

        Log.i(
            TAG,
            "native wall scheduled start future=${plan.isScheduledFutureStart} delay=${plan.delayMs}ms pos=${plan.positionMs}ms dur=${durationMs}ms syncOffset=${assignment.syncOffsetMs}ms",
        )

        val runnable = Runnable {
            pendingStartRunnable = null
            if (!released && activeMediaSignature == signature && player === exoPlayer) {
                exoPlayer.play()
                lastPlaybackSnapshot = LanWallSyncManager.LocalPlaybackSnapshot(
                    positionMs = plan.positionMs,
                    durationMs = durationMs,
                    capturedElapsedMs = SystemClock.elapsedRealtime(),
                    isPlaying = true,
                )
                startLanSync(assignment, exoPlayer)
                startSyncLoop()
            }
        }
        pendingStartRunnable = runnable
        handler.postDelayed(runnable, plan.delayMs)
    }

    private fun startLanSync(assignment: VideoWallProAssignment, exoPlayer: ExoPlayer) {
        lanWallSync.start(
            wallKey = assignment.wallSyncKey,
            screenId = assignment.screenId,
            syncOffsetMs = assignment.syncOffsetMs,
            snapshotProvider = {
                if (released || player !== exoPlayer) {
                    null
                } else {
                    // Snapshot ExoPlayer position safely on the main thread when needed,
                    // or read cached volatile state updated on the main sync loop.
                    lastPlaybackSnapshot
                }
            },
        )
    }

    @Volatile
    private var lastPlaybackSnapshot: LanWallSyncManager.LocalPlaybackSnapshot? = null

    private fun startSyncLoop() {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, SYNC_INTERVAL_MS)
    }

    private fun syncPlaybackPosition() {
        val assignment = activeAssignment ?: return
        val exoPlayer = player ?: return
        if (assignment.isStaging) return
        val startAtMs = assignment.startAtMillis ?: return

        val durationMs = exoPlayer.duration
        if (durationMs <= 0L || durationMs == C.TIME_UNSET || !exoPlayer.isPlaying) {
            lastPlaybackSnapshot = null
            return
        }

        val currentPositionMs = exoPlayer.currentPosition
        val nowElapsedMs = SystemClock.elapsedRealtime()
        lastPlaybackSnapshot = LanWallSyncManager.LocalPlaybackSnapshot(
            positionMs = currentPositionMs,
            durationMs = durationMs,
            capturedElapsedMs = nowElapsedMs,
            isPlaying = true,
        )

        val lanTargetMs = lanWallSync.resolveSlaveTargetPositionMs(durationMs)
        val targetMs = lanTargetMs ?: WallDriftController.targetPositionMs(
            nowServerMs = nowServerMs(),
            startAtMs = startAtMs,
            syncOffsetMs = assignment.syncOffsetMs,
            durationMs = durationMs,
        )

        when (
            val correction = WallDriftController.computeCorrection(
                targetMs = targetMs,
                currentMs = currentPositionMs,
                durationMs = durationMs,
                nowElapsedMs = nowElapsedMs,
                lastHardSeekElapsedMs = lastHardSeekElapsedMs,
            )
        ) {
            is WallDriftController.Correction.HardSeek -> {
                lastHardSeekElapsedMs = nowElapsedMs
                Log.d(
                    TAG,
                    "native wall hard sync source=${if (lanTargetMs != null) "lan" else "server"} drift=${correction.driftMs}ms target=${correction.targetMs} current=$currentPositionMs",
                )
                exoPlayer.seekTo(correction.targetMs)
                applyPlaybackSpeed(exoPlayer, 1.0f)
            }

            is WallDriftController.Correction.Rate -> {
                applyPlaybackSpeed(exoPlayer, correction.speed)
            }
        }
    }

    private fun applyPlaybackSpeed(exoPlayer: ExoPlayer, speed: Float) {
        if (abs(speed - lastAppliedSpeed) > SPEED_CHANGE_EPSILON) {
            exoPlayer.playbackParameters = PlaybackParameters(speed)
            lastAppliedSpeed = speed
        }
    }

    private fun applySurfaceTransform(assignment: VideoWallProAssignment) {
        playerView.post {
            val textureView = findTextureView(playerView) ?: return@post
            val viewWidth = textureView.width.toFloat().takeIf { it > 0f } ?: return@post
            val viewHeight = textureView.height.toFloat().takeIf { it > 0f } ?: return@post

            if (assignment.tileReady) {
                // Server-sliced 1:1 tile: NEVER re-apply normalized crop.
                textureView.pivotX = 0f
                textureView.pivotY = 0f
                textureView.scaleX = 1f
                textureView.scaleY = 1f
                textureView.translationX = 0f
                textureView.translationY = 0f

                val matrix = Matrix()
                when (assignment.rotationDeg) {
                    180 -> {
                        matrix.postRotate(180f, viewWidth / 2f, viewHeight / 2f)
                    }
                    90, 270 -> {
                        matrix.postTranslate(-viewWidth / 2f, -viewHeight / 2f)
                        matrix.postRotate(assignment.rotationDeg.toFloat())
                        matrix.postScale(viewWidth / viewHeight, viewHeight / viewWidth)
                        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)
                    }
                }
                textureView.setTransform(matrix)
                Log.d(
                    TAG,
                    "native wall tile-ready transform applied view=${viewWidth}x${viewHeight} rot=${assignment.rotationDeg}",
                )
            } else {
                // Legacy unsliced video fallback: apply normalized wall crop.
                val crop = assignment.crop
                textureView.setTransform(Matrix())
                textureView.pivotX = 0f
                textureView.pivotY = 0f
                textureView.scaleX = 1f / crop.w
                textureView.scaleY = 1f / crop.h
                textureView.translationX = -(crop.x / crop.w) * viewWidth
                textureView.translationY = -(crop.y / crop.h) * viewHeight
                Log.d(TAG, "native wall legacy crop applied view=${viewWidth}x${viewHeight} crop=$crop")
            }
        }
    }

    private fun resetSurfaceTransform() {
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

    private fun cancelScheduledStart() {
        pendingStartRunnable?.let(handler::removeCallbacks)
        pendingStartRunnable = null
    }

    internal fun parseNativeAssignment(playerConfigJson: String?): VideoWallProAssignment? {
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
                clientClockMidpointElapsedMillis = nativeConfig.optLongOrNull("client_clock_midpoint_elapsed_ms"),
                heartbeatRttMs = nativeConfig.optLongOrNull("heartbeat_rtt_ms"),
            )
            if (parsed.serverNowMillis == null) {
                Log.w(TAG, "native wall config missing server_now")
                return null
            }
            if (parsed.isGo && parsed.startAtMillis == null) {
                Log.w(TAG, "native wall go config missing start_at")
                return null
            }
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "invalid native wall config", e)
            null
        }
    }

    private fun refreshClockSync(assignment: VideoWallProAssignment) {
        val serverNow = assignment.serverNowMillis ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        val midpointElapsed = assignment.clientClockMidpointElapsedMillis
            ?: assignment.clientClockMidpointMillis?.let { wallMidpoint ->
                nowElapsed - (System.currentTimeMillis() - wallMidpoint)
            }
            ?: nowElapsed
        val rtt = assignment.heartbeatRttMs ?: 100L
        clockSync.recordSample(
            serverNowMs = serverNow,
            localMidpointElapsedMs = midpointElapsed,
            rttMs = rtt,
        )
    }

    private fun nowServerMs(): Long {
        return clockSync.nowServerMs(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            fallbackWallClockMs = System.currentTimeMillis(),
        )
    }

    private fun buildGoKey(assignment: VideoWallProAssignment): String {
        return "${assignment.mediaSignature}:${assignment.startAtMillis}:${assignment.syncOffsetMs}"
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

    companion object {
        private const val TAG = "NativeVideoWall"
        private const val UNKNOWN_DURATION_RETRY_MS = 100L
        private const val SYNC_INTERVAL_MS = 350L
        private const val SPEED_CHANGE_EPSILON = 0.0005f
    }
}
