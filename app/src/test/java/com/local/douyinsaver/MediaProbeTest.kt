package com.local.douyinsaver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

class MediaProbeTest {
    private val marked = "https://v3.douyinvod.com/marked.mp4?signature=private_marked"
    private val clean = "https://v3.douyinvod.com/clean.mp4?signature=private_clean"
    private val entry = "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0200f0000example"
    private val fresh = "https://v6.douyinvod.com/fresh.mp4?signature=private_fresh"

    private fun video(vararg sources: MediaSource, original: String = marked) =
        ParsedVideo("7692292719339253135", "测试视频", original, 4.0, 720, 1280,
            mediaSources = sources.toList())

    private fun source(url: String, mode: WatermarkMode = WatermarkMode.CLEAN) = MediaSource(url, mode)

    @Test fun aForbiddenAwemeColdNodeCanFallBackToAnObservedOfficialMirrorOfTheSameRendition() = runBlocking {
        val mirror = entry.replace("aweme.snssdk.com", "www.iesdouyin.com") + "&ratio=720p&token=a%2Bb%3D"
        val cold = "https://v5-se-sjy-daily-cold.douyinvod.com/rejected.mp4?signature=private_cold"
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, cold)
                cold -> response(uri, 403)
                mirror -> response(uri, 302, fresh)
                else -> response(uri)
            }
        }, cookies = { null })
        val verified = probe.verify(video(source(entry), source(mirror), source(marked, WatermarkMode.WATERMARKED)))
        assertEquals(listOf(entry, cold, mirror, fresh), requested)
        assertEquals(fresh, verified.mediaUrl)
        assertTrue(verified.mediaSources.contains(source(mirror)))
        assertFalse(verified.mediaSources.contains(source(entry)))
        assertFalse(requested.contains(marked))
    }

    @Test fun selectedEntryBecomesTheVerifiedFinalUrlUsedByPreviewAndDownloadSelection() = runBlocking {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            if (uri.toString() == entry) response(uri, 302, fresh) else response(uri)
        }, cookies = { null })
        val verified = probe.verifySelected(video(source(entry)), WatermarkMode.CLEAN)
        assertEquals(listOf(entry, fresh), requested)
        assertEquals(fresh, verified.mediaUrl)
        assertEquals(fresh, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertEquals(listOf(fresh, entry), verified.mediaSources.map { it.url })
    }

    @Test fun rejectedCleanCandidateFallsBackWithinTheCleanModeOnly() = runBlocking {
        val rejected = clean.replace("clean.mp4", "rejected.mp4")
        val requested = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                rejected -> response(uri, 403)
                entry -> response(uri, 302, fresh)
                else -> response(uri)
            }
        }, cookies = { null })
        val verified = probe.verifySelected(video(source(rejected), source(entry),
            source(marked, WatermarkMode.WATERMARKED)), WatermarkMode.CLEAN, diagnostics::add)
        assertEquals(fresh, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertFalse(requested.contains(marked))
        assertFalse(verified.mediaSources.any { it.url == rejected })
        assertTrue(diagnostics.any { it.contains("status=403") })
        assertFalse(diagnostics.any { it.contains("private_") || it.contains("video_id=") })
    }

    @Test fun unavailableCleanVersionReportsItsHttpErrorWithoutDownloadingMarked() {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            response(uri, 403)
        }, cookies = { null })
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { probe.verifySelected(video(source(clean), source(entry),
                source(marked, WatermarkMode.WATERMARKED)), WatermarkMode.CLEAN) }
        }
        assertTrue(error.message.orEmpty().startsWith("视频地址暂不可用"))
        assertTrue(error.message.orEmpty().contains("HTTP 403"))
        assertFalse(error.message.orEmpty().contains("private_"))
        assertFalse(requested.contains(marked))
    }

    @Test fun defaultVerificationChecksOnlyTheFirstHealthyCleanAddressAndRetainsRefreshBackups() = runBlocking {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            response(uri)
        }, cookies = { null })
        val verified = probe.verify(video(source(clean), source(fresh), source(entry),
            source(marked, WatermarkMode.WATERMARKED), source(marked, WatermarkMode.ORIGINAL)))
        assertEquals(listOf(clean), requested)
        assertEquals(clean, verified.mediaUrl)
        assertEquals(clean, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertTrue(verified.mediaSources.contains(source(entry)))
        assertTrue(verified.mediaSources.contains(source(fresh)))
        assertFalse(requested.contains(marked))
    }

    @Test fun legacyExplicitOriginalRefreshStillStaysUnconfirmed() = runBlocking {
        val probe = MediaProbe(connections = { uri ->
            if (uri.toString() == entry) response(uri, 302, fresh) else response(uri)
        }, cookies = { null })
        val verified = probe.verifySelected(video(original = entry), WatermarkMode.ORIGINAL)
        assertEquals(fresh, verified.mediaUrl)
        assertEquals(setOf(fresh, entry), verified.mediaSources.map { it.url }.toSet())
        assertTrue(verified.mediaSources.all { it.mode == WatermarkMode.ORIGINAL })
        assertNull(WatermarkSources.actualMode(verified, WatermarkMode.WATERMARKED))
        assertTrue(WatermarkSources.available(verified, WatermarkMode.ORIGINAL))
        assertFalse(WatermarkSources.available(verified, WatermarkMode.WATERMARKED))
        assertFalse(WatermarkSources.available(verified, WatermarkMode.CLEAN))
    }

    @Test fun anExpiredFinalUrlCanRefreshFromItsSuccessfulOriginalEntry() = runBlocking {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                clean -> response(uri, 403)
                entry -> response(uri, 302, fresh)
                else -> response(uri)
            }
        }, cookies = { null })
        val verified = probe.verifySelected(video(source(clean), source(entry),
            source(marked, WatermarkMode.WATERMARKED), original = clean), WatermarkMode.CLEAN)
        assertEquals(fresh, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertFalse(verified.mediaSources.any { it.url == clean })
        assertTrue(verified.mediaSources.contains(source(marked, WatermarkMode.WATERMARKED)))
        assertFalse(requested.contains(marked))
    }

    @Test fun anUnsafeRedirectIsRejectedBeforeTheNextRequest() {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            response(uri, 302, "https://untrusted.example/video.mp4?signature=private_redirect")
        }, cookies = { null })
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { probe.verifySelected(video(source(entry)), WatermarkMode.CLEAN) }
        }
        assertEquals(listOf(entry), requested)
        assertFalse(error.message.orEmpty().contains("private_redirect"))
    }

    @Test fun successfulProbeReadsOnlyTheMp4HeaderAndClosesAnIgnoredRange() = runBlocking {
        lateinit var connection: FakeConnection
        val probe = MediaProbe(connections = { uri ->
            response(uri, bytes = mp4Header(512 * 1024)).also { connection = it }
        }, cookies = { null })
        probe.verifySelected(video(source(clean)), WatermarkMode.CLEAN)
        assertEquals("bytes=0-4095", connection.getRequestProperty("Range"))
        assertEquals(32, connection.bytesRead)
        assertTrue(connection.disconnected)
    }

    @Test fun cancellationCannotBecomeAFallbackToAnotherSource() {
        var requests = 0
        val probe = MediaProbe(connections = { _ ->
            requests++
            throw CancellationException("test cancellation")
        }, cookies = { null })
        assertThrows(CancellationException::class.java) {
            runBlocking { probe.verifySelected(video(source(clean), source(fresh)), WatermarkMode.CLEAN) }
        }
        assertEquals(1, requests)
    }

    @Test fun defaultCleanVerificationDoesNotProbeOtherVersionsToCompareTheirRedirects() = runBlocking {
        val markedEntry = entry.replace("/play/", "/playwm/")
        val diagnostics = mutableListOf<String>()
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            if (uri.toString() in listOf(entry, markedEntry)) response(uri, 302, fresh) else response(uri)
        }, cookies = { null })
        val verified = probe.verify(video(source(entry), source(markedEntry, WatermarkMode.WATERMARKED),
            original = markedEntry), diagnostics::add)
        assertEquals(fresh, verified.mediaUrl)
        assertTrue(WatermarkSources.available(verified, WatermarkMode.CLEAN))
        assertNull(WatermarkSources.actualMode(verified, WatermarkMode.WATERMARKED))
        assertEquals(listOf(entry, fresh), requested)
        assertFalse(diagnostics.any { it.startsWith("media_rendition_conflict") })
    }

    @Test fun aSelectedCleanRedirectMatchingTheRetainedMarkedFileIsRejectedWithoutSwitching() {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            if (uri.toString() == entry) response(uri, 302, marked) else response(uri)
        }, cookies = { null })
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { probe.verifySelected(video(source(entry), source(marked, WatermarkMode.WATERMARKED)),
                WatermarkMode.CLEAN) }
        }
        assertTrue(error.message.orEmpty().contains("同一个文件"))
        assertFalse(error.message.orEmpty().contains("切换版本"))
        assertFalse(error.message.orEmpty().contains("选择原始版本"))
        assertEquals(listOf(entry), requested)
    }

    @Test fun conflictingCleanCandidatesDoNotPreventCheckingAnIndependentThirdSource() = runBlocking {
        val second = clean.replace("clean.mp4", "second.mp4")
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            if (uri.toString() in listOf(clean, second)) response(uri, 302, marked) else response(uri)
        }, cookies = { null })
        val verified = probe.verify(video(source(clean), source(second), source(fresh),
            source(marked, WatermarkMode.WATERMARKED)))
        assertTrue(requested.contains(fresh))
        assertEquals(fresh, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertFalse(verified.mediaSources.any { it.mode == WatermarkMode.CLEAN && it.url in listOf(clean, second, marked) })
        assertFalse(requested.contains(marked))
    }

    @Test fun aHealthyOriginalCannotRescueTheDefaultCleanVerificationWhenItsCleanEntryFails() {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            if (MediaUrls.isPlaybackEntry(uri.toString())) response(uri, 403) else response(uri)
        }, cookies = { null })
        val sources = WatermarkSources.videoSources(listOf(marked), mediaIds = listOf("v0200f0000same_work"))
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { probe.verify(video(*sources.toTypedArray())) }
        }
        assertTrue(error.message.orEmpty().contains("HTTP 403"))
        assertEquals(1, requested.size)
        assertTrue(requested.single().contains("/play/?"))
        assertFalse(requested.contains(marked))
    }

    @Test fun anOriginalSharingTheCleanFileDoesNotCauseAVersionConflict() = runBlocking {
        val probe = MediaProbe(connections = { uri -> response(uri) }, cookies = { null })
        val diagnostics = mutableListOf<String>()
        val verified = probe.verify(video(source(clean), source(clean, WatermarkMode.ORIGINAL), original = clean), diagnostics::add)
        assertTrue(WatermarkSources.available(verified, WatermarkMode.CLEAN))
        assertTrue(WatermarkSources.available(verified, WatermarkMode.ORIGINAL))
        assertFalse(diagnostics.any { it.startsWith("media_rendition_conflict") })
    }

    @Test fun publicMediaEntryStillGetsCheckedAfterSeveralDeadSignedCdnAlternatives() = runBlocking {
        val first = clean.replace("clean", "expired1")
        val second = clean.replace("clean", "expired2")
        val third = clean.replace("clean", "expired3")
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, fresh)
                fresh -> response(uri)
                else -> response(uri, 403)
            }
        }, cookies = { null })
        val verified = probe.verifySelected(video(source(first), source(second), source(third), source(entry)), WatermarkMode.CLEAN)
        assertTrue(requested.contains(entry))
        assertEquals(fresh, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertFalse(requested.contains(third))
    }

    @Test fun selectingUnavailableCleanCannotFallBackToAnAvailableOriginal() {
        val probe = MediaProbe(connections = { uri -> response(uri) }, cookies = { null })
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { probe.verifySelected(video(source(marked, WatermarkMode.ORIGINAL)), WatermarkMode.CLEAN) }
        }
        assertTrue(error.message.orEmpty().contains("未读取到可用的视频地址"))
    }

    @Test fun theProbeEntryQuotaSurvivesMoreThanSixtyFourUnhealthyCdnVariants() = runBlocking {
        val variants = (0 until 70).map { source("https://v3.douyinvod.com/expired$it.mp4") }
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, fresh)
                fresh -> response(uri)
                else -> response(uri, 403)
            }
        }, cookies = { null })
        val verified = probe.verifySelected(video(*(variants + source(entry)).toTypedArray()), WatermarkMode.CLEAN)
        assertEquals(fresh, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertTrue(requested.contains(entry))
        assertEquals(4, requested.size) // Two failed CDN requests and the entry + final redirect target.
    }

    @Test fun defaultUnknownOriginalIsRejectedWithoutAnyNetworkRequest() {
        var requests = 0
        val probe = MediaProbe(connections = { uri -> requests++; response(uri) }, cookies = { null })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { probe.verify(video(source(marked, WatermarkMode.ORIGINAL))) }
        }
        assertEquals(0, requests)
    }

    @Test fun selectedCleanVerificationStopsAtItsFirstHealthyUrlInsteadOfCheckingEveryBackup() = runBlocking {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri -> requested += uri.toString(); response(uri) }, cookies = { null })
        val verified = probe.verifySelected(video(source(clean), source(fresh), source(entry),
            source(marked, WatermarkMode.WATERMARKED)), WatermarkMode.CLEAN)
        assertEquals(listOf(clean), requested)
        assertEquals(clean, verified.mediaUrl)
        assertTrue(verified.mediaSources.contains(source(fresh)))
        assertTrue(verified.mediaSources.contains(source(entry)))
    }

    @Test fun anUnusedClassifiedBackupCanRefreshTheExpiredParsedUrlBeforeSaving() = runBlocking {
        var expired = false
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            if (expired && uri.toString() == clean) response(uri, 403) else response(uri)
        }, cookies = { null })
        val parsed = probe.verify(video(source(clean), source(fresh), source(entry),
            source(marked, WatermarkMode.WATERMARKED), source(marked, WatermarkMode.ORIGINAL)))
        assertEquals(listOf(clean), requested)
        expired = true
        requested.clear()
        val refreshed = probe.verifySelected(parsed, WatermarkMode.CLEAN)
        assertEquals(listOf(clean, fresh), requested)
        assertEquals(fresh, WatermarkSources.select(refreshed, WatermarkMode.CLEAN).mediaUrl)
        assertFalse(refreshed.mediaSources.any { it.mode == WatermarkMode.CLEAN && it.url == clean })
        assertTrue(refreshed.mediaSources.contains(source(entry)))
        assertFalse(requested.contains(marked))
    }

    @Test fun theSuccessfulPublicEntryIsRetainedToRefreshItsExpiredFinalUrlBeforeSaving() = runBlocking {
        var expired = false
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when {
                uri.toString() == entry -> response(uri, 302, if (expired) fresh else clean)
                expired && uri.toString() == clean -> response(uri, 403)
                else -> response(uri)
            }
        }, cookies = { null })
        val parsed = probe.verify(video(source(entry), source(marked, WatermarkMode.WATERMARKED)))
        assertEquals(listOf(entry, clean), requested)
        expired = true
        requested.clear()
        val refreshed = probe.verifySelected(parsed, WatermarkMode.CLEAN)
        assertEquals(listOf(clean, entry, fresh), requested)
        assertEquals(fresh, refreshed.mediaUrl)
        assertTrue(refreshed.mediaSources.contains(source(entry)))
        assertFalse(requested.contains(marked))
    }

    @Test fun aNonMp4ResponseFallsBackOnlyToAnotherClassifiedCleanSource() = runBlocking {
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            if (uri.toString() == clean) response(uri, bytes = ByteArray(32)) else response(uri)
        }, cookies = { null })
        val verified = probe.verify(video(source(clean), source(fresh), source(marked, WatermarkMode.WATERMARKED)))
        assertEquals(listOf(clean, fresh), requested)
        assertEquals(fresh, verified.mediaUrl)
        assertFalse(verified.mediaSources.any { it.mode == WatermarkMode.CLEAN && it.url == clean })
    }

    @Test fun cancellingTheProbeStopsBeforeReadingOrTryingAnotherCandidate() {
        var requests = 0
        lateinit var probe: MediaProbe
        probe = MediaProbe(connections = { uri ->
            requests++
            probe.cancel()
            response(uri)
        }, cookies = { null })
        assertThrows(CancellationException::class.java) {
            runBlocking { probe.verify(video(source(clean), source(fresh))) }
        }
        assertEquals(1, requests)
    }

    @Test fun aNewExplicitMarkedRedirectIsRejectedAndOnlyACleanBackupCanRefreshIt() = runBlocking {
        val targets = listOf(entry.replace("/play/", "/playwm/"),
            "https://v3.douyinvod.com/new.mp4?watermark=1",
            "https://v3.douyinvod.com/new~watermark-v2:logo.mp4")
        targets.forEach { target ->
            val requested = mutableListOf<String>()
            val probe = MediaProbe(connections = { uri ->
                requested += uri.toString()
                if (uri.toString() == clean) response(uri, 302, target) else response(uri)
            }, cookies = { null })
            val verified = probe.verify(video(source(clean), source(fresh)))
            assertEquals(listOf(clean, fresh), requested)
            assertEquals(fresh, verified.mediaUrl)
            assertFalse(verified.mediaSources.any { it.mode == WatermarkMode.CLEAN && it.url == clean })
            assertFalse(requested.contains(target))
        }
    }

    @Test fun aCleanLabelCannotOverrideAnExplicitMarkedInitialAddress() {
        var requests = 0
        val probe = MediaProbe(connections = { uri -> requests++; response(uri) }, cookies = { null })
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { probe.verify(video(source("$clean&watermark=1"))) }
        }
        assertEquals(0, requests)
        assertTrue(error.message.orEmpty().contains("请重新解析"))
        assertFalse(error.message.orEmpty().contains("切换版本"))
    }

    @Test fun aTrustedCdnHttpSecondHopIsUpgradedAndRequestedOnlyThroughHttps() = runBlocking {
        val cold = "https://v5-coldx.douyinvod.com/first.mp4?token=private_cold"
        listOf(fresh.replace("https://", "http://"),
            fresh.replace("https://", "http://").replace(".com/", ".com:80/")).forEach { httpTarget ->
            val requested = mutableListOf<URI>()
            val diagnostics = mutableListOf<String>()
            val probe = MediaProbe(connections = { uri ->
                requested += uri
                when (uri.toString()) {
                    entry -> response(uri, 302, cold)
                    cold -> response(uri, 302, httpTarget)
                    else -> response(uri)
                }
            }, cookies = { null })
            val verified = probe.verify(video(source(entry)), diagnostics::add)
            assertEquals(listOf(entry, cold, fresh), requested.map(URI::toString))
            assertTrue(requested.all { it.scheme == "https" })
            assertEquals(fresh, verified.mediaUrl)
            assertEquals(fresh, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
            assertTrue(diagnostics.any { it.contains("scheme=http host=v6.douyinvod.com") })
            assertTrue(diagnostics.any { it.contains("upgraded=true") })
            assertFalse(diagnostics.joinToString().contains("private_"))
        }
    }

    @Test fun numericColdRelayOnItsConfirmedTlsPortVerifiesMp4AndPreservesTheRefreshEntry() = runBlocking {
        val cold = "https://n98-v-ncdncold.douyinvod.com/first.mp4?signature=private_cold"
        val target = "https://24898382.ydycdn.com:58001/a%2Fb/video.mp4?signature=private_a%2Bb%3D&x=&x=1"
        val requested = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, cold)
                cold -> response(uri, 302, target)
                target -> response(uri, 206, range = "bytes 0-31/128")
                else -> error("Unexpected numeric CDN request: ${uri.host}")
            }
        }, cookies = { null })
        val verified = probe.verify(video(source(entry), source(marked, WatermarkMode.WATERMARKED)), diagnostics::add)
        assertEquals(listOf(entry, cold, target), requested)
        assertEquals(target, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
        assertTrue(verified.mediaSources.contains(source(entry)))
        assertTrue(verified.mediaSources.contains(source(target)))
        assertFalse(requested.contains(marked))
        assertTrue(diagnostics.any { it.contains("host=24898382.ydycdn.com port=58001 allowed=true") })
        assertFalse(diagnostics.joinToString().contains("private_"))
    }

    @Test fun numericRelayDoesNotMakeWrongPortsMarkedUrlsOrNonMp4ContentValid() {
        val cold = "https://n98-v-ncdncold.douyinvod.com/first.mp4"
        for (target in listOf("https://24898382.ydycdn.com:58002/video.mp4",
            "https://24898382.ydycdn.com:58001/video.mp4?watermark=1",
            "https://node.ydycdn.com:58001/video.mp4")) {
            val requested = mutableListOf<String>()
            val probe = MediaProbe(connections = { uri ->
                requested += uri.toString()
                response(uri, 302, target)
            }, cookies = { null })
            assertThrows(IllegalStateException::class.java) { runBlocking { probe.verify(video(source(cold))) } }
            assertEquals(listOf(cold), requested)
        }
        val target = "https://24898382.ydycdn.com:58001/video.mp4"
        for ((type, bytes) in listOf("text/plain" to mp4Header(), "video/mp4" to ByteArray(32))) {
            val probe = MediaProbe(connections = { uri -> response(uri, type = type, bytes = bytes) }, cookies = { null })
            assertThrows(IllegalStateException::class.java) { runBlocking { probe.verify(video(source(target))) } }
        }
    }

    @Test fun coldSchedulingSecondHopsConfirmMp4AndKeepTheCleanPlatformRefreshEntry() = runBlocking {
        val cold = "https://v5-coldx.douyinvod.com/first.mp4?signature=private_cold"
        listOf("1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com",
            "1AAAUMQG5W1GGJGYJ6U2RGVZZXEGYJT3KXA4FLTUS4A.bdcgslb.com").forEach { host ->
            val target = "https://$host/a%2Fb/video.mp4?signature=private_a%2Bb%3D&x=&x=1"
            val requested = mutableListOf<String>()
            val diagnostics = mutableListOf<String>()
            val probe = MediaProbe(connections = { uri ->
                requested += uri.toString()
                when (uri.toString()) {
                    entry -> response(uri, 302, cold)
                    cold -> response(uri, 302, target)
                    else -> response(uri, 206, range = "bytes 0-31/128")
                }
            }, cookies = { null })
            val verified = probe.verify(video(source(entry), source(marked, WatermarkMode.WATERMARKED))) {
                diagnostics += DiagnosticText.clean(it)
            }
            assertEquals(listOf(entry, cold, target), requested)
            assertEquals(target, verified.mediaUrl)
            assertEquals(target, WatermarkSources.select(verified, WatermarkMode.CLEAN).mediaUrl)
            assertTrue(verified.mediaSources.contains(source(entry)))
            assertTrue(verified.mediaSources.contains(source(target)))
            assertFalse(requested.contains(marked))
            assertTrue(diagnostics.any { it.contains("host=$host") && it.contains("allowed=true") })
            assertFalse(diagnostics.joinToString().contains("private_"))
        }
    }

    @Test fun anExpiredSchedulingAddressRefreshesToAnotherDynamicHostWithinTheCleanSource() = runBlocking {
        val first = "https://1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com/first.mp4"
        val next = "https://1AAAUMQG5W1GGJGYJ6U2RGVZZXEGYJT3KXA4FLTUS4A.bdcgslb.com/next.mp4"
        val cold = "https://v5-coldx.douyinvod.com/first.mp4"
        var expired = false
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, cold)
                cold -> response(uri, 302, if (expired) next else first)
                first -> response(uri, if (expired) 403 else 200)
                else -> response(uri)
            }
        }, cookies = { null })
        val verified = probe.verify(video(source(entry), source(marked, WatermarkMode.WATERMARKED)))
        expired = true
        requested.clear()
        val refreshed = probe.verifySelected(verified, WatermarkMode.CLEAN)
        assertEquals(listOf(first, entry, cold, next), requested)
        assertEquals(next, refreshed.mediaUrl)
        assertTrue(refreshed.mediaSources.contains(source(entry)))
        assertFalse(requested.contains(marked))
    }

    @Test fun schedulingCdnStillCannotFollowUnknownOrExplicitWatermarkedThirdHops() {
        val dynamic = "https://1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com/first.mp4"
        listOf("https://unknown.example/private_path?signature=private_secret",
            "$dynamic?watermark=1", "$dynamic?%77atermark=1").forEach { target ->
            val requested = mutableListOf<String>()
            val probe = MediaProbe(connections = { uri ->
                requested += uri.toString(); response(uri, 302, target)
            }, cookies = { null })
            assertThrows(IllegalStateException::class.java) {
                runBlocking { probe.verify(video(source(dynamic), source(marked, WatermarkMode.WATERMARKED))) }
            }
            assertEquals(listOf(dynamic), requested)
        }
    }

    @Test fun aSchedulingHostnameDoesNotMakeHtmlOrNonMp4ContentAValidVideo() {
        val dynamic = "https://1AAAUMQG5W1GGJGYJ6U2RGVZZXEGYJT3KXA4FLTUS4A.bdcgslb.com/first.mp4"
        listOf("text/html" to mp4Header(), "video/mp4" to ByteArray(32)).forEach { (type, bytes) ->
            val probe = MediaProbe(connections = { uri -> response(uri, type = type, bytes = bytes) }, cookies = { null })
            assertThrows(IllegalStateException::class.java) {
                runBlocking { probe.verify(video(source(dynamic))) }
            }
        }
    }

    @Test fun aRejectedSecondHopIsDiagnosedWithoutRequestingHttpOrUntrustedTargets() {
        val cold = "https://v5-coldx.douyinvod.com/first.mp4"
        val targets = listOf("http://unknown.example/private_path?token=private_token",
            "https://unknown.example/private_path?token=private_token",
            "http://v6.douyinvod.com:8080/private_path?token=private_token",
            "http://private_user@v6.douyinvod.com/private_path?token=private_token")
        targets.forEach { target ->
            val requested = mutableListOf<URI>()
            val diagnostics = mutableListOf<String>()
            val probe = MediaProbe(connections = { uri ->
                requested += uri
                if (uri.toString() == entry) response(uri, 302, cold) else response(uri, 302, target)
            }, cookies = { null })
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { probe.verify(video(source(entry)), diagnostics::add) }
            }
            assertEquals(listOf(entry, cold), requested.map(URI::toString))
            assertTrue(requested.all { it.scheme == "https" })
            assertTrue(diagnostics.any { it.contains("media_redirect") && it.contains("allowed=false") })
            assertFalse((diagnostics.joinToString() + error.message.orEmpty()).contains("private_"))
        }
    }

    @Test fun anInitialHttpVideoSourceRemainsRejectedWithoutMakingAnyRequest() {
        var requests = 0
        val probe = MediaProbe(connections = { uri -> requests++; response(uri) }, cookies = { null })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { probe.verify(video(source(clean.replace("https://", "http://")))) }
        }
        assertEquals(0, requests)
    }

    @Test fun upgradingTrustedHttpDoesNotPermitAnExplicitMarkedRedirect() {
        val target = "http://v6.douyinvod.com/fresh.mp4?watermark=1"
        val requested = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString(); response(uri, 302, target)
        }, cookies = { null })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { probe.verify(video(source(entry))) }
        }
        assertEquals(listOf(entry), requested)
    }

    private fun response(uri: URI, code: Int = 200, location: String? = null,
                         bytes: ByteArray = mp4Header(), range: String? = null,
                         type: String = "video/mp4") = FakeConnection(uri, code, location, bytes, range, type)

    private fun mp4Header(size: Int = 32) = ByteArray(size).apply {
        this[3] = 32
        "ftypisom".toByteArray(Charsets.US_ASCII).copyInto(this, 4)
    }

    private class FakeConnection(uri: URI, private val status: Int, private val location: String?,
                                 private val bytes: ByteArray, private val range: String?,
                                 private val type: String) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        var bytesRead = 0
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = type
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getHeaderField(name: String?): String? = when {
            name.equals("Location", true) -> location
            name.equals("Content-Range", true) -> range
            else -> null
        }
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
    }
}
