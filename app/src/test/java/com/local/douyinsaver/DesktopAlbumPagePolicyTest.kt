package com.local.douyinsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAlbumPagePolicyTest {
    private val id = "7397812747032468745"
    private val otherId = "7397812747032468746"

    @Test
    fun desktopAgentKeepsTheInstalledChromiumVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 12; Pixel; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/110.0.5481.65 Mobile Safari/537.36"
        val desktop = DesktopAlbumPagePolicy.desktopUserAgent(mobile)
        assertTrue(desktop.contains("Chrome/110.0.5481.65"))
        assertTrue(desktop.contains("X11; Linux x86_64"))
        assertFalse(desktop.contains("Mobile"))
        assertFalse(desktop.contains("; wv"))
        assertEquals("110.0.5481.65", DesktopAlbumPagePolicy.chromeVersion(desktop))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownBrowserVersionIsNotReplacedWithAnInventedVersion() {
        DesktopAlbumPagePolicy.desktopUserAgent("Unknown WebView")
    }

    @Test
    fun automaticNavigationStaysWithTheRequestedOfficialWork() {
        listOf(
            "https://www.douyin.com/note/$id",
            "https://www.douyin.com/video/$id?previous_page=feed",
            "https://www.douyin.com/?modal_id=$id",
            "https://www.douyin.com:443/note/$id",
            "https://www.iesdouyin.com/share/note/$id/",
            "https://www.iesdouyin.com/share/slides/$id/",
        ).forEach { url ->
            assertTrue(url, DesktopAlbumPagePolicy.isWorkPage(id, url))
            assertTrue(url, DesktopAlbumPagePolicy.isNavigationAllowed(id, url, false))
        }
    }

    @Test
    fun loginNavigationIsAllowedOnlyDuringExplicitManualVerification() {
        listOf(
            "https://www.douyin.com/login",
            "https://www.douyin.com/passport/web/login/",
            "https://open.douyin.com/oauth/connect/",
            "https://sso.douyin.com/check_qrconnect",
            "https://sso.iesdouyin.com/check_login/",
            "https://passport.bytedance.com/passport/web/login/",
        ).forEach { url ->
            assertFalse(url, DesktopAlbumPagePolicy.isNavigationAllowed(id, url, false))
            assertTrue(url, DesktopAlbumPagePolicy.isNavigationAllowed(id, url, true))
            assertFalse(url, DesktopAlbumPagePolicy.isWorkPage(id, url))
        }
    }

    @Test
    fun manualVerificationCannotNavigateToAnotherWorkOrAnUnrelatedPage() {
        listOf(
            "https://www.douyin.com/note/$otherId",
            "https://www.douyin.com/?modal_id=$otherId",
            "https://www.douyin.com/note/$otherId?modal_id=$id",
            "https://www.douyin.com/passport/web/login/?modal_id=$otherId",
            "https://www.douyin.com/search/test",
            "https://www.douyin.com/",
            "https://www.bytedance.com/login",
            "https://example.com/login",
            "https://douyin.com.evil.example/login",
        ).forEach { url ->
            assertFalse(url, DesktopAlbumPagePolicy.isNavigationAllowed(id, url, false))
            assertFalse(url, DesktopAlbumPagePolicy.isNavigationAllowed(id, url, true))
        }
    }

    @Test
    fun protocolsCredentialsAndNonstandardPortsStayBlockedInEveryPhase() {
        listOf(
            "http://www.douyin.com/note/$id",
            "snssdk1128://aweme/detail/$id",
            "javascript:alert(1)",
            "about:blank",
            "https://user:password@www.douyin.com/note/$id",
            "https://www.douyin.com:8443/note/$id",
            "https://user@passport.bytedance.com/passport/web/login/",
            "https://passport.bytedance.com:8443/passport/web/login/",
        ).forEach { url ->
            assertFalse(url, DesktopAlbumPagePolicy.isWorkPage(id, url))
            assertFalse(url, DesktopAlbumPagePolicy.isNavigationAllowed(id, url, false))
            assertFalse(url, DesktopAlbumPagePolicy.isNavigationAllowed(id, url, true))
        }
    }

    @Test
    fun refreshedSignaturesDoNotResetTheSameImageMotionIdentity() {
        val first = "https://aweme.snssdk.com/aweme/v1/play/?video_id=motion123&line=0&a_bogus=first&expires=1"
        val refreshed = "https://aweme.snssdk.com/aweme/v1/play/?expires=2&a_bogus=second&line=1&video_id=motion123"
        val identity = DesktopAlbumPagePolicy.mediaIdentity(first)
        assertEquals(identity, DesktopAlbumPagePolicy.mediaIdentity(refreshed))
        assertTrue(identity.contains("video_id=motion123"))
        assertFalse(identity.contains("a_bogus"))
        assertFalse(identity.contains("expires"))
        assertFalse(identity.contains("first"))
    }

    @Test
    fun genuineMediaIdsAndFilePathsRemainPartOfStability() {
        val one = "https://aweme.snssdk.com/aweme/v1/play/?video_id=motion123&item_id=work456&uri=clip789"
        val swapped = one.replace("motion123", "motion124")
        assertNotEquals(DesktopAlbumPagePolicy.mediaIdentity(one), DesktopAlbumPagePolicy.mediaIdentity(swapped))
        assertNotEquals(DesktopAlbumPagePolicy.mediaIdentity(one),
            DesktopAlbumPagePolicy.mediaIdentity(one.replace("work456", "work457")))
        assertNotEquals(DesktopAlbumPagePolicy.mediaIdentity(one),
            DesktopAlbumPagePolicy.mediaIdentity(one.replace("clip789", "clip790")))
        val cdn = "https://v3.douyinvod.com/path/one.mp4?signature=first"
        assertEquals(DesktopAlbumPagePolicy.mediaIdentity(cdn),
            DesktopAlbumPagePolicy.mediaIdentity(cdn.replace("first", "second")))
        assertNotEquals(DesktopAlbumPagePolicy.mediaIdentity(cdn),
            DesktopAlbumPagePolicy.mediaIdentity(cdn.replace("one.mp4", "two.mp4")))
    }
}
