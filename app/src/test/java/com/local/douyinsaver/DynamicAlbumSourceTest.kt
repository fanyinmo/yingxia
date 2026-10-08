package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class DynamicAlbumSourceTest {
    private val imageUrl = "https://p3.douyinpic.com/a.jpg?sign=a%2Fb%3D"
    private val motionUrl = "https://v3.douyinvod.com/a.mp4?watermark=0&sign=c%2Fd%3D"
    private val image = ParsedImage(imageUrl, mediaSources = listOf(MediaSource(imageUrl, WatermarkMode.CLEAN)))
    private val motion = ParsedMotion(motionUrl, mediaSources = listOf(MediaSource(motionUrl, WatermarkMode.CLEAN)))
    private fun album(images: List<ParsedImage>) = ParsedVideo("1", "混合图集", "", 0.0, 0, 0,
        images = images, bgmUrl = "https://sf11-cdn-tos.douyinstatic.com/music.mp3")

    @Test fun selectingLiveAndAnimationKeepsSignedResourcesOrderAndBgm() {
        val content = album(listOf(image, image.copy(kind = AlbumAssetKind.LIVE, motion = motion),
            image.copy(kind = AlbumAssetKind.ANIMATED, mimeType = "image/gif")))
        assertTrue(WatermarkSources.available(content, WatermarkMode.CLEAN))
        val result = WatermarkSources.select(content, WatermarkMode.CLEAN)
        assertEquals(listOf(AlbumAssetKind.STATIC, AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED), result.images.map { it.kind })
        assertEquals(motionUrl, result.images[1].motion?.url)
        assertEquals(imageUrl, result.images[1].url)
        assertEquals(content.bgmUrl, result.bgmUrl)
        assertEquals(content, album(content.images))
    }

    @Test fun aCoverCannotStandInForMissingOrMarkedMotion() {
        listOf(null, motion.copy(mediaSources = listOf(MediaSource(motionUrl, WatermarkMode.WATERMARKED))))
            .forEach { dynamic ->
                val content = album(listOf(image, image.copy(kind = AlbumAssetKind.LIVE, motion = dynamic)))
                assertFalse(WatermarkSources.available(content, WatermarkMode.CLEAN))
                val failure = assertThrows(IllegalArgumentException::class.java) { WatermarkSources.select(content, WatermarkMode.CLEAN) }
                assertTrue(failure.message.orEmpty().contains("第 2 张实况图"))
            }
    }

    @Test fun actualMotionOverridesAnOmittedUiHintAndOriginalSelectionStaysSeparate() {
        val content = album(listOf(image.copy(motion = motion)))
        assertTrue(content.hasDynamicAlbumAssets)
        assertEquals(AlbumAssetKind.DYNAMIC, WatermarkSources.select(content, WatermarkMode.CLEAN).images.single().kind)
        assertEquals(motionUrl, WatermarkSources.select(content, WatermarkMode.ORIGINAL).images.single().motion?.url)
        assertEquals(AlbumAssetKind.STATIC, content.images.single().kind)
    }

    @Test fun renditionSelectionPreservesEveryExplicitKindAndOwnedMotionAddress() {
        val kinds = listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.DYNAMIC, AlbumAssetKind.LIVE)
        val content = album(kinds.mapIndexed { index, kind ->
            image.copy(kind = kind, motion = motion, imageKey = "photo-$index")
        })
        for (mode in listOf(WatermarkMode.CLEAN, WatermarkMode.ORIGINAL)) {
            val selected = WatermarkSources.select(content, mode)
            assertEquals(kinds, selected.images.map { it.kind })
            assertEquals(listOf("photo-0", "photo-1", "photo-2"), selected.images.map { it.imageKey })
            assertTrue(selected.images.all { it.motion?.url == motionUrl && it.url == imageUrl })
            assertEquals(content.bgmUrl, selected.bgmUrl)
        }
        assertEquals(kinds, content.images.map { it.kind })
    }

    @Test fun aDeclaredUnknownDynamicWithoutMotionCannotPassAsAStaticCoverDownload() {
        val content = album(listOf(image.copy(kind = AlbumAssetKind.DYNAMIC)))
        assertTrue(content.hasDynamicAlbumAssets)
        assertFalse(WatermarkSources.available(content, WatermarkMode.CLEAN))
        assertThrows(IllegalArgumentException::class.java) { WatermarkSources.select(content, WatermarkMode.CLEAN) }
        assertEquals(AlbumAssetKind.DYNAMIC, content.images.single().kind)
    }
}
