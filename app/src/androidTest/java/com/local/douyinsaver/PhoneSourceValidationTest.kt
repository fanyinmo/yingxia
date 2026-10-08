package com.local.douyinsaver

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Opt-in public-network functionality only: no Activity, visible UI, injected input or permission changes. */
@RunWith(AndroidJUnit4::class)
class PhoneSourceValidationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val realApplication get() = context.applicationContext as Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private var diagnosticDirectory: File? = null
    private val mainThreadTimeoutDiagnostics: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private enum class DownloadMode { NONE, VIDEO, IMAGES, GIF, MOTION_VIDEOS, LIVE_PHOTOS }

    @Test fun validateOneExplicitOfficialShareThroughAnIsolatedHeadlessEngine() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Official phone-source validation requires run_phone_source=true", args.getString("run_phone_source") == "true")
        val share = ShareLinks.extract(requireNotNull(args.getString("shareUrl")) { "shareUrl is required" })
        val expectedId = args.getString("expectedId")?.also { require(it.matches(Regex("[0-9]{15,22}"))) }
        val mode = DownloadMode.valueOf(args.getString("download_mode") ?: "NONE")
        val checkMotion = when (args.getString("check_motion") ?: "false") {
            "true" -> true
            "false" -> false
            else -> error("check_motion must be true or false")
        }
        val awaitAutomaticMotion = when (args.getString("await_auto_motion") ?: "false") {
            "true" -> true
            "false" -> false
            else -> error("await_auto_motion must be true or false")
        }
        require(!(checkMotion && awaitAutomaticMotion)) { "Use manual or automatic motion validation, not both" }
        val cpuService = when (args.getString("cpu_service") ?: "false") {
            "true" -> true
            "false" -> false
            else -> error("cpu_service must be true or false")
        }
        val captureOriginalMotion = when (args.getString("capture_original_motion") ?: "false") {
            "true" -> true
            "false" -> false
            else -> error("capture_original_motion must be true or false")
        }
        val started = SystemClock.elapsedRealtime()
        val namespace = "phone_source_${UUID.randomUUID()}_"
        val directory = File(context.cacheDir, namespace).apply {
            check(mkdir() && canonicalFile.parentFile == context.cacheDir.canonicalFile)
        }
        diagnosticDirectory = directory
        val app = IsolatedApplication(realApplication, namespace, directory)
        val userPreferences = existingUserPreferences()
        val userBackground = File(context.filesDir, "appearance/background.jpg")
        val backgroundHash = userBackground.takeIf(File::isFile)?.let(::sha256)
        val protectedUris = DownloadRecords(realApplication).history().flatMap { it.uris }.toSet()
        val ownUris = linkedSetOf<String>()
        val installedPackage = context.packageManager.getPackageInfo(context.packageName, 0)
        val installedVersionCode = if (Build.VERSION.SDK_INT >= 28) installedPackage.longVersionCode else installedPackage.versionCode.toLong()
        val report = JSONObject().put("success", false).put("completed", false).put("runtimeElapsedMs", 0L).put("phase", "share_mapping")
            .put("installedVersionName", installedPackage.versionName).put("installedVersionCode", installedVersionCode)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("shareUrl", share).put("expectedId", expectedId ?: JSONObject.NULL).put("downloadMode", mode.name)
            .put("checkMotion", checkMotion).put("runRoot", directory.name).put("uiUsed", false)
            .put("awaitAutomaticMotion", awaitAutomaticMotion)
            .put("permissionsChanged", false).put("protectedHistoryUriCount", protectedUris.size)
            .put("testCpuForegroundServiceSetup", cpuService)
            .put("sourceEvidenceContext", if (cpuService) "HEADLESS_ISOLATED_WITH_APP_FOREGROUND_SERVICE_TEST_SETUP" else "HEADLESS_ISOLATED_WITHOUT_SERVICE_SETUP")
            .put("normalBackgroundParseAcceptanceVerified", false).put("notificationUiVerified", false)
            .put("cpuForegroundServiceStartRequested", false).put("cpuForegroundServiceOwned", false)
            .put("cpuForegroundServiceObservedForeground", false).put("cpuForegroundServiceStopped", false)
            .put("captureOriginalMotion", captureOriginalMotion).put("originalMotionMaximumItems", 3)
            .put("originalMotionMaximumBytesPerItem", 128L * 1024 * 1024)
            .put("originalMotionEvidence", JSONArray()).put("capturedOriginalMotionCount", 0)
            .put("originalMotionQualityScope", if (captureOriginalMotion) "SOURCE_AND_LOCAL_MEDIA_NOT_UI_OR_NATIVE_GALLERY" else "NOT_REQUESTED")
        File(directory, "share-input.txt").writeText(share)
        var resolvedId: String? = null
        var engine: SaverEngine? = null
        var host: FrameLayout? = null
        var failure: Throwable? = null
        var verified = false
        var wakeLock: PowerManager.WakeLock? = null
        val cpuServiceRequested = AtomicBoolean(false)
        val cpuServiceOwned = AtomicBoolean(false)
        val singletonField = if (cpuService) SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true } else null
        val singletonBefore = singletonField?.get(null)
        var originalMotions = emptyMap<Int, OriginalMotionEvidence>()
        var lastSnapshotSavedAt = started
        fun saveReport() {
            report.put("runtimeElapsedMs", SystemClock.elapsedRealtime() - started)
            val file = File(directory, "${resolvedId ?: expectedId ?: "unresolved"}.json")
            file.writeText(report.toString(2))
            println("phone_source_report=${file.absolutePath}")
        }
        fun record(snapshot: Snapshot) {
            report.put("stage", snapshot.stage.name).put("message", DiagnosticText.clean(snapshot.message, 1200))
                .put("diagnostics", DiagnosticText.clean(snapshot.diagnostics, 16_000))
                .put("motionPhase", snapshot.motion.phase.name).put("motionDetail", DiagnosticText.clean(snapshot.motion.detail, 1000))
                .put("downloaded", snapshot.downloaded).put("total", snapshot.total)
            ownUris.addAll(snapshot.history.flatMap { it.uris })
            val now = SystemClock.elapsedRealtime()
            report.put("runtimeElapsedMs", now - started)
            if (now - lastSnapshotSavedAt >= 2000L) {
                // Snapshot has already returned from main; persist only on the instrumentation worker.
                saveReport()
                lastSnapshotSavedAt = now
            }
        }
        try {
            val power = realApplication.getSystemService(Context.POWER_SERVICE) as PowerManager
            report.put("deviceIdleModeBefore", power.isDeviceIdleMode).put("interactiveBefore", power.isInteractive)
            if (cpuService) {
                report.put("phase", "cpu_service_setup").put("realSingletonPresentBefore", singletonBefore != null)
                val before = downloadServiceState()
                report.put("cpuForegroundServiceBefore", before)
                saveReport()
                assertFalse("Refusing to take ownership of an existing download service", before.getBoolean("running"))
                main {
                    assertFalse("A download service started before the fixture could acquire ownership", downloadServiceState().getBoolean("running"))
                    cpuServiceRequested.set(true)
                    // Ordinary app API: a platform rejection remains the original test failure.
                    DownloadForegroundService.start(realApplication, false)
                    cpuServiceOwned.set(true)
                }
                report.put("cpuForegroundServiceStartRequested", cpuServiceRequested.get())
                    .put("cpuForegroundServiceOwned", cpuServiceOwned.get())
                saveReport()
                awaitCpuService(foreground = true) { report.put("cpuForegroundServiceAfterStart", it) }
                report.put("cpuForegroundServiceObservedForeground", true).put("phase", "share_mapping")
                saveReport()
            }
            val cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:PhoneSourceValidation")
            wakeLock = cpuLock
            cpuLock.setReferenceCounted(false)
            cpuLock.acquire(30 * 60_000L)
            report.put("partialWakeLockAcquired", cpuLock.isHeld).put("partialWakeLockTimeoutMs", 30 * 60_000L)
            assertTrue("The bounded source test must retain CPU access", cpuLock.isHeld)
            val resolved = runBlocking(Dispatchers.IO) { withTimeout(180_000L) { ShareLinks.resolveShare(share) } }
            resolvedId = resolved.id
            report.put("id", resolved.id).put("resolvedShareUrl", resolved.url)
                .put("shareIdMatchesExpected", expectedId == null || expectedId == resolved.id)
            saveReport()
            assertTrue("The official share resolved to another work", expectedId == null || expectedId == resolved.id)
            lateinit var isolated: SaverEngine
            lateinit var parserHost: FrameLayout
            main {
                isolated = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance(app, namespace)
                // Only the real Application context owns this WebView host; it is never attached to a window.
                parserHost = FrameLayout(realApplication)
                engine = isolated; host = parserHost
                layout(parserHost)
                isolated.attachParserHost(parserHost)
                assertTrue("The isolated engine must start without old records", isolated.history.isEmpty())
                isolated.acceptShare(share)
                isolated.resolve()
            }
            report.put("phase", "parsing")
            saveReport()
            val ready = awaitStage(isolated, parserHost, TaskStage.READY, 120_000L, ::record)
            val parsed = checkNotNull(ready.content) { "READY contained no parsed work" }
            assertEquals("The parsed work must match the independently resolved share", resolved.id, parsed.id)
            report.put("parsedIdMatchesShare", true).put("parsed", metadata(parsed))
            var content = parsed
            var motionConfirmed = false
            if (checkMotion || awaitAutomaticMotion) {
                assertTrue("check_motion requires an actual album", content.isAlbum)
                report.put("phase", "checking_motion"); saveReport()
                if (checkMotion) main { isolated.checkAlbumMotion() }
                val motion = awaitMotion(isolated, parserHost, 180_000L, ::record)
                assertEquals("Dynamic sources were unavailable or require manual verification; no automatic verification is performed",
                    AlbumMotionPhase.AVAILABLE, motion.motion.phase)
                content = checkNotNull(motion.content)
                assertEquals(resolved.id, content.id)
                assertTrue(content.isAlbum && content.hasDynamicAlbumAssets)
                report.put("afterMotion", metadata(content))
                motionConfirmed = true
            }
            if (mode != DownloadMode.NONE && content.isAlbum && !motionConfirmed &&
                snapshot(isolated, parserHost).motion.phase == AlbumMotionPhase.READING) {
                // READY can be visible while the bounded automatic motion check still
                // disables saving. Wait for that normal UI state to settle before a tap.
                report.put("phase", "awaiting_automatic_motion_settlement"); saveReport()
                val settled = awaitMotion(isolated, parserHost, 45_000L, ::record)
                assertTrue("Manual verification must not be silently accepted", settled.motion.phase != AlbumMotionPhase.NEEDS_VERIFICATION)
                content = checkNotNull(settled.content)
                assertEquals(resolved.id, content.id)
                report.put("afterAutomaticMotionSettlement", metadata(content))
            }
            if (captureOriginalMotion) {
                assertTrue("Original-motion capture requires this run's confirmed same-work motion result", (checkMotion || awaitAutomaticMotion) && motionConfirmed)
                assertEquals(resolved.id, content.id)
                report.put("phase", "capturing_original_motion"); saveReport()
                originalMotions = captureOriginalMotions(content, resolved.id, directory, report, ::saveReport)
            }
            if (mode == DownloadMode.NONE) {
                report.put("phase", "source_verified")
                    .put("scope", if (captureOriginalMotion) "SOURCE_AND_ORIGINAL_MOTION_EVIDENCE" else "SOURCE_ONLY")
                    .put("downloadVerified", false)
            } else {
                validateRequestedMode(content, mode)
                report.put("phase", "downloading").put("scope", "SOURCE_AND_LOCAL_MEDIA"); saveReport()
                main {
                    assertNull("This fixture never supplies a uniform duration override", isolated.itemDurationSeconds)
                    assertEquals(0f, isolated.gifStartSeconds, 0f)
                    assertEquals(6f, isolated.gifDurationSeconds, 0f)
                    isolated.albumMode = if (!content.isAlbum && mode == DownloadMode.VIDEO) AlbumMode.IMAGES else AlbumMode.valueOf(mode.name)
                    isolated.fileName = "phone_source_${resolved.id}_${namespace.removePrefix("phone_source_").take(8)}"
                    isolated.download(force = true)
                }
                val startedDownload = snapshot(isolated, parserHost)
                record(startedDownload)
                assertTrue("Saving was not started: ${DiagnosticText.clean(startedDownload.message, 1000)}",
                    startedDownload.stage in listOf(TaskStage.DOWNLOADING, TaskStage.SAVING, TaskStage.DONE))
                val done = awaitStage(isolated, parserHost, TaskStage.DONE, 600_000L, ::record)
                assertEquals(resolved.id, done.content?.id)
                val saved = done.history.single()
                assertEquals(resolved.id, saved.id)
                assertEquals(WatermarkMode.CLEAN, saved.watermarkMode)
                assertTrue("The engine must save only new content URIs", saved.uris.isNotEmpty() && saved.uris.none { it in protectedUris })
                val files = JSONArray()
                report.put("saved", savedMetadata(saved)).put("files", files)
                saveReport()
                verifySaved(saved, content, mode, directory, originalMotions, files, ::saveReport)
                report.put("downloadVerified", true).put("phase", "media_verified")
            }
            val finalState = snapshot(isolated, parserHost)
            record(finalState)
            assertEquals(if (mode == DownloadMode.NONE) TaskStage.READY else TaskStage.DONE, finalState.stage)
            assertEquals(resolved.id, finalState.content?.id)
            assertTrue("A manual verification request is never a passing validation", finalState.motion.phase != AlbumMotionPhase.NEEDS_VERIFICATION)
            verified = true
        } catch (error: Throwable) {
            failure = error
            report.put("phaseAtFailure", report.optString("phase")).put("phase", "failed")
                .put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 2000))
        } finally {
            val cleanupErrors = mutableListOf<String>()
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    cleanupErrors.add(error.javaClass.simpleName + ": " + DiagnosticText.clean(error.message.orEmpty(), 500))
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            engine?.let { isolated ->
                cleanup { record(snapshot(isolated, host)) }
                var scope: CoroutineScope? = null
                cleanup {
                    scope = SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(isolated) as CoroutineScope
                    scope!!.cancel()
                }
                cleanup { main {
                    isolated.cancel(); isolated.cancelAlbumMotion()
                    host?.let(isolated::detachParserHost)
                } }
                cleanup { runBlocking { withTimeout(30_000L) { scope?.coroutineContext?.get(Job)?.join() } } }
                cleanup { ownUris.addAll(DownloadRecords(app, namespace).history().flatMap { it.uris }) }
            }
            ownUris.forEach { raw -> cleanup {
                check(raw !in protectedUris) { "Refusing to delete a pre-existing user URI" }
                val uri = Uri.parse(raw)
                check(uri.scheme == "content") { "The saved manifest did not contain an owned content URI" }
                context.contentResolver.delete(uri, null, null)
                val stillReadable = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
                check(!stillReadable) { "A newly published test URI could not be cleaned up" }
            } }
            app.preferenceNames.toList().forEach { name -> cleanup {
                check(name.startsWith(namespace)); context.deleteSharedPreferences(name)
            } }
            if (cpuServiceOwned.get()) {
                cleanup {
                    // Active stop sets the existing service's stopping flag; never send its CANCEL action.
                    main { DownloadForegroundService.stop(realApplication) }
                    awaitCpuService(foreground = false) { report.put("cpuForegroundServiceAfterStop", it) }
                    report.put("cpuForegroundServiceStopped", true)
                }
            }
            if (cpuService) cleanup {
                assertSame("The CPU fixture changed the real SaverEngine singleton", singletonBefore, singletonField!!.get(null))
                report.put("realSingletonPreserved", true)
            }
            cleanup { assertEquals("User preferences or records changed", userPreferences,
                userPreferences.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }) }
            cleanup { assertEquals("User background changed", backgroundHash, userBackground.takeIf(File::isFile)?.let(::sha256)) }
            cleanup { assertEquals("User history changed", protectedUris, DownloadRecords(realApplication).history().flatMap { it.uris }.toSet()) }
            cleanup { wakeLock?.let { if (it.isHeld) it.release() } }
            report.put("cleanupErrors", JSONArray(cleanupErrors)).put("ownedUris", JSONArray(ownUris.toList()))
                .put("ownedPreferenceNames", JSONArray(app.preferenceNames.toList()))
                .put("mainThreadTimeoutDiagnostics", JSONArray(mainThreadTimeoutDiagnostics.toList()))
                .put("cpuForegroundServiceStartRequested", cpuServiceRequested.get())
                .put("cpuForegroundServiceOwned", cpuServiceOwned.get())
                .put("partialWakeLockReleased", wakeLock?.isHeld != true)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("completed", true).put("success", verified && failure == null)
                .put("userStatePreserved", cleanupErrors.isEmpty())
            saveReport()
        }
        failure?.let { throw it }
        assertTrue("The JSON must report a verified, matching successful result", report.getBoolean("success"))
    }

    @Suppress("DEPRECATION")
    private fun downloadServiceState(): JSONObject {
        val manager = realApplication.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val service = manager.getRunningServices(100).firstOrNull { it.service.className == DownloadForegroundService::class.java.name }
        return JSONObject().put("running", service != null).put("foreground", service?.foreground == true)
            .put("pid", service?.pid ?: JSONObject.NULL).put("uid", service?.uid ?: JSONObject.NULL)
    }
    private fun awaitCpuService(foreground: Boolean, record: (JSONObject) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (true) {
            val state = downloadServiceState()
            record(state)
            if (if (foreground) state.getBoolean("foreground") else !state.getBoolean("running")) return
            if (SystemClock.elapsedRealtime() >= deadline)
                throw AssertionError("CPU fixture download service did not ${if (foreground) "enter foreground" else "stop"} after 10000 ms; state=$state")
            SystemClock.sleep(50L)
        }
    }

    private fun validateRequestedMode(content: ParsedVideo, mode: DownloadMode) {
        if (mode in listOf(DownloadMode.IMAGES, DownloadMode.MOTION_VIDEOS, DownloadMode.LIVE_PHOTOS))
            assertTrue("The requested image format requires parsed album data, not title keywords", content.isAlbum)
        if (content.isAlbum && mode == DownloadMode.VIDEO) assertTrue("The same album must supply its own BGM", content.bgmUrl.isNotBlank())
        if (content.isAlbum && mode == DownloadMode.GIF) assertTrue("GIF requires at least two static images or actual dynamic items", AlbumActionUiPolicy.canComposeGif(content))
        if (mode in listOf(DownloadMode.MOTION_VIDEOS, DownloadMode.LIVE_PHOTOS))
            assertTrue("The parsed album has no dynamic assets; use check_motion=true to read official motion sources", content.hasDynamicAlbumAssets)
    }

    private data class Snapshot(val stage: TaskStage, val content: ParsedVideo?, val history: List<SavedVideo>,
        val motion: AlbumMotionReadState, val message: String, val diagnostics: String, val downloaded: Long, val total: Long,
        val durationWarnings: List<DurationAdjustment>)
    private fun snapshot(engine: SaverEngine, host: FrameLayout?): Snapshot {
        lateinit var value: Snapshot
        main {
            host?.let(::layout)
            value = Snapshot(engine.stage, engine.video, engine.history.toList(), engine.albumMotionState(), engine.message,
                engine.diagnostics, engine.downloaded, engine.total, engine.durationAdjustments.toList())
        }
        return value
    }
    private fun awaitStage(engine: SaverEngine, host: FrameLayout, expected: TaskStage, timeout: Long, record: (Snapshot) -> Unit): Snapshot =
        await(engine, host, timeout, record) { it.stage == expected }
    private fun awaitMotion(engine: SaverEngine, host: FrameLayout, timeout: Long, record: (Snapshot) -> Unit): Snapshot =
        await(engine, host, timeout, record) { it.motion.phase in listOf(AlbumMotionPhase.AVAILABLE, AlbumMotionPhase.UNAVAILABLE, AlbumMotionPhase.NEEDS_VERIFICATION) }
    private fun await(engine: SaverEngine, host: FrameLayout, timeout: Long, record: (Snapshot) -> Unit, done: (Snapshot) -> Boolean): Snapshot {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var previous = ""
        while (true) {
            val state = snapshot(engine, host)
            record(state)
            val phase = "${state.stage}/${state.motion.phase}"
            if (phase != previous) { println("phone_source_stage=$phase"); previous = phase }
            if (state.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED))
                throw AssertionError("Source pipeline stopped: stage=${state.stage}; ${DiagnosticText.clean(state.message, 1000)}")
            if (state.durationWarnings.isNotEmpty()) throw AssertionError("Unexpected duration confirmation; this test never confirms user dialogs")
            if (done(state)) return state
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Source pipeline timed out after $timeout ms: stage=${state.stage}, motion=${state.motion.phase}")
            SystemClock.sleep(150L)
        }
    }
    private fun layout(host: FrameLayout) {
        host.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 1280, 1920)
    }
    private fun metadata(content: ParsedVideo): JSONObject = JSONObject().put("id", content.id).put("title", content.title)
        .put("isAlbum", content.isAlbum).put("durationSeconds", content.durationSeconds).put("width", content.width).put("height", content.height)
        .put("mediaUrl", content.mediaUrl).put("mediaSources", sources(content.mediaSources))
        .put("bgmPresent", content.bgmUrl.isNotBlank()).put("bgmUrl", content.bgmUrl).put("bgmDurationSeconds", content.bgmDurationSeconds)
        .put("bgmBytesNotFetchedByParse", true).put("imageCount", content.images.size)
        .put("images", JSONArray(content.images.mapIndexed { index, image -> JSONObject().put("index", index).put("kind", image.kind.name)
            .put("mimeHint", image.mimeType).put("url", image.url).put("width", image.width).put("height", image.height)
            .put("imageKey", image.imageKey).put("mediaSources", sources(image.mediaSources)).put("motion", image.motion?.let { motion ->
                JSONObject().put("url", motion.url).put("width", motion.width).put("height", motion.height)
                    .put("durationSeconds", motion.durationSeconds).put("embeddedInPhoto", motion.embeddedInPhoto).put("mediaSources", sources(motion.mediaSources))
            } ?: JSONObject.NULL) }))
    private fun sources(values: List<MediaSource>) = JSONArray(values.map { JSONObject().put("mode", it.mode.name).put("url", it.url) })
    private fun savedMetadata(saved: SavedVideo) = JSONObject().put("id", saved.id).put("mimeType", saved.mimeType).put("bytes", saved.bytes)
        .put("exportMode", saved.exportMode).put("uris", JSONArray(saved.uris)).put("albumSourceCount", saved.albumSourceCount)
        .put("albumTimingSignature", saved.albumTimingSignature).put("gifStartMs", saved.gifStartMs).put("gifDurationMs", saved.gifDurationMs)

    private data class OriginalMotionEvidence(val workId: String, val sourceIndex: Int, val image: ParsedImage,
        val file: File, val fileSha256: String, val video: JSONObject)

    private fun captureOriginalMotions(content: ParsedVideo, workId: String, directory: File, report: JSONObject,
        persist: () -> Unit): Map<Int, OriginalMotionEvidence> {
        assertEquals("The motion array must belong to the independently resolved work", workId, content.id)
        assertTrue("Original-motion capture requires an actual album", content.isAlbum)
        val candidates = content.images.withIndex().filter { it.value.motion != null }
        assertTrue("No image-owned actual motion source was confirmed", candidates.isNotEmpty())
        report.put("originalMotionEligibleItems", candidates.size).put("originalMotionOmittedItems", (candidates.size - 3).coerceAtLeast(0))
        val captured = linkedMapOf<Int, OriginalMotionEvidence>()
        val transfer = MediaTransfer()
        try {
            runBlocking(Dispatchers.IO) { withTimeout(180_000L) {
                for ((sourceIndex, image) in candidates.take(3)) {
                    val motion = checkNotNull(image.motion)
                    // This is the actual accepted image-owned source, not a reconstructed playback ID.
                    assertTrue("The accepted motion URL lacks explicit CLEAN provenance", motion.mediaSources.any {
                        it.mode == WatermarkMode.CLEAN && it.url == motion.url
                    })
                    WatermarkSources.requireSelectedUrl(motion.url, WatermarkMode.CLEAN, motion.mediaSources)
                    val file = File(directory, "source-motion-$sourceIndex.mp4")
                    check(file.canonicalFile.parentFile == directory.canonicalFile && !file.exists())
                    val item = JSONObject().put("workId", workId).put("sourceIndex", sourceIndex)
                        .put("imageKeyPresent", image.imageKey.isNotBlank())
                        .put("imageIdentitySha256", hex(MessageDigest.getInstance("SHA-256")
                            .digest("$workId\n$sourceIndex\n${image.imageKey}\n${image.url}".toByteArray(Charsets.UTF_8))))
                        .put("motionSourceUrlSha256", hex(MessageDigest.getInstance("SHA-256").digest(motion.url.toByteArray(Charsets.UTF_8))))
                        .put("sourceConfirmation", "CHECK_MOTION_AVAILABLE_SAME_WORK_ORDERED_IMAGE_SLOT_WITH_OWN_PARSED_MOTION")
                        .put("sourceMode", WatermarkMode.CLEAN.name).put("evidenceFile", file.absolutePath).put("status", "fetching")
                    report.getJSONArray("originalMotionEvidence").put(item)
                    persist()
                    var lastProgressSavedAt = SystemClock.elapsedRealtime()
                    val bytes = transfer.fetch(motion.url, file, 128L * 1024 * 1024, "Original image-owned motion",
                        validateUrl = { WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, motion.mediaSources) }) { read, total ->
                        item.put("downloadedBytes", read).put("declaredBytes", total)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressSavedAt >= 2000L) { persist(); lastProgressSavedAt = now }
                    }
                    AlbumMediaValidation.motionVideo(file)
                    val hash = sha256(file)
                    val video = inspectVideo(file, false, false, true)
                    item.put("status", "verified").put("bytes", bytes).put("sha256", hash).put("video", video)
                    captured[sourceIndex] = OriginalMotionEvidence(workId, sourceIndex, image, file, hash, video)
                    report.put("capturedOriginalMotionCount", captured.size)
                    persist()
                }
            } }
        } finally { transfer.cancel() }
        return captured
    }

    private fun verifySaved(saved: SavedVideo, content: ParsedVideo, mode: DownloadMode, directory: File,
        originals: Map<Int, OriginalMotionEvidence>, evidence: JSONArray, persist: () -> Unit): JSONArray {
        if (content.isAlbum) assertEquals(mode.name, saved.exportMode)
        if (originals.isNotEmpty() && content.isAlbum && mode != DownloadMode.VIDEO) {
            // This fixture requests the complete accepted array, never selected/reconstructed URL indices.
            assertEquals("The returned manifest must retain every actual source slot once and in order",
                content.images.indices.toList(), saved.albumAssets.map { it.sourceIndex })
        }
        if (!content.isAlbum && mode == DownloadMode.GIF) {
            assertEquals("image/gif", saved.mimeType)
            assertEquals(0L, saved.gifStartMs)
            assertTrue("Video GIF must retain the default bounded 6-second selection", saved.gifDurationMs in 100L..6000L)
        }
        var copied = 0L
        saved.uris.forEachIndexed { index, raw ->
            val uri = Uri.parse(raw)
            assertEquals("content", uri.scheme)
            val asset = saved.albumAssets.firstOrNull { it.uri == raw }
            val mime = asset?.mimeType ?: saved.mimeTypeFor(raw)
            assertEquals("Published MIME does not match its manifest", mime, context.contentResolver.getType(uri))
            val extension = when { mime == "video/mp4" -> "mp4"; mime == "image/gif" -> "gif"; mime == "image/jpeg" -> "jpg"; else -> "image" }
            val file = File(directory, "asset-${index + 1}.$extension")
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    require(copied <= AlbumMediaPolicy.MAX_VIDEO_BYTES) { "Copied output exceeds the test's 2 GB bound" }
                    output.write(buffer, 0, count)
                }
            } }
            assertTrue(file.length() > 0)
            val item = JSONObject().put("uri", raw).put("mimeType", mime).put("bytes", file.length()).put("sha256", sha256(file))
                .put("evidenceFile", file.absolutePath).put("sourceIndex", asset?.sourceIndex ?: -1).put("kind", asset?.kind?.name ?: "VIDEO")
            evidence.put(item)
            if (originals.isNotEmpty() && content.isAlbum && mode != DownloadMode.VIDEO) {
                val returned = checkNotNull(asset) { "The image-owned output omitted its source-index manifest" }
                assertTrue("The returned source index is outside this exact confirmed image array", returned.sourceIndex in content.images.indices)
            }
            val original = asset?.let { originals[it.sourceIndex] }
            if (original != null) {
                val returned = checkNotNull(asset)
                assertEquals("The exported record belongs to another work", original.workId, saved.id)
                assertEquals(original.workId, content.id)
                assertEquals("The returned manifest identifies another source index", original.sourceIndex, returned.sourceIndex)
                assertEquals("The returned source index identifies another image-owned motion", original.image, content.images[returned.sourceIndex])
                item.put("originalMotionSourceIndex", original.sourceIndex).put("originalMotionFile", original.file.absolutePath)
                    .put("originalMotionSha256", original.fileSha256).put("originalMotionVideo", original.video)
            } else if (originals.isNotEmpty() && asset != null && content.images.getOrNull(asset.sourceIndex)?.motion != null) {
                item.put("originalMotionComparison", "NOT_CAPTURED_WITHIN_THREE_ITEM_LIMIT")
            } else if (originals.isNotEmpty() && mode == DownloadMode.VIDEO) {
                item.put("originalMotionComparison", "BGM_COMPOSITION_NOT_A_PASSTHROUGH_OUTPUT")
            }
            when {
                asset?.embeddedMotion == true -> {
                    assertEquals(AlbumAssetKind.LIVE, asset.kind)
                    assertEquals("image/jpeg", mime)
                    val live = EmbeddedMotionReader.validate(file)
                    val clip = File(directory, "asset-${index + 1}-embedded.mp4")
                    runBlocking(Dispatchers.IO) { EmbeddedMotionReader.extractForPreview(file, clip) }
                    item.put("embeddedMotion", true).put("embeddedDurationMs", live.durationMs).put("embeddedHasAudio", live.hasAudio)
                        .put("embeddedVideo", inspectVideo(clip, false, false, true))
                    if (original != null) {
                        val embeddedHash = sha256(clip)
                        item.put("originalMotionComparison", JSONObject().put("type", "LIVE_COMPLETE_EMBEDDED_MP4")
                            .put("embeddedMp4Sha256", embeddedHash).put("entireMp4BytesEqual", embeddedHash == original.fileSha256)
                            .put("sourceAudioPreservedByWholeFileEquality", embeddedHash == original.fileSha256))
                        persist()
                        assertEquals("LIVE must embed the entire original image-owned MP4, including original audio", original.fileSha256, embeddedHash)
                    }
                }
                mime == "video/mp4" -> {
                    val video = inspectVideo(file, asset?.kind == AlbumAssetKind.ANIMATED,
                        content.isAlbum && mode == DownloadMode.VIDEO, asset != null && asset.kind != AlbumAssetKind.STATIC)
                    item.put("video", video)
                    if (original != null) {
                        val keys = listOf("encodedVideoSha256", "videoSamples", "videoSampleBytes", "trackWidth", "trackHeight", "rotation", "codecMime")
                        val checks = JSONObject().put("type", "SILENT_COMPRESSED_VIDEO_PASSTHROUGH")
                        keys.forEach { key -> checks.put("${key}Equal", original.video.get(key) == video.get(key)) }
                        val csdEqual = original.video.getJSONArray("codecSpecificData").toString() == video.getJSONArray("codecSpecificData").toString()
                        checks.put("codecSpecificDataEqual", csdEqual).put("audioAbsent", video.getJSONArray("trackTypes")
                            .let { types -> (0 until types.length()).none { types.getString(it).startsWith("audio/") } })
                        val durationDifferenceUs = kotlin.math.abs(original.video.getLong("videoTrackDurationUs") - video.getLong("videoTrackDurationUs"))
                        checks.put("videoTrackDurationDifferenceUs", durationDifferenceUs).put("videoTrackDurationPreserved", durationDifferenceUs <= 1_000L)
                        item.put("originalMotionComparison", checks)
                        persist()
                        keys.forEach { key -> assertEquals("Silent MP4 changed original image-owned video $key", original.video.get(key), video.get(key)) }
                        assertTrue("Silent MP4 changed original codec-specific bytes", csdEqual)
                        assertTrue("The original-motion output must contain no audio", checks.getBoolean("audioAbsent"))
                        assertTrue("Silent MP4 changed the original video track endpoint by more than 1 ms", durationDifferenceUs <= 1_000L)
                    }
                }
                mime.startsWith("image/") -> {
                    val image = AlbumMediaValidation.image(file, if (mime == "image/gif") GifConversionPolicy.MAX_GIF_BYTES else AlbumMediaPolicy.MAX_IMAGE_BYTES)
                    assertEquals(mime, image.mimeType)
                    item.put("width", image.width).put("height", image.height).put("animated", image.animated)
                    if (mime == "image/gif" || asset?.kind == AlbumAssetKind.ANIMATED) {
                        assertTrue("A dynamic manifest returned a still image", image.animated)
                        item.put("animation", inspectAnimation(image))
                    }
                    if (original != null && mime == "image/gif") item.put("originalMotionComparison", JSONObject()
                        .put("type", "GIF_SOURCE_AND_ACTUAL_ANIMATION_METADATA_ONLY").put("compressedSampleEqualityRequired", false))
                }
                else -> error("Unsupported published MIME in the source fixture: $mime")
            }
            persist()
        }
        assertEquals("Manifest byte count must equal the copied new files", saved.bytes, copied)
        assertEquals(saved.uris.size, evidence.length())
        return evidence
    }

    private fun inspectAnimation(image: LocalAlbumImage): JSONObject {
        if (image.mimeType == "image/gif") {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(image.file)) { decoder, _, _ -> decoder.setTargetSize(128, 128) }
            assertTrue("GIF must decode as an actual animation", drawable is AnimatedImageDrawable)
            (drawable as AnimatedImageDrawable).stop()
        }
        AnimatedImageFrames(image).use { frames ->
            assertTrue(frames.frameCount >= 2 && frames.durationMs in 1L..900_000L)
            val hashes = linkedSetOf<String>()
            repeat(minOf(12, frames.frameCount)) { index ->
                val time = frames.durationMs * index / minOf(12, frames.frameCount)
                hashes.add(pixelHash(frames.frameAt(time)))
            }
            assertTrue("Animated output contains no changing decoded frames", hashes.size >= 2)
            return JSONObject().put("frameCount", frames.frameCount).put("durationMs", frames.durationMs).put("distinctDecodedFrames", hashes.size)
        }
    }
    private fun inspectVideo(file: File, silent: Boolean, audioRequired: Boolean, changingRequired: Boolean): JSONObject {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            val video = types.indexOfFirst { it.startsWith("video/") }
            assertTrue("No video track", video >= 0)
            if (silent) assertFalse("Motion-video assets must be silent", types.any { it.startsWith("audio/") })
            if (audioRequired) assertTrue("Album BGM composition must contain audio", types.any { it.startsWith("audio/") })
            val format = extractor.getTrackFormat(video)
            val trackWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val trackHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue("The actual encoded video track dimensions must be valid", trackWidth > 0 && trackHeight > 0)
            assertTrue("The actual video track must declare its own duration", format.containsKey(MediaFormat.KEY_DURATION))
            val videoTrackDurationUs = format.getLong(MediaFormat.KEY_DURATION)
            assertTrue("The actual video track endpoint must be positive", videoTrackDurationUs > 0L)
            val csd = JSONArray()
            format.keys.filter { it.matches(Regex("csd-[0-9]+")) }.sorted().forEach { key ->
                val bytes = checkNotNull(format.getByteBuffer(key)) { "A declared codec-specific buffer is absent" }.duplicate()
                bytes.position(0)
                val length = bytes.remaining()
                require(length <= 32 * 1024 * 1024) { "Codec-specific data exceeds the bounded source inspection" }
                val hash = MessageDigest.getInstance("SHA-256").apply { update(bytes) }
                csd.put(JSONObject().put("key", key).put("bytes", length).put("sha256", hex(hash.digest())))
            }
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() else null
            val maximum = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            require(maximum <= 32 * 1024 * 1024) { "An encoded video sample exceeds the bounded decoder check" }
            val buffer = ByteBuffer.allocate(maxOf(2 * 1024 * 1024, maximum))
            val digest = MessageDigest.getInstance("SHA-256")
            extractor.selectTrack(video)
            var samples = 0
            var sampleBytes = 0L
            var firstPtsUs: Long? = null
            var lastPtsUs: Long? = null
            while (extractor.sampleTrackIndex >= 0) {
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                assertTrue("A declared video sample was unreadable", count > 0 && count <= buffer.capacity())
                buffer.position(0); buffer.limit(count); digest.update(buffer)
                digest.update(ByteBuffer.allocate(12).putLong(extractor.sampleTime).putInt(extractor.sampleFlags).array())
                if (samples == 0) firstPtsUs = extractor.sampleTime
                lastPtsUs = extractor.sampleTime
                sampleBytes += count
                samples++
                if (!extractor.advance()) break
            }
            assertTrue("Video needs continuous encoded frames", samples >= 2)
            extractor.unselectTrack(video)
            for (track in types.indices.filter { types[it].startsWith("audio/") }) {
                extractor.selectTrack(track); extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                assertTrue("An audio track has no readable samples", extractor.sampleTrackIndex == track && extractor.sampleSize > 0)
                buffer.clear(); assertTrue("An audio sample was unreadable", extractor.readSampleData(buffer, 0) > 0)
                extractor.unselectTrack(track)
            }
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            assertTrue(width > 0 && height > 0 && duration > 0)
            val hashes = linkedSetOf<String>()
            for (time in listOf(0L, duration * 500L, (duration - 34L).coerceAtLeast(0L) * 1000L).distinct()) {
                val frame = checkNotNull(retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 160, 160)) { "Cannot decode the requested video frame" }
                try { hashes.add(pixelHash(frame)) } finally { frame.recycle() }
            }
            if (changingRequired) assertTrue("Dynamic video has no changing decoded frames", hashes.size >= 2)
            return JSONObject().put("width", width).put("height", height).put("durationMs", duration).put("videoSamples", samples)
                .put("videoTrackDurationUs", videoTrackDurationUs)
                .put("trackWidth", trackWidth).put("trackHeight", trackHeight).put("rotation", rotation)
                .put("trackRotation", if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else JSONObject.NULL)
                .put("codecMime", types[video]).put("codecSpecificData", csd).put("frameRate", frameRate ?: JSONObject.NULL)
                .put("firstVideoPtsUs", firstPtsUs ?: JSONObject.NULL).put("lastVideoPtsUs", lastPtsUs ?: JSONObject.NULL)
                .put("videoSampleBytes", sampleBytes).put("sampleDigestIncludes", "ALL_COMPRESSED_VIDEO_BYTES_AND_EACH_PTS_INT64_BE_FLAGS_INT32_BE_IN_EXTRACTOR_ORDER")
                .put("trackTypes", JSONArray(types)).put("distinctDecodedFrames", hashes.size).put("encodedVideoSha256", hex(digest.digest()))
        } finally { retriever.release(); extractor.release() }
    }
    private fun pixelHash(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        return try {
            val pixels = IntArray(64 * 64)
            small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
            val bytes = ByteBuffer.allocate(pixels.size * 4)
            pixels.forEach(bytes::putInt)
            hex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
        } finally { if (small !== bitmap) small.recycle() }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun existingUserPreferences(): Map<String, Map<String, *>> {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }.toSet() +
            setOf("downloads", "download_tasks", "download_options", "download_folder", "parse_diagnostics", "appearance_v1", "preview_options", "notification_prompt")
        return names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    }
    private class IsolatedApplication(base: Context, private val prefix: String, private val directory: File) : Application() {
        val preferenceNames: MutableSet<String> = Collections.synchronizedSet(linkedSetOf())
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = if (name.startsWith(prefix)) name else prefix + name
            preferenceNames.add(isolated)
            return super.getSharedPreferences(isolated, mode)
        }
        override fun getFilesDir(): File = File(directory, "runtime-files").apply { check(isDirectory || mkdirs()) }
        override fun getCacheDir(): File = File(directory, "runtime-cache").apply { check(isDirectory || mkdirs()) }
    }
    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val completed = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val error = AtomicReference<Throwable?>()
        val action = Runnable {
            try { if (active.get()) block() } catch (failure: Throwable) { error.set(failure) }
            finally { completed.countDown() }
        }
        check(mainHandler.post(action)) { "The main thread rejected the source-test callback" }
        val returned = try { completed.await(30L, TimeUnit.SECONDS) } catch (interrupted: InterruptedException) {
            active.set(false); mainHandler.removeCallbacks(action)
            Thread.currentThread().interrupt()
            throw interrupted
        }
        if (!returned) {
            active.set(false); mainHandler.removeCallbacks(action)
            val failure = AssertionError("Source-test main-thread callback timed out after 30000 ms")
            try {
                val thread = Looper.getMainLooper().thread
                val file = File(checkNotNull(diagnosticDirectory), "main-thread-timeout-${SystemClock.elapsedRealtime()}-${UUID.randomUUID()}.txt")
                file.writeText("elapsedRealtimeMs=${SystemClock.elapsedRealtime()}\nthread=${thread.name}\nstate=${thread.state}\n" +
                    thread.stackTrace.joinToString("\n") { "at $it" })
                mainThreadTimeoutDiagnostics.add(file.absolutePath)
                println("phone_source_main_thread_timeout=${file.absolutePath}")
            } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        }
        error.get()?.let { throw it }
    }
}
