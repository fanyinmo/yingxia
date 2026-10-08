package com.local.douyinsaver

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled JS responses only: no page/network requests, account access, files or preferences. */
@RunWith(AndroidJUnit4::class)
class DesktopAlbumResolverLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val id = "7397812747032468745"

    @Test
    fun staticObservationDoesNotSucceedAndOrderedMotionNeedsTwoStablePolls() {
        withFixture(listOf(snapshot(motion = false), snapshot(), snapshot())) { fixture ->
            fixture.await { it.webView.extractions >= 1 }
            fixture.main {
                assertTrue("A static observation was accepted before the hydration grace", it.results.isEmpty())
            }
            fixture.await { it.webView.extractions >= 2 }
            fixture.main { assertTrue("One motion observation was accepted as stable", it.results.isEmpty()) }
            fixture.await { it.results.isNotEmpty() }
            fixture.main {
                assertEquals(listOf(DesktopAlbumStatus.CANDIDATE), it.results.map { result -> result.status })
                val album = checkNotNull(it.results.single().album)
                assertEquals(id, album.id)
                assertEquals(2, album.images.size)
                assertEquals(AlbumAssetKind.STATIC, album.images[0].kind)
                assertEquals(AlbumAssetKind.LIVE, album.images[1].kind)
                assertTrue(album.images[0].motion == null)
                assertTrue(album.images[1].motion != null)
                assertTrue(WatermarkSources.available(album, WatermarkMode.CLEAN))
                val diagnostics = it.diagnostics.joinToString("\n")
                assertTrue(diagnostics.contains("desktop_target_images"))
                assertTrue(diagnostics.contains("\"ownVideoPresent\":true"))
                assertTrue(diagnostics.contains("v3.douyinvod.com"))
                assertFalse(diagnostics.contains("do-not-log"))
                assertFalse(diagnostics.contains("ignoredResponseBody"))
                assertFalse(diagnostics.contains("cookie"))
            }
        }
    }

    @Test
    fun optInCompleteStaticAndNativeAnimatedArraysWaitForHydrationAndTwoStableObservations() {
        listOf(AlbumAssetKind.STATIC, AlbumAssetKind.ANIMATED).forEach { kind ->
            withFixture(listOf(snapshot(motion = false, stillKind = kind)), allowStaticAlbums = true) { fixture ->
                fixture.await { it.webView.extractions >= 1 }
                fixture.main { assertTrue("The initial still array must not hide a later clip", it.results.isEmpty()) }
                fixture.await(timeoutMs = 12_000) { it.results.isNotEmpty() }
                fixture.main {
                    // Observe actual callback timestamps rather than assuming an
                    // emulator always schedules every poll at exactly 800 ms.
                    val graceEnd = it.webView.extractionTimes.first() + 8_000L
                    assertTrue("The grace was skipped", it.resultTimes.single() >= graceEnd)
                    assertTrue("Two post-hydration complete arrays are required",
                        it.webView.extractionTimes.count { time -> time >= graceEnd } >= 2)
                    assertEquals(listOf(DesktopAlbumStatus.CANDIDATE), it.results.map { result -> result.status })
                    val album = checkNotNull(it.results.single().album)
                    assertEquals(id, album.id); assertEquals(2, album.images.size)
                    assertTrue(album.images.all { image -> image.motion == null && image.kind == kind })
                    assertTrue(WatermarkSources.available(album, WatermarkMode.CLEAN))
                }
            }
        }
    }

    @Test
    fun anOptInStillObservationDoesNotWinBeforeALateOwnedMotionAppearsDuringHydration() {
        withFixture(listOf(snapshot(motion = false), snapshot(motion = false), snapshot(), snapshot()),
            allowStaticAlbums = true) { fixture ->
            val started = SystemClock.elapsedRealtime()
            fixture.await { it.webView.extractions >= 2 }
            fixture.main { assertTrue("Still covers settled before the delayed motion", it.results.isEmpty()) }
            fixture.await { it.webView.extractions >= 3 }
            fixture.main { assertTrue("One delayed motion observation is not stable", it.results.isEmpty()) }
            fixture.await { it.results.isNotEmpty() }
            fixture.main {
                assertTrue("The existing fast dynamic path was unnecessarily delayed", SystemClock.elapsedRealtime() - started < 8_000)
                assertEquals(listOf(DesktopAlbumStatus.CANDIDATE), it.results.map { result -> result.status })
                val album = checkNotNull(it.results.single().album)
                assertEquals(AlbumAssetKind.LIVE, album.images.last().kind)
                assertTrue(album.images.last().motion != null)
            }
        }
    }

    @Test
    fun theDefaultDynamicReaderStillRejectsACompleteStaticAlbumAfterHydration() {
        withFixture(listOf(snapshot(motion = false))) { fixture ->
            fixture.await { it.webView.extractions >= 2 }
            fixture.main { assertTrue(it.results.isEmpty()) }
            fixture.await(timeoutMs = 12_000) { it.results.isNotEmpty() }
            fixture.main {
                assertEquals(listOf(DesktopAlbumStatus.UNAVAILABLE), it.results.map { result -> result.status })
                assertEquals(2, checkNotNull(it.results.single().album).images.size)
                assertTrue(checkNotNull(it.results.single().album).images.all { image -> image.motion == null })
            }
        }
    }

    @Test
    fun evenOptInCannotTreatAMissingLiveOrUnknownDynamicClipAsAStaticSuccess() {
        listOf(AlbumAssetKind.LIVE, AlbumAssetKind.DYNAMIC).forEach { kind ->
            withFixture(listOf(snapshot(motion = false, stillKind = kind)), allowStaticAlbums = true) { fixture ->
                fixture.await { it.webView.extractions >= 1 }
                fixture.main {
                    assertTrue(it.results.isEmpty())
                    // The actual eight-second wait is covered above. Advance only
                    // this controller's grace clock to test the post-grace rejection.
                    DesktopAlbumResolver::class.java.getDeclaredField("hydratedSince").apply { isAccessible = true }
                        .set(it.resolver, SystemClock.elapsedRealtime() - 8_001L)
                }
                fixture.await { it.results.isNotEmpty() }
                fixture.main {
                    assertEquals(listOf(DesktopAlbumStatus.UNAVAILABLE), it.results.map { result -> result.status })
                    assertTrue(it.results.none { result -> result.status == DesktopAlbumStatus.CANDIDATE })
                }
            }
        }
    }

    @Test
    fun ordinaryVideoFallbackRequiresOwnedVideoDataAndTwoStablePollsRatherThanBgmOrAnotherWork() {
        withFixture(listOf(videoSnapshot(includeVideo = false), videoSnapshot(videoOwner = "7397812747032468746"),
            videoSnapshot(), videoSnapshot()), allowVideo = true, allowStaticAlbums = true) { fixture ->
            fixture.await { it.webView.extractions >= 2 }
            fixture.main { assertTrue("BGM or a different work became a video candidate", it.results.isEmpty()) }
            fixture.await { it.webView.extractions >= 3 }
            fixture.main { assertTrue("One owned video observation was accepted as stable", it.results.isEmpty()) }
            fixture.await { it.results.isNotEmpty() }
            fixture.main {
                assertEquals(listOf(DesktopAlbumStatus.CANDIDATE), it.results.map { result -> result.status })
                val video = checkNotNull(it.results.single().album)
                assertEquals(id, video.id); assertFalse(video.isAlbum)
                assertEquals(32, video.width); assertEquals(32, video.height)
                assertEquals(1.0, video.durationSeconds, 0.0)
                assertEquals("https://v3.douyinvod.com/controlled-own-video.mp4", video.mediaUrl)
                assertTrue(WatermarkSources.available(video, WatermarkMode.CLEAN))
                assertEquals(listOf("https://www.douyin.com/video/$id"), it.webView.loadedUrls)
            }
        }
    }

    @Test
    fun emptyCompleteVideoPageStillAcceptsLaterOwnedHydrationAfterTheGraceBoundary() {
        withFixture(listOf(videoSnapshot(includeVideo = false)), allowVideo = true, allowStaticAlbums = true) { fixture ->
            fixture.await { it.webView.extractions >= 2 }
            fixture.main {
                DesktopAlbumResolver::class.java.getDeclaredField("hydratedSince").apply { isAccessible = true }
                    .set(it.resolver, SystemClock.elapsedRealtime() - 8_001L)
            }
            fixture.await { it.webView.extractions >= 4 }
            fixture.main {
                assertTrue("An empty complete document must not end the video fallback before later hydration", it.results.isEmpty())
                it.webView.enqueue(videoSnapshot(), videoSnapshot())
            }
            fixture.await { it.results.isNotEmpty() }
            fixture.main { assertEquals(DesktopAlbumStatus.CANDIDATE, it.results.single().status) }
        }
    }

    @Test
    fun missingPosterWrongIndexAndWrongOwnerCannotProduceCandidates() {
        withFixture(listOf(snapshot(missingPoster = true, truncated = true), snapshot(missingPoster = true, truncated = true),
            snapshot(wrongIndex = true, truncated = true), snapshot(wrongIndex = true, truncated = true),
            snapshot(owner = "7397812747032468746", truncated = true))) { fixture ->
            fixture.await { it.webView.extractions >= 5 }
            fixture.main { assertTrue("An incomplete or unrelated array was accepted", it.results.isEmpty()) }
        }
    }

    @Test
    fun globalTruncationDoesNotHideACompleteOwnedArray() {
        withFixture(listOf(snapshot(truncated = true), snapshot(truncated = true))) { fixture ->
            fixture.await { it.results.isNotEmpty() }
            fixture.main {
                assertEquals(DesktopAlbumStatus.CANDIDATE, it.results.single().status)
                val album = checkNotNull(it.results.single().album)
                assertEquals(id, album.id)
                assertEquals(2, album.images.size)
                assertTrue(album.images[1].motion != null)
                assertTrue(WatermarkSources.available(album, WatermarkMode.CLEAN))
            }
        }
    }

    @Test
    fun onlyAnActualGatePausesAndManualVerificationCanResumeTheSameWork() {
        withFixture(listOf(
            snapshot(motion = false, status = "needs_verification", gate = "login", actualGate = false),
            snapshot(motion = false, status = "needs_verification", gate = "captcha", actualGate = true),
        )) { fixture ->
            fixture.await { it.webView.extractions >= 1 }
            fixture.main { assertTrue("A permanent login button was treated as a gate", it.results.isEmpty()) }
            fixture.await { it.results.isNotEmpty() }
            fixture.main {
                assertEquals(DesktopAlbumStatus.NEEDS_VERIFICATION, it.results.single().status)
                assertFalse(it.webView.isFocusable)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, it.webView.importantForAccessibility)
                it.webView.touchEvents = 0
                touch(it.webView)
                assertEquals("The hidden page accepted touch", 0, it.webView.touchEvents)
                it.resolver.enterVerification()
                assertTrue(it.webView.isFocusable)
                assertTrue(it.webView.isFocusableInTouchMode)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, it.webView.importantForAccessibility)
                touch(it.webView)
                assertTrue("The manual page still consumed touch before dispatch", it.webView.touchEvents > 0)
                it.webView.enqueue(snapshot(), snapshot())
                it.resolver.resumeAfterVerification()
                assertFalse(it.webView.isFocusable)
                assertFalse(it.webView.isFocusableInTouchMode)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, it.webView.importantForAccessibility)
                assertEquals(listOf("https://www.douyin.com/note/$id", "https://www.douyin.com/note/$id"), it.webView.loadedUrls)
            }
            fixture.await { it.results.size == 2 }
            fixture.main {
                assertEquals(listOf(DesktopAlbumStatus.NEEDS_VERIFICATION, DesktopAlbumStatus.CANDIDATE),
                    it.results.map { result -> result.status })
            }
        }
    }

    @Test
    fun cancelledAndDisposedControllersIgnorePreviouslyQueuedJavascriptCallbacks() {
        withFixture(listOf(snapshot()), holdCallbacks = true) { fixture ->
            fixture.await { it.webView.pendingCallbacks.isNotEmpty() }
            fixture.main {
                val pending = it.webView.pendingCallbacks.single()
                it.resolver.cancel()
                assertTrue(it.webView.destroyed)
                assertEquals(listOf(DesktopAlbumStatus.CANCELLED), it.results.map { result -> result.status })
                pending.onReceiveValue(snapshot())
                it.resolver.cancel()
                assertEquals("A stale callback reported a result after cancellation", 1, it.results.size)
            }
        }
        withFixture(listOf(snapshot()), holdCallbacks = true) { fixture ->
            fixture.await { it.webView.pendingCallbacks.isNotEmpty() }
            fixture.main {
                val pending = it.webView.pendingCallbacks.single()
                it.resolver.dispose()
                assertTrue(it.webView.destroyed)
                pending.onReceiveValue(snapshot())
                it.resolver.dispose()
                assertTrue("A disposed controller reported a result", it.results.isEmpty())
            }
        }
    }

    private fun withFixture(responses: List<String>, holdCallbacks: Boolean = false,
        allowStaticAlbums: Boolean = false, allowVideo: Boolean = false, block: (Fixture) -> Unit) {
        // Use the shipped Activity as a layout host. The controlled WebView never
        // loads a real page and does not need the opt-in phonecheck probe Activity.
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val fixture = Fixture(scenario)
            scenario.onActivity { activity ->
                fixture.webView = FakeWebView(activity, responses, holdCallbacks)
                val root = FrameLayout(activity)
                root.addView(fixture.webView, FrameLayout.LayoutParams(1280, 720))
                activity.setContentView(root)
                fixture.resolver = DesktopAlbumResolver(fixture.webView, id, {
                    fixture.resultTimes += SystemClock.elapsedRealtime(); fixture.results += it
                }, { fixture.diagnostics += it },
                    allowStaticAlbums = allowStaticAlbums, allowVideo = allowVideo)
            }
            try { block(fixture) }
            finally { scenario.onActivity { fixture.resolver.dispose() } }
        }
    }

    private inner class Fixture(val scenario: ActivityScenario<MainActivity>) {
        lateinit var webView: FakeWebView
        lateinit var resolver: DesktopAlbumResolver
        val results = mutableListOf<DesktopAlbumResult>()
        val resultTimes = mutableListOf<Long>()
        val diagnostics = mutableListOf<String>()

        fun main(action: (Fixture) -> Unit) { scenario.onActivity { action(this) } }

        fun await(timeoutMs: Long = 6_000L, condition: (Fixture) -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = condition(this) }
                if (ready) return
                SystemClock.sleep(20)
            }
            throw AssertionError("Controlled desktop resolver did not reach the expected state")
        }
    }

    private class FakeWebView(context: Context, responses: List<String>, private val holdCallbacks: Boolean) : WebView(context) {
        private val queued = ArrayDeque(responses)
        private var lastResponse = responses.last()
        val loadedUrls = mutableListOf<String>()
        val pendingCallbacks = mutableListOf<ValueCallback<String>>()
        var extractions = 0
        val extractionTimes = mutableListOf<Long>()
        var destroyed = false
        var touchEvents = 0

        override fun loadUrl(url: String) { loadedUrls += url }

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            if (resultCallback == null) return
            extractions++
            extractionTimes += SystemClock.elapsedRealtime()
            if (holdCallbacks) pendingCallbacks += resultCallback
            else {
                lastResponse = queued.pollFirst() ?: lastResponse
                resultCallback.onReceiveValue(lastResponse)
            }
        }

        fun enqueue(vararg responses: String) { responses.forEach { queued.addLast(it) } }
        override fun onTouchEvent(event: MotionEvent): Boolean { touchEvents++; return false }

        override fun destroy() {
            if (destroyed) return
            destroyed = true
            super.destroy()
        }
    }

    private fun touch(view: View) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun snapshot(motion: Boolean = true, missingPoster: Boolean = false, wrongIndex: Boolean = false,
        owner: String = id, status: String = "candidate", gate: String = "none", actualGate: Boolean = false,
        truncated: Boolean = false, stillKind: AlbumAssetKind = AlbumAssetKind.STATIC): String {
        val images = JSONArray()
        repeat(2) { index ->
            val poster = "https://p3.douyinpic.com/controlled-$index.${if (!motion && stillKind == AlbumAssetKind.ANIMATED) "gif" else "jpg"}"
            val hasPoster = !(missingPoster && index == 0)
            val image = JSONObject().put("sourceIndex", if (wrongIndex && index == 1) 0 else index)
                .put("imageKey", "controlled-$index").put("width", 32).put("height", 32)
                .put("urls", JSONArray(if (hasPoster) listOf(poster) else emptyList<String>()))
                .put("displayUrls", JSONArray(if (hasPoster) listOf(poster) else emptyList<String>()))
                .put("downloadUrls", JSONArray()).put("mimeType", if (!motion && stillKind == AlbumAssetKind.ANIMATED) "image/gif" else "image/jpeg")
                .put("kind", if (motion && index == 1) "LIVE" else if (!motion) stillKind.name else "STATIC")
            if (motion && index == 1) image.put("motion", JSONObject()
                .put("playUrls", JSONArray(listOf("https://v3.douyinvod.com/controlled-motion.mp4")))
                .put("downloadUrls", JSONArray(listOf("https://v3.douyinvod.com/controlled-marked.mp4")))
                .put("mediaIds", JSONArray()).put("width", 32).put("height", 32).put("durationSeconds", 1.0))
            images.put(image)
        }
        val stats = JSONObject().put("state", "complete").put("images", 2).put("declaredImages", 2)
            .put("motions", if (motion) 1 else 0).put("missingPosters", if (missingPoster) 1 else 0)
            .put("matchingOwners", 1).put("provenance", "router").put("gate", gate)
            .put("truncated", truncated)
            .put("captchaVisible", gate == "captcha" && actualGate)
            .put("loginGateVisible", gate == "login" && actualGate)
            .put("targetImages", JSONArray(listOf(JSONObject().put("sourceIndex", 1)
                .put("kind", if (motion) "LIVE" else "STATIC").put("ownVideoPresent", motion)
                .put("ownVideoType", if (motion) "object" else "missing").put("candidateMotion", motion)
                .put("playUrls", if (motion) 1 else 0).put("downloadUrls", if (motion) 1 else 0)
                .put("fields", JSONObject().put("video", motion).put("cookie", "do-not-log"))
                .put("videoKeys", JSONArray(listOf("play_addr", "cookie")))
                .put("hosts", JSONArray(listOf("v3.douyinvod.com", "https://example.com/private?sessionid=do-not-log")))
                .put("ignoredResponseBody", "do-not-log"))))
        return JSONObject.quote(JSONObject().put("page", "https://www.douyin.com/note/$id").put("owner", owner)
            .put("title", "Controlled desktop album").put("images", images).put("bgmUrls", JSONArray())
            .put("bgmDuration", 0.0).put("status", status).put("stats", stats).toString())
    }

    private fun videoSnapshot(includeVideo: Boolean = true, videoOwner: String = id): String {
        val videos = JSONArray()
        if (includeVideo) videos.put(JSONObject().put("owner", videoOwner).put("title", "Controlled exact-work video")
            .put("playUrls", JSONArray(listOf("https://v3.douyinvod.com/controlled-own-video.mp4")))
            .put("downloadUrls", JSONArray(listOf("https://v3.douyinvod.com/controlled-marked-video.mp4")))
            .put("mediaIds", JSONArray()).put("displayPlaybackUrls", JSONArray()).put("coverUrls", JSONArray())
            .put("width", 32).put("height", 32).put("durationSeconds", 1.0))
        val stats = JSONObject().put("state", "complete").put("images", 0).put("declaredImages", 0)
            .put("motions", 0).put("missingPosters", 0).put("matchingOwners", 1).put("provenance", "pace")
            .put("gate", "none").put("captchaVisible", false).put("loginGateVisible", false)
            .put("conflictingAlbums", false)
        return JSONObject.quote(JSONObject().put("page", "https://www.douyin.com/video/$id").put("owner", id)
            .put("title", "Controlled exact-work video").put("images", JSONArray()).put("videoCandidates", videos)
            .put("bgmUrls", JSONArray(listOf("https://sf11.douyinstatic.com/controlled-bgm.mp3")))
            .put("bgmDuration", 1.0).put("status", "candidate").put("stats", stats).toString())
    }
}
