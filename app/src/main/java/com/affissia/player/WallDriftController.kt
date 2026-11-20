package com.affissia.player

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Pure synchronization math for aligned video-wall startup and continuous
 * drift correction.
 *
 * Sign convention (matches `static/js/display.js`):
 *   targetPositionMs(t) = positiveModulo((t - startAtMs) + syncOffsetMs, durationMs)
 *   driftMs = signedDriftMs(targetPositionMs, currentPositionMs, durationMs)
 *   - Positive `syncOffsetMs` advances this screen's target (compensates for a
 *     display panel with higher input/frame latency).
 *   - Positive `driftMs` means the player is BEHIND target -> speed > 1.0f.
 *   - Negative `driftMs` means the player is AHEAD of target -> speed < 1.0f.
 */
object WallDriftController {
    data class StartPlan(
        val positionMs: Long,
        val delayMs: Long,
        val isScheduledFutureStart: Boolean,
    )

    sealed class Correction {
        data class Rate(val speed: Float, val driftMs: Long) : Correction()
        data class HardSeek(val targetMs: Long, val driftMs: Long) : Correction()
    }

    /**
     * Computes initial seek position and start delay for a video wall loop.
     *
     * When `startAtMs` (adjusted for `syncOffsetMs`) is still in the future,
     * the player holds at frame `0` (zero decoder-flushing seek right before
     * `play()`) and starts at the exact scheduled timestamp.
     *
     * When joining late (`startAtMs` already passed), schedules on the next
     * quantized server tick (`quantumMs`) after `lateJoinLeadMs` so the seek
     * finishes before playback begins.
     */
    fun computeStartPlan(
        nowServerMs: Long,
        startAtMs: Long,
        syncOffsetMs: Long,
        durationMs: Long,
        lateJoinLeadMs: Long = DEFAULT_LATE_JOIN_LEAD_MS,
        quantumMs: Long = DEFAULT_START_QUANTUM_MS,
        minFutureLeadMs: Long = DEFAULT_MIN_FUTURE_LEAD_MS,
    ): StartPlan {
        require(durationMs > 0L) { "durationMs must be positive" }

        val frameZeroServerMs = startAtMs - syncOffsetMs
        val futureDelayMs = frameZeroServerMs - nowServerMs
        if (futureDelayMs >= minFutureLeadMs) {
            return StartPlan(
                positionMs = 0L,
                delayMs = futureDelayMs,
                isScheduledFutureStart = true,
            )
        }

        val startServerMs = nextAlignedServerTime(nowServerMs + lateJoinLeadMs, quantumMs)
        val elapsedSinceAnchor = (startServerMs - startAtMs) + syncOffsetMs
        val positionMs = positiveModulo(elapsedSinceAnchor, durationMs)
        val delayMs = (startServerMs - nowServerMs).coerceAtLeast(0L)
        return StartPlan(
            positionMs = positionMs,
            delayMs = delayMs,
            isScheduledFutureStart = false,
        )
    }

    /**
     * Computes target playback position at server time [nowServerMs].
     */
    fun targetPositionMs(
        nowServerMs: Long,
        startAtMs: Long,
        syncOffsetMs: Long,
        durationMs: Long,
    ): Long {
        require(durationMs > 0L) { "durationMs must be positive" }
        return positiveModulo((nowServerMs - startAtMs) + syncOffsetMs, durationMs)
    }

    /**
     * Computes smooth playback-speed correction or a cooldown-gated hard seek.
     *
     * - `|drift| <= 15ms`: deadband (`1.0f`) — avoids micro-jitter when locked.
     * - `15ms < |drift| <= 500ms`: proportional speed nudge in `[0.97f, 1.03f]`
     *   (visually imperceptible on signage video, converges 200ms in ~7s).
     * - `|drift| > 500ms`: hard seek if outside [hardSeekCooldownMs], otherwise
     *   gentle fast-catchup speed (`0.96f` / `1.04f`) to prevent seek storms on
     *   low-end Android TV decoders.
     */
    fun computeCorrection(
        targetMs: Long,
        currentMs: Long,
        durationMs: Long,
        nowElapsedMs: Long,
        lastHardSeekElapsedMs: Long,
        deadbandMs: Long = DEFAULT_DEADBAND_MS,
        hardSeekThresholdMs: Long = DEFAULT_HARD_SEEK_THRESHOLD_MS,
        hardSeekCooldownMs: Long = DEFAULT_HARD_SEEK_COOLDOWN_MS,
    ): Correction {
        require(durationMs > 0L) { "durationMs must be positive" }
        val driftMs = signedDriftMs(targetMs, currentMs, durationMs)
        val absDrift = abs(driftMs)

        if (absDrift <= deadbandMs) {
            return Correction.Rate(1.0f, driftMs)
        }

        if (absDrift > hardSeekThresholdMs) {
            val cooldownElapsed =
                lastHardSeekElapsedMs < 0L || (nowElapsedMs - lastHardSeekElapsedMs) >= hardSeekCooldownMs
            if (cooldownElapsed) {
                return Correction.HardSeek(targetMs = targetMs, driftMs = driftMs)
            }
            val catchupSpeed = if (driftMs > 0L) 1.04f else 0.96f
            return Correction.Rate(catchupSpeed, driftMs)
        }

        // Smooth proportional rate nudge clamped to [0.97f, 1.03f], with a
        // small floor (±0.005f) outside the deadband so 20–40ms drifts close
        // reliably instead of stalling.
        val rawMagnitude = (absDrift.toFloat() / PROPORTIONAL_DIVISOR_MS)
            .coerceIn(MIN_NUDGE_DELTA, MAX_NUDGE_DELTA)
        val speed = if (driftMs > 0L) 1.0f + rawMagnitude else 1.0f - rawMagnitude
        return Correction.Rate(speed, driftMs)
    }

    fun nextAlignedServerTime(minimumServerMs: Long, quantumMs: Long): Long {
        require(quantumMs > 0L) { "quantumMs must be positive" }
        return (ceil(minimumServerMs.toDouble() / quantumMs.toDouble()) * quantumMs).toLong()
    }

    fun positiveModulo(value: Long, modulus: Long): Long {
        val remainder = value % modulus
        return if (remainder < 0L) remainder + modulus else remainder
    }

    /**
     * Shortest signed distance from [currentMs] to [targetMs] on a cyclic
     * timeline of length [durationMs], in `[-durationMs/2, +durationMs/2]`.
     */
    fun signedDriftMs(targetMs: Long, currentMs: Long, durationMs: Long): Long {
        var drift = targetMs - currentMs
        val half = durationMs / 2L
        if (drift > half) {
            drift -= durationMs
        } else if (drift < -half) {
            drift += durationMs
        }
        return drift
    }

    const val DEFAULT_DEADBAND_MS = 15L
    const val DEFAULT_HARD_SEEK_THRESHOLD_MS = 500L
    const val DEFAULT_HARD_SEEK_COOLDOWN_MS = 3_000L
    const val DEFAULT_LATE_JOIN_LEAD_MS = 800L
    const val DEFAULT_START_QUANTUM_MS = 250L
    const val DEFAULT_MIN_FUTURE_LEAD_MS = 120L
    private const val PROPORTIONAL_DIVISOR_MS = 6_000f
    private const val MIN_NUDGE_DELTA = 0.005f
    private const val MAX_NUDGE_DELTA = 0.030f
}
