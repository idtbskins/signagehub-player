package com.affissia.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWallProModelsTest {
    private val sampleSha = "a".repeat(64)

    @Test
    fun parsesPhase3StagingManifestWithTileReadyAndSyncOffset() {
        val json = """
            {
              "screen_id": "scr_left",
              "asset_id": "ast_01",
              "asset_url": "https://cdn.example.com/slice_left.mp4",
              "sha256": "$sampleSha",
              "start_at": null,
              "server_now": "2026-11-20T12:00:02.500Z",
              "tile_ready": true,
              "slice_job_id": "vwsj_1234",
              "sync_state": "staging",
              "sync_offset_ms": 45,
              "rotation_deg": 90,
              "framing": {
                "fit_mode": "cover-custom",
                "crop": {"x": 0.0, "y": 0.0, "w": 0.5, "h": 1.0}
              }
            }
        """.trimIndent()

        val parsed = VideoWallProManifestParser.parse(json)
        assertEquals("scr_left", parsed.screenId)
        assertEquals("ast_01", parsed.assetId)
        assertEquals(sampleSha, parsed.sha256)
        assertNull(parsed.startAtMillis)
        assertTrue(parsed.serverNowMillis != null)
        assertTrue(parsed.tileReady)
        assertEquals("vwsj_1234", parsed.sliceJobId)
        assertTrue(parsed.isStaging)
        assertFalse(parsed.isGo)
        assertEquals(45L, parsed.syncOffsetMs)
        assertEquals(90, parsed.rotationDeg)
    }

    @Test
    fun stagingToGoTransitionPreservesMediaSignature() {
        val staging = VideoWallProAssignment(
            screenId = "scr_1",
            assetUrl = "https://cdn.example.com/tile.mp4",
            sha256 = sampleSha,
            tileReady = true,
            sliceJobId = "vwsj_99",
            syncState = VideoWallProAssignment.SYNC_STATE_STAGING,
            startAtMillis = null,
            rotationDeg = 0,
        )
        val go = staging.copy(
            syncState = VideoWallProAssignment.SYNC_STATE_GO,
            startAtMillis = 1_763_640_005_000L,
        )

        assertEquals(staging.mediaSignature, go.mediaSignature)
        assertNotEquals(staging.wallSyncKey, go.wallSyncKey)
    }

    @Test
    fun parsesIsoTimestampsWithMicrosecondsAndSqlSpaces() {
        val msZ = VideoWallProManifestParser.parseTimestampMillis("2026-11-20T12:00:05.123Z")
        val usOffset = VideoWallProManifestParser.parseTimestampMillis("2026-11-20T12:00:05.123999+00:00")
        val sqlSpace = VideoWallProManifestParser.parseTimestampMillis("2026-11-20 12:00:05.123")
        val singleDigitFrac = VideoWallProManifestParser.parseTimestampMillis("2026-11-20T12:00:05.5Z")

        assertEquals(msZ, usOffset)
        assertEquals(msZ, sqlSpace)
        assertEquals(msZ + 377L, singleDigitFrac)
    }

    @Test
    fun parsesSliceReadyImmediateGoResponse() {
        val response = """
            {
              "ok": true,
              "job_id": "vwsj_1234",
              "screen_id": "scr_right",
              "ready_count": 2,
              "total_screens": 2,
              "start_at": "2026-11-20T12:00:10.500Z"
            }
        """.trimIndent()
        val parsed = SliceReadyClient.parseResponse(200, response)
        assertTrue(parsed.ok)
        assertEquals(2, parsed.readyCount)
        assertEquals(2, parsed.totalScreens)
        assertEquals(
            VideoWallProManifestParser.parseTimestampMillis("2026-11-20T12:00:10.500Z"),
            parsed.startAtMillis,
        )
    }

    @Test
    fun parsesSliceReadyPendingNullStartAt() {
        val response = """
            {
              "ok": true,
              "job_id": "vwsj_1234",
              "screen_id": "scr_left",
              "ready_count": 1,
              "total_screens": 2,
              "start_at": null
            }
        """.trimIndent()
        val parsed = SliceReadyClient.parseResponse(200, response)
        assertTrue(parsed.ok)
        assertEquals(1, parsed.readyCount)
        assertNull(parsed.startAtMillis)
    }
}
