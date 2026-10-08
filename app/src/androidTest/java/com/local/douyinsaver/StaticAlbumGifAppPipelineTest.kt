package com.local.douyinsaver

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
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

/** Opt-in public source -> ordinary MainActivity -> actual timing field/save button -> full GIF inspection. */
@RunWith(AndroidJUnit4::class)
class StaticAlbumGifAppPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val handler = Handler(Looper.getMainLooper())
    // Provider-owned navigation metrics may change; never exempt an App business preference.
    private val webViewRuntimePreferences = setOf("WebViewChromiumPrefs", "AwOriginVisitLoggerPrefs")

    @Test fun publicStaticAlbumUsesActualPointOneSecondInputAndPublishesOneOrderedLoopingGif() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit run_static_album_gif_app=true",
            args.getString("run_static_album_gif_app") == "true")
        val expectedId = requireNotNull(args.getString("expectedId")) { "expectedId is required" }
        require(expectedId.matches(Regex("[0-9]{15,22}")))
        val share = ShareLinks.extract(requireNotNull(args.getString("shareUrl")) { "shareUrl is required" })
        assumeTrue("This MediaStore ownership test requires Android 10 or later", Build.VERSION.SDK_INT >= 29)
        // Opening ordinary MainActivity must not migrate a user's settings or prompt for a new permission.
        assertTrue("Run only after the user's existing appearance profile has been migrated normally",
            context.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE).getInt("profile_version", 0) >= 2)
        assertTrue("Pre-existing notification permission/asked state is required; this test does not change permissions",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                context.getSharedPreferences("notification_prompt", Context.MODE_PRIVATE).getBoolean("asked", false))

        val namespace = "static_album_gif_app_${UUID.randomUUID()}_"
        val directory = File(context.cacheDir, namespace).apply {
            check(mkdir() && canonicalFile.parentFile == context.cacheDir.canonicalFile)
        }
        val app = IsolatedApplication(context.applicationContext, namespace, directory)
        val protectedPreferences = businessPreferences(namespace)
        val realApplication = context.applicationContext as Application
        val protectedHistory = DownloadRecords(realApplication).history().toList()
        val protectedUris = protectedHistory.flatMap { it.uris }.toSet()
        val background = File(context.filesDir, "appearance/background.jpg")
        val backgroundHash = background.takeIf(File::isFile)?.let(::sha256)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val ownUris = linkedSetOf<String>()
        val cleanupErrors = mutableListOf<String>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = JSONObject().put("expectedId", expectedId).put("version", packageInfo.versionName)
            .put("versionCode", packageInfo.longVersionCode).put("instrumentationCompiledVersion", BuildConfig.VERSION_NAME)
            .put("targetApkSha256", sha256(File(context.applicationInfo.sourceDir)))
            .put("testApkSha256", sha256(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("namespace", namespace).put("phase", "starting").put("success", false).put("completed", false)
            .put("normalMainActivity", true).put("headless", false).put("seededContent", false)
            .put("testScope", "NAMESPACE_ISOLATED_MAINACTIVITY_UI_PARSE_TIMING_SAVE")
            .put("foregroundServiceVerified", false)
            .put("parseOverrideUsed", false).put("saveOverrideUsed", false).put("checkMotionClicked", false)
            .put("manualVerificationUsed", false).put("permissionsChanged", false).put("uiTimingInputUsed", false)
            .put("uiSaveButtonClicked", false).put("galleryVerified", false).put("chatPlaybackVerified", false)
            .put("protectedBusinessPreferenceCount", protectedPreferences.size).put("protectedHistoryCount", protectedHistory.size)
            .put("webViewRuntimePreferenceBoundary", "EXACT_PROVIDER_NAMES_ONLY; navigation metrics may change; never restored or deleted")
            .put("signedUrlsWrittenToReport", false).put("cookieProtectionVerified", false)
        val reportFile = File(directory, "static-album-gif-app.json")
        fun persist() = reportFile.writeText(report.toString(2))
        var engine: SaverEngine? = null
        var model: SaverViewModel? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        var verified = false
        val started = SystemClock.elapsedRealtime()
        persist()
        try {
            main {
                (previous as? SaverEngine)?.let {
                    check(!it.busy && !it.queueRunning && !it.batchSaving) { "An existing user task is active; refusing to replace its engine" }
                }
                engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance(app, namespace)
                check(DownloadRecords(app, namespace).history().isEmpty())
                singleton.set(null, engine)
            }
            // ACTION_SEND is the public App entry. No manually completed ParsedVideo or source check is injected.
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, share)
            })
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[SaverViewModel::class.java] }
            val uiModel = checkNotNull(model)
            await(10_000) { mainRead { uiModel.input == share && uiModel.selectedPage == AppPage.HOME } }
            clickText("解析作品")
            report.put("uiParseButtonClicked", true).put("phase", "parse_and_automatic_motion")
            persist()
            await(180_000) {
                val state = snapshot(uiModel)
                report.put("parseStage", state.stage.name).put("motionPhase", state.motionPhase.name)
                    .put("message", state.message).put("automaticMotionSettled", !state.busy && state.motionPhase != AlbumMotionPhase.READING)
                persist()
                assertFalse("Public source needs manual website verification; it is not a successful static-source test",
                    state.motionPhase == AlbumMotionPhase.NEEDS_VERIFICATION)
                assertFalse("Public source failed before a static album could be accepted: ${state.message}",
                    state.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED))
                state.stage == TaskStage.READY && !state.busy && state.motionPhase != AlbumMotionPhase.READING
            }
            val parsed = mainRead {
                val content = checkNotNull(uiModel.video)
                assertEquals(expectedId, content.id)
                assertTrue("The supplied work is currently not an album", content.isAlbum)
                assertTrue("A static sequence requires at least two actual images", content.images.size >= 2)
                assertTrue("This test accepts only completely STATIC items with no motion; unknown/live/animated resources are not a pass",
                    content.images.all { it.kind == AlbumAssetKind.STATIC && it.motion == null })
                assertTrue(uiModel.history.isEmpty())
                assertTrue(uiModel.queue.isEmpty())
                assertNull(uiModel.selectedTaskKey)
                content
            }
            report.put("id", parsed.id).put("imageCount", parsed.images.size).put("allStatic", true).put("motionCount", 0)
                .put("sourceIdentities", JSONArray(parsed.images.mapIndexed { index, image -> JSONObject()
                    .put("sourceIndex", index).put("kind", image.kind.name).put("width", image.width).put("height", image.height)
                    .put("imageKeySha256", digest(image.imageKey.toByteArray())).put("sourceUrlSha256", digest(image.url.toByteArray()))
                    .put("sourceHost", Uri.parse(image.url).host.orEmpty()) }))
            persist()
            screenshot(directory, "static-sources-ready.png")
            val timingLabel = if (parsed.bgmUrl.isNotBlank()) "设置 GIF / 合成视频时长" else "设置 GIF 时长"
            clickText(timingLabel)
            reveal("统一播放时长")
            setTimingText("0.1")
            await(10_000) { mainRead { uiModel.itemDurationSeconds == 0.1 && !uiModel.busy } }
            assertEquals("The actual production input must display the chosen decimal", "0.1", timingField().text?.toString())
            assertTrue("The timing editor must not start a save", mainRead { uiModel.history.isEmpty() })
            report.put("uiTimingInputUsed", true).put("uiTimingFieldLabel", "统一播放时长").put("itemDurationSeconds", 0.1)
                .put("phase", "timing_set_through_ui")
            persist()
            screenshot(directory, "timing-0.1.png")
            clickText("图片序列合成 GIF")
            report.put("uiSaveButtonClicked", true).put("uiSaveButtonLabel", "图片序列合成 GIF").put("phase", "saving")
            persist()
            // Assert start immediately: a READY busy-guard rejection must never become a save timeout or a pass.
            val saving = snapshot(uiModel)
            assertTrue("The UI save did not start: ${saving.stage}; ${saving.message}",
                saving.stage in listOf(TaskStage.DOWNLOADING, TaskStage.SAVING, TaskStage.DONE))
            await(300_000) {
                val state = snapshot(uiModel)
                ownUris.addAll(state.history.flatMap { it.uris })
                report.put("saveStage", state.stage.name).put("message", state.message)
                persist()
                assertFalse("Public-source GIF save failed: ${state.message}", state.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED))
                assertFalse("A pure static GIF must not request a dynamic-duration confirmation", mainRead { uiModel.durationAdjustments.isNotEmpty() })
                state.stage == TaskStage.DONE && !state.busy
            }
            val saved = mainRead {
                val content = checkNotNull(uiModel.video)
                assertEquals(parsed.id, content.id)
                assertEquals("Saving must keep the accepted source order", parsed.images, content.images)
                uiModel.history.single()
            }
            ownUris.addAll(saved.uris)
            assertEquals(expectedId, saved.id)
            assertEquals(AlbumMode.GIF.name, saved.exportMode)
            assertEquals("image/gif", saved.mimeType)
            assertEquals(parsed.images.size, saved.albumSourceCount)
            assertEquals("override=100ms", saved.albumTimingSignature)
            assertEquals(WatermarkMode.CLEAN, saved.watermarkMode)
            assertEquals(1, saved.uris.size)
            assertEquals(1, saved.albumAssets.size)
            val asset = saved.albumAssets.single()
            assertEquals(saved.uri, asset.uri)
            assertEquals(AlbumAssetKind.ANIMATED, asset.kind)
            assertEquals("image/gif", asset.mimeType)
            assertFalse(asset.embeddedMotion)
            assertEquals("", asset.motionUri)
            assertTrue("The App history must persist the same single GIF manifest", DownloadRecords(app, namespace).history() == listOf(saved))
            val uri = Uri.parse(saved.uri)
            requireOwnUri(uri, protectedUris)
            assertEquals("image/gif", context.contentResolver.getType(uri))
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(saved.bytes, it.getLong(1))
            }
            val gif = File(directory, "static-sequence-0.1.gif")
            context.contentResolver.openInputStream(uri)!!.use { input -> gif.outputStream().use { output -> input.copyTo(output) } }
            assertEquals(saved.bytes, gif.length())
            assertTrue("GIF inspection input is empty or unexpectedly exceeds the bounded test budget", gif.length() in 1..128L * 1024L * 1024L)
            val analysis = inspectEveryGifFrame(gif, parsed.images.size, directory)
            report.put("gif", analysis).put("saved", JSONObject().put("id", saved.id).put("uri", saved.uri)
                .put("bytes", saved.bytes).put("fileName", saved.fileName).put("mimeType", saved.mimeType)
                .put("exportMode", saved.exportMode).put("albumSourceCount", saved.albumSourceCount)
                .put("albumTimingSignature", saved.albumTimingSignature).put("gifExportQuality", saved.gifExportQuality))
                .put("persistedHistoryMatched", true).put("phase", "gif_verified")
            persist()
            clickText("下载记录")
            reveal(saved.title)
            assertTrue(mainRead { uiModel.selectedPage == AppPage.HISTORY && uiModel.history == listOf(saved) })
            screenshot(directory, "history-one-gif.png")
            report.put("historyUiVisited", true)
            verified = true
        } catch (error: Throwable) {
            failure = error
            report.put("phaseAtFailure", report.optString("phase")).put("phase", "failed")
                .put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 1800))
            runCatching { screenshot(directory, "failure.png") }
        } finally {
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    cleanupErrors.add(error.javaClass.simpleName + ": " + DiagnosticText.clean(error.message.orEmpty(), 500))
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            engine?.let { isolated ->
                var scope: CoroutineScope? = null
                cleanup { main {
                    isolated.cancel(); isolated.cancelAlbumMotion()
                    scope = SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(isolated) as CoroutineScope
                    scope!!.cancel()
                } }
                cleanup { runBlocking { withTimeout(30_000L) { scope?.coroutineContext?.get(Job)?.join() } } }
                cleanup { ownUris.addAll(DownloadRecords(app, namespace).history().flatMap { it.uris }) }
            }
            cleanup { scenario?.close() }
            cleanup { main { singleton.set(null, previous) } }
            ownUris.forEach { raw -> cleanup {
                val uri = Uri.parse(raw)
                requireOwnUri(uri, protectedUris)
                context.contentResolver.delete(uri, null, null)
                assertFalse("A newly created test URI is still readable after exact deletion", readable(uri))
            } }
            app.preferenceNames.toList().forEach { name -> cleanup {
                check(name.startsWith(namespace)); context.deleteSharedPreferences(name)
            } }
            cleanup { assertEquals("The test changed a real user business preference", protectedPreferences,
                businessPreferences(namespace)) }
            cleanup { assertEquals("The test changed global user history", protectedHistory, DownloadRecords(realApplication).history()) }
            cleanup { assertEquals("The test changed the user's background", backgroundHash, background.takeIf(File::isFile)?.let(::sha256)) }
            report.put("ownedUris", JSONArray(ownUris.toList())).put("cleanupErrors", JSONArray(cleanupErrors))
                .put("userStatePreserved", cleanupErrors.isEmpty()).put("completed", true)
                .put("success", verified && failure == null).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            persist()
            // Evidence remains in this run's unique cache directory; no user/cache folder-wide deletion occurs.
            println("static_album_gif_app_evidence=${directory.absolutePath}")
        }
        failure?.let { throw it }
        assertTrue(report.getBoolean("success"))
    }

    private data class Snapshot(val stage: TaskStage, val busy: Boolean, val motionPhase: AlbumMotionPhase,
                                val message: String, val history: List<SavedVideo>)
    private fun snapshot(model: SaverViewModel) = mainRead {
        Snapshot(model.stage, model.busy, model.albumMotionState().phase, DiagnosticText.clean(model.message, 600), model.history.toList())
    }

    private fun inspectEveryGifFrame(file: File, count: Int, directory: File): JSONObject {
        val data = file.readBytes()
        val width = word(data, 6); val height = word(data, 8)
        assertTrue(width > 0 && height > 0)
        val loop = gifLoopAndTrailer(data)
        assertEquals("The saved sequence must loop indefinitely", 0, loop)
        val frames = GifAnimationInspector.frames(data)
        assertEquals("There must be exactly one encoded frame per static source at 0.1 seconds", count, frames.size)
        assertTrue("Every source must retain its exact 0.1-second delay", frames.all { it.durationMs == 100L })
        assertEquals(count * 100L, frames.sumOf { it.durationMs })
        val evidence = JSONArray()
        val hashes = linkedSetOf<String>()
        frames.forEachIndexed { index, frame ->
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(frame.encoded))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                assertEquals("Frame $index has the wrong canvas width", width, bitmap.width)
                assertEquals("Frame $index has the wrong canvas height", height, bitmap.height)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val digest = MessageDigest.getInstance("SHA-256")
                val row = ByteBuffer.allocate(width * 4)
                for (y in 0 until height) {
                    row.clear(); for (x in 0 until width) row.putInt(pixels[y * width + x]); digest.update(row.array())
                }
                val hash = hex(digest.digest()); hashes.add(hash)
                evidence.put(JSONObject().put("sourceIndex", index).put("durationMs", frame.durationMs)
                    .put("decodedPixelSha256", hash).put("decodedPixelCount", pixels.size))
                if (index == 0 || index == count - 1) File(directory, "gif-frame-$index.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        assertTrue("The complete sequence contains no genuinely different decoded source images", hashes.size >= 2)
        val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data)))
        assertTrue("Android did not recognize the complete exported bytes as animated GIF", decoded is AnimatedImageDrawable)
        (decoded as AnimatedImageDrawable).stop()
        return JSONObject().put("width", width).put("height", height).put("bytes", file.length()).put("sha256", sha256(file))
            .put("frameCount", frames.size).put("totalDurationMs", frames.sumOf { it.durationMs }).put("loopCount", loop)
            .put("distinctFullDecodedFrames", hashes.size).put("allFramesDecoded", true).put("frames", evidence)
            .put("sourceFrameOrderPixelComparisonVerified", false).put("galleryVerified", false).put("chatPlaybackVerified", false)
    }

    /** Independent bounded GIF block walk: inspect NETSCAPE/ANIMEXTS and require the actual final trailer. */
    private fun gifLoopAndTrailer(data: ByteArray): Int {
        check(data.size >= 14 && data.copyOfRange(0, 6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a"))
        var position = 13
        val packed = data[10].toInt() and 255
        if (packed and 128 != 0) position += 3 * (1 shl ((packed and 7) + 1))
        fun byte(): Int { check(position < data.size); return data[position++].toInt() and 255 }
        fun skip(count: Int) { check(count >= 0 && count <= data.size - position); position += count }
        fun blocks(): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            while (true) { val size = byte(); if (size == 0) return result
                check(size <= data.size - position); result += data.copyOfRange(position, position + size); skip(size) }
        }
        var loop: Int? = null
        while (true) when (byte()) {
            0x21 -> when (byte()) {
                0xff -> {
                    val size = byte(); check(size <= data.size - position)
                    val name = data.copyOfRange(position, position + size).toString(Charsets.US_ASCII); skip(size)
                    val extensions = blocks()
                    if (name in listOf("NETSCAPE2.0", "ANIMEXTS1.0")) {
                        val values = extensions.single(); check(values.size == 3 && values[0] == 1.toByte())
                        val value = word(values, 1); check(loop == null || loop == value); loop = value
                    }
                }
                0xf9 -> { check(byte() == 4); skip(4); check(byte() == 0) }
                else -> blocks()
            }
            0x2c -> {
                skip(8); val local = byte()
                if (local and 128 != 0) skip(3 * (1 shl ((local and 7) + 1)))
                byte(); blocks()
            }
            0x3b -> { check(position == data.size) { "Unexpected data after the GIF trailer" }; return checkNotNull(loop) { "Loop metadata is missing" } }
            else -> error("Invalid GIF block")
        }
    }

    private fun word(data: ByteArray, position: Int): Int = (data[position].toInt() and 255) or ((data[position + 1].toInt() and 255) shl 8)
    private fun setTimingText(value: String) {
        assertTrue("The actual production duration field rejected text", timingField().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        instrumentation.waitForIdleSync()
    }
    private fun timingField(): AccessibilityNodeInfo {
        val all = nodes()
        val labels = all.filter { it.isVisibleToUser && it.text?.toString() == "统一播放时长" }
        val editable = all.filter { it.isVisibleToUser && it.isEnabled && it.isEditable &&
            it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
        fun containsLabel(node: AccessibilityNodeInfo): Boolean = node.text?.toString()?.contains("统一播放时长") == true ||
            node.contentDescription?.toString()?.contains("统一播放时长") == true || (0 until node.childCount).any { node.getChild(it)?.let(::containsLabel) == true }
        return editable.firstOrNull(::containsLabel) ?: editable.singleOrNull { edit ->
            val bounds = Rect().also(edit::getBoundsInScreen).apply { inset(-24, -80) }
            labels.any { label -> val point = Rect().also(label::getBoundsInScreen); bounds.contains(point.centerX(), point.centerY()) }
        } ?: error("Could not uniquely identify the visible production field labelled 统一播放时长")
    }
    private fun clickText(value: String) {
        reveal(value)
        val node = nodes().first { it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value) }
        var current: AccessibilityNodeInfo? = node
        while (current != null && current.packageName?.toString() == context.packageName) {
            if (current.isClickable && current.isEnabled) {
                assertTrue("The App button '$value' rejected its click", current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync(); return
            }
            current = current.parent
        }
        error("The visible own-App action '$value' is not enabled/clickable")
    }
    private fun reveal(value: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(28) {
                val all = nodes()
                if (all.any { it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value) }) return
                all.filter { it.isVisibleToUser && it.isScrollable && it.rangeInfo == null }
                    .any { it.performAction(direction) }
                SystemClock.sleep(140L)
            }
        }
        if (nodes().any { it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value) }) return
        error("The production App control '$value' was not accessible after bounded scrolling")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>(); val visited = mutableSetOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !visited.add(node) || !node.refresh() || node.packageName?.toString() != context.packageName) return
            result += node; repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow); instrumentation.uiAutomation.windows.forEach { visit(it.root) }
        return result
    }
    private fun screenshot(directory: File, name: String) {
        assertTrue("Only the App's own visible window may be captured", nodes().any { it.isVisibleToUser })
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
    private fun requireOwnUri(uri: Uri, protectedUris: Set<String>) {
        check(uri.toString() !in protectedUris && uri.scheme == "content" && uri.authority == "media") { "Refusing a pre-existing/non-MediaStore URI" }
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)!!.use {
            check(it.moveToFirst() && it.getString(0) == context.packageName) { "The new URI is not owned by the App" }
        }
    }
    private fun readable(uri: Uri) = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    private fun businessPreferences(namespace: String): Map<String, Map<String, *>> {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") && !it.name.startsWith(namespace) }
            .map { it.name.removeSuffix(".xml") }.toSet() + setOf("downloads", "download_tasks", "download_options", "download_folder",
                "parse_diagnostics", "appearance_v1", "preview_options", "notification_prompt")
        return (names - webViewRuntimePreferences).associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    }
    private class IsolatedApplication(base: Context, private val prefix: String, private val directory: File) : Application() {
        val preferenceNames: MutableSet<String> = Collections.synchronizedSet(linkedSetOf())
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = if (name.startsWith(prefix)) name else prefix + name
            preferenceNames.add(isolated); return super.getSharedPreferences(isolated, mode)
        }
        override fun getFilesDir(): File = File(directory, "runtime-files").apply { check(isDirectory || mkdirs()) }
        override fun getCacheDir(): File = File(directory, "runtime-cache").apply { check(isDirectory || mkdirs()) }
    }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(180L) }
        throw AssertionError("Static GIF App condition timed out after $timeout ms")
    }
    private fun <T> mainRead(block: () -> T): T {
        var value: T? = null; main { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val completed = CountDownLatch(1); val active = AtomicBoolean(true); val failure = AtomicReference<Throwable?>()
        val callback = Runnable { try { if (active.get()) block() } catch (error: Throwable) { failure.set(error) } finally { completed.countDown() } }
        check(handler.post(callback))
        val returned = try { completed.await(30L, TimeUnit.SECONDS) } catch (interrupted: InterruptedException) {
            active.set(false); handler.removeCallbacks(callback); Thread.currentThread().interrupt(); throw interrupted
        }
        if (!returned) {
            active.set(false); handler.removeCallbacks(callback)
            throw AssertionError("Static GIF test main-thread callback timed out after 30000 ms")
        }
        failure.get()?.let { throw it }
    }
    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
