package com.local.douyinsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMediaPolicyTest {
    @Test fun derivesDurationWithoutIntegerOverflowAndRejectsExcessiveWork() {
        assertEquals(9_000L, AlbumMediaPolicy.durationMs(3, 3))
        assertEquals(900_000L, AlbumMediaPolicy.durationMs(100, 9))
        listOf(0 to 3, 101 to 3, 2 to 0, 2 to 16, 100 to 15, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (count, seconds) ->
            assertThrows(IllegalArgumentException::class.java) { AlbumMediaPolicy.durationMs(count, seconds) }
        }
    }

    @Test fun decodedMimeDeterminesExtensionAndGuardsPixelCount() {
        assertEquals("jpg", AlbumMediaPolicy.imageExtension("image/jpeg", 1080, 1920))
        assertEquals("webp", AlbumMediaPolicy.imageExtension("IMAGE/WEBP", 720, 720))
        assertEquals("png", AlbumMediaPolicy.imageExtension("image/png", 100, 200))
        listOf(0 to 1, 1 to 0, -1 to 1, 32769 to 1, 10_000 to 10_000).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) { AlbumMediaPolicy.imageExtension("image/jpeg", width, height) }
        }
        assertThrows(IllegalStateException::class.java) { AlbumMediaPolicy.imageExtension("text/html", 10, 10) }
    }

    @Test fun choosesConsistentEvenCanvasAndChecksTrueByteLimits() {
        assertEquals(720 to 1280, AlbumMediaPolicy.canvas(1080, 1920))
        assertEquals(1280 to 720, AlbumMediaPolicy.canvas(1920, 1080))
        assertEquals(720 to 720, AlbumMediaPolicy.canvas(500, 500))
        assertThrows(IllegalArgumentException::class.java) { AlbumMediaPolicy.canvas(0, 10) }
        assertTrue(AlbumMediaPolicy.fitsBytes(AlbumMediaPolicy.MAX_IMAGE_BYTES, AlbumMediaPolicy.MAX_IMAGE_BYTES))
        assertFalse(AlbumMediaPolicy.fitsBytes(0, AlbumMediaPolicy.MAX_IMAGE_BYTES))
        assertFalse(AlbumMediaPolicy.fitsBytes(AlbumMediaPolicy.MAX_IMAGE_BYTES + 1, AlbumMediaPolicy.MAX_IMAGE_BYTES))
    }

    @Test fun usesTrackAndSizeToDistinguishAudioPrimingFromEndOfStream() {
        assertTrue(AlbumMediaPolicy.samplePresent(0, 0, 250))
        assertFalse(AlbumMediaPolicy.samplePresent(0, -1, -1))
        assertFalse(AlbumMediaPolicy.samplePresent(0, 1, 250))
        assertFalse(AlbumMediaPolicy.samplePresent(0, 0, 0))
        assertFalse(AlbumMediaPolicy.samplePresent(-1, -1, 250))
    }
}
