package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumSourcePolicyTest {
    private val id = "7692079601103867505"
    private val page = "https://www.iesdouyin.com/share/note/$id/"
    private val clean = "https://p3.douyinpic.com/tos-cn-i-0813/first~tplv-dy-aweme-images:q75.webp"
    private val marked = "https://p3.douyinpic.com/tos-cn-i-0813/first~tplv-dy-aweme-images:watermark-v2:q75.jpeg"
    private val music = "https://sf1.douyinstatic.com/obj/test-album-bgm.mp3"

    private fun image(cleanUrl: String = clean, markedUrl: String = marked) =
        AlbumCandidatePolicy.ImageCandidate(listOf(markedUrl), 1080, 1440,
            displayUrls = listOf(cleanUrl), downloadUrls = listOf(markedUrl))

    private fun album(images: List<AlbumCandidatePolicy.ImageCandidate>) =
        AlbumCandidatePolicy.ready(id, page, id, "图集", images, listOf(music))!!

    @Test fun legacySingleUrlDoesNotBecomeAnInventedCleanSource() {
        val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(clean), 1080, 1440)))
        assertEquals(clean, result.images.single().url)
        assertEquals(listOf(MediaSource(clean, WatermarkMode.ORIGINAL)), result.images.single().mediaSources)
        assertFalse(WatermarkSources.available(result, WatermarkMode.WATERMARKED))
        assertEquals(clean, WatermarkSources.select(result, WatermarkMode.ORIGINAL).images.single().url)
        assertThrows(IllegalArgumentException::class.java) {
            WatermarkSources.select(result, WatermarkMode.CLEAN)
        }
    }

    @Test fun theOwnedDisplayFieldDoesNotRequireAKnownWatermarkedDownloadDerivative() {
        val result = album(listOf(image("https://p3.douyinpic.com/display.jpg",
            "https://p3.douyinpic.com/download.jpg")))
        val selected = WatermarkSources.select(result, WatermarkMode.CLEAN)
        assertEquals("https://p3.douyinpic.com/display.jpg", selected.images.single().url)
        assertTrue(WatermarkSources.available(result, WatermarkMode.CLEAN))
        assertEquals(selected.images.single().url, WatermarkSources.requireSelectedUrl(selected.images.single().url,
            WatermarkMode.CLEAN, selected.images.single().mediaSources).toString())
    }

    @Test fun aSingleDisplayOnlyPhotoRetainsItsExactSignedAddressAndMusic() {
        val photo = "https://p5-sign.douyinpic.com/tos-cn-i-0813c001/photo~tplv-dy-aweme-images:q75.webp?signature=a%2Bb%3D&x=&x=1"
        val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(photo), 1080, 1440,
            displayUrls = listOf(photo))))
        val selected = WatermarkSources.select(result, WatermarkMode.CLEAN)
        assertEquals(photo, selected.images.single().url)
        assertEquals(photo, selected.coverUrl)
        assertEquals(music, selected.bgmUrl)
        assertTrue(selected.images.single().mediaSources.contains(MediaSource(photo, WatermarkMode.ORIGINAL)))
        assertFalse(selected.images.single().mediaSources.any { it.mode == WatermarkMode.WATERMARKED })
    }

    @Test fun aSharedDisplayAndDownloadAddressIsCleanWithoutAConflictingMarkedLabel() {
        listOf(clean, "$clean#download").forEach { download ->
            val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(download), 1080, 1440,
                displayUrls = listOf(clean), downloadUrls = listOf(download))))
            val selected = WatermarkSources.select(result, WatermarkMode.CLEAN)
            assertEquals(clean, selected.images.single().url)
            assertTrue(result.images.single().mediaSources.none { it.mode == WatermarkMode.WATERMARKED })
            assertEquals(clean, WatermarkSources.requireSelectedUrl(clean, WatermarkMode.CLEAN,
                selected.images.single().mediaSources).toString())
        }
    }

    @Test fun anUnknownDownloadOnlyPhotoStillCannotReplaceTheDisplayedRendition() {
        val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(clean), downloadUrls = listOf(clean))))
        assertFalse(WatermarkSources.available(result, WatermarkMode.CLEAN))
        assertThrows(IllegalArgumentException::class.java) { WatermarkSources.select(result, WatermarkMode.CLEAN) }
    }

    @Test fun explicitWatermarksAndAmbiguousSwitchesCannotBePromotedByDisplayMetadata() {
        val markedTemplate = clean.replace("tplv-dy-aweme-images:q75", "tplv-dy-aweme-images-watermark-v2:q75")
        listOf("$clean?wm=1", "$clean?%77atermark=1", "$clean?wm=unknown",
            "$clean?wm=0&wm=1", "$marked?wm=0", markedTemplate,
            "$markedTemplate?wm=0", markedTemplate.replace("~", "%7E")).forEach { photo ->
            val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(photo), displayUrls = listOf(photo))))
            assertFalse(photo, WatermarkSources.available(result, WatermarkMode.CLEAN))
            assertThrows(photo, IllegalArgumentException::class.java) { WatermarkSources.select(result, WatermarkMode.CLEAN) }
        }
    }

    @Test fun aWatermarkWordInAnAuthorFilenameIsNotAPlatformTransform() {
        val photo = "https://p5-sign.douyinpic.com/tos-cn-i-0813/my_watermark_story.jpg"
        val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(photo), displayUrls = listOf(photo))))
        assertEquals(photo, WatermarkSources.select(result, WatermarkMode.CLEAN).images.single().url)
    }

    @Test fun allDisplayOnlyImagesKeepTheirOrderWithoutFlattenedUrlsOrBackgroundMusic() {
        val second = clean.replace("first", "second")
        val result = AlbumCandidatePolicy.ready(id, page, id, "图集", listOf(
            AlbumCandidatePolicy.ImageCandidate(emptyList(), displayUrls = listOf(clean)),
            AlbumCandidatePolicy.ImageCandidate(emptyList(), displayUrls = listOf(second))))!!
        val selected = WatermarkSources.select(result, WatermarkMode.CLEAN)
        assertEquals(listOf(clean, second), selected.images.map { it.url })
        assertTrue(selected.bgmUrl.isEmpty())
    }

    @Test fun aDifferentWorkCannotGrantDisplayProvenanceToAnAlbum() {
        assertNull(AlbumCandidatePolicy.ready(id, page, "7692079601103867506", "图集", listOf(image())))
        assertNull(AlbumCandidatePolicy.ready(id, page.replace(id, "7692079601103867506"), id, "图集", listOf(image())))
    }

    @Test fun knownDownloadWatermarkRetainsTheCorrespondingDisplayAlternative() {
        val result = album(listOf(image()))
        assertEquals(marked, result.images.single().url)
        assertTrue(result.images.single().mediaSources.contains(MediaSource(marked, WatermarkMode.WATERMARKED)))
        assertTrue(result.images.single().mediaSources.contains(MediaSource(clean, WatermarkMode.CLEAN)))
        assertEquals(clean, WatermarkSources.select(result, WatermarkMode.CLEAN).images.single().url)
        assertEquals(marked, WatermarkSources.select(result, WatermarkMode.WATERMARKED).images.single().url)
    }

    @Test fun selectedAlbumKeepsImageOrderMusicAndOriginalCandidates() {
        val cleanSecond = clean.replace("first", "second")
        val markedSecond = marked.replace("first", "second")
        val original = album(listOf(image(), image(cleanSecond, markedSecond)))
        val selected = WatermarkSources.select(original, WatermarkMode.CLEAN)
        assertEquals(listOf(clean, cleanSecond), selected.images.map { it.url })
        assertEquals(listOf(marked, markedSecond), original.images.map { it.url })
        assertEquals(original.images.map { it.mediaSources }, selected.images.map { it.mediaSources })
        assertEquals(music, selected.bgmUrl)
        assertTrue(selected.isAlbum)
    }

    @Test fun missingOneCleanImageRejectsTheEntireAlbumBeforeTransfer() {
        val missingClean = AlbumCandidatePolicy.ImageCandidate(listOf(marked.replace("first", "second")),
            downloadUrls = listOf(marked.replace("first", "second")))
        val original = album(listOf(image(), missingClean))
        assertThrows(IllegalArgumentException::class.java) {
            WatermarkSources.select(original, WatermarkMode.CLEAN)
        }
        assertEquals(2, WatermarkSources.select(original, WatermarkMode.WATERMARKED).images.size)
    }

    @Test fun separatedCandidatesCanProvideTheLegacyPreviewWhenFlattenedUrlsAreAbsent() {
        val result = album(listOf(image().copy(urls = emptyList())))
        assertTrue(MediaUrls.isAllowed(result.images.single().url))
        assertEquals(clean, WatermarkSources.select(result, WatermarkMode.CLEAN).images.single().url)
    }

    @Test fun unsupportedVariantsDoNotMakeAnIncompleteAlbumSuccessful() {
        val unsafe = AlbumCandidatePolicy.ImageCandidate(listOf("https://unsupported.example/first.jpg"),
            displayUrls = listOf("https://unsupported.example/display.jpg"),
            downloadUrls = listOf("https://unsupported.example/download.jpg"))
        assertNull(AlbumCandidatePolicy.ready(id, page, id, "图集", listOf(image(), unsafe)))
    }

    @Test fun legacyAlbumRetainsAllOriginalAlternativesAndDoesNotClaimAnyWatermarkMode() {
        val next = clean.replace("first", "backup")
        val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(clean, next), 1080, 1440)))
        assertEquals(setOf(clean, next), result.images.single().mediaSources.map { it.url }.toSet())
        assertTrue(result.images.single().mediaSources.all { it.mode == WatermarkMode.ORIGINAL })
        assertTrue(WatermarkSources.available(result, WatermarkMode.ORIGINAL))
        assertNull(WatermarkSources.actualMode(WatermarkSources.select(result, WatermarkMode.ORIGINAL), WatermarkMode.ORIGINAL))
    }
}
