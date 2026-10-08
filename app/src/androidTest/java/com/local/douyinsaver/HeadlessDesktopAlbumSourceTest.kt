package com.local.douyinsaver

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Direct desktop component evidence only; no Activity, Window, input, service or power changes. */
@RunWith(AndroidJUnit4::class)
class HeadlessDesktopAlbumSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as Application
    private val handler = Handler(Looper.getMainLooper())
    private var directory: File? = null
    private var deadline = Long.MAX_VALUE
    private var executor: ExecutorService? = null
    private val worker = AtomicReference<Future<*>?>(null)
    private val probe = AtomicReference<MediaProbe?>(null)
    private val transfer = AtomicReference<MediaTransfer?>(null)
    private val diagnostics: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val mainTimeoutFiles: MutableList<String> = Collections.synchronizedList(mutableListOf())
    // Exact provider-owned runtime namespaces only; never exclude business or
    // test namespaces by prefix. Chromium's metrics/AwOriginVisitLogger.java and
    // metrics/AwSiteVisitLogger.java record visited hashes in this shared file.
    private val webViewRuntimePreferenceNames = setOf("WebViewChromiumPrefs", "AwOriginVisitLoggerPrefs")

    @Test fun explicitSameWorkDesktopCandidateHasRealOwnedMotionWithoutAnyWindow() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit run_headless_desktop_source=true", args.getString("run_headless_desktop_source") == "true")
        val shareText = requireNotNull(args.getString("shareUrl")) { "shareUrl is required" }
        val expectedId = args.getString("expectedId")?.also { require(it.matches(Regex("[0-9]{15,22}"))) }
        val root = File(context.cacheDir, "headless_desktop_source_${UUID.randomUUID()}").apply {
            check(mkdir() && canonicalFile.parentFile == context.cacheDir.canonicalFile)
        }
        directory = root
        File(root, "share-input.txt").writeText(shareText)
        val started = SystemClock.elapsedRealtime()
        deadline = started + 120_000L
        executor = Executors.newSingleThreadExecutor { action ->
            Thread(action, "headless-desktop-source").apply { isDaemon = true }
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = JSONObject().put("success", false).put("phase", "share_mapping")
            .put("sourceVerified", false).put("protectionChecksPassed", false).put("cookieProtectionVerified", false)
            .put("sourceOnly", true).put("evidenceContext", "DESKTOP_DIRECT_COMPONENT").put("scope", "DESKTOP_DIRECT_COMPONENT")
            .put("uiUsed", false).put("activityUsed", false).put("windowAttached", false)
            .put("inputInjected", false).put("permissionsChanged", false).put("cpuServiceUsed", false).put("powerSettingsChanged", false)
            .put("mobileParseVerified", false).put("appDownloadVerified", false).put("galleryVerified", false)
            .put("mediaStoreWrites", 0).put("testPreferenceWrites", 0).put("totalBudgetMs", 120_000L)
            .put("installedVersionName", installed.versionName)
            .put("installedVersionCode", if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong())
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("expectedId", expectedId ?: JSONObject.NULL).put("runRoot", root.name)
            .put("resolverStatus", "UNSTARTED").put("cookieSnapshotScope", "OBSERVED_URLS_ONLY")
        val preferences = userPreferences()
        val runtimePreferencesBefore = readPreferences(webViewRuntimePreferenceNames)
        val chromiumBefore = runtimePreferencesBefore.getValue("WebViewChromiumPrefs")
        report.put("protectedBusinessPreferenceCount", preferences.size)
            .put("webViewRuntimePreferencesBefore", runtimePreferenceSummary(runtimePreferencesBefore))
            .put("webViewRuntimePreferenceBoundary", "EXACT_PROVIDER_NAMES_KEY_SHA256_COUNT_ONLY; navigation statistics may change; no raw values, restore or deletion")
        val history = DownloadRecords(application).history().toList()
        val historyUris = history.flatMap { it.uris }.toSet()
        val background = File(context.filesDir, "appearance/background.jpg")
        val backgroundBefore = background.takeIf(File::isFile)?.let(::sha256)
        report.put("protectedHistoryCount", history.size).put("protectedHistoryUriCount", historyUris.size)
        val cookieScopes = linkedMapOf<String, List<String>>()
        val cookieScopeLabels = linkedMapOf<String, String>()
        val cookieObservations = JSONArray()
        val cookieHmacKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cookieMac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(cookieHmacKey, "HmacSHA256"))
        }
        report.put("cookieObservations", cookieObservations)
            .put("cookieHashPolicy", "HMAC_SHA256_PER_RUN_IN_MEMORY_KEY")
        var acceptCookiesBefore: Boolean? = null
        var resolvedId: String? = null
        var host: FrameLayout? = null
        var webView: WebView? = null
        var resolver: DesktopAlbumResolver? = null
        val acceptCallbacks = AtomicBoolean(true)
        val result = AtomicReference<DesktopAlbumResult?>(null)
        val settled = CountDownLatch(1)
        var failure: Throwable? = null
        var verified = false
        fun saveReport() {
            val file = File(root, "${resolvedId ?: expectedId ?: "unresolved"}.json")
            file.writeText(report.toString(2))
            println("headless_desktop_source_report=${file.absolutePath}")
        }
        fun recordCookieObservation(phase: String, observed: Map<String, List<String>>) {
            // Only labels, names, counts and keyed hashes reach JSON; no raw pair, URL or key.
            val scopes = JSONArray()
            observed.forEach { (scope, after) ->
                scopes.put(cookieScopeDifference(checkNotNull(cookieScopeLabels[scope]),
                    checkNotNull(cookieScopes[scope]), after, cookieMac))
            }
            cookieObservations.put(JSONObject().put("phase", phase)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("scopeCount", scopes.length())
                .put("scopes", scopes))
        }
        fun observeCookies(phase: String): Map<String, List<String>> {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Cookie observations require the main thread" }
            val manager = CookieManager.getInstance()
            return cookieScopes.keys.associateWith { scope -> cookiePairs(manager.getCookie(scope)) }
                .also { recordCookieObservation(phase, it) }
        }
        try {
            val share = ShareLinks.extract(shareText)
            report.put("shareUrl", share)
            main("cookie baseline") {
                val manager = CookieManager.getInstance()
                acceptCookiesBefore = manager.acceptCookie()
                // The production resolver sets this to true; never change a pre-existing false policy.
                assertTrue("DesktopAlbumResolver requires an already enabled cookie policy", acceptCookiesBefore == true)
                listOf(share to "SHARE", "https://www.douyin.com/" to "DOUYIN_ROOT",
                    "https://www.iesdouyin.com/" to "IES_ROOT").forEach { (scope, label) ->
                    cookieScopeLabels.getOrPut(scope) { label }
                    cookieScopes[scope] = cookiePairs(manager.getCookie(scope))
                }
                recordCookieObservation("baseline", cookieScopes)
            }
            val resolved = io("official share mapping") { ShareLinks.resolveShare(share) }
            main("cookie after share mapping") { observeCookies("after_share_mapping") }
            resolvedId = resolved.id
            report.put("id", resolved.id).put("resolvedShareUrl", resolved.url)
                .put("idMatchesExpected", expectedId == null || expectedId == resolved.id)
            assertTrue("The official share maps to another work", expectedId == null || expectedId == resolved.id)
            val page = "https://www.douyin.com/note/${resolved.id}"
            report.put("desktopPage", page).put("phase", "desktop_reading")
            saveReport()
            main("create unattached desktop host") {
                cookieScopeLabels.getOrPut(page) { "SAME_WORK_NOTE" }
                cookieScopes[page] = cookiePairs(CookieManager.getInstance().getCookie(page))
                observeCookies("before_webview_load")
                val parent = FrameLayout(application)
                host = parent
                val view = WebView(application)
                webView = view
                parent.addView(view, FrameLayout.LayoutParams(1280, 1920))
                layout(parent)
                assertDetached(parent, view)
                resolver = DesktopAlbumResolver(view, resolved.id, { observation ->
                    if (acceptCallbacks.get() && result.compareAndSet(null, observation)) settled.countDown()
                }, ::diagnostic)
                observeCookies("after_webview_start")
            }
            assertTrue("Desktop source read exceeded the shared 120-second budget", settled.await(remaining(), TimeUnit.MILLISECONDS))
            main("cookie after desktop settled") { observeCookies("after_desktop_settled") }
            val observation = checkNotNull(result.get())
            report.put("resolverStatus", observation.status.name).put("resolverReason", DiagnosticText.clean(observation.reason, 1200))
                .put("manualVerificationRequired", observation.status == DesktopAlbumStatus.NEEDS_VERIFICATION)
            saveReport()
            assertEquals("Only a stable complete candidate passes; verification requires a human, never automated login",
                DesktopAlbumStatus.CANDIDATE, observation.status)
            val album = checkNotNull(observation.album)
            assertEquals("The desktop page returned another work", resolved.id, album.id)
            assertTrue(album.isAlbum && album.images.size in 1..AlbumCandidatePolicy.MAX_IMAGES)
            assertTrue("All original images must pass the existing same-work CLEAN policy", WatermarkSources.available(album, WatermarkMode.CLEAN))
            val sampled = album.images.withIndex().filter { it.value.motion != null }.take(3)
            assertTrue("The same-work candidate has no image-owned motion", sampled.isNotEmpty())
            val taggedFive = observedDesktopFive()
            taggedFive.forEach { index ->
                assertTrue("The observed original source index is outside the complete candidate", index in album.images.indices)
                assertEquals("Desktop camel clipType=5 remains unclassified DYNAMIC", AlbumAssetKind.DYNAMIC, album.images[index].kind)
            }
            report.put("idMatchesShare", true).put("stableCompleteCandidateValidatedByResolver", true)
                .put("candidateCompletenessPolicy", "same page/owner id; complete declared array; original sourceIndex; two stable observations")
                .put("images", metadata(album)).put("imageCount", album.images.size)
                .put("candidateMotionCount", album.images.count { it.motion != null })
                .put("samplePolicy", "FIRST_UP_TO_3_ORIGINAL_MOTION_ITEMS").put("sampledMotionCount", sampled.size)
                .put("unsampledMotionCount", album.images.count { it.motion != null } - sampled.size)
                .put("observedDesktopClipType5Indices", JSONArray(taggedFive))
                .put("bgmPresentInSameWorkCandidate", album.bgmUrl.isNotBlank()).put("bgmFetched", false)
            val evidence = JSONArray()
            report.put("verifiedMotion", evidence)
            for ((index, image) in sampled) {
                remaining()
                val motion = checkNotNull(image.motion)
                val item = JSONObject().put("id", album.id).put("sourceIndex", index).put("imageKey", image.imageKey)
                    .put("kind", image.kind.name).put("originalMotionUrl", motion.url).put("success", false)
                evidence.put(item)
                report.put("phase", "motion_${index}_probe")
                saveReport()
                val realProbe = MediaProbe().also(probe::set)
                // A per-image wrapper with no images forces real MediaProbe verification, not its album shortcut.
                val source = ParsedVideo(album.id, "Desktop source item $index", motion.url,
                    motion.durationSeconds, motion.width, motion.height, mediaSources = motion.mediaSources)
                val checked = io("motion $index probe") { realProbe.verifySelected(source, WatermarkMode.CLEAN, ::diagnostic) }
                main("cookie after motion probe") { observeCookies("after_motion_${index}_probe") }
                assertEquals(album.id, checked.id)
                assertFalse(checked.isAlbum)
                WatermarkSources.requireSelectedUrl(checked.mediaUrl, WatermarkMode.CLEAN, checked.mediaSources)
                item.put("verifiedUrl", checked.mediaUrl).put("probeVerified", true)
                val target = File(root, "motion-$index.mp4")
                val realTransfer = MediaTransfer().also(transfer::set)
                report.put("phase", "motion_${index}_download")
                saveReport()
                val bytes = io("motion $index download") {
                    realTransfer.fetch(checked.mediaUrl, target, AlbumMediaPolicy.MAX_MOTION_BYTES, "桌面来源动态片段",
                        validateUrl = { WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, checked.mediaSources) },
                        onProgress = { _, _ -> remaining(); currentCoroutineContext().ensureActive() })
                }
                main("cookie after motion download") { observeCookies("after_motion_${index}_download") }
                assertTrue(target.canonicalFile.parentFile == root.canonicalFile && target.length() == bytes && bytes > 0)
                item.put("evidenceFile", target.absolutePath).put("bytes", bytes).put("sha256", io("source hash") { sha256(target) })
                report.put("phase", "motion_${index}_decode")
                item.put("video", io("motion $index actual samples and decoded motion") { inspectVideo(target) }).put("success", true)
                realProbe.cancel(); realTransfer.cancel()
                probe.compareAndSet(realProbe, null); transfer.compareAndSet(realTransfer, null)
                saveReport()
            }
            main("final detached host check") { assertDetached(checkNotNull(host), checkNotNull(webView)) }
            report.put("phase", "desktop_source_verified").put("verifiedMotionCount", evidence.length())
            verified = true
        } catch (error: Throwable) {
            failure = error
            report.put("phaseAtFailure", report.optString("phase")).put("phase", "failed")
                .put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 2000))
        } finally {
            acceptCallbacks.set(false)
            val errors = mutableListOf<String>()
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    errors.add(error.javaClass.simpleName + ": " + DiagnosticText.clean(error.message.orEmpty(), 1000))
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            cleanup { probe.getAndSet(null)?.cancel(); transfer.getAndSet(null)?.cancel(); worker.getAndSet(null)?.cancel(true) }
            cleanup { executor?.shutdownNow() }
            cleanup { assertTrue("The IO worker did not settle; its evidence is incomplete", executor?.awaitTermination(30, TimeUnit.SECONDS) != false) }
            cleanup { main("cookie before desktop WebView release", cleanup = true) { observeCookies("before_webview_dispose") } }
            cleanup { main("release desktop WebView", cleanup = true) {
                val controller = resolver
                if (controller != null) controller.cancel() else webView?.let { view ->
                    host?.removeView(view); view.stopLoading(); view.destroy()
                }
                host?.removeAllViews()
            } }
            cleanup { main("check pre-existing cookies", cleanup = true) {
                val manager = CookieManager.getInstance()
                // Capture every observed scope before any strict assertion can short-circuit it.
                val afterByScope = observeCookies("after_webview_dispose_before_cookie_assertions")
                acceptCookiesBefore?.let { assertEquals("Cookie acceptance changed", it, manager.acceptCookie()) }
                var newlyObserved = 0
                cookieScopes.forEach { (scope, before) ->
                    val after = checkNotNull(afterByScope[scope])
                    val remainingPairs = after.toMutableList()
                    before.forEach { pair -> assertTrue("A pre-existing cookie was removed or changed in an observed scope", remainingPairs.remove(pair)) }
                    newlyObserved += remainingPairs.size
                }
                report.put("preexistingObservedCookiesPreserved", true).put("cookiesComparedScopes", cookieScopes.size)
                    .put("cookieProtectionVerified", true)
                    .put("newlyObservedServerCookiePairs", newlyObserved)
                    .put("cookieBoundary", "Existing pairs compared in memory only; automatic new server cookies may appear; no whole-jar claim")
            } }
            cleanup {
                val after = readPreferences(webViewRuntimePreferenceNames)
                report.put("webViewRuntimePreferencesAfter", runtimePreferenceSummary(after))
                    .put("webViewRuntimePreferencesChanged", JSONArray(webViewRuntimePreferenceNames.sorted().filter {
                        runtimePreferencesBefore.getValue(it) != after.getValue(it)
                    }))
            }
            cleanup { assertEquals("Existing App preferences or diagnostics changed", preferences, readPreferences(preferences.keys)) }
            cleanup { assertEquals("User records changed", history, DownloadRecords(application).history()) }
            cleanup { assertEquals("User history URIs changed", historyUris, DownloadRecords(application).history().flatMap { it.uris }.toSet()) }
            cleanup { assertEquals("User background changed", backgroundBefore, background.takeIf(File::isFile)?.let(::sha256)) }
            if (failure != null && report.optString("phase") != "failed") {
                report.put("phaseAtFailure", "cleanup").put("phase", "failed")
                    .put("errorType", failure!!.javaClass.simpleName).put("error", DiagnosticText.clean(failure!!.message.orEmpty(), 2000))
            }
            report.put("cleanupErrors", JSONArray(errors)).put("userStatePreserved", errors.isEmpty())
                .put("sourceVerified", verified).put("protectionChecksPassed", errors.isEmpty())
                .put("webViewChromiumRuntimeCacheChanged", chromiumBefore != context.getSharedPreferences("WebViewChromiumPrefs", Context.MODE_PRIVATE).all.toMap())
                .put("diagnostics", JSONArray(diagnosticSnapshot())).put("mainThreadTimeoutDiagnostics", JSONArray(mainTimeoutFiles.toList()))
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("retainedPrivateEvidence", true)
                .put("success", verified && failure == null)
            cookieHmacKey.fill(0)
            saveReport()
        }
        failure?.let { throw it }
        assertTrue("Only matching desktop candidate plus sampled real motion and preservation checks pass", report.getBoolean("success"))
    }

    private fun metadata(album: ParsedVideo) = JSONArray(album.images.mapIndexed { index, image ->
        JSONObject().put("id", album.id).put("sourceIndex", index).put("imageKey", image.imageKey)
            .put("imageKeyPresent", image.imageKey.isNotBlank()).put("kind", image.kind.name).put("mimeHint", image.mimeType)
            .put("url", image.url).put("width", image.width).put("height", image.height)
            .put("mediaSources", sources(image.mediaSources)).put("motion", image.motion?.let { motion ->
                JSONObject().put("url", motion.url).put("width", motion.width).put("height", motion.height)
                    .put("durationSeconds", motion.durationSeconds).put("mediaSources", sources(motion.mediaSources))
            } ?: JSONObject.NULL)
    })
    private fun sources(sources: List<MediaSource>) = JSONArray(sources.map { JSONObject().put("mode", it.mode.name).put("url", it.url) })
    private fun observedDesktopFive(): Set<Int> {
        val latest = linkedMapOf<Int, JSONObject>()
        diagnosticSnapshot().filter { it.startsWith("desktop_target_images ") }.forEach { event ->
            val items = JSONObject(event.removePrefix("desktop_target_images ")).getJSONArray("items")
            repeat(items.length()) { index -> items.getJSONObject(index).let { latest[it.getInt("sourceIndex")] = it } }
        }
        return latest.filterValues { !it.has("clip_type") && it.optInt("clipType", -1) == 5 }.keys.toSet()
    }
    private fun diagnostic(event: String) {
        synchronized(diagnostics) { if (diagnostics.size < 100) diagnostics.add(DiagnosticText.clean(event, 4000)) }
    }
    private fun diagnosticSnapshot(): List<String> = synchronized(diagnostics) { diagnostics.toList() }
    private fun cookiePairs(header: String?): List<String> = header.orEmpty().split(';').map(String::trim).filter { it.contains('=') }.sorted()
    private fun cookieScopeDifference(label: String, before: List<String>, after: List<String>, mac: Mac): JSONObject {
        fun groupedHashes(pairs: List<String>): Map<String, Map<String, Int>> =
            pairs.groupBy { it.substringBefore('=') }.mapValues { (_, namedPairs) ->
                namedPairs.groupingBy { pair ->
                    mac.doFinal(pair.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                }.eachCount()
            }
        fun hashCounts(values: Map<String, Int>): JSONArray = JSONArray(values.toSortedMap().map { (hash, count) ->
            JSONObject().put("hmacSha256", hash).put("count", count)
        })
        val beforeNames = groupedHashes(before)
        val afterNames = groupedHashes(after)
        val names = JSONArray()
        var retainedTotal = 0
        var missingTotal = 0
        var newTotal = 0
        (beforeNames.keys + afterNames.keys).sorted().forEach { name ->
            val beforeHashes = beforeNames[name].orEmpty()
            val afterHashes = afterNames[name].orEmpty()
            val beforeCount = beforeHashes.values.sum()
            val afterCount = afterHashes.values.sum()
            val retained = beforeHashes.entries.sumOf { (hash, count) -> minOf(count, afterHashes[hash] ?: 0) }
            val missing = beforeCount - retained
            val added = afterCount - retained
            retainedTotal += retained
            missingTotal += missing
            newTotal += added
            names.put(JSONObject().put("name", name).put("beforeCount", beforeCount).put("afterCount", afterCount)
                .put("beforeHashes", hashCounts(beforeHashes)).put("afterHashes", hashCounts(afterHashes))
                .put("retainedCount", retained).put("missingCount", missing).put("newCount", added))
        }
        return JSONObject().put("scopeLabel", label).put("beforePairCount", before.size).put("afterPairCount", after.size)
            .put("beforeNameCount", beforeNames.size).put("afterNameCount", afterNames.size)
            .put("retainedCount", retainedTotal).put("missingCount", missingTotal).put("newCount", newTotal)
            .put("names", names)
    }
    private fun userPreferences(): Map<String, Map<String, *>> {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }.toSet() +
            setOf("downloads", "download_tasks", "download_options", "download_folder", "parse_diagnostics", "appearance_v1", "preview_options", "notification_prompt")
        return readPreferences(names - webViewRuntimePreferenceNames)
    }
    private fun readPreferences(names: Set<String>): Map<String, Map<String, *>> = names.associateWith { name ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    }
    private fun runtimePreferenceSummary(values: Map<String, Map<String, *>>): JSONArray = JSONArray(values.keys.sorted().map { name ->
        val keys = JSONArray(values.getValue(name).keys.sorted().map { key ->
            val value = values.getValue(name)[key]
            // Canonical values exist only in memory while hashing. Set ordering
            // must not make an unchanged provider statistic appear different.
            val canonical = JSONObject().put("type", if (value is Set<*>) "StringSet" else value?.javaClass?.name ?: "null")
                .put("value", if (value is Set<*>) JSONArray(value.map { it.toString() }.sorted()) else value ?: JSONObject.NULL)
            JSONObject().put("key", key)
                .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))))
                .put("count", if (value is Set<*>) value.size else if (value == null) 0 else 1)
        })
        JSONObject().put("namespace", name).put("keyCount", keys.length()).put("keys", keys)
            .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(keys.toString().toByteArray(Charsets.UTF_8))))
    })
    private fun layout(host: FrameLayout) {
        host.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 1280, 1920)
    }
    private fun assertDetached(host: FrameLayout, view: WebView) {
        assertNull(host.parent); assertNull(host.windowToken); assertNull(view.windowToken)
        assertFalse(host.isAttachedToWindow); assertFalse(view.isAttachedToWindow)
        assertSame(host, view.parent)
        assertEquals(1280, view.width); assertEquals(1920, view.height)
    }
    private fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).also {
        if (it <= 0) throw AssertionError("The headless desktop source test exceeded its 120000 ms total budget")
    }
    private fun <T> io(label: String, block: suspend () -> T): T {
        val timeout = remaining()
        val task = checkNotNull(executor).submit(Callable<T> { runBlocking(Dispatchers.IO) { block() } })
        worker.set(task)
        try { return task.get(minOf(timeout, remaining()), TimeUnit.MILLISECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
        catch (error: TimeoutException) {
            probe.get()?.cancel(); transfer.get()?.cancel(); task.cancel(true)
            throw AssertionError("Desktop source IO '$label' exceeded the remaining 120-second budget", error)
        } catch (error: Throwable) {
            task.cancel(true)
            throw error
        } finally { worker.compareAndSet(task, null) }
    }
    private fun main(label: String, cleanup: Boolean = false, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val done = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val error = AtomicReference<Throwable?>(null)
        val action = Runnable {
            try { if (active.get()) block() } catch (failure: Throwable) { error.set(failure) }
            finally { done.countDown() }
        }
        val timeout = if (cleanup) 30_000L else minOf(30_000L, remaining())
        check(handler.post(action)) { "The main thread rejected the desktop source callback" }
        val returned = try { done.await(timeout, TimeUnit.MILLISECONDS) } catch (interrupted: InterruptedException) {
            active.set(false); handler.removeCallbacks(action); Thread.currentThread().interrupt(); throw interrupted
        }
        if (!returned) {
            active.set(false); handler.removeCallbacks(action)
            val failure = AssertionError("Headless desktop main callback '$label' timed out after $timeout ms")
            try {
                val thread = Looper.getMainLooper().thread
                val file = File(checkNotNull(directory), "main-thread-timeout-${SystemClock.elapsedRealtime()}-${UUID.randomUUID()}.txt")
                file.writeText("callback=$label\nthread=${thread.name}\nstate=${thread.state}\n" + thread.stackTrace.joinToString("\n") { "at $it" })
                mainTimeoutFiles.add(file.absolutePath)
                println("headless_desktop_main_timeout=${file.absolutePath}")
            } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        }
        error.get()?.let { throw it }
    }
    private suspend fun inspectVideo(file: File): JSONObject {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            val track = types.indexOfFirst { it.startsWith("video/") }
            assertTrue("The downloaded owned source has no video track", track >= 0)
            val format = extractor.getTrackFormat(track)
            val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            require(declared <= 32 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(maxOf(2 * 1024 * 1024, declared))
            val digest = MessageDigest.getInstance("SHA-256")
            extractor.selectTrack(track)
            var count = 0
            while (extractor.sampleTrackIndex >= 0) {
                remaining(); currentCoroutineContext().ensureActive()
                assertEquals(track, extractor.sampleTrackIndex)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                assertTrue("A continuous encoded video sample could not be read", size > 0 && size <= buffer.capacity())
                buffer.position(0); buffer.limit(size); digest.update(buffer)
                digest.update(ByteBuffer.allocate(12).putLong(extractor.sampleTime).putInt(extractor.sampleFlags).array())
                count++
                if (!extractor.advance()) break
            }
            assertTrue("The actual source needs at least two readable encoded samples", count >= 2)
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue(width > 0 && height > 0 && duration in 1..AlbumMediaPolicy.MAX_MOTION_DURATION_MS)
            val decoded = linkedSetOf<String>()
            val times = (0 until 12).map { (duration - 1L) * it / 11L }.distinct()
            for (time in times) {
                remaining(); currentCoroutineContext().ensureActive()
                val frame = checkNotNull(retriever.getScaledFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 128, 128))
                try { decoded.add(pixelHash(frame)) } finally { frame.recycle() }
            }
            assertTrue("A motion URL yielded no actual changing decoded frames", decoded.size >= 2)
            return JSONObject().put("width", width).put("height", height).put("durationMs", duration)
                .put("trackTypes", JSONArray(types)).put("videoSamples", count).put("encodedVideoSha256", hex(digest.digest()))
                .put("decodedFramesChecked", times.size).put("distinctDecodedFrames", decoded.size)
        } finally { retriever.release(); extractor.release() }
    }
    private fun pixelHash(frame: Bitmap): String {
        val small = Bitmap.createScaledBitmap(frame, 64, 64, true)
        return try {
            val pixels = IntArray(64 * 64)
            small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
            val bytes = ByteBuffer.allocate(pixels.size * 4)
            pixels.forEach(bytes::putInt)
            hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
        } finally { if (small !== frame) small.recycle() }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
