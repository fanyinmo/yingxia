package com.local.douyinsaver

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.io.DataInputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import kotlin.math.abs

/** Public media is requested only by explicit instrumentation parameters; no user records are used. */
@RunWith(AndroidJUnit4::class)
class PublicGifPipelineTest {
    @Test fun explicitlyRequestedPublicVideoParsesDownloadsAndExportsAnimatedGif() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Public GIF downloads require run_public_gif=true", arguments.getString("run_public_gif") == "true")
        val share = ShareLinks.extract(requireNotNull(arguments.getString("shareUrl")) { "shareUrl must be explicit" })
        val id = requireNotNull(arguments.getString("videoId")) { "videoId must be explicit" }
        require(id.matches(Regex("[0-9]{10,25}")))
        val context = instrumentation.targetContext
        val originalExport = arguments.getString("save_original_video") == "true"
        val namespace = "public_gif_${UUID.randomUUID()}_"
        val app = IsolatedApplication(context.applicationContext, namespace)
        val report = JSONObject().put("requestedId", id).put("version", InstalledTestTarget.versionName).put("instrumentationCompiledVersion", BuildConfig.VERSION_NAME)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("parseStage", "UNSTARTED").put("phase", "setup").put("success", false)
        val reportFile = File(context.cacheDir, "public-${if (originalExport) "video" else "gif"}-result-$id.json")
        val outputFile = File(context.cacheDir, "public-${if (originalExport) "video" else "gif"}-output-$id.${if (originalExport) "mp4" else "gif"}")
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        var engine: SaverEngine? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var downloader: ContentDownloader? = null
        val createdUris = linkedSetOf<String>()
        val cleanupErrors = mutableListOf<String>()
        val events = mutableListOf<String>()
        val started = SystemClock.elapsedRealtime()
        try {
            instrumentation.runOnMainSync {
                engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance(app, namespace)
                singleton.set(null, engine)
            }
            scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            lateinit var model: SaverViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[SaverViewModel::class.java]
                assertSame("The Activity must use this run's isolated engine before any share action", engine,
                    SaverViewModel::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(model))
                model.acceptShare(share)
                model.resolve()
            }
            phase(report, "parse")
            val deadline = SystemClock.elapsedRealtime() + 90_000L
            var stage = TaskStage.IDLE
            var message = ""
            var diagnostics = ""
            var parsed: ParsedVideo? = null
            while (true) {
                instrumentation.runOnMainSync {
                    stage = model.stage
                    message = model.message
                    diagnostics = model.diagnostics
                    parsed = model.video
                }
                if (stage in listOf(TaskStage.READY, TaskStage.FAILED, TaskStage.CANCELLED) ||
                    SystemClock.elapsedRealtime() >= deadline) break
                SystemClock.sleep(200)
            }
            report.put("parseStage", stage.name).put("message", DiagnosticText.clean(message, 600))
                .put("diagnostics", DiagnosticText.clean(diagnostics, 16_000))
            parsed?.let { content ->
                report.put("parsedId", content.id).put("album", content.isAlbum)
                    .put("durationSeconds", content.durationSeconds.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("width", content.width).put("height", content.height)
                    .put("mediaHost", runCatching { URI(content.mediaUrl).host }.getOrNull().orEmpty())
                    .put("cleanSources", content.mediaSources.count { it.mode == WatermarkMode.CLEAN })
            }
            // Persist the parse result before assertions: failures must also leave a reviewable report.
            reportFile.writeText(report.toString(2))
            assertEquals("Public parse failed: ${DiagnosticText.clean(message, 600)}", TaskStage.READY, stage)
            val content = checkNotNull(parsed)
            assertEquals(id, content.id)
            assertFalse("This test expects an ordinary video", content.isAlbum)
            assertTrue("No clean playback source was parsed", WatermarkSources.available(content, WatermarkMode.CLEAN))
            assertTrue("The isolated engine must begin without history", model.history.isEmpty())
            if (arguments.getString("diagnose_range_transport") == "true") {
                val origin = MediaUrls.requireAllowed(content.mediaUrl)
                assertTrue(origin.host.endsWith(".ydycdn.com") && origin.port == 58001)
                val ranges = JSONArray()
                var previousTag: String? = null
                var previousTotal: String? = null
                repeat(2) { index ->
                    val start = index * 1_048_576L
                    val end = start + 1_048_575L
                    val connection = origin.toURL().openConnection() as java.net.HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 15_000
                        connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                        connection.setRequestProperty("Referer", "https://www.douyin.com/")
                        connection.setRequestProperty("Accept-Encoding", "identity")
                        connection.setRequestProperty("Range", "bytes=$start-$end")
                        previousTag?.let { connection.setRequestProperty("If-Range", it) }
                        android.webkit.CookieManager.getInstance().getCookie(content.mediaUrl)?.let { connection.setRequestProperty("Cookie", it) }
                        val status = connection.responseCode
                        val range = connection.getHeaderField("Content-Range").orEmpty()
                        val tag = connection.getHeaderField("ETag")
                        val item = JSONObject().put("start", start).put("end", end).put("status", status)
                            .put("contentRange", range).put("contentLength", connection.contentLengthLong)
                            .put("acceptRanges", connection.getHeaderField("Accept-Ranges").orEmpty())
                            .put("strongETag", tag != null && !tag.startsWith("W/"))
                            .put("etagSame", index == 0 || tag == previousTag)
                        ranges.put(item)
                        report.put("rangeResponses", ranges)
                        assertEquals(206, status)
                        assertEquals(1_048_576L, connection.contentLengthLong)
                        assertTrue(range.startsWith("bytes $start-$end/"))
                        val total = range.substringAfter('/')
                        if (index == 1) assertEquals(previousTotal, total)
                        val data = connection.inputStream.use { it.readBytes() }
                        assertEquals(1_048_576, data.size)
                        item.put("actualBytes", data.size)
                        if (index == 0) assertEquals("ftyp", data.copyOfRange(4, 8).toString(Charsets.US_ASCII))
                        if (index == 1 && previousTag != null) assertEquals(previousTag, tag)
                        previousTag = tag
                        previousTotal = total
                    } finally { connection.disconnect() }
                }
                report.put("success", true).put("diagnosticOnly", true).put("phase", "range_verified")
                return
            }

