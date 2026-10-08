package com.local.douyinsaver

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit official-network investigation. Does not launch an Activity or alter user data. */
@RunWith(AndroidJUnit4::class)
class EmulatorVideoSourceTest {
    @Test fun captureExactWorkBeforeProbingSoARejectedCdnRemainsDiagnosable() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires run_emulator_source=true", args.getString("run_emulator_source") == "true")
        val share = ShareLinks.extract(requireNotNull(args.getString("shareUrl")))
        val expectedId = requireNotNull(args.getString("expectedId"))
        require(expectedId.matches(Regex("[0-9]{15,22}")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "emulator_video_source_${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        val report = JSONObject().put("expectedId", expectedId).put("uiUsed", false)
            .put("probePassed", false).put("rawCandidateUrlsPrivate", true)
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        fun save() {
            val snapshot = synchronized(diagnostics) { diagnostics.toList() }
            report.put("diagnostics", JSONArray(snapshot))
            File(root, "source.json").writeText(report.toString(2))
        }
        println("emulator_video_source_directory=${root.absolutePath}")
        val parsed = AtomicReference<ParsedVideo?>()
        val parseError = AtomicReference<String?>()
        val settled = CountDownLatch(1)
        var parser: BrowserParser? = null
        var browser: WebView? = null
        var host: FrameLayout? = null
        try {
            val resolved = ShareLinks.resolveShare(share)
            report.put("resolvedId", resolved.id).put("resolvedUrl", resolved.url)
            save()
            assertEquals("The share must map to the requested work", expectedId, resolved.id)
            instrumentation.runOnMainSync {
                host = FrameLayout(context)
                browser = WebView(context).also { view ->
                    host!!.addView(view, FrameLayout.LayoutParams(1080, 1920))
                    host!!.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
                    host!!.layout(0, 0, 1080, 1920)
                }
                parser = BrowserParser(browser!!, expectedId,
                    onResult = { parsed.set(it); settled.countDown() },
                    onError = { parseError.set(it); settled.countDown() },
                    onDiagnostic = { diagnostics += DiagnosticText.clean(it, 2400) }, initialUrl = resolved.url)
            }
            assertTrue("Official page extraction timed out", settled.await(75, TimeUnit.SECONDS))
            parseError.get()?.let { report.put("parseFailure", it); save() }
            val content = parsed.get()
            assertNotNull("Official page did not return a candidate: ${parseError.get()}", content)
            content!!
            report.put("candidate", JSONObject().put("id", content.id).put("title", content.title)
                .put("mediaUrl", content.mediaUrl).put("width", content.width).put("height", content.height)
                .put("duration", content.durationSeconds).put("isAlbum", content.isAlbum)
                .put("sources", JSONArray(content.mediaSources.map { source ->
                    JSONObject().put("url", source.url).put("mode", source.mode.name)
                })))
            save() // Keep candidate evidence even when every following request is rejected.
            val playerSnapshot = AtomicReference<String?>()
            val playerDone = CountDownLatch(1)
            instrumentation.runOnMainSync {
                browser!!.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('video')).slice(0,8).map(v=>({src:v.src,currentSrc:v.currentSrc,ready:v.readyState,width:v.videoWidth,height:v.videoHeight,duration:Number.isFinite(v.duration)?v.duration:0})))") {
                    playerSnapshot.set(it); playerDone.countDown()
                }
            }
            if (playerDone.await(5, TimeUnit.SECONDS)) report.put("playersPrivate", playerSnapshot.get())
            assertEquals(expectedId, content.id)
            assertFalse("This explicit case must be a video", content.isAlbum)
            try {
                val verified = runBlocking { withTimeout(65_000) { MediaProbe().verify(content) { diagnostics += it } } }
                report.put("probePassed", true).put("verifiedUrl", verified.mediaUrl)
                report.put("verifiedSources", JSONArray(verified.mediaSources.map { source ->
                    JSONObject().put("url", source.url).put("mode", source.mode.name)
                }))
            } catch (error: Exception) {
                report.put("probeFailureType", error.javaClass.simpleName)
                    .put("probeFailure", DiagnosticText.clean(error.message.orEmpty(), 1200))
                throw error
            } finally { save() }
        } finally {
            save()
            instrumentation.runOnMainSync {
                if (parser != null) parser!!.dispose() else browser?.destroy()
                parser = null; browser = null; host?.removeAllViews(); host = null
            }
        }
    }
}
