package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AlbumPageFallbackTest {
    private val id = "7685772229787700580"
    private val videoPage = "https://www.iesdouyin.com/share/video/$id/"

    @Test fun theOfficialMobileNoteAndItsSignedQueryAreUsedUnchanged() {
        val note = "https://www.iesdouyin.com/share/note/$id/?share_sign=a%2Bb%3D&schema_type=37&x=1&x=2"
        assertEquals(note, AlbumPageFallback.initialPageUrl(id, note))
        assertTrue(AlbumPageFallback.isNotePageUrl(id, note))
        assertFalse(AlbumPageFallback.isNotePageUrl(id, videoPage))
    }

    @Test fun officialMobileVideoAndSlidesRoutesKeepTheirProvidedContext() {
        listOf(videoPage, videoPage.replace("/video/", "/slides/")).forEach { page ->
            val withQuery = "$page?from_ssr=1&share_sign=a%2Bb"
            assertEquals(withQuery, AlbumPageFallback.initialPageUrl(id, withQuery))
            assertFalse(AlbumPageFallback.isNotePageUrl(id, withQuery))
        }
    }

    @Test fun desktopOrFeaturedRoutesUseTheDefaultMobileVideoPage() {
        listOf(null, "https://www.douyin.com/video/$id", "https://jingxuan.douyin.com/m/video/$id",
            "https://www.douyin.com/?modal_id=$id").forEach { page ->
            assertEquals(videoPage, AlbumPageFallback.initialPageUrl(id, page))
        }
    }

    @Test fun theInitialMobilePageMustMatchTheWorkAndRetainTheOriginRules() {
        listOf(videoPage.replace(id, "7692342361661597922"), videoPage.replace("https:", "http:"),
            videoPage.replace("www.iesdouyin.com", "evil.test"),
            videoPage.replace("www.iesdouyin.com", "user@www.iesdouyin.com"),
            videoPage.replace("www.iesdouyin.com", "www.iesdouyin.com:8443")).forEach { page ->
            assertEquals(page, videoPage, AlbumPageFallback.initialPageUrl(id, page))
            assertFalse(AlbumPageFallback.isNotePageUrl(id, page))
        }
    }

    @Test fun theRealSamplesConfirmedEmptyVideoRouteMayTryItsOfficialNoteRouteOnce() {
        assertTrue(AlbumPageFallback.afterEmptyVideoPage(id, videoPage, true, 3, false, false))
        assertFalse(AlbumPageFallback.afterEmptyVideoPage(id, videoPage, true, 3, true, false))
    }

    @Test fun incompleteLoadingRouteMismatchAndVisibleVerificationDoNotTriggerTheFallback() {
        assertFalse(AlbumPageFallback.afterEmptyVideoPage(id, videoPage, true, 2, false, false))
        assertFalse(AlbumPageFallback.afterEmptyVideoPage(id, videoPage, false, 3, false, false))
        assertFalse(AlbumPageFallback.afterEmptyVideoPage(id, videoPage, true, 3, false, true))
    }

    @Test fun aNoteErrorOrAnotherWorksErrorCannotRepeatOrRedirectTheFallback() {
        val other = videoPage.replace(id, "7692342361661597922")
        assertFalse(AlbumPageFallback.afterEmptyVideoPage(id, other, true, 3, false, false))
        assertFalse(AlbumPageFallback.afterEmptyVideoPage(id, videoPage.replace("/video/", "/note/"), true, 3, false, false))
    }

    @Test fun onlyTheTrustedHttpsVideoRouteCanEnableTheFixedOfficialRequest() {
        listOf(videoPage.replace("https:", "http:"), videoPage.replace("www.iesdouyin.com", "evil.test"),
            videoPage.replace("www.iesdouyin.com", "www.iesdouyin.com:8443"),
            videoPage.replace("www.iesdouyin.com", "user@www.iesdouyin.com"),
            "https://www.douyin.com/?modal_id=$id").forEach { page ->
            assertFalse(page, AlbumPageFallback.afterEmptyVideoPage(id, page, true, 3, false, false))
        }
    }
}
