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

    @Test fun acceptsRotatingColdSchedulingHostsWithoutRewritingSignedAddresses() {
        val hosts = listOf("1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com",
            "1AAAUMQG5W1GGJGYJ6U2RGVZZXEGYJT3KXA4FLTUS4A.bdcgslb.com")
        val cold = MediaUrls.requireAllowed("https://v5-coldx.douyinvod.com/first.mp4")
        hosts.forEach { host ->
            val target = "https://$host/a%2Fb/video.mp4?signature=a%2Bb%3D&x=&x=1"
            assertEquals(target, MediaUrls.requireAllowed(target).toString())
            assertEquals(target, MediaUrls.redirect(cold, target))
            assertTrue(MediaUrls.isAllowed(target.replace(host, host.lowercase())))
            assertTrue(MediaUrls.isAllowed(target.replace(host, "$host:443")))
        }
    }

    @Test fun coldSchedulingRulesRejectApexNestedHostsLookalikesAndUnsafeAuthorities() {
        val host = "1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com"
        listOf("https://bdcgslb.com/video.mp4", "https://nested.$host/video.mp4",
            "https://$host.evil.example/video.mp4", "https://fake-bdcgslb.com/video.mp4",
            "https://-invalid.bdcgslb.com/video.mp4", "https://invalid-.bdcgslb.com/video.mp4",
            "https://${"a".repeat(64)}.bdcgslb.com/video.mp4", "https://$host./video.mp4",
            "https://user:password@$host/video.mp4", "https://$host@evil.example/video.mp4",
            "http://$host/video.mp4", "https://$host:80/video.mp4",
            "https://$host:8443/video.mp4").forEach(::assertRejected)
    }

    @Test fun aTrustedSchedulingHttpLocationUsesHttpsWithTheSameSignatureButNotOtherPorts() {
        val cold = MediaUrls.requireAllowed("https://v5-coldx.douyinvod.com/first.mp4")
        val host = "1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com"
        val signedPath = "/a%2Fb.mp4?signature=a%2Bb%3D&x=&x=1"
        assertEquals("https://$host$signedPath", MediaUrls.redirect(cold, "http://$host:80$signedPath"))
        assertRejected("http://$host$signedPath")
        assertThrows(IllegalArgumentException::class.java) { MediaUrls.redirect(cold, "http://$host:8080$signedPath") }
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
        listOf(null, "", "https://private.test/video.mp4", "http://v26-web.douyinvod.com:8080/video.mp4",
            "https://127.0.0.1/a", "https://v26-web.douyinvod.com/a bad?token=secret", "?token=secret value", "x".repeat(16_385)).forEach { target ->
            val error = assertThrows(IllegalArgumentException::class.java) { MediaUrls.redirect(current, target) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun trustedHttpRedirectsUpgradeToHttpsWithoutChangingRawSignedComponents() {
        val current = MediaUrls.requireAllowed("https://v5-coldx.douyinvod.com/first.mp4")
        listOf("http://v26-web.douyinvod.com/a%2Fb/video.mp4?token=a%2Bb%3D&x=&x=1#player",
            "http://v26-web.douyinvod.com:80/a%2Fb/video.mp4?token=a%2Bb%3D&x=&x=1#player").forEach { http ->
            val expected = http.replace("http://", "https://").replace(".com:80/", ".com/")
            assertEquals(expected, MediaUrls.redirect(current, http))
            assertRejected(http) // Initial addresses continue to require HTTPS.
        }
    }

    @Test fun redirectDiagnosticsShowRejectedTargetsWithoutPathsQueriesOrCredentials() {
        val current = MediaUrls.requireAllowed("https://v5-coldx.douyinvod.com/first.mp4")
        val diagnostics = mutableListOf<String>()
        val error = assertThrows(IllegalArgumentException::class.java) {
            MediaUrls.redirect(current, "http://private_user:private_password@unknown.example:80/private_path?token=private_token",
                diagnostics::add)
        }
        assertEquals(listOf("media_redirect scheme=http host=unknown.example port=80 allowed=false"), diagnostics)
        val all = diagnostics.joinToString() + error.message.orEmpty()
        assertFalse(all.contains("private_"))
    }

    @Test fun trustedHttpUpgradeStillRefusesOtherPortsAndUntrustedOrLookalikeHosts() {
        val current = MediaUrls.requireAllowed("https://v5-coldx.douyinvod.com/first.mp4")
        listOf("http://unknown.example/a.mp4", "http://v5-coldx.douyinvod.com.evil.example/a.mp4",
            "http://v5-coldx.douyinvod.com:443/a.mp4", "http://v5-coldx.douyinvod.com:8080/a.mp4",
            "http://user@v5-coldx.douyinvod.com/a.mp4", "http://127.0.0.1/a.mp4",
            "http://aweme.snssdk.com/aweme/v1/user/").forEach { target ->
            assertThrows(IllegalArgumentException::class.java) { MediaUrls.redirect(current, target) }
        }
    }

    @Test fun compatibleRedirectLogsTheOriginalHttpTargetAndItsValidatedHttpsUpgrade() {
        val current = MediaUrls.requireAllowed("https://v5-coldx.douyinvod.com/first.mp4")
        val diagnostics = mutableListOf<String>()
        MediaUrls.redirect(current, "http://v26-web.douyinvod.com:80/private_path?token=private_token", diagnostics::add)
        assertEquals(listOf("media_redirect scheme=http host=v26-web.douyinvod.com port=80 allowed=false",
            "media_redirect scheme=https host=v26-web.douyinvod.com port=-1 allowed=true upgraded=true"), diagnostics)
        assertFalse(diagnostics.joinToString().contains("private_"))
    }

    private fun assertRejected(url: String) {
        assertFalse(url, MediaUrls.isAllowed(url))
        assertThrows(url, IllegalArgumentException::class.java) { MediaUrls.requireAllowed(url) }
    }
}
