package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class DesktopMotionDisplaySourcesTest {
    private val id = "7685772229787700580"
    private val play = "https://v11.douyinvod.com/web/owned-clip.mp4?signature=a%2Fb%3D&expires=123"
    private val backup = "https://v26.douyinvod.com/weba/owned-clip.mp4?signature=c%2Fd%3D"
    private val download = "https://v3.douyinvod.com/download.mp4?signature=e%2Ff%3D"
    private val poster = "https://p3.douyinpic.com/owned-poster.webp?signature=g%2Fh%3D"

    @Test fun aLonePlaybackUrlWithoutTheDisplayRoleRemainsUnclassified() {
        val sources = WatermarkSources.videoSources(listOf(play))
        assertEquals(listOf(MediaSource(play, WatermarkMode.ORIGINAL)), sources)
        assertTrue(WatermarkSources.videoSources(listOf(play), listOf(play))
            .none { it.mode == WatermarkMode.CLEAN })
    }

    @Test fun ownedDisplayPlaybackRolesRetainEverySignedAddressExactly() {
        val sources = WatermarkSources.videoSources(listOf(play, backup),
            displayPlaybackUrls = listOf(play, backup))
        assertEquals(setOf(play, backup), sources.filter { it.mode == WatermarkMode.CLEAN }.map { it.url }.toSet())
        assertEquals(setOf(play, backup), sources.filter { it.mode == WatermarkMode.ORIGINAL }.map { it.url }.toSet())
        assertTrue(sources.none { it.mode == WatermarkMode.WATERMARKED })
        assertEquals(play, WatermarkSources.requireSelectedUrl(play, WatermarkMode.CLEAN, sources).toString())
    }

    @Test fun aRoleOutsideTheExactPlaybackFieldDoesNotAddOrClassifyAnAddress() {
        listOf(backup, "$play#player").forEach { orphan ->
            val sources = WatermarkSources.videoSources(listOf(play), displayPlaybackUrls = listOf(orphan))
            assertEquals(listOf(MediaSource(play, WatermarkMode.ORIGINAL)), sources)
        }
        val identicalFields = WatermarkSources.videoSources(listOf(play), listOf(play),
            displayPlaybackUrls = listOf(backup))
        assertTrue(identicalFields.contains(MediaSource(play, WatermarkMode.WATERMARKED)))
        assertTrue(identicalFields.none { it.mode == WatermarkMode.CLEAN })
    }

    @Test fun displayRolesDoNotOverrideMarkedOrAmbiguousAddresses() {
        val rejected = listOf("$play&watermark=1", "$play&wm=true", "$play&watermark=maybe",
            "$play&watermark=0&watermark=1",
            "https://v11.douyinvod.com/owned~watermark-v2:logo.mp4?signature=a%2Fb%3D",
            "https://v11.douyinvod.com/owned~watermark-v2:logo.mp4?wm=0")
        rejected.forEach { url ->
            val sources = WatermarkSources.videoSources(listOf(url), displayPlaybackUrls = listOf(url))
            assertFalse(sources.any { it.url == url && it.mode == WatermarkMode.CLEAN })
            assertThrows(IllegalArgumentException::class.java) {
                WatermarkSources.requireSelectedUrl(url, WatermarkMode.CLEAN, sources)
            }
        }
        val markedEntry = "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200f0000owned_clip"
        assertFalse(WatermarkSources.videoSources(listOf(markedEntry), displayPlaybackUrls = listOf(markedEntry))
            .any { it.url == markedEntry && it.mode == WatermarkMode.CLEAN })
    }

    @Test fun unsafeDisplayPlaybackRolesCannotBecomeMediaSources() {
        listOf(play.replace("https:", "http:"), play.replace("v11.douyinvod.com", "unsafe.example"),
            play.replace("https://", "https://user:password@"),
            play.replace("v11.douyinvod.com", "v11.douyinvod.com:8443")).forEach { url ->
            assertTrue(WatermarkSources.videoSources(listOf(url), displayPlaybackUrls = listOf(url)).isEmpty())
        }
    }

    @Test fun anIdenticalDownloadRequestDoesNotConflictWithAConfirmedDisplayRole() {
        listOf(play, "$play#download").forEach { sameRequest ->
            val sources = WatermarkSources.videoSources(listOf(play), listOf(sameRequest),
                displayPlaybackUrls = listOf(play))
            assertTrue(sources.contains(MediaSource(play, WatermarkMode.CLEAN)))
            assertTrue(sources.none { it.mode == WatermarkMode.WATERMARKED })
            assertEquals(play, WatermarkSources.requireSelectedUrl(play, WatermarkMode.CLEAN, sources).toString())
        }
        val otherSignature = play.replace("expires=123", "expires=124")
        val distinctRequestSources = WatermarkSources.videoSources(listOf(play), listOf(otherSignature),
            displayPlaybackUrls = listOf(play))
        assertTrue(distinctRequestSources.contains(MediaSource(otherSignature, WatermarkMode.WATERMARKED)))
    }

    @Test fun ordinaryVideoSourceSelectionKeepsItsExistingDefaultRules() {
        val sources = WatermarkSources.videoSources(listOf(play), listOf(download))
        assertEquals(listOf(MediaSource(play, WatermarkMode.ORIGINAL), MediaSource(download, WatermarkMode.ORIGINAL),
            MediaSource(download, WatermarkMode.WATERMARKED), MediaSource(play, WatermarkMode.CLEAN)), sources)
        assertEquals(sources, WatermarkSources.videoSources(listOf(play), listOf(download), displayPlaybackUrls = emptyList()))
        val playbackEntry = "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0200f0000owned_clip"
        assertEquals(WatermarkSources.videoSources(listOf(playbackEntry)),
            WatermarkSources.videoSources(listOf(playbackEntry), displayPlaybackUrls = emptyList()))
    }

    @Test fun albumMappingUsesOnlyTheConfirmedOwnedMotionRole() {
        fun album(roles: List<String> = emptyList(), owner: String = id): ParsedVideo? = AlbumCandidatePolicy.ready(
            id, "https://www.douyin.com/note/$id", owner, "实况图集", listOf(
                AlbumCandidatePolicy.ImageCandidate(listOf(poster), displayUrls = listOf(poster),
                    kind = AlbumAssetKind.DYNAMIC, motion = AlbumCandidatePolicy.MotionCandidate(
                        playUrls = listOf(play), displayPlaybackUrls = roles))))
        val unclassified = requireNotNull(album())
        assertFalse(WatermarkSources.available(unclassified, WatermarkMode.CLEAN))
        val confirmed = requireNotNull(album(listOf(play)))
        assertTrue(WatermarkSources.available(confirmed, WatermarkMode.CLEAN))
        assertEquals(AlbumAssetKind.DYNAMIC, confirmed.images.single().kind)
        assertEquals(AlbumAssetKind.DYNAMIC, WatermarkSources.select(confirmed, WatermarkMode.CLEAN).images.single().kind)
        assertEquals(play, WatermarkSources.select(confirmed, WatermarkMode.CLEAN).images.single().motion?.url)
        assertEquals(play, confirmed.images.single().motion?.url)
        assertNull(album(listOf(play), "7689301063664454962"))
    }
}
