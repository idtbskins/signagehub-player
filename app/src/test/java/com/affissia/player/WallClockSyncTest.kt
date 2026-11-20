package com.affissia.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallClockSyncTest {
    @Test
    fun prefersLowRttClusterAndRejectsWifiSpikeOutliers() {
        val clock = WallClockSync()
        val trueOffset = 1_700_000_000_000L

        // Initial low-RTT samples establish an accurate lock.
        clock.recordSample(serverNowMs = trueOffset + 1_000L, localMidpointElapsedMs = 1_000L, rttMs = 24L)
        clock.recordSample(serverNowMs = trueOffset + 3_002L, localMidpointElapsedMs = 3_000L, rttMs = 20L)
        clock.recordSample(serverNowMs = trueOffset + 5_001L, localMidpointElapsedMs = 5_000L, rttMs = 22L)
        assertTrue(clock.hasLock)
        assertEquals(trueOffset + 1L, clock.offsetMs)

        // Sudden 900ms asymmetric Wi-Fi spike (offset appears off by +400ms):
        // excluded from best-RTT cluster (minRtt + 40ms).
        clock.recordSample(serverNowMs = trueOffset + 7_400L, localMidpointElapsedMs = 7_000L, rttMs = 900L)
        assertEquals(trueOffset + 1L, clock.offsetMs)

        // Massive 4s stall outlier: dropped completely.
        clock.recordSample(serverNowMs = trueOffset + 11_000L, localMidpointElapsedMs = 9_000L, rttMs = 4_000L)
        assertEquals(trueOffset + 1L, clock.offsetMs)
    }

    @Test
    fun slewsGraduallyOnSmallClockDrift() {
        val clock = WallClockSync()
        clock.recordSample(serverNowMs = 100_000L, localMidpointElapsedMs = 10_000L, rttMs = 20L)
        assertEquals(90_000L, clock.offsetMs)

        // All subsequent samples shift by +30ms (still within HARD_SNAP_DELTA_MS).
        // Each update slews by at most MAX_SLEW_STEP_MS (6ms).
        val afterFirst = clock.recordSample(serverNowMs = 102_030L, localMidpointElapsedMs = 12_000L, rttMs = 18L)
        assertEquals(90_006L, afterFirst)
        val afterSecond = clock.recordSample(serverNowMs = 104_030L, localMidpointElapsedMs = 14_000L, rttMs = 18L)
        assertEquals(90_012L, afterSecond)
    }

    @Test
    fun snapsImmediatelyOnLargeServerClockJump() {
        val clock = WallClockSync()
        clock.recordSample(serverNowMs = 100_000L, localMidpointElapsedMs = 10_000L, rttMs = 20L)
        // Server jumped +5 seconds; should snap immediately once new sample dominates.
        clock.recordSample(serverNowMs = 200_000L, localMidpointElapsedMs = 150_000L, rttMs = 18L)
        assertEquals(50_000L, clock.offsetMs)
    }
}
