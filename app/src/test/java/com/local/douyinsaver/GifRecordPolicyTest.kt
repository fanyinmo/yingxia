package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class GifRecordPolicyTest {
    private val video = ParsedVideo("1", "video", "https://v3.douyinvod.com/a.mp4", 10.0, 720, 1280)
    private val gif = SavedVideo("1", "video", "content://fixture/gif", 100,
        mimeType = "image/gif", watermarkMode = WatermarkMode.CLEAN,
        exportMode = AlbumMode.GIF.name, gifStartMs = 0, gifDurationMs = 6_000)

    @Test fun videoAndGifAreIndependentDownloads() {
        val mp4 = gif.copy(mimeType = "video/mp4", exportMode = "")
        assertFalse(AlbumRecordPolicy.matches(mp4, video, AlbumMode.GIF))
        assertFalse(AlbumRecordPolicy.matches(gif, video, AlbumMode.IMAGES))
        assertTrue(AlbumRecordPolicy.matches(mp4, video, AlbumMode.IMAGES))
        assertTrue(AlbumRecordPolicy.matches(gif, video, AlbumMode.GIF))
    }

    @Test fun differentVideoRangesNeverSuppressEachOther() {
        assertFalse(AlbumRecordPolicy.matches(gif, video, AlbumMode.GIF, 1f, 6f))
        assertFalse(AlbumRecordPolicy.matches(gif, video, AlbumMode.GIF, 0f, 3f))
        assertTrue(AlbumRecordPolicy.matches(gif.copy(gifStartMs = 8_000, gifDurationMs = 2_000),
            video, AlbumMode.GIF, 8f, 6f))
        assertFalse(AlbumRecordPolicy.matches(gif, video.copy(durationSeconds = 0.0), AlbumMode.GIF))
        assertFalse(AlbumRecordPolicy.matches(gif, video, AlbumMode.GIF, Float.NaN))
    }

    @Test fun embeddedLivePhotoAndConvertedGifCannotSuppressEachOther() {
        val album = video.copy(images = listOf(ParsedImage("https://p3.douyinpic.com/a.webp",
            kind = AlbumAssetKind.LIVE, motion = ParsedMotion(video.mediaUrl))))
        val converted = gif.copy(isAlbum = true, albumSourceCount = 1,
            albumTimingSignature = AlbumTiming.signature(DownloadOptions()), albumAssets = listOf(
            SavedAlbumAsset(gif.uri, "image/gif", AlbumAssetKind.ANIMATED, sourceIndex = 0)))
        val livePhoto = converted.copy(exportMode = AlbumMode.IMAGES.name, mimeType = "image/jpeg",
            uris = listOf(gif.uri), albumTimingSignature = "", albumAssets = listOf(
                SavedAlbumAsset(gif.uri, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true, sourceIndex = 0)))
        val legacyPair = livePhoto.copy(mimeType = "*/*", uris = listOf(gif.uri, "content://fixture/mp4"),
            albumAssets = listOf(SavedAlbumAsset(gif.uri, "image/webp", AlbumAssetKind.LIVE, "content://fixture/mp4")))
        assertTrue(AlbumRecordPolicy.matches(converted, album, AlbumMode.GIF))
        assertFalse(AlbumRecordPolicy.matches(converted, album, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(livePhoto, album, AlbumMode.GIF))
        assertTrue(AlbumRecordPolicy.matches(livePhoto, album, AlbumMode.IMAGES))
        assertFalse("A separate legacy MP4 cannot stand in for a single live-photo image",
            AlbumRecordPolicy.matches(legacyPair, album, AlbumMode.IMAGES))
        assertFalse("An embedded marker on a non-JPEG is not a valid live-photo manifest",
            AlbumRecordPolicy.matches(livePhoto.copy(albumAssets = listOf(
                livePhoto.albumAssets.single().copy(mimeType = "image/webp"))), album, AlbumMode.IMAGES))
        assertFalse("A JPEG without its embedded-motion marker is only a cover",
            AlbumRecordPolicy.matches(livePhoto.copy(albumAssets = listOf(
                livePhoto.albumAssets.single().copy(embeddedMotion = false))), album, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(converted.copy(albumAssets = listOf(
            SavedAlbumAsset(gif.uri, "image/jpeg", AlbumAssetKind.STATIC))), album, AlbumMode.GIF))
    }

    @Test fun changingTheStaticImageDefaultCannotReuseAnEarlierAlbumGif() {
        val album = staticAlbum()
        val threeSeconds = albumExport(album, AlbumMode.GIF, DownloadOptions(imageSeconds = 3))
        assertTrue(AlbumRecordPolicy.matches(threeSeconds, album, AlbumMode.GIF, imageSeconds = 3))
        assertFalse(AlbumRecordPolicy.matches(threeSeconds, album, AlbumMode.GIF, imageSeconds = 5))
        val fiveSeconds = albumExport(album, AlbumMode.GIF, DownloadOptions(imageSeconds = 5))
        assertTrue(AlbumRecordPolicy.matches(fiveSeconds, album, AlbumMode.GIF, imageSeconds = 5))
        assertFalse(AlbumRecordPolicy.matches(fiveSeconds, album, AlbumMode.GIF, imageSeconds = 3))
    }

    @Test fun aCustomAlbumGifDurationCannotSuppressAutomaticTiming() {
        val album = staticAlbum()
        val custom = albumExport(album, AlbumMode.GIF, DownloadOptions(itemDurationSeconds = 0.1))
        assertTrue(AlbumRecordPolicy.matches(custom, album, AlbumMode.GIF, itemDurationSeconds = 0.1))
        assertFalse(AlbumRecordPolicy.matches(custom, album, AlbumMode.GIF))
        assertFalse(AlbumRecordPolicy.matches(custom, album, AlbumMode.GIF, itemDurationSeconds = 0.2))
        assertTrue("An explicit override makes the unused static default irrelevant",
            AlbumRecordPolicy.matches(custom, album, AlbumMode.GIF, imageSeconds = 5, itemDurationSeconds = 0.1))
        val automatic = albumExport(album, AlbumMode.GIF, DownloadOptions())
        assertFalse(AlbumRecordPolicy.matches(automatic, album, AlbumMode.GIF, itemDurationSeconds = 0.1))
    }

    @Test fun composedAlbumVideosAlsoRequireTheSameAutomaticOrCustomTiming() {
        val album = staticAlbum()
        val automatic = albumExport(album, AlbumMode.VIDEO, DownloadOptions())
        assertTrue(AlbumRecordPolicy.matches(automatic, album, AlbumMode.VIDEO))
        assertFalse(AlbumRecordPolicy.matches(automatic, album, AlbumMode.VIDEO, imageSeconds = 5))
        assertFalse(AlbumRecordPolicy.matches(automatic, album, AlbumMode.VIDEO, itemDurationSeconds = 0.1))
        val custom = albumExport(album, AlbumMode.VIDEO, DownloadOptions(itemDurationSeconds = 0.1))
        assertTrue(AlbumRecordPolicy.matches(custom, album, AlbumMode.VIDEO, itemDurationSeconds = 0.1))
        assertFalse(AlbumRecordPolicy.matches(custom, album, AlbumMode.VIDEO))
    }

    @Test fun timingUnknownLegacyExportsDoNotSuppressARepeatSave() {
        val album = staticAlbum()
        listOf(AlbumMode.GIF, AlbumMode.VIDEO).forEach { mode ->
            val legacy = albumExport(album, mode, DownloadOptions()).copy(albumTimingSignature = "")
            assertFalse(AlbumRecordPolicy.matches(legacy, album, mode))
        }
    }

    @Test fun gifHistoryUsesAnimatedPreviewAndCorrectLabel() {
        assertEquals("GIF 动图", savedContentLabel(gif))
        assertEquals(AlbumAssetKind.ANIMATED, savedAlbumImages(gif).single().kind)
        assertEquals("image/gif", gif.mimeTypeFor(gif.uri))
    }

    private fun staticAlbum() = video.copy(images = listOf(
        ParsedImage("https://p3.douyinpic.com/a.jpeg", mimeType = "image/jpeg", imageKey = "a"),
        ParsedImage("https://p3.douyinpic.com/b.jpeg", mimeType = "image/jpeg", imageKey = "b")))

    private fun albumExport(album: ParsedVideo, mode: AlbumMode, timing: DownloadOptions): SavedVideo =
        gif.copy(isAlbum = true, exportMode = mode.name, albumSourceCount = album.images.size,
            mimeType = if (mode == AlbumMode.VIDEO) "video/mp4" else "image/gif",
            albumTimingSignature = AlbumTiming.signature(timing), albumAssets = if (mode == AlbumMode.VIDEO) emptyList()
                else listOf(SavedAlbumAsset(gif.uri, "image/gif", AlbumAssetKind.ANIMATED, sourceIndex = 0)))
}
