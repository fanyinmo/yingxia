package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumBgmPolicyTest {
    private val id = "7692079601103867505"
    private val image = AlbumCandidatePolicy.ImageCandidate(listOf("https://p3.douyinpic.com/album.jpg"))
    private val music = "https://sf3-cdn-tos.douyinstatic.com/obj/ies-music/work.mp3?token=a%2Bb"
    private fun album(urls: List<String> = emptyList(), owner: String = id) = AlbumCandidatePolicy.ready(
        id, "https://www.iesdouyin.com/share/note/$id/", owner, "图集", listOf(image), urls)!!

    @Test fun officialMusicUrlsArePreservedAndTrustedHttpIsUpgradedToTlsOnly() {
        assertEquals(music, AlbumCandidatePolicy.secureBgmUrl(music))
        assertEquals(music, AlbumCandidatePolicy.secureBgmUrl(music.replace("https:", "http:")))
        assertEquals(music, album(listOf(music.replace("https:", "http:"))).bgmUrl)
        assertFalse(MediaUrls.isAllowed(music.replace("https:", "http:")))
    }

    @Test fun opaqueMusicIdsAndUntrustedOrMalformedAddressesCannotCreateAudioUrls() {
        listOf("music-id", "//sf3-cdn-tos.douyinstatic.com/music.mp3", "file:///music.mp3",
            "blob:https://www.douyin.com/audio", "https://evil.test/music.mp3",
            "http://douyinstatic.com.evil.test/music.mp3", "http://evil@sf3.douyinstatic.com/music.mp3",
            "http://sf3.douyinstatic.com:80/music.mp3", "http://sf3.douyinstatic.com:8443/music.mp3",
            "http://sf3.douyinstatic.com/music clip.mp3", "http://sf3.douyinstatic.com\\@evil.test/a")
            .forEach { assertNull(it, AlbumCandidatePolicy.secureBgmUrl(it)) }
    }

    @Test fun unsupportedFirstCandidateDoesNotHideItsMatchingWorkAudio() {
        assertEquals(music, album(listOf("opaque-id", "http://evil.test/song", music)).bgmUrl)
    }

    @Test fun audioBearingWorkMp4CanBeOfferedAsBgmAndDoesNotReplaceOrderedImages() {
        val mp4 = "https://aweme.snssdk.com/aweme/v1/play/?video_id=work-id"
        val result = album(listOf(mp4, music))
        assertEquals(mp4, result.bgmUrl)
        assertEquals("", result.mediaUrl)
        assertTrue(result.isAlbum)
        assertEquals(image.urls.single(), result.images.single().url)
    }

    @Test fun missingBgmHasBoundedGraceForLateHydrationWithoutBlockingImageOnlyDownload() {
        val result = album()
        assertFalse(AlbumCandidatePolicy.musicReadyOrGraceElapsed(result, null, 10_000))
        assertFalse(AlbumCandidatePolicy.musicReadyOrGraceElapsed(result, 1_000, 6_999))
        assertTrue(AlbumCandidatePolicy.musicReadyOrGraceElapsed(result, 1_000, 7_000))
        assertFalse(AlbumCandidatePolicy.musicReadyOrGraceElapsed(result, 1_000, 999))
    }

    @Test fun musicReadinessDoesNotDelayOrdinaryVideoOrAnAlbumWithItsOwnAudio() {
        assertTrue(AlbumCandidatePolicy.musicReadyOrGraceElapsed(album(listOf(music)), 1_000, 1_000))
        val video = ParsedVideo(id, "视频", "https://v1.douyinvod.com/video.mp4", 8.0, 720, 1280)
        assertTrue(AlbumCandidatePolicy.musicReadyOrGraceElapsed(video, null, 1_000))
    }

    @Test fun matchingAudioCannotMakeADifferentOwnerAlbumValid() {
        assertNull(AlbumCandidatePolicy.ready(id, "https://www.iesdouyin.com/share/note/$id/",
            "7692079601103867506", "other", listOf(image), listOf(music)))
    }
}
