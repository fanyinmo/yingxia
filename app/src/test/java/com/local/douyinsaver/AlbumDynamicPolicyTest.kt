package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumDynamicPolicyTest {
    private val id = "7692079601103867505"
    private val page = "https://www.iesdouyin.com/share/note/$id/"
    private val photo = "https://p3.douyinpic.com/tos-cn-i/photo.webp?signature=a%2Bb"
    private val clip = "https://v5.douyinvod.com/live/photo.mp4?signature=a%2Bb&wm=0"
    private val bgm = "https://sf1.douyinstatic.com/obj/album-bgm.mp3"

    private fun image(motion: AlbumCandidatePolicy.MotionCandidate? = null,
        kind: AlbumAssetKind = AlbumAssetKind.STATIC, mimeType: String = "") =
        AlbumCandidatePolicy.ImageCandidate(listOf(photo), 1080, 1440, displayUrls = listOf(photo),
            kind = kind, mimeType = mimeType, motion = motion)

    private fun album(images: List<AlbumCandidatePolicy.ImageCandidate>) =
        AlbumCandidatePolicy.ready(id, page, id, "混合图集", images, listOf(bgm))!!

    @Test fun staticAnimatedAndUnclassifiedMotionKeepTheirOriginalOrderAndSoundtrack() {
        val motion = AlbumCandidatePolicy.MotionCandidate(playUrls = listOf(clip), width = 1080,
            height = 1440, durationSeconds = 2.4)
        val result = album(listOf(image(), image(kind = AlbumAssetKind.ANIMATED, mimeType = "image/gif"), image(motion)))
        assertEquals(listOf(AlbumAssetKind.STATIC, AlbumAssetKind.ANIMATED, AlbumAssetKind.DYNAMIC), result.images.map { it.kind })
        assertNull(result.images[0].motion)
        assertEquals("image/gif", result.images[1].mimeType)
        assertEquals(clip, result.images[2].motion!!.url)
        assertEquals(2.4, result.images[2].motion!!.durationSeconds, 0.0001)
        assertEquals(bgm, result.bgmUrl)
    }

    @Test fun exactPhotoVideoIdCanSupplyItsOwnCleanPlaybackEntry() {
        val result = album(listOf(image(AlbumCandidatePolicy.MotionCandidate(mediaIds = listOf("owned-photo-video-abc123")))))
        val motion = result.images.single().motion!!
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=owned-photo-video-abc123", motion.url)
        assertTrue(motion.mediaSources.any { it.url == motion.url && it.mode == WatermarkMode.CLEAN })
        assertTrue(motion.mediaSources.none { it.url.contains(id) || it.url == bgm || it.url == photo })
    }

    @Test fun missingClipKeepsItsUnknownDynamicKindAndExplicitLiveRemainsLive() {
        val result = album(listOf(image(AlbumCandidatePolicy.MotionCandidate())))
        assertEquals(AlbumAssetKind.DYNAMIC, result.images.single().kind)
        assertNull(result.images.single().motion)
        val flagged = album(listOf(image(kind = AlbumAssetKind.LIVE)))
        assertEquals(AlbumAssetKind.LIVE, flagged.images.single().kind)
        assertNull(flagged.images.single().motion)
    }

    @Test fun untrustedClipOriginsCannotProvideMotionOrDiscardAnotherPhoto() {
        val result = album(listOf(image(), image(AlbumCandidatePolicy.MotionCandidate(
            playUrls = listOf("https://unsupported.example/live.mp4"),
            downloadUrls = listOf("http://v5.douyinvod.com/live.mp4")))))
        assertEquals(2, result.images.size)
        assertEquals(AlbumAssetKind.DYNAMIC, result.images.last().kind)
        assertNull(result.images.last().motion)
    }

    @Test fun aWatermarkedClipCannotGainCleanProvenanceFromItsCleanStaticCover() {
        val result = album(listOf(image(AlbumCandidatePolicy.MotionCandidate(playUrls = listOf(clip.replace("wm=0", "wm=1"))))))
        val motion = result.images.single().motion!!
        assertTrue(result.images.single().mediaSources.any { it.mode == WatermarkMode.CLEAN })
        assertFalse(motion.mediaSources.any { it.mode == WatermarkMode.CLEAN })
    }

    @Test fun unsafeDurationDimensionsAndMimeHintsAreBoundedWithoutChangingSignedAddresses() {
        val result = album(listOf(image(AlbumCandidatePolicy.MotionCandidate(playUrls = listOf(clip),
            width = Int.MAX_VALUE, height = -1, durationSeconds = Double.NaN), mimeType = "text/html")))
        val motion = result.images.single().motion!!
        assertEquals(clip, motion.url)
        assertEquals(32_768, motion.width)
        assertEquals(0, motion.height)
        assertEquals(0.0, motion.durationSeconds, 0.0)
        assertEquals("", result.images.single().mimeType)
    }

    @Test fun aDifferentWorkCannotGrantItsDynamicClipToTheTargetAlbum() {
        assertNull(AlbumCandidatePolicy.ready(id, page, "7692079601103867506", "混合图集", listOf(
            image(AlbumCandidatePolicy.MotionCandidate(playUrls = listOf(clip))))))
    }

    @Test fun aGifMimeHintDoesNotRequireMultipleFramesBeforeTheFileHasBeenRead() {
        val result = album(listOf(image(mimeType = "image/gif")))
        assertEquals("image/gif", result.images.single().mimeType)
        assertEquals(AlbumAssetKind.STATIC, result.images.single().kind)
        assertNull(result.images.single().motion)
    }

    @Test fun explicitAnimationLiveAndUnknownKindsSurviveIdenticalOwnedMp4Sources() {
        val owned = AlbumCandidatePolicy.MotionCandidate(playUrls = listOf(clip), durationSeconds = 2.4)
        val kinds = listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.LIVE, AlbumAssetKind.DYNAMIC)
        val result = album(kinds.map { image(owned, it) })
        assertEquals(kinds, result.images.map { it.kind })
        assertTrue(result.images.all { it.motion?.url == clip })
        assertTrue(result.images.all { it.motion?.mediaSources?.contains(MediaSource(clip, WatermarkMode.CLEAN)) == true })
        assertEquals(bgm, result.bgmUrl)
    }
}
