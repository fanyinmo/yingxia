package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class LiveFeatureRemovalTest {
    private fun album(vararg images: ParsedImage) = ParsedVideo("123", "Album", "", 0.0, 0, 0, images = images.toList())
    @Test fun everySourceKindOffersOnlySupportedFormats() {
        AlbumAssetKind.entries.forEach { kind ->
            val content = album(ParsedImage("https://fixture/image", kind = kind, motion = if (kind == AlbumAssetKind.STATIC) null else ParsedMotion("https://fixture/motion")))
            val modes = AlbumActionUiPolicy.dynamicModes(content)
            assertTrue(modes.all { it.isSupportedExport() })
            assertFalse(AlbumActionUiPolicy.primaryLabel(content).contains("实况"))
            modes.forEach { assertFalse(AlbumActionUiPolicy.saveLabel(content, it).contains("实况")) }
            assertNull(AlbumActionUiPolicy.selectedDynamicMode(content, AlbumMode.CONVERT_TO_LIVE))
            assertNull(AlbumActionUiPolicy.selectedDynamicMode(content, AlbumMode.LIVE_PHOTOS))
        }
    }
    @Test fun animationsStillOfferOriginalAndGifAndStaticSequencesKeepGif() {
        val animation = album(ParsedImage("https://fixture/a.gif", kind = AlbumAssetKind.ANIMATED))
        assertEquals(listOf(AlbumMode.IMAGES, AlbumMode.GIF), AlbumActionUiPolicy.dynamicModes(animation))
        assertEquals("保存动图", AlbumActionUiPolicy.primaryLabel(animation))
        val still = ParsedImage("https://fixture/image")
        assertTrue(AlbumActionUiPolicy.canComposeGif(album(still, still)))
        assertEquals("保存 2 张图片", AlbumActionUiPolicy.primaryLabel(album(still, still)))
    }
    @Test fun legacyFormatsAreRejectedButMediaFormatsRemainEnabled() {
        assertFalse(AlbumMode.LIVE_PHOTOS.isSupportedExport())
        assertFalse(AlbumMode.CONVERT_TO_LIVE.isSupportedExport())
        listOf(AlbumMode.IMAGES, AlbumMode.COVERS, AlbumMode.GIF, AlbumMode.VIDEO, AlbumMode.MOTION_VIDEOS).forEach {
            assertTrue(it.isSupportedExport())
        }
    }
    @Test fun oldEmbeddedRecordRemainsReadableAndCannotSuppressSilentMotionDownload() {
        val uri = "content://fixture/old"
        val saved = SavedVideo("123", "Old", uri, 100, isAlbum = true,
            albumAssets = listOf(SavedAlbumAsset(uri, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true)))
        val images = savedAlbumImages(saved)
        assertEquals(uri, images.single().url)
        assertTrue(images.single().motion!!.embeddedInPhoto)
        assertFalse(savedContentLabel(saved).contains("实况"))
        val content = album(ParsedImage("https://fixture/cover", kind = AlbumAssetKind.LIVE, motion = ParsedMotion("https://fixture/motion")))
        assertFalse(AlbumRecordPolicy.matches(saved, content, AlbumMode.IMAGES))
        assertFalse(AlbumRecordPolicy.matches(saved, content, AlbumMode.LIVE_PHOTOS))
    }
}
