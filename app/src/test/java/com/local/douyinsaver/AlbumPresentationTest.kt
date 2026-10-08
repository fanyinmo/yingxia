package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumPresentationTest {
    @Test fun mixedGalleryCountsLivePairsAsOneItem() {
        val cover = "content://fixture/live-cover"
        val motion = "content://fixture/live-motion"
        val saved = SavedVideo("id", "Mixed", cover, 100, mimeType = "image/*", isAlbum = true,
            uris = listOf(cover, motion, "content://fixture/animation", "content://fixture/still"),
            albumAssets = listOf(
                SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, motion),
                SavedAlbumAsset("content://fixture/animation", "image/gif", AlbumAssetKind.ANIMATED),
                SavedAlbumAsset("content://fixture/still", "image/png")))

        val items = savedAlbumImages(saved)
        assertEquals(3, items.size)
        assertEquals(listOf(cover, "content://fixture/animation", "content://fixture/still"), items.map { it.url })
        assertEquals(motion, items.first().motion?.url)
        assertEquals("图集 · 3 项 · 1 动态素材 · 1 动图", savedContentLabel(saved))
        assertFalse("The motion clip must not become an image preview", items.any { it.url == motion })
    }

    @Test fun earlierStaticGalleryKeepsItsImageOrder() {
        val sources = listOf("content://fixture/one", "content://fixture/two")
        val saved = SavedVideo("id", "Old gallery", sources.first(), 100, mimeType = "image/jpeg", uris = sources, isAlbum = true)
        assertEquals(sources, savedAlbumImages(saved).map { it.url })
        assertEquals("图集 · 2 张", savedContentLabel(saved))
        assertTrue(savedAlbumImages(saved).all { it.kind == AlbumAssetKind.STATIC && it.motion == null })
    }

    @Test fun composedGalleryRemainsVideoRatherThanFakeImageList() {
        val saved = SavedVideo("id", "Composed", "content://fixture/video", 100, isAlbum = true)
        assertTrue(savedAlbumImages(saved).isEmpty())
        assertEquals("图集合成视频", savedContentLabel(saved))
        assertEquals("视频", savedContentLabel(saved.copy(isAlbum = false)))
    }

    @Test fun missingLiveMotionStaysVisibleAsLiveCover() {
        val saved = SavedVideo("id", "Live", "content://fixture/cover", 10, isAlbum = true,
            albumAssets = listOf(SavedAlbumAsset("content://fixture/cover", "image/jpeg", AlbumAssetKind.LIVE)))
        val item = savedAlbumImages(saved).single()
        assertEquals(AlbumAssetKind.LIVE, item.kind)
        assertNull(item.motion)
        assertEquals("图集 · 1 项 · 1 动态素材", savedContentLabel(saved))
    }

    @Test fun untypedLegacyMotionManifestDoesNotGuessLiveType() {
        val saved = SavedVideo("id", "Live", "content://fixture/cover", 10,
            albumAssets = listOf(SavedAlbumAsset("content://fixture/cover", "image/jpeg", motionUri = "content://fixture/motion")))
        assertEquals(AlbumAssetKind.DYNAMIC, savedAlbumImages(saved).single().kind)
        assertEquals("图集 · 1 项 · 1 动态素材", savedContentLabel(saved))
    }

    @Test fun partialDeletionKeepsOrphanMotionPlayableWithoutDecodingItAsImage() {
        val cover = "content://fixture/cover"
        val clip = "content://fixture/clip"
        val saved = SavedVideo("id", "Live", cover, 100, isAlbum = true, uris = listOf(cover, clip),
            mimeType = "*/*", albumAssets = listOf(SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, clip)))
        val remainder = AlbumRecordPolicy.retainFiles(saved, listOf(clip))
        val item = savedAlbumImages(remainder).single()
        assertEquals("", item.url)
        assertEquals(clip, item.motion?.url)
        assertEquals(AlbumAssetKind.LIVE, item.kind)
        assertEquals("video/mp4", remainder.mimeTypeFor(remainder.coverUri))
        assertEquals("图集 · 1 项 · 1 动态素材", savedContentLabel(remainder))
    }

    @Test fun animatedVideoRemainsAnimatedRatherThanBeingRelabelledLive() {
        val animated = ParsedImage("https://fixture/cover", kind = AlbumAssetKind.ANIMATED,
            motion = ParsedMotion("https://fixture/motion", durationSeconds = 2.7))
        assertEquals(AlbumAssetKind.ANIMATED, albumPreviewKind(animated))
        assertEquals("图集 · 1 项 · 1 动图", albumContentLabel(listOf(animated)))
        val saved = SavedVideo("id", "Animation", "content://fixture/clip", 100, isAlbum = true,
            albumAssets = listOf(SavedAlbumAsset("content://fixture/clip", "video/mp4", AlbumAssetKind.ANIMATED)))
        val item = savedAlbumImages(saved).single()
        assertEquals(AlbumAssetKind.ANIMATED, item.kind)
        assertEquals("", item.url)
        assertEquals("content://fixture/clip", item.motion?.url)
        assertEquals("图集 · 1 项 · 1 动图", savedContentLabel(saved))
    }

    @Test fun embeddedLivePhotoHasOneSavedUriAndExplicitExtractableMotion() {
        val uri = "content://fixture/live-photo"
        val saved = SavedVideo("id", "Live", uri, 100, isAlbum = true,
            albumAssets = listOf(SavedAlbumAsset(uri, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true)))
        val item = savedAlbumImages(saved).single()
        assertEquals(uri, item.url)
        assertEquals(uri, item.motion?.url)
        assertTrue(item.motion?.embeddedInPhoto == true)
        assertEquals("图集 · 1 项 · 1 动态素材", savedContentLabel(saved))
    }

    @Test fun staticSequenceNeedsTwoPicturesAndDynamicActionsAdaptToSourceKinds() {
        fun gallery(vararg images: ParsedImage) = ParsedVideo("id", "Album", "", 0.0, 0, 0, images = images.toList())
        val still = ParsedImage("https://fixture/still")
        val live = still.copy(kind = AlbumAssetKind.LIVE)
        val animated = still.copy(kind = AlbumAssetKind.ANIMATED)
        assertFalse(AlbumActionUiPolicy.hasDynamic(gallery(still, still)))
        assertFalse(AlbumActionUiPolicy.canComposeGif(gallery(still)))
        assertTrue(AlbumActionUiPolicy.canComposeGif(gallery(still, still)))
        assertTrue(AlbumActionUiPolicy.canComposeGif(gallery(live)))
        assertEquals("保存 2 张图片", AlbumActionUiPolicy.primaryLabel(gallery(still, still)))
        assertEquals("保存动态素材", AlbumActionUiPolicy.primaryLabel(gallery(live, still)))
        assertEquals("保存动图", AlbumActionUiPolicy.primaryLabel(gallery(animated, still)))
        assertEquals("保存动态素材", AlbumActionUiPolicy.primaryLabel(gallery(live, animated)))
    }

    @Test fun automaticTimingUsesEachActualMotionAndDoesNotInventMissingDurations() {
        val still = ParsedImage("https://fixture/still")
        val live = still.copy(kind = AlbumAssetKind.LIVE, motion = ParsedMotion("https://fixture/motion", durationSeconds = 2.7))
        val animated = still.copy(kind = AlbumAssetKind.ANIMATED, motion = ParsedMotion("https://fixture/animated", durationSeconds = 4.1))
        val album = ParsedVideo("id", "Album", "", 0.0, 0, 0, images = listOf(still, live, animated))
        assertEquals(9.8, AlbumDurationUiPolicy.totalSeconds(album, null, 3)!!, 0.0001)
        assertEquals(1.5, AlbumDurationUiPolicy.totalSeconds(album, 0.5, 3)!!, 0.0001)
        assertNull(AlbumDurationUiPolicy.totalSeconds(album.copy(images = listOf(still, live.copy(motion = null))), null, 3))
        assertNull(AlbumDurationUiPolicy.totalSeconds(album.copy(images = listOf(animated.copy(motion = null))), null, 3))
    }

    @Test fun customTimingAcceptsTenthsAndConfirmationExplainsTrimOrLoop() {
        assertTrue(AlbumDurationUiPolicy.acceptsInput("0.1"))
        assertTrue(AlbumDurationUiPolicy.acceptsInput("120"))
        assertFalse(AlbumDurationUiPolicy.acceptsInput("1.25"))
        assertFalse(AlbumDurationUiPolicy.acceptsInput("-1"))
        assertNull(AlbumDurationUiPolicy.parseOverride("0"))
        assertNull(AlbumDurationUiPolicy.parseOverride("121"))
        assertEquals(0.1, AlbumDurationUiPolicy.parseOverride("0.1")!!, 0.0001)
        assertTrue(AlbumDurationUiPolicy.adjustmentDescription(DurationAdjustment(0, 3.0, 1.5)).contains("截取开头"))
        assertTrue(AlbumDurationUiPolicy.adjustmentDescription(DurationAdjustment(2, 3.0, 6.0)).contains("循环播放"))
        assertTrue(AlbumDurationUiPolicy.adjustmentDescription(DurationAdjustment(2, 3.0, 6.0)).startsWith("第 3 项"))
    }

    @Test fun confirmationKeepsMillisecondDifferencesVisibleAtTenthsBoundaries() {
        assertEquals("第 1 项：原时长 2.95 秒 → 3 秒，循环播放至设置时长。",
            AlbumDurationUiPolicy.adjustmentDescription(DurationAdjustment(0, 2.95, 3.0)))
        assertEquals("第 2 项：原时长 3.149 秒 → 3 秒，截取开头。",
            AlbumDurationUiPolicy.adjustmentDescription(DurationAdjustment(1, 3.149, 3.0)))
        assertEquals("第 3 项：原时长 3.123 秒 → 4.6 秒，循环播放至设置时长。",
            AlbumDurationUiPolicy.adjustmentDescription(DurationAdjustment(2, 3.123, 4.6)))
    }

    @Test fun untypedMotionRequiresExplicitOutputChoiceInsteadOfGuessingLiveOrAnimation() {
        val image = ParsedImage("https://fixture/cover", motion = ParsedMotion("https://fixture/motion", durationSeconds = 2.7))
        val album = ParsedVideo("id", "Album", "", 0.0, 0, 0, images = listOf(image))
        assertEquals(AlbumAssetKind.DYNAMIC, albumPreviewKind(image))
        assertEquals("图集 · 1 项 · 1 动态素材", albumContentLabel(album.images))
        assertTrue(AlbumActionUiPolicy.requiresFormatChoice(album))
        assertEquals("保存动态素材", AlbumActionUiPolicy.primaryLabel(album))
        assertEquals(listOf(AlbumMode.MOTION_VIDEOS, AlbumMode.GIF), AlbumActionUiPolicy.dynamicModes(album))
        assertNull(AlbumActionUiPolicy.selectedDynamicMode(album, AlbumMode.IMAGES))
        assertNull(AlbumActionUiPolicy.selectedDynamicMode(album, AlbumMode.LIVE_PHOTOS))
        assertEquals(AlbumMode.MOTION_VIDEOS, AlbumActionUiPolicy.selectedDynamicMode(album, AlbumMode.MOTION_VIDEOS))
        assertEquals(AlbumMode.GIF, AlbumActionUiPolicy.selectedDynamicMode(album, AlbumMode.GIF))
        assertEquals("已移除格式", AlbumActionUiPolicy.saveLabel(album, AlbumMode.LIVE_PHOTOS))
        assertEquals("保存为无声动图", AlbumActionUiPolicy.saveLabel(album, AlbumMode.MOTION_VIDEOS))
    }

    @Test fun knownLiveAndNativeAnimationKeepTheirDefaultWithoutUnknownChoices() {
        val image = ParsedImage("https://fixture/cover", kind = AlbumAssetKind.LIVE,
            motion = ParsedMotion("https://fixture/motion", durationSeconds = 2.7))
        val album = ParsedVideo("id", "Album", "", 0.0, 0, 0, images = listOf(image))
        assertFalse(AlbumActionUiPolicy.requiresFormatChoice(album))
        assertEquals(listOf(AlbumMode.IMAGES, AlbumMode.GIF), AlbumActionUiPolicy.dynamicModes(album))
        assertEquals(AlbumMode.IMAGES, AlbumActionUiPolicy.selectedDynamicMode(album, AlbumMode.IMAGES))
        assertNull(AlbumActionUiPolicy.selectedDynamicMode(album, AlbumMode.MOTION_VIDEOS))
        assertEquals("保存动态素材", AlbumActionUiPolicy.saveLabel(album, AlbumMode.IMAGES))
        val animation = album.copy(images = listOf(image.copy(kind = AlbumAssetKind.ANIMATED)))
        assertEquals("保存动图", AlbumActionUiPolicy.saveLabel(animation, AlbumMode.IMAGES))
        assertFalse(AlbumActionUiPolicy.requiresFormatChoice(animation))
    }

}
