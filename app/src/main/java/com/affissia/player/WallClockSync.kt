package com.affissia.player

import kotlin.math.abs

/**
 * Monotonic, RTT-filtered server clock estimator (Cristian / NTP-lite).
 *
 * Uses local monotonic elapsed time (`SystemClock.elapsedRealtime()`) rather
 * than wall-clock `System.currentTimeMillis()`, so Android SNTP / timezone /
 * manual clock jumps never introduce sudden playback jumps across a video wall.
 *
 * Keeps a rolling window of recent heartbeat samples, prefers the lowest-RTT
 * cluster (minimizing asymmetric Wi-Fi jitter), and gently slews the locked
 * offset by at most [MAX_SLEW_STEP_MS] per sample unless a large clock step
 * (> [HARD_SNAP_DELTA_MS]) occurs.
 */
class WallClockSync(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val maxSampleAgeMs: Long = DEFAULT_MAX_SAMPLE_AGE_MS,
) {
    data class ClockSample(
        val offsetMs: Long,
        val rttMs: Long,
        val observedElapsedMs: Long,
    )

    private val samples = ArrayDeque<ClockSample>()
    private var lockedOffsetMs: Long = 0L
    private var locked = false
    private var lastBestRttMs: Long = Long.MAX_VALUE

    val hasLock: Boolean
        @Synchronized get() = locked

    val offsetMs: Long
        @Synchronized get() = lockedOffsetMs

    val bestRttMs: Long
        @Synchronized get() = lastBestRttMs

    /**
     * Records a new `(serverNowMs, localMidpointElapsedMs, rttMs)` sample.
     * Returns the updated estimated `serverNow - localElapsed` offset.
     */
    @Synchronized
    fun recordSample(
        serverNowMs: Long,
        localMidpointElapsedMs: Long,
        rttMs: Long,
    ): Long {
        val safeRtt = rttMs.coerceAtLeast(0L)
        pruneExpired(localMidpointElapsedMs)

        // Drop obvious network-spike outliers once we already hold low-RTT samples.
        if (samples.isNotEmpty() && safeRtt > MAX_OUTLIER_RTT_MS && lastBestRttMs < 500L) {
            return lockedOffsetMs
        }

        val sampleOffset = serverNowMs - localMidpointElapsedMs
        samples.addLast(
            ClockSample(
                offsetMs = sampleOffset,
                rttMs = safeRtt,
                observedElapsedMs = localMidpointElapsedMs,
            ),
        )
        while (samples.size > maxSamples) {
            samples.removeFirst()
        }

        val minRtt = samples.minOf { it.rttMs }
        lastBestRttMs = minRtt
        val bestCluster = samples
            .filter { it.rttMs <= minRtt + BEST_RTT_MARGIN_MS }
            .map { it.offsetMs }
            .sorted()

        val targetOffset = medianOfSorted(bestCluster)
        if (!locked) {
            lockedOffsetMs = targetOffset
            locked = true
            return lockedOffsetMs
        }

        val delta = targetOffset - lockedOffsetMs
        lockedOffsetMs = when {
            abs(delta) > HARD_SNAP_DELTA_MS -> targetOffset
            abs(delta) <= MAX_SLEW_STEP_MS -> targetOffset
            delta > 0 -> lockedOffsetMs + MAX_SLEW_STEP_MS
            else -> lockedOffsetMs - MAX_SLEW_STEP_MS
        }
        return lockedOffsetMs
    }

    @Synchronized
    fun nowServerMs(nowElapsedMs: Long, fallbackWallClockMs: Long = 0L): Long {
        return if (locked) {
            nowElapsedMs + lockedOffsetMs
        } else {
            fallbackWallClockMs
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
        locked = false
        lockedOffsetMs = 0L
        lastBestRttMs = Long.MAX_VALUE
    }

    private fun pruneExpired(nowElapsedMs: Long) {
        while (samples.isNotEmpty() && nowElapsedMs - samples.first().observedElapsedMs > maxSampleAgeMs) {
            samples.removeFirst()
        }
    }

    private fun medianOfSorted(values: List<Long>): Long {
        val n = values.size
        if (n == 0) return 0L
        val mid = n / 2
        return if (n % 2 == 1) {
            values[mid]
        } else {
            (values[mid - 1] + values[mid]) / 2L
        }
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES = 12
        const val DEFAULT_MAX_SAMPLE_AGE_MS = 120_000L
        const val BEST_RTT_MARGIN_MS = 40L
        const val MAX_OUTLIER_RTT_MS = 2_500L
        const val MAX_SLEW_STEP_MS = 6L
        const val HARD_SNAP_DELTA_MS = 300L
    }
}
