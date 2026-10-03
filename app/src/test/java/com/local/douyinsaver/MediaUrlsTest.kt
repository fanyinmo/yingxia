package com.local.douyinsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlsTest {
    @Test fun acceptsKnownOriginsAndPreservesSignedQuery() {
        listOf("douyinvod.com", "douyin.com", "iesdouyin.com", "bytecdn.com", "byteimg.com", "bytedance.com").forEach { root ->
            assertTrue(MediaUrls.isAllowed("https://$root/video.mp4"))
            assertTrue(MediaUrls.isAllowed("https://v11-weba.$root/a/video.mp4?token=a%2Bb&x=1"))
        }
        val signed = "https://v11-weba.douyinvod.com/a/video.mp4?token=a%2Bb&x=1"
        assertEquals(signed, MediaUrls.requireAllowed(signed).toString())
    }

    @Test fun acceptsHttpsAndHostCaseAndExplicitTlsPort() {
        assertTrue(MediaUrls.isAllowed("HTTPS://V11-WEBA.DOUYINVOD.COM:443/video.mp4"))
    }

    @Test fun acceptsPublicPlaybackEntryWithoutBroadeningOtherServices() {
        val entry = "https://aweme.snssdk.com/aweme/v1/play/?video_id=example&ratio=1080p&line=0"
        assertEquals(entry, MediaUrls.requireAllowed(entry).toString())
        assertTrue(MediaUrls.isAllowed("https://aweme.snssdk.com/aweme/v1/playwm/?video_id=example"))
        listOf(
            "https://snssdk.com/aweme/v1/play/",
            "https://other.snssdk.com/aweme/v1/play/",
            "https://aweme.snssdk.com.attacker.test/aweme/v1/play/",
            "https://aweme.snssdk.com/aweme/v1/user/",
            "https://aweme.snssdk.com/aweme/v1/play/../user/",
            "http://aweme.snssdk.com/aweme/v1/play/",
        ).forEach(::assertRejected)
    }

    @Test fun rejectsLookalikeDomainsAndEmbeddedCredentials() {
        listOf(
            "https://douyinvod.com.attacker.test/video.mp4",
            "https://fake-douyinvod.com/video.mp4",
            "https://evilbytedance.com/video.mp4",
            "https://douyinvod.com@attacker.test/video.mp4",
            "https://attacker.test@douyinvod.com/video.mp4",
            "https://user:password@v11-weba.douyinvod.com/video.mp4",
            "https://douyinvod.com./video.mp4",
            "https://v11..douyinvod.com/video.mp4",
        ).forEach(::assertRejected)
    }

    @Test fun rejectsUnsafeSchemesPortsAndMalformedUrls() {
        listOf(
            "", "video.mp4", "//douyinvod.com/video.mp4",
            "http://douyinvod.com/video.mp4", "file:///video.mp4",
            "blob:https://douyinvod.com/video", "https://douyinvod.com:80/video.mp4",
            "https://douyinvod.com:8443/video.mp4", "https://douyinvod.com:0/video.mp4",
            "https://douyinvod.com/a b.mp4", "https://douyinvod.com\\@attacker.test/a",
        ).forEach(::assertRejected)
    }

    @Test fun rejectsIpAddressesAndLocalNetworkOrigins() {
        listOf(
            "https://127.0.0.1/video.mp4", "https://192.168.1.1/video.mp4",
            "https://10.0.0.1/video.mp4", "https://172.16.0.1/video.mp4",
            "https://169.254.169.254/video.mp4", "https://localhost/video.mp4",
            "https://[::1]/video.mp4", "https://[fe80::1]/video.mp4",
            "https://8.8.8.8/video.mp4", "https://2130706433/video.mp4",
        ).forEach(::assertRejected)
    }

    @Test fun followsOnlyValidatedRedirectsAndDoesNotExposeMalformedAddress() {
        val current = MediaUrls.requireAllowed("https://aweme.snssdk.com/aweme/v1/play/?video_id=test")
        val cdn = "https://v26-web.douyinvod.com/video.mp4?token=a%2Bb"
        assertEquals(cdn, MediaUrls.redirect(current, cdn))
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=next", MediaUrls.redirect(current, "?video_id=next"))
        listOf(null, "", "https://private.test/video.mp4", "http://v26-web.douyinvod.com/video.mp4",
            "https://127.0.0.1/a", "https://v26-web.douyinvod.com/a bad?token=secret", "x".repeat(16_385)).forEach { target ->
            val error = assertThrows(IllegalArgumentException::class.java) { MediaUrls.redirect(current, target) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    private fun assertRejected(url: String) {
        assertFalse(url, MediaUrls.isAllowed(url))
        assertThrows(url, IllegalArgumentException::class.java) { MediaUrls.requireAllowed(url) }
    }
}
