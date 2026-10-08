package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class DesktopVideoCandidatePolicyTest {
    private val id = "7684581692060830976"
    private val page = "https://www.douyin.com/video/$id"
    private val direct = "https://v26-web.douyinvod.com/actual.mp4?signature=a%2Bb%3D&x=&x=1"
    private fun candidate(owner: String = id, urls: List<String> = listOf(direct),
        displayed: List<String> = urls, width: Int = 1280, height: Int = 720, duration: Double = 90.0) =
        DesktopVideoCandidatePolicy.Candidate(owner, "目标视频", urls, emptyList(), emptyList(), displayed,
            emptyList(), width, height, duration)

    @Test fun exactOwnedDesktopPlaybackRoleRetainsSignedAddressAndStillRequiresNetworkVerification() {
        val result = requireNotNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(candidate())))
        assertEquals(id, result.id)
        assertFalse(result.isAlbum)
        assertEquals(direct, result.mediaUrl)
        assertEquals(90.0, result.durationSeconds, 0.0)
        assertTrue(result.mediaSources.contains(MediaSource(direct, WatermarkMode.CLEAN)))
    }

    @Test fun recommendationsWrongRoutesAndUntrustedOriginsCannotSupplyThisWork() {
        assertNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(candidate("7689306999973377371"))))
        listOf("https://www.douyin.com/video/7689306999973377371", "https://evil.example/video/$id",
            "http://www.douyin.com/video/$id", "https://www.douyin.com.evil.example/video/$id").forEach {
            assertNull(DesktopVideoCandidatePolicy.ready(id, it, listOf(candidate())))
        }
    }

    @Test fun aLoneSnakeSourceOrMarkedUrlCannotInheritAnUnrelatedDesktopDisplayRole() {
        assertNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(candidate(displayed = emptyList()))))
        assertNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(candidate(displayed = listOf("https://v3.douyinvod.com/other.mp4")))))
        assertNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(candidate(urls = listOf("$direct&watermark=1")))))
        assertNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(candidate(urls = listOf(
            "https://www.iesdouyin.com/aweme/v1/playwm/?video_id=bad")))))
    }

    @Test fun onlyTheOwnedWorkCanSupplyAlternateDimensionsOrMediaIdentifiers() {
        val other = candidate("7689306999973377371", listOf("https://v3.douyinvod.com/recommendation.mp4"), width = 3840, height = 2160)
        val result = requireNotNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(candidate(), other)))
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertFalse(result.mediaSources.any { it.url.contains("recommendation") })
    }

    @Test fun incompleteInvalidOrNonfiniteMetadataDoesNotBecomeReady() {
        listOf(candidate(width = 0), candidate(height = 0), candidate(width = 16385), candidate(duration = 0.0),
            candidate(duration = Double.NaN), candidate(duration = Double.POSITIVE_INFINITY)).forEach {
            assertNull(DesktopVideoCandidatePolicy.ready(id, page, listOf(it)))
        }
    }
}
