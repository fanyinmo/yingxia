package com.local.douyinsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerCandidatePolicyTest {
    @Test fun acceptsObservedWorkOnlyAfterMetadataIsReady() {
        val video = candidate()
        assertNotNull(video)
        assertEquals(ID, video!!.id)
        assertEquals(2560, video.width)
        assertEquals(1440, video.height)
        assertEquals(83.916, video.durationSeconds, 0.000001)
        assertEquals(MEDIA_URL, video.mediaUrl)
        assertEquals("鸣~~菲比啾比~", video.title)
    }

    @Test fun rejectsPrecreatedPlayerAndZeroSizedPlaceholders() {
        assertNull(candidate(readyState = 0, width = 0, height = 0, duration = 0.0))
        assertNull(candidate(readyState = 0))
        assertNull(candidate(width = 0))
        assertNull(candidate(height = 0))
        assertNull(candidate(width = -1))
    }

    @Test fun rejectsMissingInvalidAndNonFiniteDuration() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertNull(candidate(duration = it))
        }
    }

    @Test fun rejectsRecommendationsAndOtherPageIds() {
        assertNull(candidate(ownerId = "7691956509040221327"))
        assertNull(candidate(ownerId = ""))
        assertNull(candidate(pageUrl = "https://www.douyin.com/video/7691956509040221327"))
        assertNull(candidate(pageUrl = "https://www.douyin.com/"))
        assertNull(candidate(pageUrl = "https://www.douyin.com.attacker.test/video/$ID"))
    }

    @Test fun rejectsUntrustedOrUnsupportedMediaDespiteValidMetadata() {
        listOf(
            "https://unsupported.test/video.mp4",
            "https://douyinvod.com.attacker.test/video.mp4",
            "https://attacker.test@v26-web.douyinvod.com/video.mp4",
            "http://v26-web.douyinvod.com/video.mp4",
            "blob:https://www.douyin.com/player-placeholder",
            "",
        ).forEach { assertNull(candidate(mediaUrl = it)) }
    }

    @Test fun normalizesTitleWithoutChangingVideoIdentity() {
        assertEquals("鸣~~菲比啾比~", candidate(title = "  鸣~~菲比啾比~\n")!!.title)
        assertEquals("抖音视频 $ID", candidate(title = " \n ")!!.title)
        assertEquals(500, candidate(title = "长".repeat(600))!!.title.length)
    }

    private fun candidate(
        pageUrl: String = "https://www.douyin.com/video/$ID",
        ownerId: String = ID,
        mediaUrl: String = MEDIA_URL,
        title: String = "鸣~~菲比啾比~",
        readyState: Int = 1,
        width: Int = 2560,
        height: Int = 1440,
        duration: Double = 83.916,
    ) = PlayerCandidatePolicy.ready(ID, pageUrl, ownerId, mediaUrl, title, readyState, width, height, duration)

    private companion object {
        const val ID = "7689301063664454962"
        const val MEDIA_URL = "https://v26-web.douyinvod.com/video/test.mp4?token=example%2Bvalue"
    }
}
