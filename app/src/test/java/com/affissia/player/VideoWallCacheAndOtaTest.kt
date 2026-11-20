package com.affissia.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class VideoWallCacheAndOtaTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cacheStoreVerifiesSha256AndEvictsOldestOverBudget() {
        val root = tempFolder.newFolder("wall_cache")
        val store = VideoWallCacheStore(rootDir = root, maxBytes = 150L, maxFiles = 2)

        val payload1 = ByteArray(80) { 1 }
        val payload2 = ByteArray(80) { 2 }
        val payload3 = ByteArray(80) { 3 }

        val sha1 = sha256Hex(payload1)
        val sha2 = sha256Hex(payload2)
        val sha3 = sha256Hex(payload3)

        val f1 = File(root, "$sha1.asset").apply { writeBytes(payload1); setLastModified(1_000L) }
        val f2 = File(root, "$sha2.asset").apply { writeBytes(payload2); setLastModified(2_000L) }
        val f3 = File(root, "$sha3.asset").apply { writeBytes(payload3); setLastModified(3_000L) }
        val staleTmp = File(root, "partial.tmp").apply { writeBytes(ByteArray(10)); setLastModified(1_000L) }

        val deleted = store.pruneCache(protectedFile = f3, nowMs = 20L * 60L * 1000L)
        assertTrue(deleted >= 2)
        assertFalse(staleTmp.exists())
        assertFalse(f1.exists())
        assertFalse(f2.exists()) // 80 + 80 = 160 > 150 budget, so only protected newest f3 survives
        assertTrue(f3.exists())

        val assignment3 = VideoWallProAssignment(
            screenId = "scr_1",
            assetUrl = "https://cdn.example.com/3.mp4",
            sha256 = sha3,
        )
        assertTrue(store.readyStatusFor(assignment3).isReady)

        // Tampered file fails SHA-256 verification and is automatically deleted.
        f3.writeBytes(ByteArray(80) { 9 })
        val tamperedStatus = store.readyStatusFor(assignment3)
        assertFalse(tamperedStatus.isReady)
        assertEquals("cache_sha256_mismatch", tamperedStatus.reason)
        assertFalse(f3.exists())
    }

    @Test
    fun otaSemverComparisonHandlesVPrefixAndPrerelease() {
        assertTrue(OtaChecker.isNewer("2.2.0", "2.1.11"))
        assertTrue(OtaChecker.isNewer("v2.2.0", "2.1.11"))
        assertTrue(OtaChecker.isNewer("2.2.1", "v2.2.0-rc1"))
        assertFalse(OtaChecker.isNewer("2.1.11", "2.1.11"))
        assertFalse(OtaChecker.isNewer("2.1.9", "2.1.11"))
        assertFalse(OtaChecker.isNewer("invalid", "2.1.11"))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
