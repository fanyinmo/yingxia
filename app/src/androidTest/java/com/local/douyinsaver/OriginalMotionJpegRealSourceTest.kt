package com.local.douyinsaver

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Explicit local-file opt-in. This tests transport/publication, not public parsing or Xiaomi Gallery. */
@RunWith(AndroidJUnit4::class)
class OriginalMotionJpegRealSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun userSuppliedEmbeddedMotionJpegRetainsAllMediaThroughContentDownloader() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit real-file test opt-in is required", args.getString("run_original_motion_jpeg") == "true")
        require(args.getString("source_sha256") == SOURCE_SHA) { "The explicitly authorized source SHA must match the fixed fixture" }
        val source = File(context.cacheDir, "own_real_motion_$SOURCE_SHA.jpg")
        require(source.canonicalFile.parentFile == context.cacheDir.canonicalFile && source.isFile) { "The fixed owned input copy is missing" }
        assertEquals(SOURCE_BYTES, source.length())
        assertEquals("The owned source copy differs from the user-supplied original", SOURCE_SHA, hash(source))
        val prefix = "original_motion_jpeg_${UUID.randomUUID().toString().replace("-", "")}"
        val directory = File(context.cacheDir, prefix).also { check(it.mkdir()) }
        val clips = mutableListOf<File>()
        val createdUris = linkedSetOf<String>()
        val prefsBefore = preferencesSnapshot()
        val appearanceBefore = appearanceSnapshot()
        val historyBefore = DownloadRecords(context).history()
        val historyFilesBefore = historyFileSnapshot(historyBefore)
        val scratchBefore = albumScratchNames()
        val report = JSONObject().put("scope", "OPT_IN_USER_SUPPLIED_JPEG_CONTROLLED_CONTENT_DOWNLOAD_NOT_PUBLIC_PARSER_OR_NATIVE_GALLERY")
            .put("version", InstalledTestTarget.versionName)
            .put("versionCode", context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
            .put("ordinaryApkSha256", hash(File(context.applicationInfo.sourceDir)))
            .put("testApkSha256", hash(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("sourceName", source.name).put("sourceSha256", SOURCE_SHA).put("sourceBytes", SOURCE_BYTES)
            .put("nativeGalleryRecognized", false).put("publicSourceParsed", false)
            .put("preferencesBefore", preferencesSummary(prefsBefore))
            .put("backgroundFilesBefore", JSONObject(appearanceBefore))
            .put("protectedHistoryRecords", historyBefore.size)
            .put("protectedHistoryFiles", historyFilesBefore.size)
        val attempts = JSONArray(); report.put("attempts", attempts)
        var failure: Throwable? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            assertEquals(1440, bounds.outWidth); assertEquals(1080, bounds.outHeight)
            val container = checkNotNull(MotionPhotoContainer.inspect(source)) { "The supplied JPEG has no real embedded clip" }
            assertEquals(528_793L, container.videoOffset)
            assertEquals(2_837_979L, container.videoLength)
            assertEquals(0L, container.presentationTimestampUs)
            val verified = EmbeddedMotionReader.validate(source)
            assertTrue("The actual source lost its AAC audio", verified.hasAudio)
            assertEquals(960, verified.width); assertEquals(720, verified.height)
            val originalClip = File(directory, "source-embedded.mp4").also(clips::add)
            EmbeddedMotionReader.extractForPreview(source, originalClip)
            assertEquals(EMBEDDED_SHA, hash(originalClip))
            val originalTracks = inspectAllTracks(originalClip)
            report.put("sourceTracks", tracksJson(originalTracks))
            val video = originalTracks.single { it.mime.startsWith("video/") }
            val audio = originalTracks.single { it.mime.startsWith("audio/") }
            assertEquals("video/avc", video.mime); assertEquals(89, video.packets.size)
            assertEquals(960, video.width); assertEquals(720, video.height)
            assertTrue("Video track duration differs from the real source", abs(video.durationUs - 2_966_666L) <= 1_000L)
            assertEquals("audio/mp4a-latm", audio.mime)
            assertTrue("AAC samples are missing", audio.packets.isNotEmpty())
            val originalVideoDecode = decodeAll(originalClip, video.index, video = true)
            val originalAudioDecode = decodeAll(originalClip, audio.index, video = false)
            report.put("sourceFullVideoDecode", originalVideoDecode.json())
                .put("sourceFullAudioDecode", originalAudioDecode.json())
                .put("audioMinusVideoTrackDurationUs", audio.durationUs - video.durationUs)
                .put("coverPresentationTimestampUs", container.presentationTimestampUs)
                .put("sourceContainerDurationMs", verified.durationMs)
            assertEquals(89, originalVideoDecode.outputs.size)
            assertEquals(video.packets.map { it.ptsUs }.sorted(), originalVideoDecode.outputs.map { it.ptsUs })
            assertEquals("Every real-source video frame must decode distinctly", 89,
                originalVideoDecode.outputs.map { it.sha256 }.distinct().size)
            val input = ParsedVideo("9999999999999999941", "explicitly supplied client Motion JPEG", "", 0.0, 1440, 1080,
                images = listOf(ParsedImage(PHOTO_URL, 1440, 1080,
                    mediaSources = listOf(MediaSource(PHOTO_URL, WatermarkMode.CLEAN)), kind = AlbumAssetKind.LIVE,
                    mimeType = "image/jpeg", imageKey = "authorized-jpeg-$SOURCE_SHA")))
            assertFalse(WatermarkSources.available(input, WatermarkMode.CLEAN))
            assertNull(WatermarkSources.actualMode(input, WatermarkMode.CLEAN))
            assertTrue(WatermarkSources.availableForDownload(input, WatermarkMode.CLEAN))
            assertTrue(WatermarkSources.requiresEmbeddedLiveVerification(input))
            for (mode in listOf(AlbumMode.IMAGES, AlbumMode.MOTION_VIDEOS)) {
                val attempt = JSONObject().put("mode", mode.name); attempts.put(attempt)
                val requests = mutableListOf<String>()
                val transfer = MediaTransfer(connections = { uri ->
                    require(uri.toString() == PHOTO_URL) { "Only the exact controlled image response is authorized" }
                    requests.add(uri.toString())
                    val bytes = source.readBytes()
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
                val probe = MediaProbe(connections = { error("An album must not request a video probe") }, cookies = { null })
                var callbacks = 0
                val result = ContentDownloader(context, transfer, probe).download(input, null,
                    DownloadOptions(albumMode = mode, fileName = "${prefix}_${mode.name}"), { _, _ -> }, {}, { completed ->
                        createdUris.addAll(completed.uris); callbacks++
                    })
                assertEquals(1, callbacks); assertEquals(listOf(PHOTO_URL), requests)
                assertEquals(1, result.uris.size); assertEquals(1, result.albumAssets.size)
                assertEquals(WatermarkMode.CLEAN, result.watermarkMode)
                val asset = result.albumAssets.single()
                assertEquals(AlbumAssetKind.LIVE, asset.kind); assertTrue(asset.embeddedMotion)
                assertEquals("", asset.motionUri); assertEquals(0, asset.sourceIndex)
                assertEquals("image/jpeg", context.contentResolver.getType(Uri.parse(result.uri)))
                assertTrue(result.fileName.endsWith("_001_MP.jpg"))
                assertPublishedImage(result)
                val evidence = File(directory, "saved-${mode.name}.jpg")
                context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { stream ->
                    evidence.outputStream().use { stream.copyTo(it) }
                }
                assertEquals(SOURCE_BYTES, evidence.length()); assertEquals(SOURCE_SHA, hash(evidence))
                assertEquals(container, EmbeddedMotionReader.validate(evidence).container)
                val clip = File(directory, "saved-${mode.name}-embedded.mp4").also(clips::add)
                EmbeddedMotionReader.extractForPreview(evidence, clip)
                assertEquals(EMBEDDED_SHA, hash(clip))
                val outputTracks = inspectAllTracks(clip)
                assertTrue("Every source track/codec/packet/PTS must remain unchanged", originalTracks == outputTracks)
                val outputVideo = decodeAll(clip, video.index, video = true)
                val outputAudio = decodeAll(clip, audio.index, video = false)
                assertEquals("Every decoded video frame and its PTS must be retained", originalVideoDecode, outputVideo)
                assertEquals("Every decoded audio block and its PTS must be retained", originalAudioDecode, outputAudio)
                assertEquals(SOURCE_SHA, hash(source))
                assertEquals(AlbumAssetKind.LIVE, input.images.single().kind); assertNull(input.images.single().motion)
                assertEquals("authorized-jpeg-$SOURCE_SHA", input.images.single().imageKey)
                assertFalse(WatermarkSources.available(input, WatermarkMode.CLEAN))
                assertNull(WatermarkSources.actualMode(input, WatermarkMode.CLEAN))
                attempt.put("savedEvidenceFile", evidence.name).put("wholeJpegSha256", hash(evidence))
                    .put("embeddedMp4Sha256", hash(clip)).put("savedUri", result.uri).put("publishedImagePending", 0)
                    .put("uriCount", 1).put("savedKind", asset.kind.name).put("recordWatermarkMode", result.watermarkMode!!.name)
                    .put("sourcePairingKind", input.images.single().kind.name).put("sourceSeparateMotion", JSONObject.NULL)
                    .put("allPacketsAndTrackMetadataPreserved", true).put("videoDecode", outputVideo.json())
                    .put("audioDecode", outputAudio.json()).put("status", "PASS")
            }
        } catch (error: Throwable) { failure = error }
        finally {
            val cleanupErrors = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { cleanupErrors.add(error) } }
            createdUris.forEach { value -> cleanup {
                context.contentResolver.delete(Uri.parse(value), null, null)
                context.contentResolver.query(Uri.parse(value), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
                    assertEquals("Exact created image URI remains after cleanup", 0, it.count)
                }
            } }
            clips.forEach { clip -> cleanup {
                check(clip.canonicalFile.parentFile == directory.canonicalFile)
                check(!clip.exists() || clip.delete()) { "Could not remove an exact owned temporary clip" }
            } }
            cleanup { assertNoPublishedFiles(prefix) }
            cleanup { assertEquals(scratchBefore, albumScratchNames()) }
            cleanup { assertEquals(SOURCE_SHA, hash(source)); assertEquals(SOURCE_BYTES, source.length()) }
            cleanup { assertTrue("Existing preferences or diagnostics changed", prefsBefore == preferencesSnapshot()) }
            cleanup { assertTrue("Existing background files changed", appearanceBefore == appearanceSnapshot()) }
            cleanup { assertTrue("Existing history records changed", historyBefore == DownloadRecords(context).history()) }
            cleanup { assertTrue("Existing history media changed", historyFilesBefore == historyFileSnapshot(historyBefore)) }
            if (failure == null && cleanupErrors.isNotEmpty()) failure = cleanupErrors.first()
            cleanupErrors.filter { it !== failure }.forEach { failure?.addSuppressed(it) }
            report.put("sourceCopyLeftForRootCleanup", source.name)
                .put("sourceUnchanged", runCatching { source.length() == SOURCE_BYTES && hash(source) == SOURCE_SHA }.getOrDefault(false))
                .put("exactCreatedUrisCleaned", cleanupErrors.isEmpty())
                .put("ownedTemporaryClipCount", clips.size).put("cleanupFailures", JSONArray(cleanupErrors.map { it.javaClass.simpleName }))
                .put("preferencesAndHistoryAndBackgroundProtected", cleanupErrors.isEmpty())
                .put("status", if (failure == null) "PASS" else "FAIL")
            failure?.let { report.put("failureType", it.javaClass.simpleName).put("failureMessage", DiagnosticText.clean(it.message.orEmpty(), 1000)) }
            File(directory, "report.json").writeText(report.toString(2), Charsets.UTF_8)
            println("ORIGINAL_MOTION_JPEG_REPORT=${directory.name}/report.json status=${report.getString("status")}")
        }
        failure?.let { throw it }
        Unit
    }

    private data class Packet(val ptsUs: Long, val flags: Int, val bytes: Int, val sha256: String) {
        fun json() = JSONObject().put("ptsUs", ptsUs).put("flags", flags).put("bytes", bytes).put("sha256", sha256)
    }
    private data class Track(val index: Int, val mime: String, val durationUs: Long, val width: Int, val height: Int,
        val rotation: Int, val sampleRate: Int, val channels: Int, val csd: Map<String, String>, val packets: List<Packet>) {
        fun json() = JSONObject().put("index", index).put("mime", mime).put("trackDurationUs", durationUs)
            .put("width", width).put("height", height).put("rotation", rotation)
            .put("sampleRate", sampleRate).put("channels", channels).put("codecSpecificDataSha256", JSONObject(csd))
            .put("packetCount", packets.size).put("packets", JSONArray(packets.map { it.json() }))
    }
    private data class Decoded(val ptsUs: Long, val bytes: Long, val sha256: String) {
        fun json() = JSONObject().put("ptsUs", ptsUs).put("decodedBytes", bytes).put("sha256", sha256)
    }
    private data class Decode(val outputs: List<Decoded>, val reachedEos: Boolean, val video: Boolean) {
        fun json() = JSONObject().put("video", video).put("reachedEos", reachedEos).put("decodedOutputCount", outputs.size)
            .put("distinctDecodedOutputs", outputs.map { it.sha256 }.distinct().size)
            .put("digestFormat", if (video) "CANONICAL_CROPPED_Y_U_V_BYTES_NO_PADDING" else "ALL_PCM_OUTPUT_BYTES")
            .put("outputs", JSONArray(outputs.map { it.json() }))
    }
    private fun inspectAllTracks(file: File): List<Track> {
        val inventory = MediaExtractor()
        val count = try { inventory.setDataSource(file.absolutePath); inventory.trackCount } finally { inventory.release() }
        return (0 until count).map { track ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val format = extractor.getTrackFormat(track)
                fun integer(key: String) = if (format.containsKey(key)) format.getInteger(key) else 0
                val csd = (0..3).mapNotNull { n -> val key = "csd-$n"; format.getByteBuffer(key)?.let { buffer ->
                    val bytes = ByteArray(buffer.remaining()).also(buffer.duplicate()::get); key to hash(bytes)
                } }.toMap()
                extractor.selectTrack(track)
                val packets = mutableListOf<Packet>()
                while (extractor.sampleTrackIndex == track && extractor.sampleSize > 0) {
                    val size = extractor.sampleSize
                    require(size <= 32L*1024*1024 && packets.size < 10_000) { "Actual source sample exceeds bounded inspection" }
                    val buffer = ByteBuffer.allocate(size.toInt())
                    val read = extractor.readSampleData(buffer, 0)
                    assertEquals(size.toInt(), read)
                    packets.add(Packet(extractor.sampleTime, extractor.sampleFlags, read, hash(buffer.array())))
                    if (!extractor.advance()) break
                }
                require(packets.isNotEmpty()) { "Actual source track has no readable samples" }
                Track(track, format.getString(MediaFormat.KEY_MIME).orEmpty(),
                    if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1,
                    integer(MediaFormat.KEY_WIDTH), integer(MediaFormat.KEY_HEIGHT), integer(MediaFormat.KEY_ROTATION),
                    integer(MediaFormat.KEY_SAMPLE_RATE), integer(MediaFormat.KEY_CHANNEL_COUNT), csd, packets)
            } finally { extractor.release() }
        }
    }
    private fun decodeAll(file: File, track: Int, video: Boolean): Decode {
        val extractor = MediaExtractor(); var codec: MediaCodec? = null; var started = false
        try {
            extractor.setDataSource(file.absolutePath); extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (video) format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder; decoder.configure(format, null, null, 0); decoder.start(); started = true
            val outputs = mutableListOf<Decoded>(); val info = MediaCodec.BufferInfo()
            var inputEnded = false; var outputEnded = false
            val deadline = SystemClock.elapsedRealtime()+90_000L
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Complete media decode timed out before EOS" }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index)); buffer.clear()
                        if (extractor.sampleTrackIndex == track && extractor.sampleSize > 0) {
                            require(extractor.sampleSize <= buffer.capacity()) { "Actual encoded sample exceeds decoder buffer" }
                            val count = extractor.readSampleData(buffer, 0)
                            require(count > 0)
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        } else {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val decoded = if (video) {
                                checkNotNull(decoder.getOutputImage(index)) { "Decoder did not provide a complete YUV frame" }.use { image ->
                                    val (bytes, digest) = imageDigest(image)
                                    Decoded(info.presentationTimeUs, bytes, digest)
                                }
                            } else {
                                val buffer = checkNotNull(decoder.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset); buffer.limit(info.offset+info.size)
                                val digest = MessageDigest.getInstance("SHA-256"); digest.update(buffer)
                                Decoded(info.presentationTimeUs, info.size.toLong(), hex(digest.digest()))
                            }
                            outputs.add(decoded)
                            require(outputs.size <= 10_000) { "Decoded output count exceeds bounded real-source check" }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
            require(outputs.isNotEmpty()) { "Decoder reached EOS without media" }
            return Decode(outputs, outputEnded, video)
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release(); extractor.release()
        }
    }
    private fun imageDigest(image: Image): Pair<Long, String> {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "Unexpected raw decoder image format" }
        val crop = image.cropRect
        assertEquals(960, crop.width()); assertEquals(720, crop.height())
        val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
        image.planes.forEachIndexed { index, plane ->
            val shift = if (index == 0) 0 else 1
            val width = crop.width() shr shift; val height = crop.height() shr shift
            val left = crop.left shr shift; val top = crop.top shr shift
            val buffer = plane.buffer.duplicate(); val start = buffer.position(); val row = ByteArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = buffer.get(start+(top+y)*plane.rowStride+(left+x)*plane.pixelStride)
                digest.update(row); bytes += row.size
            }
        }
        return bytes to hex(digest.digest())
    }
    private fun tracksJson(tracks: List<Track>) = JSONArray(tracks.map { it.json() })
    private fun hash(file: File) = file.inputStream().use(::hash)
    private fun hash(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128*1024)
        while (true) { val count = stream.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        return hex(digest.digest())
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun preferencesSnapshot(): Map<String, Map<String, Any?>> {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }.toSet() + setOf(
                "appearance_v1", "downloads", "download_tasks", "download_options", "preview_options",
                "download_folder", "notification_prompt", "parse_diagnostics")
        return names.sorted().associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues {
            (_, value) -> if (value is Set<*>) value.toSet() else value
        } }
    }
    private fun preferencesSummary(snapshot: Map<String, Map<String, Any?>>) = JSONArray(snapshot.map { (name, values) ->
        val canonical = values.toSortedMap().map { (key, value) ->
            "$key:${value?.javaClass?.simpleName}:${if (value is Set<*>) value.map { it.toString() }.sorted() else value}"
        }.joinToString("\n")
        JSONObject().put("namespace", name).put("keys", JSONArray(values.keys.sorted())).put("keyCount", values.size)
            .put("valuesSha256", hash(canonical.toByteArray(Charsets.UTF_8)))
    })
    private fun appearanceSnapshot() = File(context.filesDir, "appearance").let { folder ->
        folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to hash(it) }
    }
    private fun historyFileSnapshot(history: List<SavedVideo>) = history.flatMap { it.uris }.distinct().associateWith { value ->
        runCatching { context.contentResolver.openInputStream(Uri.parse(value))!!.use(::hash) }
            .getOrElse { "unreadable:${it.javaClass.simpleName}" }
    }
    private fun albumScratchNames() = context.cacheDir.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("album_") }.map { it.name }.toSet()
    private fun assertPublishedImage(saved: SavedVideo) {
        assertTrue(Uri.parse(saved.uri).path.orEmpty().contains("images"))
        context.contentResolver.query(Uri.parse(saved.uri), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
    private fun assertNoPublishedFiles(prefix: String) {
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { uri ->
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?", arrayOf("$prefix*"), null)!!.use { assertEquals(0, it.count) }
        }
    }
    private companion object {
        const val SOURCE_SHA = "e9cf8538855de919acb116fcb8ef47f01beef319bc9c22c347a1513de6aaddb3"
        const val EMBEDDED_SHA = "4a0ff354c37ab77aa4e526333d3175036cd0912a724fd4cf2af9f28622a0fdb3"
        const val SOURCE_BYTES = 3_366_772L
        const val PHOTO_URL = "https://p3.douyinpic.com/owned_real_motion_jpeg.jpeg"
    }
}
