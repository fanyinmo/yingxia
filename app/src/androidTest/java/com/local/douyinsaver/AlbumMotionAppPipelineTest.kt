package com.local.douyinsaver

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit public samples exercise the actual App action; only this run's media and preferences are removed. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AlbumMotionAppPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun explicitlyRequestedAlbumChecksSourcesAndSavesItsChosenDynamicFormatThroughTheApp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("run_album_motion_app") == "true")
        val id = requireNotNull(args.getString("id"))
        require(id.matches(Regex("\\d{15,22}")))
        val share = ShareLinks.extract(requireNotNull(args.getString("shareUrl")))
        val batch = args.getString("batch") == "true"
        val desktopSetup = args.getString("seed_cover_from_desktop") == "true"
        val requestedMode = AlbumMode.valueOf(args.getString("mode") ?: args.getString("export_mode") ?:
            if (args.getString("export_gif") == "true") AlbumMode.GIF.name else AlbumMode.LIVE_PHOTOS.name)
        require(requestedMode in listOf(AlbumMode.LIVE_PHOTOS, AlbumMode.MOTION_VIDEOS, AlbumMode.GIF))
        val gifExport = requestedMode == AlbumMode.GIF
        val expectedMotionHash = args.getString("expected_motion_sha256")
        require(expectedMotionHash == null || expectedMotionHash.matches(Regex("[0-9a-f]{64}")))
        val expectedVideoHash = args.getString("expected_video_track_sha256")
        require(expectedVideoHash == null || expectedVideoHash.matches(Regex("[0-9a-f]{64}")))
        val report = JSONObject().put("id", id).put("version", InstalledTestTarget.versionName).put("instrumentationCompiledVersion", BuildConfig.VERSION_NAME)
            .put("batch", batch).put("success", false).put("parseSource", if (desktopSetup) "DESKTOP_COVER_TEST_SETUP" else "MOBILE_SHARE")
            .put("exportFormat", requestedMode.name)
        val directory = File(context.cacheDir, "album_motion_app_${UUID.randomUUID()}").apply { check(mkdir()) }
        val namespace = "album_motion_app_${UUID.randomUUID()}_"
        val app = IsolatedApplication(context.applicationContext, namespace)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        var engine: SaverEngine? = null
        var scenario: ActivityScenario<MainActivity>? = null
        val ownUris = linkedSetOf<String>()
        val cleanup = mutableListOf<String>()
        val started = SystemClock.elapsedRealtime()
        try {
            // Explicit setup for an emulator whose mobile sharing page is rejected. This is
            // reported separately and never counted as a successful initial mobile parse.
            val setupAlbum = if (desktopSetup) readDesktopCover(id) else null
            main {
                engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance(app, namespace)
                if (setupAlbum != null && batch) engine!!.queueParseOverride = { completeCover(engine!!, setupAlbum) }
                singleton.set(null, engine)
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            lateinit var model: SaverViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[SaverViewModel::class.java]
                if (batch) { model.enqueueInput(share); model.startQueue() }
                else { model.acceptShare(share); if (setupAlbum != null) completeCover(engine!!, setupAlbum) else model.resolve() }
            }
            report.put("phase", "fast_parse")
            await(90_000) {
                if (batch) !model.queueRunning && model.queue.any { it.status in listOf(QueueStatus.READY, QueueStatus.FAILED) }
                else model.stage in listOf(TaskStage.READY, TaskStage.FAILED, TaskStage.CANCELLED)
            }
            var base: ParsedVideo? = null
            var taskKey: String? = null
            var finalImageCount = 0
            var resolved: ParsedVideo? = null
            var effectiveMode = requestedMode
            var expectedTimingSignature = ""
            main {
                taskKey = if (batch) model.queue.single().key else null
                base = if (batch) model.queueResults[taskKey] else model.video
                report.put("parseStage", model.stage.name).put("message", DiagnosticText.clean(model.message, 600))
                    .put("diagnostics", DiagnosticText.clean(model.diagnostics, 16_000))
                if (base == null) {
                    assertFalse("Failed-share recovery is a single-link action", batch)
                    assertEquals(TaskStage.FAILED, model.stage)
                    assertEquals(id, model.browsingId)
                    report.put("parseSource", "MOBILE_SHARE_THEN_MANUAL_DESKTOP").put("mobilePageDenied", true)
                } else {
                    assertEquals(id, base!!.id)
                    assertTrue(base!!.isAlbum)
                }
                assertTrue(model.history.isEmpty())
            }
            report.put("initialImages", base?.images?.size ?: 0).put("initialMotions", base?.images?.count { it.motion != null } ?: 0)
            val autoReadAtReady = mainRead { model.albumMotionState(taskKey).phase == AlbumMotionPhase.READING }
            report.put("automaticReadAtFastReady", autoReadAtReady)
            if (autoReadAtReady) {
                report.put("phase", "await_automatic_sources")
                await(45_000) { !model.busy && model.albumMotionState(taskKey).phase != AlbumMotionPhase.READING }
                report.put("automaticReadFinalState", mainRead { model.albumMotionState(taskKey).phase.name })
            }
            main {
                report.put("automaticReadDetail", model.albumMotionState(taskKey).detail)
                    .put("diagnosticsAfterAutomaticRead", DiagnosticText.clean(model.diagnostics, 16_000))
            }
            main { assertFalse("Automatic checking must settle before a save action", model.busy) }
            screenshot(directory, "ready.png")
            val sourcesAfterAutomaticRead = mainRead { if (batch) model.queueResults[taskKey] else model.video }
            if (args.getString("require_automatic_motion") == "true") {
                main {
                    assertEquals("Automatic source reading must succeed without clicking retry: ${model.albumMotionState(taskKey).detail}",
                        AlbumMotionPhase.AVAILABLE, model.albumMotionState(taskKey).phase)
                }
                assertTrue("Automatic reading must provide real per-image motion", sourcesAfterAutomaticRead?.hasDynamicAlbumAssets == true)
            }
            if (sourcesAfterAutomaticRead == null || !sourcesAfterAutomaticRead.hasDynamicAlbumAssets || sourcesAfterAutomaticRead.images.any {
                    it.kind in listOf(AlbumAssetKind.LIVE, AlbumAssetKind.DYNAMIC) && it.motion == null }) {
                assertTrue("The App must offer a check-only retry after automatic reading",
                    clickUiButton("重新检查动态资源", exact = true))
                screenshot(directory, "reading.png")
                report.put("phase", "check_sources")
                await(90_000) { !model.busy && model.albumMotionState(taskKey).phase in listOf(
                    AlbumMotionPhase.AVAILABLE, AlbumMotionPhase.UNAVAILABLE, AlbumMotionPhase.NEEDS_VERIFICATION) }
                main {
                    report.put("retryDetail", model.albumMotionState(taskKey).detail)
                        .put("diagnosticsAfterRetry", DiagnosticText.clean(model.diagnostics, 16_000))
                    assertEquals("Source check failed: ${model.albumMotionState(taskKey).detail}",
                        AlbumMotionPhase.AVAILABLE, model.albumMotionState(taskKey).phase)
                    assertTrue("Checking sources must not create download records", model.history.isEmpty())
                    report.put("sourceCheckDidNotSave", true)
                }
            }
            main {
                resolved = if (batch) model.queueResults[taskKey] else model.video
                val content = checkNotNull(resolved)
                assertEquals(id, content.id); assertTrue(content.isAlbum); assertTrue(content.hasDynamicAlbumAssets)
                base?.let { initial ->
                    assertTrue(content.images.size >= initial.images.size)
                    val mapped = AlbumSelection.correspondingIndices(initial, content, initial.images.indices.toList())
                    assertEquals(initial.images.size, mapped.size)
                    assertTrue(mapped.all { it in content.images.indices })
                }
                assertTrue(model.history.isEmpty())
                effectiveMode = if (!AlbumActionUiPolicy.requiresFormatChoice(content) && requestedMode != AlbumMode.GIF)
                    AlbumMode.IMAGES else requestedMode
                expectedTimingSignature = if (effectiveMode == AlbumMode.GIF) AlbumTiming.signature(
                    DownloadOptions(imageSeconds = model.imageSeconds, itemDurationSeconds = model.itemDurationSeconds)) else ""
            }
            screenshot(directory, "sources-ready.png")
            val contentForSave = checkNotNull(resolved)
            if (effectiveMode != AlbumMode.IMAGES) assertTrue("The format must be an explicit UI choice",
                clickUiButton(when (effectiveMode) {
                    AlbumMode.GIF -> "GIF 动图"; AlbumMode.LIVE_PHOTOS -> "实况照片"; else -> "无声动图"
                }, exact = true))
            // Click the real accessible App button if laid out; scrolling never interacts with the website.
            val clicked = clickUiButton(AlbumActionUiPolicy.saveLabel(contentForSave, effectiveMode), exact = true)
            report.put("uiButtonClicked", clicked)
            assertTrue("The App's visible save-motion button must be clickable", clicked)
            screenshot(directory, "saving.png")
            report.put("phase", "save_chosen_format").put("effectiveMode", effectiveMode.name)
            await(180_000) {
                (!model.busy && (if (batch) model.queue.single().status in listOf(QueueStatus.DONE, QueueStatus.FAILED, QueueStatus.CANCELLED)
                else model.stage in listOf(TaskStage.DONE, TaskStage.FAILED, TaskStage.CANCELLED))) ||
                    model.albumMotionState(taskKey).phase in listOf(AlbumMotionPhase.UNAVAILABLE, AlbumMotionPhase.NEEDS_VERIFICATION)
            }
            var saved: SavedVideo? = null
            main {
                report.put("saveStage", model.stage.name).put("message", DiagnosticText.clean(model.message, 600))
                    .put("motionPhase", model.albumMotionState(taskKey).phase.name)
                    .put("motionDetail", DiagnosticText.clean(model.albumMotionState(taskKey).detail, 600))
                    .put("diagnostics", DiagnosticText.clean(model.diagnostics, 16_000))
                ownUris.addAll(model.history.flatMap { it.uris })
                assertEquals("App save failed: ${model.message}", if (batch) QueueStatus.DONE else TaskStage.DONE,
                    if (batch) model.queue.single().status else model.stage)
                val current = checkNotNull(if (batch) model.queueResults[taskKey] else model.video)
                assertEquals(id, current.id)
                assertEquals(contentForSave.images.map { it.imageKey }, current.images.map { it.imageKey })
                assertEquals(contentForSave.images.map { it.url }, current.images.map { it.url })
                assertEquals(contentForSave.images.map { it.motion?.url }, current.images.map { it.motion?.url })
                finalImageCount = current.images.size
                saved = model.history.single()
            }
            val result = checkNotNull(saved)
            assertEquals(id, result.id)
            assertEquals(effectiveMode.name, result.exportMode)
            assertEquals("The saved timing must reflect this export's settings", expectedTimingSignature, result.albumTimingSignature)
            assertEquals(WatermarkMode.CLEAN, result.watermarkMode)
            assertEquals(finalImageCount, result.albumAssets.size)
            val assets = JSONArray()
            result.albumAssets.forEachIndexed { index, asset ->
                val uri = Uri.parse(asset.uri)
                assertEquals(index, asset.sourceIndex)
                assertEquals("New exports publish no separate live-photo MP4", "", asset.motionUri)
                assertEquals(asset.mimeType, context.contentResolver.getType(uri))
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                if (asset.embeddedMotion) {
                    assertEquals(AlbumAssetKind.LIVE, asset.kind)
                    assertEquals("image/jpeg", asset.mimeType)
                    val photoFile = File(directory, "image-${index + 1}-live.jpg")
                    context.contentResolver.openInputStream(uri)!!.use { input -> photoFile.outputStream().use { input.copyTo(it) } }
                    val verified = EmbeddedMotionReader.validate(photoFile)
                    val file = runBlocking { EmbeddedMotionReader.extractForPreview(context, uri,
                        File(directory, "image-${index + 1}-embedded.mp4")) }
                    AlbumMediaValidation.motionVideo(file)
                    val hash = sha256(file)
                    val expectedOriginalHash = args.getString("expected_motion_sha256_${index + 1}") ?: expectedMotionHash.takeIf { index == 0 }
                    val expectedEncodedHash = args.getString("expected_video_track_sha256_${index + 1}") ?: expectedVideoHash.takeIf { index == 0 }
                    if (expectedOriginalHash != null) assertEquals("Original source bytes must remain exact", expectedOriginalHash, hash)
                    val cover = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(photoFile.absolutePath, cover)
                    assertTrue(cover.outWidth > 0 && cover.outHeight > 0)
                    val evidence = inspectMotion(file)
                    verifyExpectedMotion(evidence, index)
                    if (expectedEncodedHash != null) assertEquals(expectedEncodedHash, evidence.getString("encodedVideoSha256"))
                    assets.put(evidence.put("index", index).put("bytes", file.length()).put("photoBytes", photoFile.length())
                        .put("sha256", hash).put("coverWidth", cover.outWidth).put("coverHeight", cover.outHeight)
                        .put("embeddedMotion", true).put("hasAudio", verified.hasAudio).put("sourceBytesMatched", expectedOriginalHash != null))
                } else if (asset.mimeType == "video/mp4") {
                    assertEquals(AlbumAssetKind.ANIMATED, asset.kind)
                    val file = File(directory, "image-${index + 1}-silent.mp4")
                    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                    AlbumMediaValidation.motionVideo(file)
                    val evidence = inspectMotion(file)
                    assertFalse("Video animations must be silent", evidence.getBoolean("hasAudio"))
                    verifyExpectedMotion(evidence, index)
                    val expectedEncodedHash = args.getString("expected_video_track_sha256_${index + 1}") ?: expectedVideoHash.takeIf { index == 0 }
                    if (expectedEncodedHash != null) assertEquals("Compressed frame bytes/timestamps must remain exact",
                        expectedEncodedHash, evidence.getString("encodedVideoSha256"))
                    assets.put(evidence.put("index", index).put("bytes", file.length()).put("sha256", sha256(file)).put("embeddedMotion", false))
                } else if (asset.mimeType == "image/gif") {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                    assertTrue(decoded is AnimatedImageDrawable)
                    (decoded as AnimatedImageDrawable).stop()
                    val evidence = inspectGif(bytes)
                    assertTrue(evidence.optInt("distinctFrames") >= 2)
                    File(directory, "image-${index + 1}.gif").writeBytes(bytes)
                    assets.put(evidence.put("index", index).put("bytes", bytes.size))
                }
            }
            assertTrue("At least one real dynamic file must be published", assets.length() > 0)
            assertEquals(result.albumAssets.size, result.uris.size)
            report.put("assets", assets).put("albumTimingSignature", result.albumTimingSignature)
                .put("historyCount", 1).put("success", true).put("phase", "verified")
            main { model.selectedPage = AppPage.HISTORY }
            screenshot(directory, "history.png")
            assertTrue("History must open this saved album", clickUiButton("查看图集"))
            SystemClock.sleep(300)
            val previewIndex = result.albumAssets.indexOfFirst { it.embeddedMotion || it.mimeType in listOf("image/gif", "video/mp4") }
            assertTrue(previewIndex >= 0)
            assertTrue("Saved item must open in the item preview", clickUiButton("图集第 ${previewIndex + 1} 张", exact = true))
            val previewAsset = result.albumAssets[previewIndex]
            val animatedImagePreview = previewAsset.mimeType == "image/gif"
            screenshot(directory, "cover-preview.png")
            if (animatedImagePreview) await(15_000) { animatedPreview()?.let { (it.drawable as AnimatedImageDrawable).isRunning } == true }
            else {
                assertTrue("The published motion must remain playable", clickUiButton(
                    if (previewAsset.embeddedMotion) "播放实况" else "播放动图", exact = true))
                await(15_000) { videoPreview()?.player?.let { it.playbackState == Player.STATE_READY && it.currentPosition > 0 } == true }
            }
            val previewHashes = linkedSetOf<String>()
            repeat(12) {
                val bounds = Rect()
                main { if (animatedImagePreview) checkNotNull(animatedPreview()).getGlobalVisibleRect(bounds)
                    else checkNotNull(videoPreview()?.videoSurfaceView).getGlobalVisibleRect(bounds) }
                val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val crop = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                    val small = Bitmap.createScaledBitmap(crop, 64, 64, true)
                    try {
                        val pixels = ByteBuffer.allocate(small.byteCount)
                        small.copyPixelsToBuffer(pixels)
                        previewHashes += MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) }
                    } finally { if (small !== crop) small.recycle(); crop.recycle() }
                } finally { screen.recycle() }
                SystemClock.sleep(150)
            }
            assertTrue("The real App dynamic preview must change its displayed pixels", previewHashes.size > 1)
            report.put("previewPlaying", true).put("distinctPreviewFrames", previewHashes.size)
            screenshot(directory, "preview.png")
        } catch (error: Throwable) {
            report.put("success", false).put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 1000))
            throw error
        } finally {
            // Keep own-run evidence even if the emulator's MediaProvider blocks cleanup.
            report.put("runRoot", directory.name).put("cleanupStarted", true)
                .put("ownedUris", JSONArray(ownUris.toList()))
                .put("ownedPreferenceNames", JSONArray(app.preferenceNames.toList()))
            File(directory, "before-cleanup.json").writeText(report.toString(2))
            runCatching { main { engine?.let { isolated ->
                ownUris.addAll(isolated.history.flatMap { it.uris }); isolated.cancel()
            } } }.onFailure { cleanup += it.javaClass.simpleName }
            runCatching { scenario?.close() }.onFailure { cleanup += it.javaClass.simpleName }
            runCatching { main {
                engine?.let { (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel() }
                singleton.set(null, previous)
            } }.onFailure { cleanup += it.javaClass.simpleName }
            ownUris.forEach { raw -> runCatching { context.contentResolver.delete(Uri.parse(raw), null, null) }
                .onFailure { cleanup += it.javaClass.simpleName } }
            app.preferenceNames.toList().forEach { name -> runCatching {
                check(name.startsWith(namespace)); context.deleteSharedPreferences(name)
            }.onFailure { cleanup += it.javaClass.simpleName } }
            report.put("cleanupErrors", JSONArray(cleanup)).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("runRoot", directory.name)
            if (cleanup.isNotEmpty()) report.put("success", false)
            val json = report.toString(2)
            File(directory, "report.json").writeText(json)
            File(context.cacheDir, "album-motion-app-$id${if (batch) "-batch" else ""}-${requestedMode.name.lowercase()}.json").writeText(json)
            File(context.cacheDir, "album-motion-app-$id${if (batch) "-batch" else ""}${if (gifExport) "-gif" else ""}.json").writeText(json)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> mainRead(block: () -> T): T {
        var result: T? = null
        main { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun completeCover(engine: SaverEngine, album: ParsedVideo) {
        @Suppress("UNCHECKED_CAST")
        val stage = SaverEngine::class.java.getDeclaredField("stage\$delegate").apply { isAccessible = true }
            .get(engine) as androidx.compose.runtime.MutableState<TaskStage>
        stage.value = TaskStage.VERIFYING
        SaverEngine::class.java.getDeclaredMethod("acceptVerifiedResult", Int::class.javaPrimitiveType,
            ParsedVideo::class.java, List::class.java).apply { isAccessible = true }
            .invoke(engine, engine.generation, album, emptyList<SavedVideo>())
    }

    private fun readDesktopCover(id: String): ParsedVideo {
        val result = AtomicReference<DesktopAlbumResult?>()
        val latch = CountDownLatch(1)
        ActivityScenario.launch<DesktopAlbumProbeActivity>(Intent(context, DesktopAlbumProbeActivity::class.java)).use { probe ->
            probe.onActivity { activity ->
                activity.resolver = DesktopAlbumResolver(activity.probeWebView, id, { result.set(it); latch.countDown() })
            }
            assertTrue("Desktop test setup timed out", latch.await(40, TimeUnit.SECONDS))
            assertEquals("Desktop test setup was not a confirmed album", DesktopAlbumStatus.CANDIDATE, result.get()?.status)
            val album = checkNotNull(result.get()?.album)
            assertEquals(id, album.id)
            return album.copy(images = album.images.map { it.copy(motion = null, kind = AlbumAssetKind.STATIC) })
        }
    }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false; main { ready = condition() }
            if (ready) return
            SystemClock.sleep(150)
        }
        fail("Timed out waiting for the App")
    }

    private fun screenshot(directory: File, name: String) {
        SystemClock.sleep(300)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun animatedPreview(): ImageView? {
        fun find(view: View): ImageView? {
            if (view is ImageView && view.isAttachedToWindow && view.drawable is AnimatedImageDrawable) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
    }

    private fun videoPreview(): PlayerView? {
        fun find(view: View): PlayerView? {
            if (view is PlayerView && view.isAttachedToWindow && view.player != null) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
    }

    private fun inspectMotion(file: File): JSONObject {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val format = extractor.getTrackFormat(track)
            val trackTypes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            val digest = MessageDigest.getInstance("SHA-256")
            val maximum = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocate(maxOf(4 * 1024 * 1024, maximum).coerceAtMost(32 * 1024 * 1024))
            extractor.selectTrack(track)
            var samples = 0
            while (extractor.sampleTime >= 0) {
                assertTrue(extractor.sampleSize <= buffer.capacity())
                buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val data = ByteArray(size); buffer.position(0); buffer.get(data); digest.update(data)
                digest.update(ByteBuffer.allocate(12).putLong(extractor.sampleTime).putInt(extractor.sampleFlags).array())
                samples++; if (!extractor.advance()) break
            }
            assertTrue(samples > 1)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue(width > 0 && height > 0)
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: 0.0 else 0.0
            val videoTrackDurationUs = format.getLong(MediaFormat.KEY_DURATION)
            val durationMs = videoTrackDurationUs / 1000
            assertTrue(durationMs > 0)
            retriever.setDataSource(file.absolutePath)
            val frames = linkedSetOf<String>()
            repeat(5) { index ->
                val frame = checkNotNull(retriever.getScaledFrameAtTime((durationMs * 1000 * index / 4).coerceAtMost(durationMs * 1000 - 1),
                    MediaMetadataRetriever.OPTION_CLOSEST, 128, 128))
                try {
                    val pixels = ByteBuffer.allocate(frame.byteCount); frame.copyPixelsToBuffer(pixels)
                    frames += MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) }
                } finally { frame.recycle() }
            }
            assertTrue(frames.size > 1)
            return JSONObject().put("width", width).put("height", height).put("frameRate", fps)
                .put("encodedSamples", samples).put("durationMs", durationMs).put("distinctFrames", frames.size)
                .put("videoTrackDurationUs", videoTrackDurationUs)
                .put("encodedVideoSha256", digest.digest().joinToString("") { "%02x".format(it) })
                .put("trackTypes", JSONArray(trackTypes)).put("hasAudio", trackTypes.any { it.startsWith("audio/") })
        } finally { retriever.release(); extractor.release() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyExpectedMotion(evidence: JSONObject, sourceIndex: Int) {
        val args = InstrumentationRegistry.getArguments()
        fun expectation(name: String) = args.getString("${name}_${sourceIndex + 1}") ?: args.getString(name).takeIf { sourceIndex == 0 }
        expectation("expected_width")?.let { assertEquals(it.toInt(), evidence.getInt("width")) }
        expectation("expected_height")?.let { assertEquals(it.toInt(), evidence.getInt("height")) }
        expectation("expected_frame_rate")?.let { assertEquals(it.toDouble(), evidence.getDouble("frameRate"), 0.1) }
        expectation("expected_duration_ms")?.let { assertTrue(kotlin.math.abs(it.toLong() - evidence.getLong("durationMs")) <= 100) }
        expectation("expected_video_duration_us")?.let {
            assertTrue("The saved video track must preserve the expected endpoint to within 1 ms",
                kotlin.math.abs(it.toLong() - evidence.getLong("videoTrackDurationUs")) <= 1_000L)
        }
        expectation("expected_has_audio")?.let { assertEquals(it.toBooleanStrict(), evidence.getBoolean("hasAudio")) }
    }

    private fun clickUiButton(label: String, exact: Boolean = false): Boolean {
        repeat(8) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root == null) { SystemClock.sleep(300); return@repeat }
            fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                fun matches(value: CharSequence?) = if (exact) value?.toString() == label else value?.toString()?.contains(label) == true
                if (node.isVisibleToUser && (matches(node.text) || matches(node.contentDescription))) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { child -> find(child)?.let { return it } }
                return null
            }
            val button = find(root)
            if (button != null) {
                var node: AccessibilityNodeInfo? = button
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isEnabled == true) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        val now = SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        val up = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        try {
                            if (instrumentation.uiAutomation.injectInputEvent(down, true) &&
                                instrumentation.uiAutomation.injectInputEvent(up, true)) return true
                        } finally { down.recycle(); up.recycle() }
                    }
                }
            }
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                return false
            }
            scroll(root)
            SystemClock.sleep(300)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun inspectGif(bytes: ByteArray): JSONObject {
        assertEquals("GIF89a", bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII))
        val movie = checkNotNull(Movie.decodeByteArray(bytes, 0, bytes.size))
        val bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val hashes = linkedSetOf<String>()
        try {
            (0..4).forEach { index ->
                bitmap.eraseColor(Color.TRANSPARENT)
                movie.setTime((movie.duration() * index / 4).coerceAtMost(movie.duration() - 1))
                movie.draw(Canvas(bitmap), 0f, 0f)
                val pixels = ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(pixels)
                hashes += MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) }
            }
            return JSONObject().put("width", movie.width()).put("height", movie.height())
                .put("durationMs", movie.duration()).put("distinctFrames", hashes.size)
        } finally { bitmap.recycle() }
    }

    private class IsolatedApplication(base: Context, private val prefix: String) : Application() {
        val preferenceNames: MutableSet<String> = Collections.synchronizedSet(linkedSetOf())
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = if (name.startsWith(prefix)) name else prefix + name
            preferenceNames.add(isolated)
            return super.getSharedPreferences(isolated, mode)
        }
    }
}
