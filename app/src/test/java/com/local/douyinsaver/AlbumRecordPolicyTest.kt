package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumRecordPolicyTest {
    private val cover = "content://fixture/cover"
    private val clip = "content://fixture/motion"
    private val animation = "content://fixture/animation"
    private val liveImage = ParsedImage("https://p3.douyinpic.com/a.jpg", kind = AlbumAssetKind.LIVE,
        motion = ParsedMotion("https://v3.douyinvod.com/a.mp4"))
    private val live = ParsedVideo("1", "live", "", 0.0, 0, 0, images = listOf(liveImage))
    private val saved = SavedVideo("1", "live", cover, 100, mimeType = "*/*", uris = listOf(cover, clip),
        isAlbum = true, albumAssets = listOf(SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, clip)))

    @Test fun gifQualityChoicesDoNotSuppressEachOthersDownloadsOrInvalidateOldClearExports() {
        val video = ParsedVideo("v", "clip", "", 2.0, 1280, 720)
        val old = SavedVideo("v", "clip", animation, 100, mimeType = "image/gif", exportMode = AlbumMode.GIF.name,
            gifDurationMs = 2000)
        assertTrue(AlbumRecordPolicy.matches(old, video, AlbumMode.GIF, gifDurationSeconds = 2f))
        assertFalse(AlbumRecordPolicy.matches(old, video, AlbumMode.GIF, gifDurationSeconds = 2f, gifExportQuality = GifExportQuality.SHARE))
        val sharing = old.copy(gifExportQuality = GifExportQuality.SHARE.name)
        assertTrue(AlbumRecordPolicy.matches(sharing, video, AlbumMode.GIF, gifDurationSeconds = 2f, gifExportQuality = GifExportQuality.SHARE))
        assertFalse(AlbumRecordPolicy.matches(sharing, video, AlbumMode.GIF, gifDurationSeconds = 2f))
    }

    @Test fun removedAnimationConversionNeverMatchesOldLiveManifest() {
        val content = live.copy(images = listOf(liveImage.copy(kind = AlbumAssetKind.ANIMATED)))
        val packed = saved.copy(uris = listOf(cover), albumAssets = listOf(
            SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true)))
        assertFalse(AlbumRecordPolicy.matches(packed, content, AlbumMode.CONVERT_TO_LIVE))
        assertFalse(AlbumRecordPolicy.matches(packed, content, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(packed.copy(albumAssets = listOf(
            SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.STATIC))), content, AlbumMode.CONVERT_TO_LIVE))
    }

    @Test fun oldPhotoRecordsCannotSuppressNewSilentClipAndIncompletePairsDoNotMatch() {
        assertFalse("Legacy sidecars do not suppress the new single live-photo export", AlbumRecordPolicy.matches(saved, live, AlbumMode.IMAGES))
        val packed = saved.copy(mimeType = "image/jpeg", uris = listOf(cover), albumAssets = listOf(
            SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true)))
        assertFalse(AlbumRecordPolicy.matches(packed, live, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(saved.copy(albumAssets = emptyList(), mimeType = "image/jpeg",
            uris = listOf(cover)), live, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(saved.copy(uris = listOf(cover)), live, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(saved.copy(albumAssets = listOf(
            SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE))), live, AlbumMode.IMAGES))
    }

    @Test fun mixedGalleryCountsAssetsRatherThanPublishedFiles() {
        val mixed = live.copy(images = listOf(liveImage, ParsedImage("https://p3.douyinpic.com/b.gif",
            kind = AlbumAssetKind.ANIMATED)))
        val files = saved.copy(uris = listOf(clip, animation), albumAssets = listOf(
            SavedAlbumAsset(clip, "video/mp4", AlbumAssetKind.ANIMATED)) +
            SavedAlbumAsset(animation, "image/gif", AlbumAssetKind.ANIMATED))
        assertTrue(AlbumRecordPolicy.matches(files, mixed, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(files.copy(albumAssets = files.albumAssets.map {
            if (it.uri == animation) it.copy(kind = AlbumAssetKind.STATIC) else it
        }), mixed, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(files, mixed, AlbumMode.VIDEO))
    }

    @Test fun legacyImagesNeedFreshManifestsAndComposedVideosRemainSeparate() {
        val jpegUrl = "https://p3.douyinpic.com/a.jpg"
        val still = live.copy(images = listOf(ParsedImage(jpegUrl, mediaSources = listOf(MediaSource(jpegUrl, WatermarkMode.CLEAN)))))
        val old = saved.copy(uris = listOf(cover), mimeType = "image/jpeg", albumAssets = emptyList())
        assertFalse(AlbumRecordPolicy.matches(old, still, AlbumMode.IMAGES))
        assertTrue(AlbumRecordPolicy.matches(old.copy(albumAssets = listOf(SavedAlbumAsset(cover, "image/jpeg"))), still, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(old, still.copy(images = listOf(
            ParsedImage("https://p3.douyinpic.com/a.webp"))), AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(old, still.copy(images = listOf(ParsedImage(jpegUrl,
            mimeType = "image/gif", mediaSources = listOf(MediaSource("https://p3.douyinpic.com/a.gif", WatermarkMode.CLEAN))))), AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(old, still.copy(images = still.images + still.images), AlbumMode.IMAGES))
        val composed = old.copy(uri = clip, uris = listOf(clip), mimeType = "video/mp4",
            albumTimingSignature = AlbumTiming.signature(DownloadOptions()))
        assertTrue(AlbumRecordPolicy.matches(composed, still, AlbumMode.VIDEO))
        assertFalse(AlbumRecordPolicy.matches(composed, still, AlbumMode.IMAGES))
    }

    @Test fun partialDeletionPreservesActualTypesAndNeverLeavesDanglingPairLinks() {
        val motionOnly = AlbumRecordPolicy.retainFiles(saved, listOf(clip))
        assertEquals(clip, motionOnly.uri)
        assertEquals("video/mp4", motionOnly.mimeTypeFor(clip))
        assertEquals("video/mp4", motionOnly.mimeType)
        assertEquals("", motionOnly.albumAssets.single().motionUri)
        assertFalse(AlbumRecordPolicy.matches(motionOnly, live, AlbumMode.IMAGES))
        val coverOnly = AlbumRecordPolicy.retainFiles(saved, listOf(cover))
        assertEquals("image/jpeg", coverOnly.mimeType)
        assertEquals("", coverOnly.albumAssets.single().motionUri)
        assertFalse(AlbumRecordPolicy.matches(coverOnly, live, AlbumMode.IMAGES))
        val currentStaticPage = live.copy(images = listOf(liveImage.copy(kind = AlbumAssetKind.STATIC, motion = null)))
        assertFalse("An incomplete saved live pair must not suppress a later static-page download",
            AlbumRecordPolicy.matches(coverOnly, currentStaticPage, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(saved.copy(uris = listOf(cover)), currentStaticPage, AlbumMode.IMAGES))
        assertThrows(IllegalArgumentException::class.java) { AlbumRecordPolicy.retainFiles(saved, emptyList()) }
    }

    @Test fun unknownDynamicHistoryMatchesOnlyItsExplicitSavedFormat() {
        val unknown = live.copy(images = listOf(liveImage.copy(kind = AlbumAssetKind.DYNAMIC)))
        val packed = saved.copy(uris = listOf(cover), mimeType = "image/jpeg", exportMode = AlbumMode.LIVE_PHOTOS.name,
            albumAssets = listOf(SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true)))
        val silent = saved.copy(uri = clip, uris = listOf(clip), mimeType = "video/mp4", exportMode = AlbumMode.MOTION_VIDEOS.name,
            albumAssets = listOf(SavedAlbumAsset(clip, "video/mp4", AlbumAssetKind.ANIMATED)))
        assertFalse(AlbumRecordPolicy.matches(packed, unknown, AlbumMode.LIVE_PHOTOS))
        assertTrue(AlbumRecordPolicy.matches(silent, unknown, AlbumMode.MOTION_VIDEOS))
        assertFalse(AlbumRecordPolicy.matches(packed, unknown, AlbumMode.MOTION_VIDEOS))
        assertFalse(AlbumRecordPolicy.matches(silent, unknown, AlbumMode.LIVE_PHOTOS))
        assertFalse(AlbumRecordPolicy.matches(packed, unknown, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(silent, unknown, AlbumMode.IMAGES))
    }
}
