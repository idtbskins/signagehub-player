package com.affissia.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallDriftControllerTest {
    @Test
    fun futureStartSchedulesFrameZeroWithSyncOffset() {
        val now = 10_000L
        val startAt = 12_500L
        val duration = 30_000L

        // Screen A (0ms offset): starts frame 0 in exactly 2500ms.
        val planA = WallDriftController.computeStartPlan(
            nowServerMs = now,
            startAtMs = startAt,
            syncOffsetMs = 0L,
            durationMs = duration,
        )
        assertTrue(planA.isScheduledFutureStart)
        assertEquals(0L, planA.positionMs)
        assertEquals(2_500L, planA.delayMs)

        // Screen B (+60ms panel latency compensation): starts frame 0 60ms earlier.
        val planB = WallDriftController.computeStartPlan(
            nowServerMs = now,
            startAtMs = startAt,
            syncOffsetMs = 60L,
            durationMs = duration,
        )
        assertTrue(planB.isScheduledFutureStart)
        assertEquals(0L, planB.positionMs)
        assertEquals(2_440L, planB.delayMs)

        // Screen C (-40ms fast panel): starts frame 0 40ms later.
        val planC = WallDriftController.computeStartPlan(
            nowServerMs = now,
            startAtMs = startAt,
            syncOffsetMs = -40L,
            durationMs = duration,
        )
        assertTrue(planC.isScheduledFutureStart)
        assertEquals(0L, planC.positionMs)
        assertEquals(2_540L, planC.delayMs)
    }

    @Test
    fun lateJoinComputesQuantizedFuturePositionWithLoopModulo() {
        val startAt = 10_000L
        val now = 45_100L // 35.1s after start on a 30s loop
        val duration = 30_000L

        val plan = WallDriftController.computeStartPlan(
            nowServerMs = now,
            startAtMs = startAt,
            syncOffsetMs = 50L,
            durationMs = duration,
            lateJoinLeadMs = 800L,
            quantumMs = 250L,
        )
        // ceil((45100 + 800) / 250) * 250 = ceil(45900 / 250) * 250 = 46000
        assertFalse(plan.isScheduledFutureStart)
        assertEquals(900L, plan.delayMs)
        // (46000 - 10000 + 50) % 30000 = 6050
        assertEquals(6_050L, plan.positionMs)
    }

    @Test
    fun deadbandProportionalNudgeAndHardSeekCooldown() {
        val duration = 20_000L

        // Within 15ms deadband -> 1.0f
        val locked = WallDriftController.computeCorrection(
            targetMs = 5_010L,
            currentMs = 5_000L,
            durationMs = duration,
            nowElapsedMs = 10_000L,
            lastHardSeekElapsedMs = -1L,
        )
        assertTrue(locked is WallDriftController.Correction.Rate)
        assertEquals(1.0f, (locked as WallDriftController.Correction.Rate).speed, 0.0001f)

        // Behind by 120ms -> smooth positive nudge (~1.02f)
        val behind = WallDriftController.computeCorrection(
            targetMs = 5_120L,
            currentMs = 5_000L,
            durationMs = duration,
            nowElapsedMs = 10_000L,
            lastHardSeekElapsedMs = -1L,
        ) as WallDriftController.Correction.Rate
        assertTrue(behind.speed in 1.015f..1.025f)

        // Ahead by 120ms -> smooth negative nudge (~0.98f)
        val ahead = WallDriftController.computeCorrection(
            targetMs = 5_000L,
            currentMs = 5_120L,
            durationMs = duration,
            nowElapsedMs = 10_000L,
            lastHardSeekElapsedMs = -1L,
        ) as WallDriftController.Correction.Rate
        assertTrue(ahead.speed in 0.975f..0.985f)

        // Wrap-around across loop boundary: target=20ms, current=19980ms -> drift = +40ms (NOT -19960ms!)
        val wrap = WallDriftController.computeCorrection(
            targetMs = 20L,
            currentMs = 19_980L,
            durationMs = duration,
            nowElapsedMs = 10_000L,
            lastHardSeekElapsedMs = -1L,
        ) as WallDriftController.Correction.Rate
        assertEquals(40L, wrap.driftMs)
        assertTrue(wrap.speed > 1.0f)

        // Large drift (> 500ms) outside cooldown -> HardSeek
        val hard = WallDriftController.computeCorrection(
            targetMs = 6_000L,
            currentMs = 5_000L,
            durationMs = duration,
            nowElapsedMs = 10_000L,
            lastHardSeekElapsedMs = 5_000L,
        )
        assertTrue(hard is WallDriftController.Correction.HardSeek)
        assertEquals(6_000L, (hard as WallDriftController.Correction.HardSeek).targetMs)

        // Large drift inside 3s cooldown -> gentle catchup speed instead of seek storm
        val throttled = WallDriftController.computeCorrection(
            targetMs = 6_000L,
            currentMs = 5_000L,
            durationMs = duration,
            nowElapsedMs = 11_000L,
            lastHardSeekElapsedMs = 10_000L,
        )
        assertTrue(throttled is WallDriftController.Correction.Rate)
        assertEquals(1.04f, (throttled as WallDriftController.Correction.Rate).speed, 0.0001f)
    }
}