            val requestedSeconds = if (content.durationSeconds.isFinite() && content.durationSeconds > 0)
                minOf(6.0, content.durationSeconds).toFloat() else 6f
            require(requestedSeconds >= 0.1f) { "The requested video is too short for GIF export" }
            report.put("requestedStartMs", 0).put("requestedDurationSeconds", requestedSeconds)
            val records = DownloadRecords(app, namespace)
            downloader = ContentDownloader(app)
            val saved = runBlocking(Dispatchers.IO) {
                withTimeout(if (originalExport) 900_000L else 240_000L) {
                    phase(report, "download")
                    downloader!!.download(content, null, DownloadOptions(albumMode = if (originalExport) AlbumMode.IMAGES else AlbumMode.GIF,
                        fileName = "public_gif_${UUID.randomUUID()}", gifStartSeconds = 0f,
                        gifDurationSeconds = requestedSeconds, watermarkMode = WatermarkMode.CLEAN),
                        onProgress = { read, total -> report.put("progressDone", read).put("progressTotal", total) },
                        onSaving = { phase(report, if (originalExport) "save" else "convert") },
                        onSaved = { result ->
                            createdUris.addAll(result.uris)
                            records.save(result)
                        },
                        onDiagnostic = { event -> if (events.size < 100) events.add(DiagnosticText.clean(event, 700)) })
                }
            }
            report.put("downloadEvents", JSONArray(events)).put("mimeType", saved.mimeType)
                .put("bytes", saved.bytes).put("exportMode", saved.exportMode)
                .put("gifStartMs", saved.gifStartMs).put("gifDurationMs", saved.gifDurationMs)
                .put("fileCount", saved.uris.size)
            phase(report, "validate")
            assertEquals(if (originalExport) "video/mp4" else "image/gif", saved.mimeType)
            assertEquals(if (originalExport) "" else AlbumMode.GIF.name, saved.exportMode)
            assertEquals(WatermarkMode.CLEAN, saved.watermarkMode)
            assertEquals(0L, saved.gifStartMs)
            if (!originalExport) {
                assertTrue(saved.gifDurationMs in 100L..6_000L)
                assertTrue(saved.gifDurationMs <= (requestedSeconds * 1000).toLong())
            }
            assertEquals(listOf(saved.uri), saved.uris)
            val uri = Uri.parse(saved.uri)
            assertEquals(saved.mimeType, context.contentResolver.getType(uri))
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("${if (originalExport) DownloadStorage.DEFAULT_LOCATION else DownloadStorage.DEFAULT_IMAGE_LOCATION}/", it.getString(1))
            }
            // Close MediaProvider's stream before native animation decoding.
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertEquals(saved.bytes, bytes.size.toLong())
            if (originalExport) {
                outputFile.writeBytes(bytes)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(outputFile.absolutePath)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    extractor.selectTrack(track)
                    assertTrue(extractor.sampleSize > 0)
                    assertTrue(extractor.advance() && extractor.sampleSize > 0)
                } finally { extractor.release() }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(outputFile.absolutePath)
                    val frame = checkNotNull(reader.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST))
                    try { report.put("decodedWidth", frame.width).put("decodedHeight", frame.height) } finally { frame.recycle() }
                    report.put("decodedDurationMs", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong())
                        .put("nativeVideoDecoded", true)
                } finally { reader.release() }
            } else {
                assertEquals("GIF89a", bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII))
                val animation = inspectAnimation(bytes)
                assertTrue("A GIF needs multiple actual image blocks", animation.first >= 2)
                assertTrue("Stored GIF delay differs from the exported range", abs(animation.second - saved.gifDurationMs) <= 10L)
                report.put("animationFrames", animation.first).put("animationDurationMs", animation.second)
                    .put("nativeAnimationDecoded", true)
            }
            assertEquals(listOf(saved), DownloadRecords(app, namespace).history())
            outputFile.writeBytes(bytes)
            report.put("cacheOutput", outputFile.name).put("success", true)
            phase(report, "verified")
        } catch (error: Throwable) {
            report.put("errorType", error.javaClass.simpleName)
                .put("error", DiagnosticText.clean(error.message.orEmpty(), 1_000))
            val causes = JSONArray()
            var cause: Throwable? = error.cause
            repeat(5) {
                cause?.let { item ->
                    causes.put(JSONObject().put("type", item.javaClass.simpleName)
                        .put("message", DiagnosticText.clean(item.message.orEmpty(), 400)))
                    cause = item.cause
                }
            }
            report.put("errorCauses", causes)
            throw error
        } finally {
            runCatching { downloader?.cancel() }.onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            runCatching { scenario?.close() }.onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            runCatching {
                instrumentation.runOnMainSync {
                    try {
                        engine?.let { isolated ->
                            isolated.cancel()
                            (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }
                                .get(isolated) as CoroutineScope).cancel()
                        }
                    } finally { singleton.set(null, previous) }
                }
            }.onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            // Only exact URI values captured from this operation's onSaved callback are deleted.
            createdUris.forEach { value -> runCatching {
                val uri = Uri.parse(value)
                require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
                check(context.contentResolver.delete(uri, null, null) == 1) { "This run's GIF was not deleted" }
            }.onFailure { cleanupErrors.add(it.javaClass.simpleName) } }
            synchronized(app.preferenceNames) { app.preferenceNames.toList() }.forEach { context.deleteSharedPreferences(it) }
            val verified = report.optBoolean("success")
            if (cleanupErrors.isNotEmpty()) report.put("success", false).put("cleanupFailed", true)
            report.put("createdFilesCleaned", createdUris.size).put("cleanupErrors", JSONArray(cleanupErrors))
                .put("downloadEvents", JSONArray(events)).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            reportFile.writeText(report.toString(2))
            Log.i("PublicGifPipeline", "result id=$id stage=${report.optString("parseStage")} success=${report.optBoolean("success")} cleanupErrors=${cleanupErrors.size}")
            if (verified && cleanupErrors.isNotEmpty()) fail("GIF export passed, but this run's cleanup failed: $cleanupErrors")
        }
    }

    /** Engine and storage preferences stay private to this run, including download_folder. */
    private class IsolatedApplication(base: Context, private val namespace: String) : Application() {
        val preferenceNames: MutableSet<String> = Collections.synchronizedSet(linkedSetOf())
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = if (name.startsWith(namespace)) name else namespace + name
            preferenceNames.add(isolated)
            return super.getSharedPreferences(isolated, mode)
        }
    }

    @Suppress("DEPRECATION")
    private fun inspectAnimation(bytes: ByteArray): Pair<Int, Long> {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        assertTrue("Android must decode the saved GIF as an animation", drawable is AnimatedImageDrawable)
        (drawable as AnimatedImageDrawable).stop()
        val structure = gifStructure(bytes)
        val movie = checkNotNull(Movie.decodeByteArray(bytes, 0, bytes.size))
        val bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        try {
            for (time in listOf(0, (structure.second / 2).toInt(), maxOf(0, structure.second.toInt() - 20))) {
                bitmap.eraseColor(Color.TRANSPARENT)
                movie.setTime(time)
                movie.draw(Canvas(bitmap), 0f, 0f)
                assertTrue("The native GIF decoder did not produce a real opaque frame", Color.alpha(bitmap.getPixel(
                    bitmap.width / 2, bitmap.height / 2)) > 0)
            }
        } finally { bitmap.recycle() }
        return structure
    }

    private fun gifStructure(bytes: ByteArray): Pair<Int, Long> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        fun skip(count: Int) { repeat(count) { input.readUnsignedByte() } }
        fun ushort() = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun blocks() { while (true) { val count = input.readUnsignedByte(); if (count == 0) return; skip(count) } }
        skip(6)
        assertTrue(ushort() in 1..GifConversionPolicy.MAX_EDGE)
        assertTrue(ushort() in 1..GifConversionPolicy.MAX_EDGE)
        val packed = input.readUnsignedByte()
        skip(2)
        if (packed and 0x80 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        var frames = 0
        var delays = 0
        var duration = 0L
        while (true) {
            when (val block = input.readUnsignedByte()) {
                0x21 -> if (input.readUnsignedByte() == 0xf9) {
                    assertEquals(4, input.readUnsignedByte())
                    skip(1); duration += ushort() * 10L; delays++; skip(1)
                    assertEquals(0, input.readUnsignedByte())
                } else blocks()
                0x2c -> {
                    skip(8)
                    val flags = input.readUnsignedByte()
                    if (flags and 0x80 != 0) skip(3 * (1 shl ((flags and 7) + 1)))
                    input.readUnsignedByte(); blocks(); frames++
                }
                0x3b -> break
                else -> fail("Unexpected GIF block $block")
            }
        }
        assertEquals(frames, delays)
        assertEquals(0x3b, bytes.last().toInt() and 255)
        frames to duration
    }

    private fun phase(report: JSONObject, value: String) {
        report.put("phase", value)
        Log.i("PublicGifPipeline", "phase=$value")
    }
}
