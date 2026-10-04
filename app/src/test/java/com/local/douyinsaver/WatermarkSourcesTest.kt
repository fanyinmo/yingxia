package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class WatermarkSourcesTest {
    private val entry = "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200f0000sample_id&ratio=1080p&token=a%2Fb%3D&watermark=1"
    private val cleanCdn = "https://v3.douyinvod.com/playback.mp4?signature=a%2Fb%3D"
    private val markedCdn = "https://v3.douyinvod.com/download.mp4?signature=c%2Fd%3D"
    private fun video(sources: List<MediaSource>) = ParsedVideo("7689301063664454962", "标题", markedCdn, 3.0, 720, 1280,
        mediaSources = sources)

    @Test fun publicPlaybackEntryBuildsTwoVersionsWithoutChangingTheMediaIdentity() {
        val variants = WatermarkSources.videoSources(listOf(entry))
        assertEquals(2, variants.count { it.mode != WatermarkMode.ORIGINAL })
        assertTrue(variants.contains(MediaSource(entry, WatermarkMode.ORIGINAL)))
        val clean = variants.single { it.mode == WatermarkMode.CLEAN }.url
        val marked = variants.single { it.mode == WatermarkMode.WATERMARKED }.url
        assertEquals(entry, marked)
        assertEquals(entry.replace("/playwm/", "/play/").replace("watermark=1", "watermark=0"), clean)
        assertTrue(clean.contains("token=a%2Fb%3D"))
        assertTrue(MediaUrls.isAllowed(clean))
    }

    @Test fun playbackQueryRequiresExactlyOneValidMediaIdAndExactTrustedOrigin() {
        listOf(entry.replace("aweme.snssdk.com", "aweme.snssdk.com.evil.test"),
            entry.replace("video_id=", "other="), entry + "&video_id=second1234567",
            entry.replace("v0200f0000sample_id", "x"), entry.replace("https:", "http:")).forEach {
            assertTrue(WatermarkSources.videoSources(listOf(it)).none { source -> source.mode == WatermarkMode.CLEAN })
        }
    }

    @Test fun independentSourcesStaySignedAndSelectingCleanDoesNotMutateOriginal() {
        val original = video(WatermarkSources.videoSources(listOf(cleanCdn), listOf(markedCdn)))
        assertEquals(cleanCdn, WatermarkSources.select(original, WatermarkMode.CLEAN).mediaUrl)
        assertEquals(markedCdn, WatermarkSources.select(original, WatermarkMode.WATERMARKED).mediaUrl)
        assertEquals(markedCdn, original.mediaUrl)
        assertEquals(WatermarkMode.CLEAN, WatermarkSources.actualMode(original, WatermarkMode.CLEAN))
    }

    @Test fun loneUnknownCdnOrIdenticalTwoFieldsCannotMasqueradeAsClean() {
        listOf(WatermarkSources.videoSources(listOf(markedCdn)),
            WatermarkSources.videoSources(listOf(markedCdn), listOf(markedCdn))).forEach {
            val original = video(it)
            assertFalse(WatermarkSources.available(original, WatermarkMode.CLEAN))
            assertThrows(IllegalArgumentException::class.java) { WatermarkSources.select(original, WatermarkMode.CLEAN) }
            assertEquals(markedCdn, WatermarkSources.select(original, WatermarkMode.ORIGINAL).mediaUrl)
        }
        assertFalse(WatermarkSources.available(video(emptyList()), WatermarkMode.WATERMARKED))
        assertNull(WatermarkSources.actualMode(video(emptyList()), WatermarkMode.WATERMARKED))
    }

    @Test fun contradictoryWatermarkFlagsAndArbitraryFilenamesAreNotProofOfClean() {
        listOf(cleanCdn + "&watermark=0&watermark=1", cleanCdn.replace("playback", "no_watermark"))
            .forEach { assertTrue(WatermarkSources.videoSources(listOf(it)).none { source -> source.mode != WatermarkMode.ORIGINAL }) }
    }

    @Test fun fieldRolesDoNotOverrideAnExplicitPlaybackRenditionOrConflictingFlags() {
        val variants = WatermarkSources.videoSources(listOf(entry), listOf(markedCdn))
        assertFalse(variants.any { it.url == entry && it.mode == WatermarkMode.CLEAN })
        val contradictory = cleanCdn + "&watermark=0&watermark=1"
        assertFalse(WatermarkSources.videoSources(listOf(contradictory), listOf(markedCdn))
            .any { it.url == contradictory && it.mode == WatermarkMode.CLEAN })
    }

    @Test fun explicitWatermarkUrlsKeepBothFieldsUnchanged() {
        val clean = cleanCdn + "&watermark=0"
        val marked = markedCdn + "&watermark=1"
        val sources = WatermarkSources.imageSources(listOf(clean), listOf(marked))
        assertTrue(sources.contains(MediaSource(clean, WatermarkMode.CLEAN)))
        assertTrue(sources.contains(MediaSource(marked, WatermarkMode.WATERMARKED)))
    }

    @Test fun anExplicitImageTransformCannotBeOverriddenByAContradictoryQuery() {
        val markedPath = "https://p3.douyinpic.com/first~watermark-v2:logo.webp?watermark=0"
        assertTrue(WatermarkSources.imageSources(listOf(markedPath), listOf(markedCdn + "&watermark=1"))
            .none { it.url == markedPath && it.mode == WatermarkMode.CLEAN })
    }

    @Test fun aPlaybackEntryWithoutWatermarkSwitchDoesNotInventAnExtraParameter() {
        val original = entry.substringBefore("&watermark=")
        val variants = WatermarkSources.videoSources(listOf(original))
        assertEquals(original, variants.single { it.mode == WatermarkMode.WATERMARKED }.url)
        assertEquals(original.replace("/playwm/", "/play/"), variants.single { it.mode == WatermarkMode.CLEAN }.url)
    }

    @Test fun aKnownCleanOnlySourceCannotBeOfferedAsTheWatermarkedVersion() {
        val original = video(listOf(MediaSource(markedCdn, WatermarkMode.CLEAN)))
        assertFalse(WatermarkSources.available(original, WatermarkMode.WATERMARKED))
        assertTrue(WatermarkSources.available(original, WatermarkMode.CLEAN))
        assertThrows(IllegalArgumentException::class.java) { WatermarkSources.select(original, WatermarkMode.WATERMARKED) }
    }

    @Test fun anObservedDomPlayerCannotBeDeclaredCleanByAnUnrelatedDownloadRole() {
        assertTrue(WatermarkSources.videoSources(emptyList(), listOf(markedCdn), listOf(cleanCdn))
            .none { it.mode == WatermarkMode.CLEAN })
        assertTrue(WatermarkSources.videoSources(emptyList(), listOf(markedCdn), listOf(entry))
            .any { it.mode == WatermarkMode.CLEAN })
    }

    @Test fun retainedUnconfirmedBackupDoesNotInheritTheOtherCandidatesWatermarkLabel() {
        val unknown = "https://v6.douyinvod.com/original.mp4"
        val content = video(listOf(MediaSource(markedCdn, WatermarkMode.WATERMARKED))).copy(mediaUrl = unknown)
        assertFalse(WatermarkSources.candidates(content, WatermarkMode.WATERMARKED).contains(unknown))
        assertTrue(WatermarkSources.candidates(content, WatermarkMode.ORIGINAL).contains(unknown))
        assertNull(WatermarkSources.actualMode(content, WatermarkMode.ORIGINAL))
        assertNull(WatermarkSources.actualMode(content, WatermarkMode.WATERMARKED))
        assertEquals(WatermarkMode.WATERMARKED, WatermarkSources.actualMode(
            WatermarkSources.select(content, WatermarkMode.WATERMARKED), WatermarkMode.WATERMARKED))
    }

    @Test fun exactOpaqueVideoMediaIdsAddBothPlatformRenditionsWithoutInventingAnOriginalUrl() {
        val mediaId = "v0200f0000same_work"
        val sources = WatermarkSources.videoSources(listOf(cleanCdn), mediaIds = listOf(mediaId, mediaId))
        assertTrue(sources.contains(MediaSource(cleanCdn, WatermarkMode.ORIGINAL)))
        assertTrue(sources.any { it.mode == WatermarkMode.CLEAN && it.url == "https://aweme.snssdk.com/aweme/v1/play/?video_id=$mediaId" })
        assertTrue(sources.any { it.mode == WatermarkMode.WATERMARKED && it.url == "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=$mediaId" })
        assertFalse(sources.any { it.mode == WatermarkMode.ORIGINAL && MediaUrls.isPlaybackEntry(it.url) })
    }

    @Test fun workNumbersUrlsAndUntrustedUriShapesCannotBecomeVideoMediaIds() {
        val invalid = listOf("7689301063664454962", "https://trusted.example/id", "../v0200f0000other", "small", "v0200f0000 id")
        assertTrue(WatermarkSources.videoSources(emptyList(), mediaIds = invalid).isEmpty())
    }

    @Test fun allTrustedRawPlaybackDownloadAndDomUrlsRemainAvailableAsOriginals() {
        val dom = "https://v9.douyinvod.com/current.mp4"
        val sources = WatermarkSources.videoSources(listOf(cleanCdn), listOf(markedCdn), listOf(dom, "https://unsafe.example/a.mp4"))
        assertEquals(setOf(cleanCdn, markedCdn, dom), sources.filter { it.mode == WatermarkMode.ORIGINAL }.map { it.url }.toSet())
        assertFalse(sources.any { it.url == dom && it.mode != WatermarkMode.ORIGINAL })
    }

    @Test fun aFullPlaybackFieldDoesNotDiscardTheOtherFieldsOriginalSources() {
        val playback = (0 until 64).map { "https://v3.douyinvod.com/gear$it.mp4" }
        val dom = "https://v9.douyinvod.com/current.mp4"
        val sources = WatermarkSources.videoSources(playback, listOf(markedCdn), listOf(dom))
        val originals = sources.filter { it.mode == WatermarkMode.ORIGINAL }.map { it.url }
        assertEquals(66, originals.size)
        assertTrue(originals.contains(markedCdn))
        assertTrue(originals.contains(dom))
    }

    @Test fun aFullCdnFieldCannotTruncateThePlatformEntryGeneratedFromItsOwnedMediaUri() {
        val playback = (0 until 64).map { "https://v3.douyinvod.com/gear$it.mp4" }
        val sources = WatermarkSources.videoSources(playback, listOf(markedCdn), mediaIds = listOf("v0200f0000same_work"))
        val candidates = WatermarkSources.candidates(video(sources), WatermarkMode.CLEAN)
        assertEquals(65, candidates.size)
        assertTrue(candidates.any(MediaUrls::isPlaybackEntry))
        assertTrue(MediaUrls.isPlaybackEntry(candidates.last()))
    }

    @Test fun transferRejectsKnownOtherRenditionsEvenWithAnAddedFragment() {
        val sources = listOf(MediaSource(cleanCdn, WatermarkMode.CLEAN), MediaSource(markedCdn, WatermarkMode.WATERMARKED))
        listOf(markedCdn, "$markedCdn#player").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                WatermarkSources.requireSelectedUrl(url, WatermarkMode.CLEAN, sources)
            }
        }
    }

    @Test fun transferRejectsExplicitMarkedEntriesFlagsAndImageTransforms() {
        val urls = listOf(entry.substringBefore("&watermark="), entry.replace("watermark=1", "watermark=0"),
            "$cleanCdn&wm=true", "$cleanCdn&%77atermark=1", "$cleanCdn&watermark=0&watermark=1",
            "https://p3.douyinpic.com/photo~watermark-v2:logo.webp",
            "https://p3.douyinpic.com/photo~watermark-v2:logo.webp?watermark=0",
            "https://p3.douyinpic.com/photo~tplv-dy-aweme-images-watermark-v2:q75.jpeg",
            "https://p3.douyinpic.com/photo~tplv-dy-aweme-images-watermark-v2:q75.jpeg?wm=0",
            "https://p3.douyinpic.com/photo%7Etplv-dy-aweme-images-watermark-v2:q75.jpeg",
            "https://p3.douyinpic.com/photo~watermark-v2.webp")
        urls.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                WatermarkSources.requireSelectedUrl(url, WatermarkMode.CLEAN, emptyList())
            }
        }
    }

    @Test fun transferDoesNotTreatUnclassifiedOriginalsAsAConflictingVersion() {
        val sources = listOf(MediaSource(cleanCdn, WatermarkMode.CLEAN), MediaSource(cleanCdn, WatermarkMode.ORIGINAL))
        assertEquals(cleanCdn, WatermarkSources.requireSelectedUrl(cleanCdn, WatermarkMode.CLEAN, sources).toString())
        val fresh = "https://v9.douyinvod.com/fresh.mp4?token=a%2Fb%3D"
        assertEquals(fresh, WatermarkSources.requireSelectedUrl(fresh, WatermarkMode.CLEAN,
            listOf(MediaSource(fresh, WatermarkMode.ORIGINAL))).toString())
    }

    @Test fun transferKeepsSignedCleanUrlsAndDoesNotGuessFromFilenames() {
        listOf("$cleanCdn&watermark=0", cleanCdn.replace("playback", "no_watermark")).forEach { url ->
            assertEquals(url, WatermarkSources.requireSelectedUrl(url, WatermarkMode.CLEAN, emptyList()).toString())
        }
    }

    @Test fun transferStillRejectsUntrustedOriginsForAllLegacyModes() {
        WatermarkMode.entries.forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                WatermarkSources.requireSelectedUrl("https://unsafe.example/media.mp4", mode, emptyList())
            }
        }
    }

    @Test fun missingSourceErrorsOnlyAskToParseAgain() {
        val videoError = assertThrows(IllegalArgumentException::class.java) {
            WatermarkSources.select(video(emptyList()), WatermarkMode.CLEAN)
        }
        val album = video(emptyList()).copy(images = listOf(ParsedImage("https://p3.douyinpic.com/photo.jpg")))
        val imageError = assertThrows(IllegalArgumentException::class.java) {
            WatermarkSources.select(album, WatermarkMode.CLEAN)
        }
        listOf(videoError, imageError).forEach {
            assertTrue(it.message.orEmpty().contains("请重新解析"))
            assertFalse(it.message.orEmpty().contains("切换版本"))
            assertFalse(it.message.orEmpty().contains("无水印"))
        }
    }
}
