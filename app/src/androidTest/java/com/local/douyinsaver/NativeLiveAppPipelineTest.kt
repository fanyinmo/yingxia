package com.local.douyinsaver

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
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
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Controlled source qualification is deliberately separate from public-source/OEM Gallery acceptance.
 * Ordinary MainActivity UI and real production downloader/storage are used; only namespace parsing
 * and exact network response bodies are supplied. No save override, fake URI, or preset completion.
 */
@RunWith(AndroidJUnit4::class)
class NativeLiveAppPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val handler = Handler(Looper.getMainLooper())
    private val providerMetrics = setOf("WebViewChromiumPrefs", "AwOriginVisitLoggerPrefs")

    @Test fun primaryLiveButtonVerifiesAndPreservesCompleteNativeJpegWithoutSeparateMotionUrl() = isolated("primary") { test ->
        val source = test.album("9999999999999999801", "primary", listOf(test.live(test.nativeUrl)))
        test.launchSingle(source)
        test.assertPendingSource(source)
        test.screenshot("primary-pending-live.png")
        test.blockTransfer = true
        try {
            clickText("保存实况")
            assertTrue("The real primary action did not reach the real transfer", test.transferEntered.await(10, TimeUnit.SECONDS))
            main {
                assertEquals(TaskStage.DOWNLOADING, test.model.stage)
                assertTrue(test.model.history.isEmpty())
                assertNotEquals("A download candidate is not verified motion", AlbumMotionPhase.AVAILABLE,
                    test.model.albumMotionState().phase)
                assertTrue(test.model.message.contains("检查实况"))
            }
            assertTrue("A transfer still being checked must not publish a URI", test.ownedUris().isEmpty())
            test.report.put("notAvailableBeforeFileVerification", true).put("noUriBeforeFileVerification", true)
            test.screenshot("primary-verifying-before-bytes.png")
        } finally { test.transferRelease.countDown() }
        test.awaitSingleDone()
        val saved = mainRead { test.model.history.single() }
        test.assertNativeSaved(saved, source, 0, "primary")
        assertEquals(listOf(test.nativeUrl), test.requests.toList())
        assertEquals(source, mainRead { test.model.video })
        test.report.put("uiSaveButton", "保存实况").put("uiPrimarySaveUsed", true)
        clickText("下载记录")
        test.screenshot("primary-history-live.png")
        assertTrue("The saved file must be presented as actual embedded live content",
            mainRead { savedAlbumImages(test.model.history.single()).single().motion?.embeddedInPhoto == true })
    }

    @Test fun individualLivePreviewSavesOnlySelectedNativeImageAndRetainsOriginalIndex() = isolated("individual") { test ->
        val source = test.album("9999999999999999802", "individual",
            listOf(test.live(test.coverUrl), test.live(test.nativeUrl)))
        test.launchSingle(source)
        test.assertPendingSource(source)
        // The placeholder thumbnail has no decoded Image semantics. Its real
        // clickable card always exposes the visible position/type caption.
        clickText("1 / 2 · 实况")
        await(5_000) { nodes().any { it.isVisibleToUser && it.text?.toString() == "实况 · 1 / 2" } }
        clickText("下一张")
        await(5_000) { nodes().any { it.isVisibleToUser && it.text?.toString() == "实况 · 2 / 2" } }
        test.screenshot("individual-second-live-preview.png")
        clickText("保存这张实况")
        test.awaitSingleDone()
        val saved = mainRead { test.model.history.single() }
        test.assertNativeSaved(saved, source, 1, "individual-second")
        assertEquals("The unselected plain-cover item must never be fetched", listOf(test.nativeUrl), test.requests.toList())
        assertEquals(1, saved.albumSourceCount)
        assertEquals(source, mainRead { test.model.video })
        test.report.put("uiSaveButton", "保存这张实况").put("uiNextImageUsed", true)
            .put("unselectedImageNotRequested", true).put("selectionRetainsOriginalIndex", true)
        test.screenshot("individual-saved.png")
    }

    @Test fun liveQueueCardSavesNativeFileAndBatchRejectsStillCoverThenContinuesNeighbor() = isolated("batch") { test ->
        val good = test.album("9999999999999999803", "native_card", listOf(test.live(test.nativeUrl)))
        val bad = test.album("9999999999999999804", "still_declared_live", listOf(test.live(test.coverUrl)))
        val neighbor = test.album("9999999999999999805", "static_neighbor", listOf(test.static(test.neighborUrl)))
        test.launchQueue(listOf(good, bad, neighbor))
        main {
            assertEquals(3, test.model.queue.size)
            assertTrue(test.model.queue.all { it.status == QueueStatus.READY })
            assertTrue(test.model.history.isEmpty())
            test.model.queue.forEach { task -> assertNotEquals(AlbumMotionPhase.AVAILABLE,
                test.model.albumMotionState(task.key).phase) }
        }
        test.screenshot("batch-three-pending.png")
        clickTextInCard("保存实况", good.title)
        await(30_000) { mainRead {
            !test.model.busy && !test.model.batchSaving && test.model.queue.first().status == QueueStatus.DONE
        } }
        val native = mainRead { test.model.history.single() }
        test.assertNativeSaved(native, good, 0, "batch-card")
        val nativeUriBytes = read(Uri.parse(native.uri))
        assertEquals(listOf(test.nativeUrl), test.requests.toList())
        test.screenshot("batch-card-native-saved.png")
        clickText("保存已解析")
        await(30_000) { mainRead {
            !test.model.busy && !test.model.batchSaving && test.model.queue.all {
                it.status in listOf(QueueStatus.DONE, QueueStatus.FAILED)
            }
        } }
        val histories = mainRead {
            assertEquals(listOf(QueueStatus.DONE, QueueStatus.FAILED, QueueStatus.DONE), test.model.queue.map { it.status })
            assertTrue("Plain JPEG must fail because its live clip is absent", test.model.queue[1].message.contains("实况动态内容"))
            assertEquals("Only native live plus the later neighbor may create records", setOf(good.id, neighbor.id), test.model.history.map { it.id }.toSet())
            assertFalse(test.model.history.any { it.id == bad.id })
            test.model.history.toList()
        }
        val afterNative = histories.single { it.id == good.id }
        assertEquals("A skipped completed native item must preserve its exact record", native, afterNative)
        assertArrayEquals("The native neighbor's saved URI was changed", nativeUriBytes, read(Uri.parse(native.uri)))
        val static = histories.single { it.id == neighbor.id }
        assertEquals(1, static.uris.size)
        assertEquals(AlbumAssetKind.STATIC, static.albumAssets.single().kind)
        assertFalse(static.albumAssets.single().embeddedMotion)
        assertArrayEquals(test.cover.readBytes(), read(Uri.parse(static.uri)))
        assertEquals("Both complete-native and plain-still JPEG are controlled exact inputs",
            listOf(test.nativeUrl, test.coverUrl, test.neighborUrl), test.requests.toList())
        val published = test.ownedUris()
        assertEquals("The failed LIVE cover must create no pending or published URI", histories.flatMap { it.uris }.toSet(), published)
        assertEquals(2, published.size)
        assertTrue(DownloadRecords(test.app, test.namespace).history() == histories)
        test.report.put("uiQueueCardSaveUsed", true).put("uiSaveParsedUsed", true)
            .put("stillDeclaredLiveRejected", true).put("failedItemHasNoUriOrRecord", true)
            .put("laterNeighborSaved", true).put("completedNativeRecordAndBytesPreserved", true)
            .put("queueStatuses", JSONArray(mainRead { test.model.queue.map { it.status.name } }))
            .put("staticNeighbor", JSONObject().put("bytes", static.bytes).put("sha256", digest(read(Uri.parse(static.uri)))))
        test.screenshot("batch-failed-cover-neighbor-saved.png")
    }

    private inner class Scope(val name: String, val namespace: String, val directory: File, val app: IsolatedApplication) {
        lateinit var engine: SaverEngine
        lateinit var model: SaverViewModel
        var activity: ActivityScenario<MainActivity>? = null
        lateinit var cover: File
        lateinit var originalVideo: File
        lateinit var native: File
        val nativeUrl = "https://p3.douyinpic.com/${namespace}native.jpg"
        val coverUrl = "https://p3.douyinpic.com/${namespace}cover.jpg"
        val neighborUrl = "https://p3.douyinpic.com/${namespace}neighbor.jpg"
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val transferEntered = CountDownLatch(1)
        val transferRelease = CountDownLatch(1)
        @Volatile var blockTransfer = false
        val report = JSONObject().put("name", name).put("namespace", namespace).put("success", false)
            .put("completed", false).put("normalMainActivity", true).put("headless", false)
            .put("controlledFixture", true).put("seededContent", true).put("realPublic", false)
            .put("oemGalleryVerified", false).put("nativePhoneGalleryVerified", false)
            .put("saveOverrideUsed", false).put("fakeUriUsed", false).put("fileCompletionOverrideUsed", false)
            .put("foregroundServiceVerified", false).put("signedUrlsWrittenToReport", false)
            .put("cookieProtectionVerified", false).put("manualMotionCheckClicked", false)
            .put("targetApkSha256", sha256(File(context.applicationInfo.sourceDir)))
            .put("testApkSha256", sha256(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("version", InstalledTestTarget.versionName).put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
        fun live(url: String) = ParsedImage(url, 1280, 720, listOf(MediaSource(url, WatermarkMode.CLEAN)),
            AlbumAssetKind.LIVE, "image/jpeg", motion = null, imageKey = digest(url.toByteArray()))
        fun static(url: String) = live(url).copy(kind = AlbumAssetKind.STATIC)
        fun album(id: String, label: String, images: List<ParsedImage>) = ParsedVideo(id, namespace + label, "", 0.0,
            1280, 720, images.first().url, images)
        fun initializeFixture() {
            cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            originalVideo = File(directory, "fixture-original.mp4").also { output ->
                instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
            }
            assertEquals("The independently decoded authorized source fixture changed",
                "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", sha256(originalVideo))
            native = runBlocking { withTimeout(30_000) {
                MotionPhotoFixtureWriter.createForGallery(cover, originalVideo, File(directory, "fixture-native-MP.jpg"),
                    MotionPhotoContainer.Format.XIAOMI, presentationTimestampUs = 0)
            } }
            val verified = EmbeddedMotionReader.validate(native)
            assertEquals(1280, verified.width); assertEquals(720, verified.height); assertEquals(2_000L, verified.durationMs)
            val sourceClip = runBlocking { withTimeout(30_000) {
                EmbeddedMotionReader.extractForPreview(native, File(directory, "fixture-tail.mp4"))
            } }
            assertArrayEquals("The fixture must really contain all original movie bytes", originalVideo.readBytes(), sourceClip.readBytes())
            assertNull("The corresponding plain cover fixture must not contain live motion", MotionPhotoContainer.inspect(cover))
            report.put("fixture", JSONObject().put("coverSha256", sha256(cover)).put("nativeSha256", sha256(native))
                .put("nativeBytes", native.length()).put("motionSha256", sha256(originalVideo)).put("durationMs", 2_000)
                .put("sourceWasCapturedLive", false).put("construction", "CONTROLLED_MATCHED_JPEG_PLUS_COMPLETE_60_FRAME_MP4"))
        }
        fun initializeEngine() {
            val bodies = mapOf(nativeUrl to native.readBytes(), coverUrl to cover.readBytes(), neighborUrl to cover.readBytes())
            val transfer = MediaTransfer(connections = { uri ->
                val bytes = checkNotNull(bodies[uri.toString()]) { "Unexpected request outside this controlled image fixture" }
                requests.add(uri.toString()); transferEntered.countDown()
                if (blockTransfer) check(transferRelease.await(15, TimeUnit.SECONDS)) { "Controlled transfer gate timed out" }
                object : HttpURLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getContentType() = "image/jpeg"
                    override fun getContentLengthLong() = bytes.size.toLong()
                    override fun getInputStream() = ByteArrayInputStream(bytes)
                }
            }, cookies = { null })
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(app, namespace)
            SaverEngine::class.java.getDeclaredField("downloader").apply { isAccessible = true }
                .set(engine, ContentDownloader(app, transfer, null))
            assertNull(engine.queueSaveOverride)
            engine.updateNamingRule(NamingRule.TITLE)
            engine.fileName = namespace + "single"
        }
        fun launchSingle(source: ParsedVideo) {
            activity = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            activity!!.onActivity { activity ->
                model = ViewModelProvider(activity)[SaverViewModel::class.java]
                model.acceptShare("https://www.douyin.com/note/${source.id}")
                acceptPending(source)
            }
            await(10_000) { mainRead { model.stage == TaskStage.READY && !model.busy } }
        }
        fun launchQueue(sources: List<ParsedVideo>) {
            main {
                engine.queueParseOverride = { task ->
                    acceptPending(sources.single { task.source.endsWith("/${it.id}") })
                }
            }
            activity = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            activity!!.onActivity { activity ->
                model = ViewModelProvider(activity)[SaverViewModel::class.java]
                model.enqueueInput(sources.joinToString("\n") { "https://www.douyin.com/note/${it.id}" })
            }
            clickText("解析全部")
            await(10_000) { mainRead { !model.queueRunning && !model.busy && model.queueResults.size == sources.size } }
            report.put("parseOverrideUsed", true).put("uiParseAllUsed", true)
        }
        private fun acceptPending(source: ParsedVideo) {
            @Suppress("UNCHECKED_CAST")
            val stage = SaverEngine::class.java.getDeclaredField("stage\$delegate").apply { isAccessible = true }
                .get(engine) as MutableState<TaskStage>
            stage.value = TaskStage.VERIFYING
            // A completed controlled desktop array skips only automatic network acquisition.
            // Its LIVE/null-motion classification remains UNAVAILABLE until real bytes are checked.
            SaverEngine::class.java.getDeclaredField("desktopShareCandidate").apply { isAccessible = true }.set(engine, source)
            SaverEngine::class.java.getDeclaredMethod("acceptVerifiedResult", Int::class.javaPrimitiveType,
                ParsedVideo::class.java, List::class.java).apply { isAccessible = true }
                .invoke(engine, engine.generation, source, emptyList<SavedVideo>())
            report.put("acceptedControlledVerifiedResult", true).put("automaticNetworkReadSkippedByFixture", true)
        }
        fun assertPendingSource(source: ParsedVideo) = main {
            assertEquals(TaskStage.READY, model.stage); assertFalse(model.busy)
            assertEquals(source, model.video)
            assertTrue(source.images.all { it.kind == AlbumAssetKind.LIVE && it.motion == null })
            assertEquals("Header-qualified candidate must not claim a retrieved clip", AlbumMotionPhase.UNAVAILABLE,
                model.albumMotionState().phase)
            assertTrue(WatermarkSources.availableForDownload(source, WatermarkMode.CLEAN))
            assertFalse("Strict playable-source policy is deliberately unchanged", WatermarkSources.available(source, WatermarkMode.CLEAN))
            assertTrue(model.history.isEmpty()); assertTrue(ownedUris().isEmpty())
            report.put("pendingSourceKind", "LIVE").put("pendingSeparateMotion", false).put("pendingMotionPhase", model.albumMotionState().phase.name)
        }
        fun awaitSingleDone() = await(30_000) {
            mainRead {
                assertFalse("Native download failed: ${model.message}", model.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED))
                model.stage == TaskStage.DONE && !model.busy
            }
        }
        fun assertNativeSaved(saved: SavedVideo, content: ParsedVideo, index: Int, label: String) {
            assertEquals(content.id, saved.id); assertEquals(AlbumMode.IMAGES.name, saved.exportMode)
            assertEquals(WatermarkMode.CLEAN, saved.watermarkMode)
            assertEquals("image/jpeg", saved.mimeType); assertEquals(1, saved.uris.size)
            assertEquals(saved.uri, saved.uris.single())
            val asset = saved.albumAssets.single()
            assertEquals(AlbumAssetKind.LIVE, asset.kind); assertTrue(asset.embeddedMotion)
            assertEquals("", asset.motionUri); assertEquals(index, asset.sourceIndex); assertEquals(saved.uri, asset.uri)
            val uri = Uri.parse(saved.uri)
            assertTrue("Native photograph must use the images collection", uri.path.orEmpty().contains("images"))
            requireOwnUri(uri, emptySet())
            assertEquals("image/jpeg", context.contentResolver.getType(uri))
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(native.length(), it.getLong(1))
            }
            assertArrayEquals("Complete source JPEG, XMP and MP4 must be preserved byte-for-byte", native.readBytes(), read(uri))
            val output = File(directory, "$label-saved-native.jpg").apply { writeBytes(read(uri)) }
            val verified = EmbeddedMotionReader.validate(output)
            assertEquals(MotionPhotoContainer.inspect(native), verified.container)
            assertEquals(1280, verified.width); assertEquals(720, verified.height); assertEquals(2_000L, verified.durationMs)
            val tail = runBlocking { withTimeout(30_000) {
                EmbeddedMotionReader.extractForPreview(output, File(directory, "$label-saved-tail.mp4"))
            } }
            assertArrayEquals(originalVideo.readBytes(), tail.readBytes())
            val frames = assertAllOriginalFrames(tail)
            assertTrue("Real namespaced persistence must contain the same record", DownloadRecords(app, namespace).history().contains(saved))
            report.put("saved-$label", JSONObject().put("id", saved.id).put("uri", saved.uri).put("sourceIndex", index)
                .put("kind", asset.kind.name).put("mimeType", asset.mimeType).put("embeddedMotion", true)
                .put("bytes", output.length()).put("sha256", sha256(output)).put("wholeNativeFileUnchanged", true)
                .put("motionSha256", sha256(tail)).put("wholeMotionFileUnchanged", true).put("frames", frames))
        }
        fun ownedUris(): Set<String> {
            val rows = linkedSetOf<String>()
            for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                val arguments = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$namespace*"))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME), arguments, null)!!.use {
                    while (it.moveToNext()) {
                        check(it.getString(1) == context.packageName) { "Unexpected owner in an exact unique test prefix" }
                        rows.add(ContentUris.withAppendedId(collection, it.getLong(0)).toString())
                    }
                }
            }
            return rows
        }
        fun screenshot(name: String) = capture(directory, name)
        fun persist() = File(directory, "native-live-app.json").writeText(report.toString(2))
    }

    private fun isolated(name: String, run: (Scope) -> Unit) {
        assumeTrue("Requires explicit run_native_live_app=true", InstrumentationRegistry.getArguments().getString("run_native_live_app") == "true")
        assumeTrue("MediaStore ownership assertions require Android 10+", Build.VERSION.SDK_INT >= 29)
        assertTrue("Existing appearance migration is required; no user preferences may be migrated by this fixture",
            context.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE).getInt("profile_version", 0) >= 2)
        assertTrue("Do not change the user's notification permission/asked state",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                context.getSharedPreferences("notification_prompt", Context.MODE_PRIVATE).getBoolean("asked", false))
        val namespace = "native_live_app_${UUID.randomUUID().toString().replace("-", "")}_"
        val directory = File(context.cacheDir, namespace).apply { check(mkdir() && canonicalFile.parentFile == context.cacheDir.canonicalFile) }
        val app = IsolatedApplication(context.applicationContext, namespace, directory)
        val test = Scope(name, namespace, directory, app)
        val protectedPreferences = businessPreferences(namespace)
        val realApp = context.applicationContext as Application
        val protectedHistory = DownloadRecords(realApp).history().toList()
        val protectedUris = protectedHistory.flatMap { it.uris }.toSet()
        val background = File(context.filesDir, "appearance/background.jpg")
        val backgroundHash = background.takeIf(File::isFile)?.let(::sha256)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        var failure: Throwable? = null
        var passed = false
        var initialized = false
        val cleanupErrors = mutableListOf<String>()
        val ownUris = linkedSetOf<String>()
        val started = SystemClock.elapsedRealtime()
        test.report.put("protectedHistoryCount", protectedHistory.size).put("protectedBusinessPreferenceCount", protectedPreferences.size)
        test.persist()
        try {
            test.initializeFixture()
            main {
                (previous as? SaverEngine)?.let { check(!it.busy && !it.queueRunning && !it.batchSaving) { "A pre-existing user task is active" } }
                test.initializeEngine(); initialized = true; singleton.set(null, test.engine)
            }
            assertTrue("This run's exact unique MediaStore prefix is not empty", test.ownedUris().isEmpty())
            run(test)
            assertNull(test.engine.queueSaveOverride)
            val runtime = File(directory, "runtime-cache")
            assertTrue("Actual album temporaries must be cleaned", runtime.listFiles().orEmpty().none { it.name.startsWith("album_") })
            ownUris.addAll(test.ownedUris())
            assertEquals(mainRead { test.model.history }, DownloadRecords(app, namespace).history())
            passed = true
        } catch (error: Throwable) {
            failure = error
            test.report.put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 1600))
            runCatching { test.screenshot("failure.png") }
        } finally {
            test.transferRelease.countDown()
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) {
                cleanupErrors.add(error.javaClass.simpleName + ": " + DiagnosticText.clean(error.message.orEmpty(), 500))
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            if (initialized) {
                var scope: CoroutineScope? = null
                cleanup { main {
                    test.engine.cancel(); test.engine.cancelAlbumMotion()
                    scope = SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(test.engine) as CoroutineScope
                    scope!!.cancel()
                } }
                cleanup { runBlocking { withTimeout(30_000) { scope?.coroutineContext?.get(Job)?.join() } } }
            }
            cleanup { test.activity?.close() }
            cleanup { main { singleton.set(null, previous) } }
            cleanup { ownUris.addAll(test.ownedUris()) }
            ownUris.forEach { raw -> cleanup {
                val uri = Uri.parse(raw); requireOwnUri(uri, protectedUris)
                assertEquals("Only a newly owned URI may be removed", 1, context.contentResolver.delete(uri, null, null))
                assertFalse("Owned test URI still readable after exact deletion", readable(uri))
            } }
            cleanup { assertTrue("A pending/published test row survived cleanup", test.ownedUris().isEmpty()) }
            app.preferenceNames.toList().forEach { pref -> cleanup { check(pref.startsWith(namespace)); context.deleteSharedPreferences(pref) } }
            cleanup { assertEquals("A real user business preference changed", protectedPreferences, businessPreferences(namespace)) }
            cleanup { assertEquals("Global download history changed", protectedHistory, DownloadRecords(realApp).history()) }
            cleanup { assertEquals("The user's background changed", backgroundHash, background.takeIf(File::isFile)?.let(::sha256)) }
            test.report.put("ownedUris", JSONArray(ownUris.toList())).put("cleanupErrors", JSONArray(cleanupErrors))
                .put("userStatePreserved", cleanupErrors.isEmpty()).put("completed", true).put("success", passed && failure == null)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("requests", JSONArray(test.requests.map { JSONObject().put("host", Uri.parse(it).host).put("urlSha256", digest(it.toByteArray())) }))
            test.persist()
            println("native_live_app_evidence=${directory.absolutePath}")
        }
        failure?.let { throw it }
    }

    private fun assertAllOriginalFrames(file: File): JSONObject {
        val bytes = instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.json").use { it.readBytes() }
        assertEquals("Independent 60-frame fixture manifest changed", "ac6d70c4a9d9727a3219ccb26c120a96bb307c16ba510ca8b235185375b0c453", digest(bytes))
        val expected = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("validation").getJSONObject("independentSequentialDecode").getJSONArray("frames")
        assertEquals(60, expected.length())
        val extractor = MediaExtractor()
        var packets = 0
        try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            val format = extractor.getTrackFormat(video)
            assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(video)
            while (extractor.sampleSize > 0) {
                assertTrue("Unexpected extra encoded sample", packets < expected.length())
                assertTrue("Original sample PTS changed", abs(extractor.sampleTime - expected.getJSONObject(packets).getLong("ptsUs")) <= 2L)
                packets++; if (!extractor.advance()) break
            }
        } finally { extractor.release() }
        assertEquals(60, packets)
        val reader = MediaMetadataRetriever()
        var decoded = 0; var red = 0; var blue = 0
        try {
            reader.setDataSource(file.absolutePath)
            assertEquals(2_000L, reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong())
            for (start in 0 until 60 step 4) {
                val frames = reader.getFramesAtIndex(start, minOf(4, 60 - start))
                try {
                    assertEquals(minOf(4, 60 - start), frames.size)
                    frames.forEachIndexed { offset, frame ->
                        assertEquals(1280, frame.width); assertEquals(720, frame.height)
                        val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
                        val actual = if (Color.red(pixel) > Color.blue(pixel) + 80) "red" else if (Color.blue(pixel) > Color.red(pixel) + 80) "blue" else "other"
                        assertEquals("Actual decoded frame ${start + offset} changed", expected.getJSONObject(start + offset).getString("color"), actual)
                        for (x in listOf(0, 150, 1129, 1279)) {
                            val edge = frame.getPixel(x, frame.height / 2)
                            assertTrue("Original black side bars were lost", maxOf(Color.red(edge), Color.green(edge), Color.blue(edge)) <= 4)
                        }
                        if (actual == "red") red++ else if (actual == "blue") blue++
                        decoded++
                    }
                } finally { frames.forEach { it.recycle() } }
            }
        } finally { reader.release() }
        assertEquals(60, decoded); assertEquals(30, red); assertEquals(30, blue)
        return JSONObject().put("encodedFrames", packets).put("decodedFrames", decoded).put("redFrames", red).put("blueFrames", blue)
            .put("allOriginalPtsAndGeometry", true).put("durationMs", 2_000)
    }

    private fun clickText(value: String) = click(value) { true }
    private fun clickTextInCard(value: String, title: String) = click(value) { node ->
        var parent: AccessibilityNodeInfo? = node
        while (parent != null && parent.packageName?.toString() == context.packageName) {
            val subtree = descendants(parent)
            if (subtree.any { it.text?.toString() == title } && subtree.count {
                    it.text?.toString() == value || it.contentDescription?.toString() == value
                } <= 2) return@click true
            parent = parent.parent
        }
        false
    }
    private fun click(value: String, accept: (AccessibilityNodeInfo) -> Boolean) {
        for (direction in listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)) {
            repeat(25) {
                val all = nodes()
                val candidate = all.firstOrNull { it.isVisibleToUser &&
                    (it.text?.toString() == value || it.contentDescription?.toString() == value) && accept(it) }
                if (candidate != null) {
                    var target: AccessibilityNodeInfo? = candidate
                    while (target != null && target.packageName?.toString() == context.packageName) {
                        if (target.isClickable && target.isEnabled) {
                            assertTrue("Production UI action '$value' rejected its click", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            instrumentation.waitForIdleSync(); return
                        }
                        target = target.parent
                    }
                }
                all.filter { it.isVisibleToUser && it.isScrollable && it.rangeInfo == null }.any { it.performAction(direction) }
                SystemClock.sleep(120)
            }
        }
        error("No uniquely qualified enabled production UI action '$value' after bounded scrolling")
    }
    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>(); val visited = mutableSetOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !visited.add(node) || !node.refresh() || node.packageName?.toString() != context.packageName) return
            out.add(node); repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(root); return out
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        return roots.flatMap(::descendants).distinct()
    }
    private fun capture(directory: File, name: String) {
        assertTrue("Only the own App visible window may be captured", nodes().any { it.isVisibleToUser })
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun requireOwnUri(uri: Uri, protected: Set<String>) {
        check(uri.toString() !in protected && uri.scheme == "content" && uri.authority == "media") { "Refusing pre-existing or non-MediaStore URI" }
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)!!.use {
            check(it.moveToFirst() && it.getString(0) == context.packageName) { "New test URI is not App owned" }
        }
    }
    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun readable(uri: Uri) = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    private fun businessPreferences(namespace: String): Map<String, Map<String, *>> {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") && !it.name.startsWith(namespace) }.map { it.name.removeSuffix(".xml") }.toSet() +
            setOf("downloads", "download_tasks", "download_options", "download_folder", "parse_diagnostics", "appearance_v1", "preview_options", "notification_prompt")
        return (names - providerMetrics).associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
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
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        throw AssertionError("Native live App condition timed out after $timeout ms")
    }
    private fun <T> mainRead(block: () -> T): T {
        var value: T? = null; main { value = block() }; @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val done = CountDownLatch(1); val active = AtomicBoolean(true); val failure = AtomicReference<Throwable?>()
        val callback = Runnable { try { if (active.get()) block() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() } }
        check(handler.post(callback))
        val returned = try { done.await(30, TimeUnit.SECONDS) } catch (error: InterruptedException) {
            active.set(false); handler.removeCallbacks(callback); Thread.currentThread().interrupt(); throw error
        }
        if (!returned) { active.set(false); handler.removeCallbacks(callback); error("Native live main-thread callback timed out after 30000 ms") }
        failure.get()?.let { throw it }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
