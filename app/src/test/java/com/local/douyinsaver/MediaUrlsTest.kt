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

    @Test fun observedS19ColdRelayChainAcceptsOnlyTheExactHttpsOriginAndKeepsRawSignedBytes() {
        val entry = MediaUrls.requireAllowed("https://aweme.snssdk.com/aweme/v1/play/?video_id=owned-video")
        val cold = MediaUrls.requireAllowed(MediaUrls.redirect(entry,
            "https://n98-v-ncdncold.douyinvod.com/cache/video.mp4?signature=original%2Bb%3D"))
        val raw = "/cache/a%2fb.mp4?signature=a%2bb%3d&x=&x=1#player"
        for (host in listOf("aym95u.z.cjjd14.com", "AYM95U.Z.CJJD14.COM")) {
            val target = "https://$host:33443$raw"
            assertEquals(target, MediaUrls.requireAllowed(target).toString())
            assertEquals(target, MediaUrls.redirect(cold, target))
            assertEquals(target, MediaUrls.redirect(cold, "//$host:33443$raw"))
        }
        val relay = MediaUrls.requireAllowed("https://aym95u.z.cjjd14.com:33443$raw")
        assertEquals("https://aym95u.z.cjjd14.com:33443/next%2fclip.mp4?signature=b%2bA%3d&x=&x=1",
            MediaUrls.redirect(relay, "../next%2fclip.mp4?signature=b%2bA%3d&x=&x=1"))
        assertEquals("https://aym95u.z.cjjd14.com:33443/cache/a%2fb.mp4?signature=b%2bA%3d&x=&x=1",
            MediaUrls.redirect(relay, "?signature=b%2bA%3d&x=&x=1"))
    }

    @Test fun observedColdRelayExceptionRejectsOtherHostsPortsHttpCredentialsAndIpLiterals() {
        val cold = MediaUrls.requireAllowed("https://n98-v-ncdncold.douyinvod.com/first.mp4")
        val target = "https://aym95u.z.cjjd14.com:33443/video.mp4"
        val invalid = listOf(
            "https://cjjd14.com:33443/video.mp4", "https://z.cjjd14.com:33443/video.mp4",
            "https://aym95u.cjjd14.com:33443/video.mp4", "https://different.z.cjjd14.com:33443/video.mp4",
            "https://nested.aym95u.z.cjjd14.com:33443/video.mp4", "https://aym95u.z.cjjd14.com.:33443/video.mp4",
            "https://aym95u.z.cjjd14.com.attacker.test:33443/video.mp4", "https://aym95u.z.fake-cjjd14.com:33443/video.mp4",
            "https://aym95u.z.cjjd14.com/video.mp4", "https://aym95u.z.cjjd14.com:443/video.mp4",
            "https://aym95u.z.cjjd14.com:33442/video.mp4", "https://aym95u.z.cjjd14.com:33444/video.mp4",
            "https://aym95u.z.cjjd14.com:80/video.mp4", "https://aym95u.z.cjjd14.com:58001/video.mp4",
            "https://v11-weba.douyinvod.com:33443/video.mp4", "https://24898382.ydycdn.com:33443/video.mp4",
            "https://node.bdcgslb.com:33443/video.mp4", "https://unknown.example:33443/video.mp4",
            "http://aym95u.z.cjjd14.com:33443/video.mp4", "http://aym95u.z.cjjd14.com:80/video.mp4",
            "http://aym95u.z.cjjd14.com/video.mp4", "https://user@aym95u.z.cjjd14.com:33443/video.mp4",
            "https://user:password@aym95u.z.cjjd14.com:33443/video.mp4",
            "https://aym95u.z.cjjd14.com:33443@attacker.test/video.mp4",
            "https://%61ym95u.z.cjjd14.com:33443/video.mp4", "https://aym95u.z.cjjd14.com\\@attacker.test:33443/video.mp4",
            "https://127.0.0.1:33443/video.mp4", "https://10.0.0.1:33443/video.mp4",
            "https://172.16.0.1:33443/video.mp4", "https://192.168.1.1:33443/video.mp4",
            "https://169.254.169.254:33443/video.mp4", "https://2130706433:33443/video.mp4",
            "https://localhost:33443/video.mp4", "https://8.8.8.8:33443/video.mp4",
            "https://[::1]:33443/video.mp4", "https://[fe80::1]:33443/video.mp4",
            "https://[fc00::1]:33443/video.mp4", "https://[::ffff:127.0.0.1]:33443/video.mp4",
            "https://[2001:4860:4860::8888]:33443/video.mp4",
        )
        invalid.forEach { url ->
            assertRejected(url)
            assertThrows(url, IllegalArgumentException::class.java) { MediaUrls.redirect(cold, url) }
        }
        assertTrue(MediaUrls.isAllowed(target))
        // The exact-host exception cannot be used by an HTTP Location upgrade to grant another port.
        assertThrows(IllegalArgumentException::class.java) {
            MediaUrls.redirect(cold, "http://aym95u.z.cjjd14.com:33443/video.mp4")
        }
    }

    @Test fun observedColdRelayDiagnosticsKeepOnlyTheHostPortAndDecision() {
        val cold = MediaUrls.requireAllowed("https://n98-v-ncdncold.douyinvod.com/first.mp4")
        val accepted = mutableListOf<String>()
        MediaUrls.redirect(cold, "https://aym95u.z.cjjd14.com:33443/private_path?signature=private_secret#private_fragment", accepted::add)
        assertEquals(listOf("media_redirect scheme=https host=aym95u.z.cjjd14.com port=33443 allowed=true"), accepted)
        val rejected = mutableListOf<String>()
        val error = assertThrows(IllegalArgumentException::class.java) {
            MediaUrls.redirect(cold, "https://private_user:private_password@aym95u.z.cjjd14.com:33443/private_path?signature=private_secret",
                rejected::add)
        }
        assertEquals(listOf("media_redirect scheme=https host=aym95u.z.cjjd14.com port=33443 allowed=false"), rejected)
        assertFalse((accepted + rejected).joinToString().plus(error.message.orEmpty()).contains("private_"))
    }

    @Test fun acceptsOnlyTheConfirmedEightHexColdRelayHostAndTlsPortWithoutChangingItsSignature() {
        val cold = MediaUrls.requireAllowed("https://n98-v-ncdncold.douyinvod.com/first.mp4")
        val path = "/a%2Fb/video.mp4?signature=a%2Bb%3D&x=&x=1#player"
        for (host in listOf("24898382.ydycdn.com", "24898382.YDYCDN.COM",
            "24d522e0.ydycdn.com", "2486c351.ydycdn.com", "24D522E0.YDYCDN.COM")) {
            val target = "https://$host:58001$path"
            assertEquals(target, MediaUrls.requireAllowed(target).toString())
            assertEquals(target, MediaUrls.redirect(cold, target))
            assertEquals(target, MediaUrls.redirect(cold, "//$host:58001$path"))
        }
        val relay = MediaUrls.requireAllowed("https://24898382.ydycdn.com:58001$path")
        assertEquals("https://24898382.ydycdn.com:58001/a%2Fb/next.mp4?signature=b%2BA%3D",
            MediaUrls.redirect(relay, "next.mp4?signature=b%2BA%3D"))
        assertEquals("https://24898382.ydycdn.com:58001/a%2Fb/video.mp4?signature=b%2BA%3D",
            MediaUrls.redirect(relay, "?signature=b%2BA%3D"))
    }

    @Test fun eightHexRelayExceptionDoesNotAllowOtherHostsPortsSchemesOrCredentials() {
        val cold = MediaUrls.requireAllowed("https://n98-v-ncdncold.douyinvod.com/first.mp4")
        val targets = listOf(
            "https://ydycdn.com:58001/video.mp4", "https://node.ydycdn.com:58001/video.mp4",
            "https://2489838.ydycdn.com:58001/video.mp4", "https://248983820.ydycdn.com:58001/video.mp4",
            "https://nested.24898382.ydycdn.com:58001/video.mp4", "https://24898382.ydycdn.com./video.mp4",
            "https://24898382.ydycdn.com.attacker.test:58001/video.mp4", "https://24898382.fake-ydycdn.com:58001/video.mp4",
            "https://24898382.ydycdn.com/video.mp4", "https://24898382.ydycdn.com:443/video.mp4",
            "https://24898382.ydycdn.com:58000/video.mp4", "https://24898382.ydycdn.com:58002/video.mp4",
            "https://v11-weba.douyinvod.com:58001/video.mp4", "https://24898382.example.com:58001/video.mp4",
            "http://24898382.ydycdn.com:58001/video.mp4", "http://24898382.ydycdn.com:80/video.mp4",
            "https://private_user@24898382.ydycdn.com:58001/video.mp4", "https://127.0.0.1:58001/video.mp4",
            "https://24d522g0.ydycdn.com:58001/video.mp4", "https://24d522e.ydycdn.com:58001/video.mp4",
            "https://24d522e00.ydycdn.com:58001/video.mp4", "https://24d522e0-9.ydycdn.com:58001/video.mp4",
            "https://nested.24d522e0.ydycdn.com:58001/video.mp4", "https://24d522e0.ydycdn.com.:58001/video.mp4",
            "https://24d522e0.ydycdn.com.attacker.test:58001/video.mp4", "https://24d522e0.fake-ydycdn.com:58001/video.mp4",
            "https://24d522e0.ydycdn.com/video.mp4", "https://24d522e0.ydycdn.com:443/video.mp4",
            "https://24d522e0.ydycdn.com:33443/video.mp4", "https://24d522e0.ydycdn.com:58002/video.mp4",
            "http://24d522e0.ydycdn.com:58001/video.mp4", "http://24d522e0.ydycdn.com:80/video.mp4",
            "http://24d522e0.ydycdn.com/video.mp4", "https://user:password@24d522e0.ydycdn.com:58001/video.mp4",
            "https://10.0.0.1:58001/video.mp4", "https://192.168.1.1:58001/video.mp4",
            "https://[::1]:58001/video.mp4", "https://[fc00::1]:58001/video.mp4",
            "https://[::ffff:127.0.0.1]:58001/video.mp4",
        )
        targets.forEach { target ->
            assertRejected(target)
            assertThrows(target, IllegalArgumentException::class.java) { MediaUrls.redirect(cold, target) }
        }
    }

    @Test fun observedS01AndS02NumberedHexRelayKeepsOnlyHttpsTlsAndPreservesSignedBytes() {
        val entry = MediaUrls.requireAllowed("https://aweme.snssdk.com/aweme/v1/play/?video_id=owned-video")
        val cold = MediaUrls.requireAllowed(MediaUrls.redirect(entry,
            "https://n98-v-ncdncold.douyinvod.com/cache/video.mp4?signature=original%2Bb%3D"))
        val path = "/cache/a%2fb.mp4?signature=a%2bb%3d&x=&x=1#player"
        for (host in listOf("65bf9670-9.sjxydc.com", "65BF9670-9.SJXYDC.COM",
            "76550b13-9.sjxydc.com", "76550B13-9.SJXYDC.COM")) {
            for (port in listOf("", ":443", ":4430")) {
                val target = "https://$host$port$path"
                assertEquals(target, MediaUrls.requireAllowed(target).toString())
                assertEquals(target, MediaUrls.redirect(cold, target))
                assertEquals(target, MediaUrls.redirect(cold, "//$host$port$path"))
            }
        }
        val relay = MediaUrls.requireAllowed("https://65bf9670-9.sjxydc.com$path")
        assertEquals("https://65bf9670-9.sjxydc.com/next%2fclip.mp4?signature=b%2bA%3d&x=&x=1",
            MediaUrls.redirect(relay, "../next%2fclip.mp4?signature=b%2bA%3d&x=&x=1"))
        assertEquals("https://65bf9670-9.sjxydc.com/cache/a%2fb.mp4?signature=b%2bA%3d&x=&x=1",
            MediaUrls.redirect(relay, "?signature=b%2bA%3d&x=&x=1"))
        val relay4430 = MediaUrls.requireAllowed("https://76550b13-9.sjxydc.com:4430$path")
        assertEquals("https://76550b13-9.sjxydc.com:4430/next%2fclip.mp4?signature=b%2bA%3d&x=&x=1",
            MediaUrls.redirect(relay4430, "../next%2fclip.mp4?signature=b%2bA%3d&x=&x=1"))
        assertEquals("https://76550b13-9.sjxydc.com:4430/cache/a%2fb.mp4?signature=b%2bA%3d&x=&x=1",
            MediaUrls.redirect(relay4430, "?signature=b%2bA%3d&x=&x=1"))
        val diagnostics = mutableListOf<String>()
        MediaUrls.redirect(cold, "https://65bf9670-9.sjxydc.com/private_path?signature=private_secret#private_fragment", diagnostics::add)
        assertEquals(listOf("media_redirect scheme=https host=65bf9670-9.sjxydc.com port=-1 allowed=true"), diagnostics)
        MediaUrls.redirect(cold, "https://76550b13-9.sjxydc.com:4430/private_path?signature=private_secret#private_fragment", diagnostics::add)
        assertEquals("media_redirect scheme=https host=76550b13-9.sjxydc.com port=4430 allowed=true", diagnostics.last())
        assertFalse(diagnostics.joinToString().contains("private_"))
    }

    @Test fun observedNumberedRelayRejectsWrongLabelsPortsHttpCredentialsAndPrivateAddresses() {
        val cold = MediaUrls.requireAllowed("https://n98-v-ncdncold.douyinvod.com/first.mp4")
        val targets = listOf(
            "https://sjxydc.com/video.mp4", "https://node.sjxydc.com/video.mp4",
            "https://65bf9670.sjxydc.com/video.mp4", "https://65bf967-9.sjxydc.com/video.mp4",
            "https://65bf96700-9.sjxydc.com/video.mp4", "https://65bf967g-9.sjxydc.com/video.mp4",
            "https://65bf9670-a.sjxydc.com/video.mp4", "https://65bf9670-99.sjxydc.com/video.mp4",
            "https://65bf9670-.sjxydc.com/video.mp4", "https://nested.65bf9670-9.sjxydc.com/video.mp4",
            "https://65bf9670-9.sjxydc.com./video.mp4", "https://65bf9670-9.sjxydc.com.attacker.test/video.mp4",
            "https://65bf9670-9.fake-sjxydc.com/video.mp4", "https://65bf9670-9.sjxydc.com:80/video.mp4",
            "https://65bf9670-9.sjxydc.com:8443/video.mp4", "https://65bf9670-9.sjxydc.com:58001/video.mp4",
            "https://65bf9670-9.sjxydc.com:33443/video.mp4", "https://user:password@65bf9670-9.sjxydc.com/video.mp4",
            "https://65bf9670-9.sjxydc.com@attacker.test/video.mp4", "https://%365bf9670-9.sjxydc.com/video.mp4",
            "http://65bf9670-9.sjxydc.com/video.mp4", "http://65bf9670-9.sjxydc.com:80/video.mp4",
            "http://65BF9670-9.SJXYDC.COM:80/video.mp4", "http://65bf9670-9.sjxydc.com:443/video.mp4",
            "https://127.0.0.1/video.mp4", "https://10.0.0.1/video.mp4", "https://172.16.0.1/video.mp4",
            "https://192.168.1.1/video.mp4", "https://169.254.169.254/video.mp4", "https://localhost/video.mp4",
            "https://[::1]/video.mp4", "https://[fe80::1]/video.mp4", "https://[fc00::1]/video.mp4",
            "https://[::ffff:127.0.0.1]/video.mp4",
            "https://76550b13-9.sjxydc.com:4429/video.mp4", "https://76550b13-9.sjxydc.com:4431/video.mp4",
            "https://76550b13-9.sjxydc.com:0/video.mp4", "http://76550b13-9.sjxydc.com:4430/video.mp4",
            "https://sjxydc.com:4430/video.mp4", "https://76550b13-a.sjxydc.com:4430/video.mp4",
            "https://76550b13-99.sjxydc.com:4430/video.mp4", "https://76550b1g-9.sjxydc.com:4430/video.mp4",
            "https://nested.76550b13-9.sjxydc.com:4430/video.mp4", "https://76550b13-9.sjxydc.com.attacker.test:4430/video.mp4",
            "https://76550b13-9.fake-sjxydc.com:4430/video.mp4", "https://user:password@76550b13-9.sjxydc.com:4430/video.mp4",
            "https://v11-weba.douyinvod.com:4430/video.mp4", "https://node.bdcgslb.com:4430/video.mp4",
            "https://24d522e0.ydycdn.com:4430/video.mp4", "https://aym95u.z.cjjd14.com:4430/video.mp4",
            "https://127.0.0.1:4430/video.mp4", "https://[::1]:4430/video.mp4",
        )
        targets.forEach { target ->
            assertRejected(target)
            assertThrows(target, IllegalArgumentException::class.java) { MediaUrls.redirect(cold, target) }
        }
    }

    @Test fun numericRelayDiagnosticsRetainHostAndPortWithoutRawSignedComponents() {
        val cold = MediaUrls.requireAllowed("https://n98-v-ncdncold.douyinvod.com/first.mp4")
        val events = mutableListOf<String>()
        MediaUrls.redirect(cold, "https://24898382.ydycdn.com:58001/private_path?signature=private_secret", events::add)
        assertEquals(listOf("media_redirect scheme=https host=24898382.ydycdn.com port=58001 allowed=true"), events)
        assertFalse(events.joinToString().contains("private_"))
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

    @Test fun acceptsOnlyTheObservedHashedColdVideoRelayOriginsWithoutChangingSignedBytes() {
        val hosts = listOf("6f32c5f7924369207481c19d6428c1b4.v.smtcdns.com",
            "3fd23c7e0c4b5d518abc0a7605779a2f.v.smtcdns.com")
        val path = "/a%2fb/video.mp4?signature=a%2bb%3d&x=&x=1#player"
        hosts.forEach { host ->
            val exact = "https://$host$path"
            assertEquals(exact, MediaUrls.requireAllowed(exact).toString())
            val uppercase = "HTTPS://${host.uppercase()}:443$path"
            assertEquals(uppercase, MediaUrls.requireAllowed(uppercase).toString())
        }
    }

    @Test fun acceptsTheConfirmed48HexColdVideoRelayWithoutRewritingItsSignedAddress() {
        val hash = "4cc80c3608d206b6ba9bae1571431c1a838beec9dbf37226"
        assertEquals(48, hash.length)
        val host = "$hash.v.smtcdns.com"
        val path = "/a%2fb/video.mp4?signature=a%2bb%3d&x=&x=1#player"
        val target = "https://$host$path"
        val cold = MediaUrls.requireAllowed("https://v11-colds.douyinvod.com/first.mp4")
        assertEquals(target, MediaUrls.requireAllowed(target).toString())
        assertEquals(target, MediaUrls.redirect(cold, target))
        val uppercase = "HTTPS://${host.uppercase()}:443$path"
        assertEquals(uppercase, MediaUrls.requireAllowed(uppercase).toString())
        assertEquals(uppercase, MediaUrls.redirect(cold, uppercase))
        assertEquals(target, MediaUrls.redirect(cold, "http://$host:80$path"))
    }

    @Test fun hashedColdRelayLengthsRemainExactly32Or48AndRejectAllOtherObservedBoundaries() {
        listOf(31, 33, 40, 47, 49, 64).forEach { length ->
            assertRejected("https://${"a".repeat(length)}.v.smtcdns.com/video.mp4")
        }
        val hash = "4cc80c3608d206b6ba9bae1571431c1a838beec9dbf37226"
        listOf("https://g${hash.drop(1)}.v.smtcdns.com/video.mp4",
            "https://nested.$hash.v.smtcdns.com/video.mp4", "https://$hash.v.smtcdns.com.evil.example/video.mp4",
            "https://user@$hash.v.smtcdns.com/video.mp4", "https://$hash.v.smtcdns.com:8443/video.mp4",
            "http://$hash.v.smtcdns.com/video.mp4").forEach(::assertRejected)
    }

    @Test fun hashedRelayRulesRejectApexOtherSubdomainsIncorrectHashesAndUnsafeAuthorities() {
        val hash = "6f32c5f7924369207481c19d6428c1b4"
        val host = "$hash.v.smtcdns.com"
        listOf("smtcdns.com", "v.smtcdns.com", "$hash.smtcdns.com", "other.v.smtcdns.com",
            "${hash.dropLast(1)}.v.smtcdns.com", "${hash}a.v.smtcdns.com", "g${hash.drop(1)}.v.smtcdns.com",
            "-${hash.drop(1)}.v.smtcdns.com", "nested.$host", "$host.evil.example",
            "$hash.v.fake-smtcdns.com", "$host.").forEach { assertRejected("https://$it/video.mp4") }
        listOf("http://$host/video.mp4", "https://user:password@$host/video.mp4",
            "https://user@$host/video.mp4", "https://$host@evil.example/video.mp4",
            "https://$host:80/video.mp4", "https://$host:8443/video.mp4",
            "https://$host:0/video.mp4").forEach(::assertRejected)
    }

    @Test fun theObservedColdRelayChainAndRelativeRedirectsRetainTheOriginalSignature() {
        val entry = MediaUrls.requireAllowed("https://aweme.snssdk.com/aweme/v1/play/?video_id=owned-video")
        val coldUrl = "https://v11-colds.douyinvod.com/cache/video.mp4?signature=a%2bb%3d&x=&x=1"
        val cold = MediaUrls.requireAllowed(MediaUrls.redirect(entry, coldUrl))
        val host = "6f32c5f7924369207481c19d6428c1b4.v.smtcdns.com"
        val signed = "/cache/a%2fb.mp4?signature=a%2bb%3d&x=&x=1#player"
        val relay = MediaUrls.requireAllowed(MediaUrls.redirect(cold, "https://$host$signed"))
        assertEquals("https://$host$signed", relay.toString())
        assertEquals("https://$host/next%2fclip.mp4?signature=a%2bb%3d&x=&x=1",
            MediaUrls.redirect(relay, "../next%2fclip.mp4?signature=a%2bb%3d&x=&x=1"))
        assertEquals("https://$host/cache/a%2fb.mp4?signature=b%2bA%3d&x=&x=1",
            MediaUrls.redirect(relay, "?signature=b%2bA%3d&x=&x=1"))
        assertEquals("https://${host.uppercase()}:443$signed",
            MediaUrls.redirect(cold, "//${host.uppercase()}:443$signed"))
    }

    @Test fun aTrustedRelayHttpLocationRequestsHttpsWithoutChangingRawComponentsOrTrustRules() {
        val cold = MediaUrls.requireAllowed("https://v11-colds.douyinvod.com/cache/video.mp4")
        val host = "3fd23c7e0c4b5d518abc0a7605779a2f.v.smtcdns.com"
        val path = "/a%2fb.mp4?signature=a%2bb%3d&x=&x=1#player"
        for (port in listOf("", ":80")) {
            val original = "http://$host$port$path"
            assertEquals("https://$host$path", MediaUrls.redirect(cold, original))
            assertRejected(original)
        }
        listOf("http://$host:443$path", "http://$host:8080$path", "http://user@$host$path",
            "http://nested.$host$path", "http://smtcdns.com$path", "http://other.v.smtcdns.com$path").forEach {
            assertThrows(IllegalArgumentException::class.java) { MediaUrls.redirect(cold, it) }
        }
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
