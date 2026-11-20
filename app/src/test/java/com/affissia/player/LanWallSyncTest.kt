package com.affissia.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanWallSyncTest {
    @Test
    fun encodesAndDecodesBeaconPacket() {
        val original = LanWallBeacon(
            wallKey = "vwsj_01:1763640005000",
            screenId = "scr_01",
            positionMs = 12_345L,
            durationMs = 30_000L,
            syncOffsetMs = 25L,
            sequence = 7L,
        )
        val bytes = original.encode()
        val decoded = LanWallBeacon.decode(bytes)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun deterministicLeaderElectionAndSlaveTargetCalculation() {
        val state = LanWallSyncState(masterTimeoutMs = 2_500L, estimatedLanTransitMs = 2L)
        val wallKey = "vwsj_01:1763640005000"

        // Before hearing any lower-ID peer, self acts as Master.
        assertTrue(state.shouldBroadcastAsMaster(nowElapsedMs = 1_000L))
        assertNull(state.resolveSlaveTargetPositionMs(wallKey, selfSyncOffsetMs = 0L, durationMs = 30_000L, nowElapsedMs = 1_000L))

        // Higher screen_id peer ("scr_03") does NOT preempt "scr_02".
        val higherPeer = LanWallBeacon(wallKey, "scr_03", 5_000L, 30_000L, 0L, 1L)
        assertFalse(state.onBeaconReceived(higherPeer, wallKey, selfScreenId = "scr_02", receivedElapsedMs = 1_100L))
        assertTrue(state.shouldBroadcastAsMaster(nowElapsedMs = 1_100L))

        // Lower screen_id peer ("scr_01") preempts "scr_02" -> "scr_02" becomes Slave.
        // Master has syncOffset = +10ms and is at position 5000ms at t=1200ms.
        val masterBeacon = LanWallBeacon(wallKey, "scr_01", 5_000L, 30_000L, syncOffsetMs = 10L, sequence = 1L)
        assertTrue(state.onBeaconReceived(masterBeacon, wallKey, selfScreenId = "scr_02", receivedElapsedMs = 1_200L))
        assertFalse(state.shouldBroadcastAsMaster(nowElapsedMs = 1_500L))
        assertEquals("scr_01", state.activeMasterScreenId(nowElapsedMs = 1_500L))

        // At t=1400ms (age=200ms), slave has syncOffset = +40ms:
        // masterNow = 5000 + 200 + 2(transit) = 5202
        // canonical = 5202 - 10 = 5192
        // slaveTarget = 5192 + 40 = 5232
        val target = state.resolveSlaveTargetPositionMs(
            expectedWallKey = wallKey,
            selfSyncOffsetMs = 40L,
            durationMs = 30_000L,
            nowElapsedMs = 1_400L,
        )
        assertEquals(5_232L, target)

        // If master disappears for > 2500ms, slave gracefully falls back to null (server-clock sync)
        // and promotes itself to Master.
        assertNull(state.resolveSlaveTargetPositionMs(wallKey, 40L, 30_000L, nowElapsedMs = 4_000L))
        assertTrue(state.shouldBroadcastAsMaster(nowElapsedMs = 4_000L))
    }

    @Test
    fun ignoresBeaconsFromDifferentWallsAndOutOfOrderSequences() {
        val state = LanWallSyncState()
        val wallA = "vwsj_A:100"
        val wallB = "vwsj_B:200"

        assertFalse(
            state.onBeaconReceived(
                LanWallBeacon(wallB, "scr_01", 1_000L, 10_000L, 0L, 1L),
                expectedWallKey = wallA,
                selfScreenId = "scr_02",
                receivedElapsedMs = 1_000L,
            ),
        )
        assertTrue(
            state.onBeaconReceived(
                LanWallBeacon(wallA, "scr_01", 2_000L, 10_000L, 0L, 5L),
                expectedWallKey = wallA,
                selfScreenId = "scr_02",
                receivedElapsedMs = 1_100L,
            ),
        )
        // Out-of-order older sequence (seq 4 < 5) is ignored.
        assertFalse(
            state.onBeaconReceived(
                LanWallBeacon(wallA, "scr_01", 1_500L, 10_000L, 0L, 4L),
                expectedWallKey = wallA,
                selfScreenId = "scr_02",
                receivedElapsedMs = 1_150L,
            ),
        )
    }
}
