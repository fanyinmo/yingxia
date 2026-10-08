package com.local.douyinsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnimatedImageVideoPolicyTest {
    @Test fun retainsHdCompositionInputWithoutTheCompatibilityGifDownscale() {
        assertEquals(1280 to 720, AnimatedImageVideoPolicy.canvas(1280, 720))
        assertEquals(720 to 1280, AnimatedImageVideoPolicy.canvas(720, 1280))
        assertEquals(1513 to 851, AnimatedImageVideoPolicy.canvas(1513, 851))
        assertEquals(48 to 48, AnimatedImageVideoPolicy.canvas(48, 48))
        assertEquals(960 to 540, GifConversionPolicy.canvas(1280, 720))
    }
    @Test fun boundsLargerSourcesWhileKeepingTheirAspectRatio() {
        assertEquals(1600 to 900, AnimatedImageVideoPolicy.canvas(3840, 2160))
        assertEquals(900 to 1600, AnimatedImageVideoPolicy.canvas(2160, 3840))
        assertEquals(1600 to 1600, AnimatedImageVideoPolicy.canvas(2048, 2048))
        listOf(0 to 1, 1 to 0, 32769 to 1, 10000 to 10000).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) { AnimatedImageVideoPolicy.canvas(width, height) }
        }
    }

    @Test fun sourceGeometryIsRetainedWhenAnEncoderAcceptsSmallFrames() {
        assertEquals(48 to 48, AnimatedImageVideoPolicy.encoderCanvas(48, 48, 2, 2, 2, 2))
        assertEquals(1280 to 720, AnimatedImageVideoPolicy.encoderCanvas(1280, 720, 64, 64, 16, 16))
    }

    @Test fun encoderMinimumAndAlignmentAddPaddingWithoutChangingSourceGeometry() {
        assertEquals(64 to 64, AnimatedImageVideoPolicy.encoderCanvas(48, 48, 64, 64, 16, 8))
        assertEquals(64 to 128, AnimatedImageVideoPolicy.encoderCanvas(49, 25, 64, 128, 16, 8))
        assertEquals(1520 to 864, AnimatedImageVideoPolicy.encoderCanvas(1513, 851, 64, 64, 16, 16))
        // These are encoding canvases, not a replacement for the native frame decoder's geometry.
        assertEquals(49 to 25, AnimatedImageVideoPolicy.canvas(49, 25))
    }

    @Test fun unsupportedOrUnboundedEncoderConstraintsAreRejectedBeforeAllocatingPixels() {
        assertThrows(IllegalArgumentException::class.java) {
            AnimatedImageVideoPolicy.encoderCanvas(48, 48, 4096, 64, 16, 16)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnimatedImageVideoPolicy.encoderCanvas(48, 48, 64, 64, 3, 2)
        }
    }
}
