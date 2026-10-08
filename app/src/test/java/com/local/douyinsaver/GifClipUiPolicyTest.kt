package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class GifClipUiPolicyTest {
    private fun video(seconds: Double = 30.0) = ParsedVideo("video", "Video", "https://media.test/video", seconds, 720, 1280)
    private fun gallery(vararg images: ParsedImage) = video().copy(images = images.toList())
    private fun live(url: String = "https://media.test/live", seconds: Double = 3.0) = ParsedImage(
        "https://media.test/cover", kind = AlbumAssetKind.LIVE, motion = ParsedMotion(url, durationSeconds = seconds))

    @Test fun ordinaryVideoDefaultsAreSixSecondsFromTheBeginning() {
        assertEquals(0f to 6f, GifClipUiPolicy.defaults(video(), 0f, 6f))
        assertTrue(GifClipUiPolicy.canOffer(video()))
        assertNull(GifClipUiPolicy.problem(video(), 0f, 6f))
    }

    @Test fun shorterKnownVideoDefaultsFitTheAvailableRange() {
        assertEquals(0f to 2f, GifClipUiPolicy.defaults(video(2.0), 0f, 6f))
        assertEquals(0f to 0.5f, GifClipUiPolicy.defaults(video(0.5), 0f, 6f))
        assertNull(GifClipUiPolicy.problem(video(0.5), 0f, 0.5f))
    }

    @Test fun invalidStoredSettingsHaveBoundedDefaults() {
        assertEquals(0f to 6f, GifClipUiPolicy.defaults(video(), Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(0f to 30f, GifClipUiPolicy.defaults(video(), -4f, 60f))
        val defaults = GifClipUiPolicy.defaults(video(8.0), 50f, 6f)
        assertEquals(7.9f, defaults.first, 0.0001f)
        assertEquals(0.1f, defaults.second, 0.0001f)
        assertNull(GifClipUiPolicy.problem(video(8.0), defaults.first, defaults.second))
    }

    @Test fun invalidInputDisablesGifWithoutSilentlyChangingTheDraft() {
        for (invalid in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNotNull(GifClipUiPolicy.problem(video(), invalid, 6f))
            assertNotNull(GifClipUiPolicy.problem(video(), 0f, invalid))
        }
        assertNotNull(GifClipUiPolicy.problem(video(), -1f, 6f))
        assertNotNull(GifClipUiPolicy.problem(video(), 0f, 0.09f))
        assertNull(GifClipUiPolicy.problem(video(), 0f, 0.1f))
        assertNull(GifClipUiPolicy.problem(video(), 0f, 16f))
        assertNotNull(GifClipUiPolicy.problem(video(0.0), Float.MAX_VALUE, Float.MAX_VALUE))
    }

    @Test fun knownLengthRejectsOutOfRangeSelectionsAndAllowsExactEnd() {
        assertNull(GifClipUiPolicy.problem(video(10.0), 5f, 5f))
        assertNotNull(GifClipUiPolicy.problem(video(10.0), 5f, 6f))
        assertNotNull(GifClipUiPolicy.problem(video(10.0), 10f, 1f))
        assertNull(GifClipUiPolicy.problem(video(10.0), 9.5f, 0.5f))
        assertNull(GifClipUiPolicy.problem(video(10.0), 9.8f, 0.2f))
    }

    @Test fun oneTenthSecondIsTheMinimumExportRange() {
        assertNotNull(GifClipUiPolicy.problem(video(0.09), 0f, 0.09f))
        assertNull(GifClipUiPolicy.problem(video(0.1), 0f, 0.1f))
    }

    @Test fun unknownDurationAllowsARequestForTheDownloaderToMeasure() {
        for (unknown in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(GifClipUiPolicy.knownDuration(video(unknown)))
            assertEquals(0f to 6f, GifClipUiPolicy.defaults(video(unknown), 0f, 6f))
            assertNull(GifClipUiPolicy.problem(video(unknown), 40f, 15f))
            assertNull(GifClipUiPolicy.problem(video(unknown), 0f, 0.1f))
            assertNull(GifClipUiPolicy.remaining(video(unknown), 0f))
        }
    }

    @Test fun staticPicturesAndOriginalAnimationsDoNotOfferFakeGifConversion() {
        assertFalse(GifClipUiPolicy.canOffer(gallery(ParsedImage("https://media.test/still"))))
        assertFalse(GifClipUiPolicy.canOffer(gallery(ParsedImage("https://media.test/animation",
            kind = AlbumAssetKind.ANIMATED, mimeType = "image/gif"))))
        assertFalse(GifClipUiPolicy.canOffer(gallery(ParsedImage("https://media.test/missing-live", kind = AlbumAssetKind.LIVE))))
    }

    @Test fun readableLiveMotionIsRequiredForEveryLiveItemBeforeConversion() {
        assertTrue(GifClipUiPolicy.canOffer(gallery(live(), ParsedImage("https://media.test/still"))))
        assertTrue(GifClipUiPolicy.sourceComplete(gallery(live(), ParsedImage("https://media.test/still"))))
        assertFalse(GifClipUiPolicy.sourceComplete(gallery(live(""))))
        assertFalse(GifClipUiPolicy.sourceComplete(gallery(live(),
            ParsedImage("https://media.test/missing-live", kind = AlbumAssetKind.LIVE))))
        assertFalse(GifClipUiPolicy.sourceComplete(gallery(live(), live(" "))))
        assertFalse(GifClipUiPolicy.sourceComplete(gallery(live(),
            ParsedImage("https://media.test/unknown-motion", kind = AlbumAssetKind.DYNAMIC))))
    }

    @Test fun motionMarksAnItemConvertibleEvenWhenItsKindWasOmitted() {
        val image = live().copy(kind = AlbumAssetKind.STATIC)
        assertTrue(GifClipUiPolicy.canOffer(gallery(image)))
        assertTrue(GifClipUiPolicy.sourceComplete(gallery(image)))
    }

    @Test fun visibleSecondsUseCompactNumericLabels() {
        assertEquals("6", GifClipUiPolicy.seconds(6f))
        assertEquals("0.25", GifClipUiPolicy.seconds(0.25f))
        assertEquals("12.5", GifClipUiPolicy.seconds(12.5f))
    }

    @Test fun fullSourceAndRemainderUseActualDurationWithoutAnArbitraryCap() {
        val content = video(178.723991)
        assertEquals(178.723991f, GifClipUiPolicy.remaining(content, 0f)!!, 0.001f)
        assertEquals(163.223991f, GifClipUiPolicy.remaining(content, 15.5f)!!, 0.001f)
        assertNull(GifClipUiPolicy.problem(content, 0f, GifClipUiPolicy.remaining(content, 0f)))
        assertNull(GifClipUiPolicy.problem(content, 15.5f, GifClipUiPolicy.remaining(content, 15.5f)))
        assertNull(GifClipUiPolicy.remaining(content, 178.7f))
        assertNull(GifClipUiPolicy.remaining(content, -1f))
    }
}
