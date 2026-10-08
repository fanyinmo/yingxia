package com.local.douyinsaver

import android.graphics.Color
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** F19: one synthetic work with all four source kinds, isolated records and exact owned URIs. */
@RunWith(AndroidJUnit4::class)
class FourKindAlbumPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val kinds = listOf(AlbumAssetKind.STATIC, AlbumAssetKind.ANIMATED, AlbumAssetKind.LIVE, AlbumAssetKind.DYNAMIC)

    @Test fun fourSourceKindsKeepIdentityKnownDefaultsAndEverySingleItemIsIsolated() = runBlocking(Dispatchers.IO) {
        fixture("save") { scope ->
            val original = scope.content
            assertEquals(kinds, original.images.map { it.kind })
            val reordered = original.copy(images = listOf(original.images[3], original.images[1], original.images[0], original.images[2]))
            assertEquals(listOf(0), AlbumSelection.correspondingIndices(original, reordered, listOf(3)))
            assertEquals(listOf(3), AlbumSelection.correspondingIndices(original, reordered, listOf(2)))
            assertNotNull(runCatching { AlbumSelection.correspondingIndices(original, reordered.copy(id = "9999999999999999910"), listOf(3)) }.exceptionOrNull())
            for (mode in listOf(AlbumMode.LIVE_PHOTOS, AlbumMode.MOTION_VIDEOS)) {
                val network = network(scope.bodies)
                val whole = network.downloader.download(original, null, options(scope, mode), { _, _ -> }, {}, scope::save)
                val expected = listOf(AlbumAssetKind.STATIC, AlbumAssetKind.ANIMATED, AlbumAssetKind.LIVE,
                    if (mode == AlbumMode.LIVE_PHOTOS) AlbumAssetKind.LIVE else AlbumAssetKind.ANIMATED)
                assertEquals(4, whole.uris.size)
                assertEquals(expected, whole.albumAssets.map { it.kind })
                assertEquals(listOf(0, 1, 2, 3), whole.albumAssets.map { it.sourceIndex })
                assertEquals(scope.expectedRequests, network.requests)
                whole.albumAssets.forEachIndexed { index, asset -> verifyAsset(scope, asset, index, mode) }
                assertEquals(whole, DownloadRecords(context, scope.namespace).history().first())
                scope.evidence.put(JSONObject().put("mode", mode.name).put("sourceKinds", JSONArray(kinds.map { it.name }))
                    .put("savedKinds", JSONArray(expected.map { it.name })).put("sourceIndices", JSONArray(whole.albumAssets.map { it.sourceIndex })))
            }
            for (index in original.images.indices) {
                val selected = AlbumSelection.select(original, listOf(index))
                val image = original.images[index]
                val authorized = listOfNotNull(image.url, image.motion?.url)
                val network = network(scope.bodies.filterKeys { it in authorized })
                val single = network.downloader.download(selected, null,
                    options(scope, AlbumMode.LIVE_PHOTOS).copy(selectedImageIndices = listOf(index)), { _, _ -> }, {}, scope::save)
                assertEquals(1, single.uris.size)
                assertEquals(index, single.albumAssets.single().sourceIndex)
                assertEquals(authorized, network.requests)
                verifyAsset(scope, single.albumAssets.single(), index, AlbumMode.LIVE_PHOTOS)
                scope.evidence.put(JSONObject().put("singleOriginalIndex", index).put("requestCount", network.requests.size))
            }
            assertEquals(kinds, original.images.map { it.kind })
            assertEquals(scope.motionHash, hash(scope.motion))
            assertEquals(scope.unknownMotionHash, hash(scope.unknownMotion))
        }
    }

    @Test fun fourSourceKindsComposeTheirActualFramesAndSameWorkBgmInOriginalOrder() = runBlocking(Dispatchers.IO) {
        fixture("bgm") { scope ->
            val nativeVideo = AnimatedImageVideoConverter(context).convert(
                AlbumMediaValidation.image(scope.animation), File(scope.directory, "native-duration.mp4"))
            val nativeDuration = duration(nativeVideo)
            val motionDuration = duration(scope.motion)
            val unknownDuration = duration(scope.unknownMotion)
            val staticDuration = 300L
            val network = network(scope.bodies)
            var warnings = 0
            val saved = network.downloader.download(scope.content, null,
                options(scope, AlbumMode.VIDEO).copy(staticImageSeconds = staticDuration / 1000.0),
                { _, _ -> }, {}, scope::save, { warnings++; true })
            val output = copy(saved.uri, scope.directory, "four-kind-bgm.mp4")
            if (InstrumentationRegistry.getArguments().getString("capture_f19") == "true") {
                // Capture before every BGM assertion so a failing concat keeps reviewable media and its timeline.
                val diagnostic = JSONObject().put("check", "bgm_concat_timeline")
                    .put("sourceDurationsMs", JSONArray(listOf(staticDuration, nativeDuration, motionDuration, unknownDuration)))
                    .put("sampleStepMs", 100).put("frameOption", "OPTION_CLOSEST")
                scope.evidence.put(diagnostic)
                diagnostic.put("avcDecoderCapabilities", avcDecoderCapabilities())
                runCatching {
                    diagnostic.put("finalCacheFile", archive(scope, output, "four-kind-bgm.mp4")?.relativeTo(context.cacheDir)?.path)
                    diagnostic.put("nativeCacheFile", archive(scope, nativeVideo, "native-gif-intermediate.mp4")?.relativeTo(context.cacheDir)?.path)
                    diagnostic.put("finalDurationMs", duration(output)).put("finalCenterColors", centerColorTimeline(output))
                    diagnostic.put("nativeDurationMs", nativeDuration).put("nativeCenterColors", centerColorTimeline(nativeVideo))
                }.onFailure { error ->
                    diagnostic.put("diagnosticErrorType", error.javaClass.simpleName)
                        .put("diagnosticError", DiagnosticText.clean(error.message.orEmpty(), 1000))
                }
            }
            assertEquals(0, warnings)
            assertEquals(scope.expectedRequests + scope.content.bgmUrl, network.requests)
            assertEquals(1, saved.uris.size)
            assertEquals("video/mp4", saved.mimeType)
            val expectedDuration = staticDuration + nativeDuration + motionDuration + unknownDuration
            assertTrue("Four actual item durations did not sum: ${duration(output)} / $expectedDuration",
                abs(duration(output) - expectedDuration) <= 180L)
            assertTrue(trackTypes(output).containsAll(listOf("video/avc", "audio/mp4a-latm")))
            assertColor(output, 150_000L, "blue")
            var offset = staticDuration
            assertColor(output, (offset + nativeDuration / 5) * 1000L, "red")
            assertColor(output, (offset + nativeDuration * 4 / 5) * 1000L, "blue")
            offset += nativeDuration
            assertColor(output, (offset + 250L) * 1000L, "red")
            assertColor(output, (offset + 1500L) * 1000L, "blue")
            offset += motionDuration
            assertColor(output, (offset + 250L) * 1000L, "blue")
            assertColor(output, (offset + unknownDuration * 3 / 4) * 1000L, "red")
            assertTrue("Looped BGM ends early", lastAudioTimestampUs(output) >= (expectedDuration - 250L) * 1000L)
            assertEquals(kinds, scope.content.images.map { it.kind })
            assertEquals(scope.motionHash, hash(scope.motion))
            assertEquals(scope.unknownMotionHash, hash(scope.unknownMotion))
            scope.evidence.put(JSONObject().put("sourceKinds", JSONArray(kinds.map { it.name }))
                .put("sourceDurationsMs", JSONArray(listOf(staticDuration, nativeDuration, motionDuration, unknownDuration)))
                .put("expectedDurationMs", expectedDuration).put("actualDurationMs", duration(output)).put("orderedFramesVerified", true))
            archive(scope, output, "four-kind-bgm.mp4")
        }
    }

    private data class Scope(val directory: File, val namespace: String, val content: ParsedVideo,
        val bodies: Map<String, ByteArray>, val motion: File, val motionHash: String, val unknownMotion: File,
        val unknownMotionHash: String, val animation: File,
        val expectedRequests: List<String>, val evidence: JSONArray = JSONArray(), val createdUris: MutableSet<String> = linkedSetOf()) {
        fun save(saved: SavedVideo) {
            createdUris.addAll(saved.uris)
            DownloadRecords(InstrumentationRegistry.getInstrumentation().targetContext, namespace).save(saved)
        }
    }

    private suspend fun fixture(label: String, body: suspend (Scope) -> Unit) {
        val token = UUID.randomUUID().toString().replace("-", "")
        val directory = File(context.cacheDir, "f19_$token").apply {
            check(mkdir() && canonicalFile.parentFile == context.cacheDir.canonicalFile)
        }
        val namespace = "f19_${token}_"
        val installedPackage = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = JSONObject().put("fixture", "F19").put("kind", label).put("version", BuildConfig.VERSION_NAME)
            .put("installedVersionName", installedPackage.versionName.orEmpty())
            .put("installedVersionCode", if (android.os.Build.VERSION.SDK_INT >= 28) installedPackage.longVersionCode else installedPackage.versionCode.toLong())
            .put("instrumentationCompiledVersion", BuildConfig.VERSION_NAME)
            .put("success", false).put("controlled", true).put("publicWork", false)
        var scope: Scope? = null
        val cleanupErrors = JSONArray()
        try {
            val blue = asset("album_test/second_blue.png", directory)
            val liveCover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_PNG, directory)
            val unknownCover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.BLUE_FIRST_LONG_PNG, directory)
            val audio = asset("album_test/short_bgm.m4a", directory)
            val animation = asset("dynamic_album_test/two_frames.gif", directory)
            // Only source preparation is fixed; native GIF conversion and final BGM exports stay real.
            val motion = asset("motion_test/fixed_red_blue_2s.mp4", directory).also {
                assertEquals("Fixed LIVE source differs from its independent manifest",
                    "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(it))
            }
            val unknownMotion = asset("motion_test/fixed_blue_red_4s.mp4", directory).also {
                assertEquals("Fixed unknown-DYNAMIC source differs from its independent manifest",
                    "6bffa756909f402a6445a8c3f65e2921b2f645acdaa739e3f54ee0f32c88b7a4", hash(it))
            }
            val urls = (0..3).map { "https://p3.douyinpic.com/f19_${token}_$it" }
            val liveUrl = "https://v3.douyinvod.com/f19_${token}_live.mp4"
            val unknownUrl = "https://v3.douyinvod.com/f19_${token}_unknown.mp4"
            val bgm = "https://lf-music.douyinstatic.com/f19_$token.m4a"
            fun image(index: Int, motionUrl: String? = null) = ParsedImage(urls[index], if (motionUrl == null) 48 else 1280,
                if (motionUrl == null) 48 else 720,
                mediaSources = listOf(MediaSource(urls[index], WatermarkMode.CLEAN)), kind = kinds[index], imageKey = "f19-photo-$index",
                motion = motionUrl?.let { ParsedMotion(it, mediaSources = listOf(MediaSource(it, WatermarkMode.CLEAN))) })
            val content = ParsedVideo("9999999999999999919", "F19 同作品四类型自制素材", "", 0.0, 48, 48,
                images = listOf(image(0), image(1), image(2, liveUrl), image(3, unknownUrl)), bgmUrl = bgm)
            val bodies = mapOf(urls[0] to blue.readBytes(), urls[1] to animation.readBytes(), urls[2] to liveCover.readBytes(),
                urls[3] to unknownCover.readBytes(), liveUrl to motion.readBytes(), unknownUrl to unknownMotion.readBytes(), bgm to audio.readBytes())
            val active = Scope(directory, namespace, content, bodies, motion, hash(motion), unknownMotion, hash(unknownMotion), animation,
                listOf(urls[0], urls[1], urls[2], liveUrl, urls[3], unknownUrl))
            scope = active
            assertTrue(DownloadRecords(context, namespace).history().isEmpty())
            body(active)
            report.put("success", true).put("checks", active.evidence)
        } catch (error: Throwable) {
            report.put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 1000))
            throw error
        } finally {
            scope?.createdUris?.forEach { raw -> runCatching {
                check(context.contentResolver.delete(Uri.parse(raw), null, null) == 1)
            }.onFailure { cleanupErrors.put(it.javaClass.simpleName) } }
            listOf("downloads", "download_tasks").forEach { context.deleteSharedPreferences(namespace + it) }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
            if (cleanupErrors.length() > 0) report.put("success", false)
            report.put("checks", scope?.evidence ?: JSONArray())
                .put("cleanupErrors", cleanupErrors).put("createdFilesCleaned", scope?.createdUris?.size ?: 0)
            File(context.cacheDir, "f19-$label-result.json").writeText(report.toString(2))
            assertEquals("Only exact F19 owned files may be cleaned", 0, cleanupErrors.length())
        }
    }

    private data class Network(val downloader: AlbumDownloader, val requests: MutableList<String>)
    private fun network(bodies: Map<String, ByteArray>): Network {
        val requests = mutableListOf<String>()
        val transfer = MediaTransfer(connections = { uri ->
            requests += uri.toString()
            val bytes = checkNotNull(bodies[uri.toString()]) { "Unexpected request outside this F19 item" }
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentType() = "application/octet-stream"
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        }, cookies = { null })
        return Network(AlbumDownloader(context, transfer), requests)
    }
    private fun options(scope: Scope, mode: AlbumMode) = DownloadOptions(albumMode = mode,
        fileName = scope.namespace + UUID.randomUUID().toString().replace("-", ""))

    private suspend fun verifyAsset(scope: Scope, asset: SavedAlbumAsset, index: Int, mode: AlbumMode) {
        assertEquals(index, asset.sourceIndex)
        assertEquals("", asset.motionUri)
        val file = copy(asset.uri, scope.directory, "saved-$index-${mode.name}-${UUID.randomUUID()}")
        val expectedMime = when (index) { 0 -> "image/png"; 1 -> "image/gif"; 2 -> "image/jpeg"
            else -> if (mode == AlbumMode.LIVE_PHOTOS) "image/jpeg" else "video/mp4" }
        assertEquals(expectedMime, asset.mimeType)
        assertEquals(expectedMime, context.contentResolver.getType(Uri.parse(asset.uri)))
        context.contentResolver.query(Uri.parse(asset.uri), arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            assertTrue(cursor.getString(1).contains("_${(index + 1).toString().padStart(3, '0')}"))
        }
        when (index) {
            0 -> assertTrue(scope.bodies[scope.content.images[0].url]!!.contentEquals(file.readBytes()))
            1 -> assertTrue(scope.animation.readBytes().contentEquals(file.readBytes()))
            else -> if (expectedMime == "image/jpeg") {
                assertTrue(asset.embeddedMotion)
                assertTrue(EmbeddedMotionReader.validate(file).hasAudio)
                val extracted = EmbeddedMotionReader.extractForPreview(file, File(scope.directory, "extracted-${UUID.randomUUID()}.mp4"))
                assertEquals(if (index == 2) scope.motionHash else scope.unknownMotionHash, hash(extracted))
            } else {
                assertFalse(asset.embeddedMotion)
                assertEquals(listOf("video/avc"), trackTypes(file))
                assertEquals(videoSampleDigest(scope.unknownMotion), videoSampleDigest(file))
                assertColor(file, 250_000L, "blue"); assertColor(file, duration(scope.unknownMotion) * 750L, "red")
            }
        }
        archive(scope, file, "item-$index-${mode.name.lowercase()}.$expectedMime".replace("/", "-"))
    }

    private fun archive(scope: Scope, file: File, name: String): File? {
        if (InstrumentationRegistry.getArguments().getString("capture_f19") != "true") return null
        val archive = File(context.cacheDir, "f19_evidence_${scope.namespace.removeSuffix("_")}").apply { check(isDirectory || mkdir()) }
        val destination = file.copyTo(File(archive, name), overwrite = true)
        println("f19_evidence=${archive.absolutePath}")
        return destination
    }
    private fun asset(path: String, directory: File) = File(directory, path.substringAfterLast('/')).also { output ->
        instrumentation.context.assets.open(path).use { input -> output.outputStream().use { input.copyTo(it) } }
    }
    private fun copy(raw: String, directory: File, name: String) = File(directory, name).also { file ->
        context.contentResolver.openInputStream(Uri.parse(raw))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun avcDecoderCapabilities(): JSONObject {
        val codecs = JSONArray()
        val errors = JSONArray()
        val inventory = JSONObject().put("codecList", "REGULAR_CODECS").put("mimeType", "video/avc")
            .put("codecs", codecs).put("enumerationErrors", errors)
        val codecInfos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrElse { error ->
            errors.put(JSONObject().put("errorType", error.javaClass.simpleName)
                .put("error", DiagnosticText.clean(error.message.orEmpty(), 1000)))
            return inventory
        }
        codecInfos.forEach { codec ->
            val isAvcDecoder = runCatching { !codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
                .getOrElse { error ->
                    errors.put(JSONObject().put("errorType", error.javaClass.simpleName)
                        .put("error", DiagnosticText.clean(error.message.orEmpty(), 1000)))
                    return@forEach
                }
            if (!isAvcDecoder) return@forEach
            val row = JSONObject().put("name", codec.name)
            codecs.put(row)
            val capabilities = runCatching { checkNotNull(codec.getCapabilitiesForType("video/avc").videoCapabilities) }
                .getOrElse { error ->
                    row.put("capabilityErrorType", error.javaClass.simpleName)
                        .put("capabilityError", DiagnosticText.clean(error.message.orEmpty(), 1000))
                    return@forEach
                }
            fun record(name: String, read: () -> Any) {
                runCatching { row.put(name, read()) }.onFailure { error ->
                    row.put("${name}ErrorType", error.javaClass.simpleName)
                        .put("${name}Error", DiagnosticText.clean(error.message.orEmpty(), 1000))
                }
            }
            record("minWidth") { capabilities.supportedWidths.lower }
            record("minHeight") { capabilities.supportedHeights.lower }
            record("widthAlignment") { capabilities.widthAlignment }
            record("heightAlignment") { capabilities.heightAlignment }
            record("supports48x48At30") { capabilities.areSizeAndRateSupported(48, 48, 30.0) }
            record("supports1280x720At30") { capabilities.areSizeAndRateSupported(1280, 720, 30.0) }
        }
        return inventory
    }
    private fun centerColorTimeline(file: File): JSONArray {
        val reader = MediaMetadataRetriever()
        val timeline = JSONArray()
        try {
            reader.setDataSource(file.absolutePath)
            val length = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            for (timeMs in 0L..(length - 1L).coerceAtLeast(0L) step 100L) {
                val sample = JSONObject().put("timeMs", timeMs)
                timeline.put(sample)
                val frame = reader.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame == null) sample.put("frameUnavailable", true)
                else try {
                    val color = frame.getPixel(frame.width / 2, frame.height / 2)
                    val actual = when {
                        Color.red(color) > Color.blue(color) + 80 -> "red"
                        Color.blue(color) > Color.red(color) + 80 -> "blue"
                        else -> "other"
                    }
                    sample.put("color", actual).put("red", Color.red(color)).put("green", Color.green(color))
                        .put("blue", Color.blue(color)).put("width", frame.width).put("height", frame.height)
                } finally { frame.recycle() }
            }
        } finally { reader.release() }
        return timeline
    }
    private fun duration(file: File): Long {
        val reader = MediaMetadataRetriever()
        return try { reader.setDataSource(file.absolutePath); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
        finally { reader.release() }
    }
    private fun trackTypes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try { extractor.setDataSource(file.absolutePath); (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() } }
        finally { extractor.release() }
    }
    private fun videoSampleDigest(file: File): String {
        val extractor = MediaExtractor(); val digest = MessageDigest.getInstance("SHA-256")
        try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            extractor.selectTrack(video)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            while (true) {
                buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                val data = ByteArray(size); buffer.position(0); buffer.get(data); digest.update(data)
                digest.update(ByteBuffer.allocate(12).putLong(extractor.sampleTime).putInt(extractor.sampleFlags).array())
                extractor.advance()
            }
        } finally { extractor.release() }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun lastAudioTimestampUs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val audio = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            extractor.selectTrack(audio); var last = -1L
            while (extractor.sampleTime >= 0) { last = extractor.sampleTime; if (!extractor.advance()) break }
            last
        } finally { extractor.release() }
    }
    private fun assertColor(file: File, timestampUs: Long, expected: String) {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            val frame = checkNotNull(reader.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST))
            try {
                val color = frame.getPixel(frame.width / 2, frame.height / 2)
                val actual = when { Color.red(color) > Color.blue(color) + 80 -> "red"; Color.blue(color) > Color.red(color) + 80 -> "blue"; else -> "other" }
                assertEquals("Four-kind ordered frame at $timestampUs us", expected, actual)
            } finally { frame.recycle() }
        } finally { reader.release() }
    }
}
