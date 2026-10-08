package com.local.douyinsaver

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Exact controlled response bodies, real ContentDownloader/codecs/MediaStore. These tests
 * prove derived exports from a verified embedded JPEG, not public-source or OEM Gallery acceptance.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedLiveDerivedExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val nativeUrl = "https://p3.douyinpic.com/controlled_derived_native.jpg"
    private val staticUrl = "https://p3.douyinpic.com/controlled_derived_static.png"
    private val bgmUrl = "https://lf3-cdn-tos.douyinstatic.com/controlled_derived_bgm.m4a"

    @Test fun embeddedLiveWithoutMotionUrlExportsItsCompleteTwoSecondMotionAsGif() = isolated("gif") { scope ->
        val source = album(listOf(image(nativeUrl, AlbumAssetKind.LIVE)))
        val result = scope.download(source, AlbumMode.GIF)
        assertEquals(listOf(nativeUrl), scope.requests)
        assertEquals("image/gif", result.mimeType)
        assertEquals(AlbumAssetKind.ANIMATED, result.albumAssets.single().kind)
        assertFalse(result.albumAssets.single().embeddedMotion)
        assertEquals("", result.albumAssets.single().motionUri)
        assertEquals(0, result.albumAssets.single().sourceIndex)
        scope.assertPublished(result, "image/gif", imageCollection = true)
        val output = scope.copyOutput(result, "saved.gif")
        scope.report.put("gif", inspectGif(output, scope.directory))
        scope.assertSourcesUnchanged(source)
    }

    @Test fun mixedStaticAndEmbeddedLiveUsesOriginalMotionDurationAndExplicitSeparateBgm() = isolated("video") { scope ->
        val source = album(listOf(image(staticUrl, AlbumAssetKind.STATIC), image(nativeUrl, AlbumAssetKind.LIVE)), withBgm = true)
        val result = scope.download(source, AlbumMode.VIDEO)
        assertEquals(listOf(staticUrl, nativeUrl, bgmUrl), scope.requests)
        assertEquals("video/mp4", result.mimeType)
        scope.assertPublished(result, "video/mp4", imageCollection = false)
        val output = scope.copyOutput(result, "saved.mp4")
        scope.report.put("composition", inspectComposition(output, scope.directory, scope.static))
            .put("staticDefaultMs", 300).put("originalMotionMs", 2000).put("suggestedTotalMs", 2300)
            .put("explicitBgmInputHz", 880).put("originalMotionAudioHz", 440)
            .put("actualOutputAudioFrequencyVerifiedOnAndroid", false)
            .put("audioFrequencyRequiresIndependentPcmAnalysis", true)
        scope.assertSourcesUnchanged(source)
    }

    private fun image(url: String, kind: AlbumAssetKind) = ParsedImage(url, 1280, 720,
        mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)), kind = kind, motion = null)
    private fun album(images: List<ParsedImage>, withBgm: Boolean = false) = ParsedVideo(
        "9999999999999999841", "Controlled native live derived export", "", 0.0, 1280, 720,
        images = images, bgmUrl = if (withBgm) bgmUrl else "", bgmDurationSeconds = if (withBgm) 3.0 else 0.0)

    private inner class Scope(val directory: File, val namespace: String, val app: IsolatedApplication) {
        val prefix = "YXEmbeddedDerived" + UUID.randomUUID().toString().replace("-", "")
        val records = DownloadRecords(app)
        val report = JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("androidSdk", Build.VERSION.SDK_INT).put("realPublicSource", false).put("ordinaryMainActivityUiUsed", false)
            .put("oemGalleryVerified", false).put("chatPlaybackVerified", false).put("namespace", namespace)
        val requests = mutableListOf<String>()
        val uris = linkedSetOf<String>()
        val sourceHashes = linkedMapOf<File, String>()
        lateinit var native: File
        lateinit var video: File
        lateinit var static: File
        lateinit var bgm: File

        suspend fun initialize() {
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            static = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.BLUE_MIDDLE_PNG, directory)
            video = asset("fixed_red_blue_2s.mp4", "original-motion-440hz.mp4")
            assertEquals("2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(video))
            bgm = asset("native_bgm_880hz_3s.m4a", "explicit-bgm-880hz.m4a")
            assertEquals("9562e23ecfdf8d124217db84fcd81d136bc3b8a986b42f0c7437c2b04f291378", hash(bgm))
            native = MotionPhotoFixtureWriter.createForGallery(cover, video, File(directory, "source-MP.jpg"),
                MotionPhotoContainer.Format.XIAOMI, presentationTimestampUs = 0)
            val verified = EmbeddedMotionReader.validate(native)
            assertEquals(1280, verified.width); assertEquals(720, verified.height); assertTrue(verified.hasAudio)
            assertEquals(0L, verified.container.presentationTimestampUs)
            assertArrayEquals(video.readBytes(), native.readBytes().copyOfRange(verified.container.videoOffset.toInt(), native.length().toInt()))
            listOf(cover, static, video, bgm, native).forEach { sourceHashes[it] = hash(it) }
            report.put("sources", JSONArray(sourceHashes.map { (file, sha) ->
                JSONObject().put("file", file.name).put("bytes", file.length()).put("sha256", sha) }))
        }

        private fun asset(name: String, outputName: String): File = File(directory, outputName).also { output ->
            instrumentation.context.assets.open("motion_test/$name").use { input -> output.outputStream().use { input.copyTo(it) } }
        }

        suspend fun download(source: ParsedVideo, mode: AlbumMode): SavedVideo {
            assertTrue("The unique run prefix must have no pre-existing pending or published row", ownedUris(prefix).isEmpty())
            assertTrue(records.history().isEmpty())
            val bodies = mapOf(nativeUrl to native.readBytes(), staticUrl to static.readBytes(), bgmUrl to bgm.readBytes())
            val transfer = MediaTransfer(connections = { uri ->
                val bytes = checkNotNull(bodies[uri.toString()]) { "Unexpected controlled resource request" }
                requests += uri.toString()
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
            var saving = 0; var callbacks = 0; var mismatch = 0
            val result = withTimeout(180_000) {
                ContentDownloader(app, transfer, null).download(source, null,
                    DownloadOptions(albumMode = mode, fileName = prefix, watermarkMode = WatermarkMode.CLEAN,
                        itemDurationSeconds = null, staticImageSeconds = 0.3, gifExportQuality = GifExportQuality.SHARE),
                    { _, _ -> }, { saving++ }, { callbacks++; uris.addAll(it.uris); records.save(it) },
                    onDurationMismatch = { mismatch++; false })
            }
            assertEquals(1, saving); assertEquals(1, callbacks); assertEquals(0, mismatch)
            assertEquals(listOf(result), records.history())
            assertEquals(mode.name, result.exportMode)
            assertEquals(WatermarkMode.CLEAN, result.watermarkMode)
            assertTrue(result.isAlbum); assertEquals(1, result.uris.size)
            assertTrue("Production temporary downloads must be removed", app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("album_") })
            report.put("saveCallbacks", callbacks).put("savingCallbacks", saving).put("durationMismatchCallbacks", mismatch)
                .put("savedUri", result.uri).put("savedBytes", result.bytes).put("savedMimeType", result.mimeType)
                .put("savedFileName", result.fileName).put("savedMode", result.exportMode).put("validatedWatermarkMode", result.watermarkMode!!.name)
                .put("historyCount", records.history().size).put("inputKinds", JSONArray(source.images.map { it.kind.name }))
                .put("inputMotionUrlsAbsent", source.images.all { it.motion == null }).put("productionTemporaryFilesRemoved", true)
            return result
        }

        fun assertPublished(saved: SavedVideo, mime: String, imageCollection: Boolean) {
            val rows = ownedUris(prefix)
            assertEquals("Exactly one whole derived resource must be published, including pending rows", 1, rows.size)
            assertEquals(identity(saved.uri), identity(rows.single()))
            assertEquals(imageCollection, Uri.parse(saved.uri).path.orEmpty().contains("images"))
            assertEquals(mime, context.contentResolver.getType(Uri.parse(saved.uri)))
            context.contentResolver.query(Uri.parse(saved.uri), arrayOf(MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(saved.fileName, it.getString(1))
                assertEquals(saved.bytes, it.getLong(2))
            }
            report.put("pending", 0).put("ownedRowCount", 1).put("independentOriginalVideoRowPublished", false)
        }

        fun copyOutput(saved: SavedVideo, name: String) = File(directory, name).also { file ->
            context.contentResolver.openInputStream(Uri.parse(saved.uri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            assertEquals(saved.bytes, file.length()); report.put("outputFile", name).put("outputSha256", hash(file))
        }

        fun assertSourcesUnchanged(source: ParsedVideo) {
            assertEquals("LIVE", source.images.last().kind.name)
            assertTrue(source.images.all { it.motion == null })
            sourceHashes.forEach { (file, expected) -> assertEquals("Caller source was modified: ${file.name}", expected, hash(file)) }
            report.put("sourceBytesPreserved", true).put("sourceKnownKindsAndNullMotionPreserved", true)
        }
        fun persist() = File(directory, "report.json").writeText(report.toString(2), Charsets.UTF_8)
    }

    private fun isolated(name: String, run: suspend (Scope) -> Unit) = runBlocking(Dispatchers.IO) {
        assumeTrue("These real pending-row and full-frame checks require Android 29+", Build.VERSION.SDK_INT >= 29)
        val namespace = "embedded_live_derived_${UUID.randomUUID()}_"
        val directory = File(context.cacheDir, namespace.removeSuffix("_")).apply {
            check(mkdir() && canonicalFile.parentFile == context.cacheDir.canonicalFile)
        }
        val app = IsolatedApplication(context.applicationContext, namespace, directory)
        val scope = Scope(directory, namespace, app)
        val prefs = preferences(namespace)
        val history = DownloadRecords(context.applicationContext).history().toList()
        val protected = history.flatMap { it.uris }.map(::identity).toSet()
        val background = File(context.filesDir, "appearance/background.jpg")
        val backgroundHash = background.takeIf(File::isFile)?.let(::hash)
        var passed = false; var failure: Throwable? = null
        val cleanupErrors = mutableListOf<String>()
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) {
            cleanupErrors += error.javaClass.simpleName + ": " + DiagnosticText.clean(error.message.orEmpty(), 600)
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        scope.report.put("test", name).put("protectedHistoryCount", history.size).put("protectedPreferenceCount", prefs.size)
        scope.persist()
        try { scope.initialize(); run(scope); passed = true } catch (error: Throwable) {
            failure = error; scope.report.put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 1200))
        } finally {
            cleanup { scope.uris.addAll(ownedUris(scope.prefix)) }
            scope.uris.distinctBy(::identity).forEach { raw -> cleanup {
                require(identity(raw) !in protected)
                val uri = Uri.parse(raw)
                require(uri.scheme == "content" && uri.authority == "media")
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
                    check(it.moveToFirst() && it.getString(0) == context.packageName && it.getString(1).startsWith(scope.prefix))
                }
                assertEquals(1, context.contentResolver.delete(uri, null, null))
                assertFalse(runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false))
            } }
            cleanup { assertTrue("No owned published or pending row may survive", ownedUris(scope.prefix).isEmpty()) }
            app.preferenceNames.toList().forEach { pref -> cleanup { check(pref.startsWith(namespace)); context.deleteSharedPreferences(pref) } }
            cleanup { assertEquals("Global preferences changed", prefs, preferences(namespace)) }
            cleanup { assertEquals("Global records changed", history, DownloadRecords(context.applicationContext).history()) }
            cleanup { assertEquals("User background changed", backgroundHash, background.takeIf(File::isFile)?.let(::hash)) }
            scope.report.put("requests", JSONArray(scope.requests.map { JSONObject().put("host", Uri.parse(it).host).put("urlSha256", digest(it.toByteArray())) }))
                .put("cleanupErrors", JSONArray(cleanupErrors)).put("ownedUris", JSONArray(scope.uris.toList()))
                .put("ownedRowsRemoved", cleanupErrors.isEmpty()).put("userStatePreserved", cleanupErrors.isEmpty())
                .put("completed", true).put("success", passed && failure == null)
            scope.persist()
            println("embedded_live_derived_evidence=${directory.absolutePath}")
        }
        failure?.let { throw it }
        Unit
    }

    private fun inspectGif(file: File, directory: File): JSONObject {
        val bytes = file.readBytes()
        assertEquals(480, word(bytes, 6)); assertEquals(270, word(bytes, 8)); assertEquals(0, gifLoop(bytes))
        val frames = GifAnimationInspector.frames(bytes)
        assertEquals(20, frames.size); assertTrue(frames.all { it.durationMs == 100L }); assertEquals(2000L, frames.sumOf { it.durationMs })
        val evidence = JSONArray(); val hashes = linkedSetOf<String>()
        frames.forEachIndexed { index, frame ->
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(frame.encoded))) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            try {
                assertEquals(480, bitmap.width); assertEquals(270, bitmap.height)
                assertEquals("Complete GIF frame $index is wrong", if (index < 10) "red" else "blue", color(bitmap))
                assertBars(bitmap)
                val sha = pixelHash(bitmap); hashes += sha
                evidence.put(JSONObject().put("index", index).put("delayMs", frame.durationMs).put("color", color(bitmap)).put("argbSha256", sha))
                if (index in listOf(0, 10, 19)) png(bitmap, File(directory, "gif-frame-$index.png"))
            } finally { bitmap.recycle() }
        }
        assertTrue(hashes.size >= 2)
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        assertTrue(drawable is AnimatedImageDrawable); (drawable as AnimatedImageDrawable).stop()
        return JSONObject().put("width", 480).put("height", 270).put("frames", evidence).put("frameCount", 20)
            .put("totalDurationMs", 2000).put("loopCount", 0).put("allFramesDecoded", true).put("distinctFullFrames", hashes.size)
    }

    private fun inspectComposition(file: File, directory: File, staticSource: File): JSONObject {
        val reference = ImageDecoder.decodeBitmap(ImageDecoder.createSource(staticSource)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val staticColor = try { reference.getPixel(reference.width / 2, reference.height / 2) }
            finally { reference.recycle() }
        val extractor = MediaExtractor(); val packets = JSONArray(); val pts = mutableListOf<Long>()
        var lastAudioUs = Long.MIN_VALUE; var audioCount = 0
        val audioPackets = JSONArray()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals("One composed video and one explicit BGM track are required", 2, extractor.trackCount)
            val video = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
            val audio = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
            val format = extractor.getTrackFormat(video)
            assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
            assertTrue("Actual video duration differs from 0.3 + 2.0 seconds", abs(format.getLong(MediaFormat.KEY_DURATION) - 2_300_000L) <= 33_334L)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            for (track in listOf(video, audio)) {
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (extractor.sampleSize > 0) {
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0)
                    assertTrue(size > 0 && size <= buffer.capacity())
                    val data = ByteArray(size); buffer.position(0); buffer.get(data)
                    val timestamp = extractor.sampleTime
                    val packet = JSONObject().put("ptsUs", timestamp).put("bytes", size).put("flags", extractor.sampleFlags).put("sha256", digest(data))
                    if (track == video) { pts += timestamp; packets.put(packet) }
                    else { assertTrue(timestamp > lastAudioUs); lastAudioUs = timestamp; audioCount++; audioPackets.put(packet) }
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(track)
            }
        } finally { extractor.release() }
        assertEquals("The 0.3s static + complete 2s clip must contain all 69 output frames", 69, pts.size)
        pts.forEachIndexed { index, timestamp -> assertTrue("Video frame $index PTS=$timestamp", abs(timestamp - index * 1_000_000L / 30) <= 2L) }
        assertTrue("BGM must reach the composition tail", lastAudioUs >= 2_260_000L); assertTrue(audioCount >= 90)
        val reader = MediaMetadataRetriever(); val frames = JSONArray()
        try {
            reader.setDataSource(file.absolutePath)
            assertTrue(abs(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() - 2300L) <= 34L)
            for (start in pts.indices step 4) {
                val decoded = reader.getFramesAtIndex(start, minOf(4, pts.size - start))
                try {
                    assertEquals(minOf(4, pts.size - start), decoded.size)
                    decoded.forEachIndexed { offset, bitmap ->
                        val index = start + offset
                        assertEquals(1280, bitmap.width); assertEquals(720, bitmap.height)
                        val expected = if (index < 9 || index >= 39) "blue" else "red"
                        assertEquals("Actual complete composition frame $index has lost its source order", expected, color(bitmap))
                        assertBars(bitmap)
                        val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        if (index < 9) {
                            val error = maxOf(abs(Color.red(center) - Color.red(staticColor)),
                                abs(Color.green(center) - Color.green(staticColor)),
                                abs(Color.blue(center) - Color.blue(staticColor)))
                            assertTrue("Static frame $index is visibly darkened: channel error=$error", error <= 8)
                        }
                        frames.put(JSONObject().put("index", index).put("ptsUs", pts[index]).put("color", color(bitmap)).put("argbSha256", pixelHash(bitmap)))
                        frames.getJSONObject(frames.length() - 1).put("centerRgb", JSONArray(listOf(
                            Color.red(center), Color.green(center), Color.blue(center))))
                        if (index in listOf(0, 9, 39, 68)) png(bitmap, File(directory, "video-frame-$index.png"))
                    }
                } finally { decoded.forEach { it.recycle() } }
            }
        } finally { reader.release() }
        return JSONObject().put("expectedDurationMs", 2300).put("videoPackets", packets).put("decodedFrames", frames)
            .put("audioPackets", audioPackets).put("audioPacketCount", audioCount).put("audioLastPtsUs", lastAudioUs)
            .put("allVideoFramesDecoded", true).put("allVideoAndAudioPacketsRead", true)
            .put("sourceOrder", "blue-static-0.3s/red-motion-1s/blue-motion-1s")
    }

    private fun color(bitmap: Bitmap): String {
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return if (Color.red(pixel) > Color.blue(pixel) + 80) "red" else if (Color.blue(pixel) > Color.red(pixel) + 80) "blue" else "other"
    }
    private fun assertBars(bitmap: Bitmap) {
        for (fraction in listOf(0.0, 0.10, 0.90, 0.999)) {
            val pixel = bitmap.getPixel((fraction * bitmap.width).toInt(), bitmap.height / 2)
            assertTrue("Source aspect ratio and black side bars must remain", maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 6)
        }
    }
    private fun pixelHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256"); val pixels = IntArray(bitmap.width); val bytes = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) { bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            bytes.clear(); pixels.forEach { bytes.putInt(it) }; digest.update(bytes.array()) }
        return hex(digest.digest())
    }
    private fun png(bitmap: Bitmap, file: File) = file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }

    private fun ownedUris(prefix: String): Set<String> = buildSet {
        for (collection in listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$prefix*", context.packageName))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)!!.use {
                while (it.moveToNext()) add(ContentUris.withAppendedId(collection, it.getLong(0)).toString())
            }
        }
    }
    private fun identity(raw: String) = Uri.parse(raw).pathSegments.takeLast(3).joinToString("/")
    private fun preferences(namespace: String): Map<String, Map<String, *>> {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") && !it.name.startsWith(namespace) }.map { it.name.removeSuffix(".xml") }.toSet() +
            setOf("downloads", "download_tasks", "download_options", "download_folder", "appearance_v1", "preview_options", "notification_prompt")
        return names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value } }
    }
    private class IsolatedApplication(base: Context, private val prefix: String, private val directory: File) : Application() {
        val preferenceNames = linkedSetOf<String>()
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = if (name.startsWith(prefix)) name else prefix + name
            preferenceNames.add(isolated); return super.getSharedPreferences(isolated, mode)
        }
        override fun getCacheDir(): File = File(directory, "runtime-cache").apply { check(isDirectory || mkdir()) }
        override fun getFilesDir(): File = File(directory, "runtime-files").apply { check(isDirectory || mkdir()) }
    }
    private fun gifLoop(data: ByteArray): Int {
        check(data.size >= 14 && data.copyOfRange(0, 6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a"))
        var position = 13; val global = data[10].toInt() and 255
        if (global and 128 != 0) position += 3 * (1 shl ((global and 7) + 1))
        fun byte(): Int { check(position < data.size); return data[position++].toInt() and 255 }
        fun skip(count: Int) { check(count >= 0 && count <= data.size - position); position += count }
        fun blocks(): List<ByteArray> = buildList { while (true) { val size = byte(); if (size == 0) break
            check(size <= data.size - position); add(data.copyOfRange(position, position + size)); skip(size) } }
        var loop: Int? = null
        while (true) when (byte()) {
            0x21 -> when (byte()) {
                0xff -> { val size = byte(); check(size <= data.size - position)
                    val name = data.copyOfRange(position, position + size).toString(Charsets.US_ASCII); skip(size)
                    val values = blocks(); if (name in listOf("NETSCAPE2.0", "ANIMEXTS1.0")) {
                        val entry = values.single(); check(entry.size == 3 && entry[0] == 1.toByte()); val value = word(entry, 1)
                        check(loop == null || loop == value); loop = value } }
                0xf9 -> { check(byte() == 4); skip(4); check(byte() == 0) }
                else -> blocks()
            }
            0x2c -> { skip(8); val local = byte(); if (local and 128 != 0) skip(3 * (1 shl ((local and 7) + 1))); byte(); blocks() }
            0x3b -> { check(position == data.size); return checkNotNull(loop) }
            else -> error("Invalid GIF block")
        }
    }
    private fun word(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }; hex(digest.digest())
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
