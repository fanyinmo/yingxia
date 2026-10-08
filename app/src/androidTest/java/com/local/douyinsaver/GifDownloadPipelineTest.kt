package com.local.douyinsaver

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs

/** Controlled responses and self-made colors only. Cleanup owns this run's UUID-prefixed media. */
@RunWith(AndroidJUnit4::class)
class GifDownloadPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun videoRetriesCleanSourceExportsActualFramesAndKeepsPublicationAfterRecordFailure() = isolated { scope ->
        val source = motionFixture(scope.directory)
        val sourceBytes = source.readBytes()
        val sourceDuration = durationMs(source)
        val expired = "https://v3.douyinvod.com/gif_test_expired.mp4"
        val backup = "https://v3.douyinvod.com/gif_test_backup.mp4"
        val marked = "https://v3.douyinvod.com/gif_test_marked.mp4?watermark=1"
        val requests = mutableListOf<String>()
        val downloader = VideoGifDownloader(context, transfer(requests, mapOf(
            expired to Response(ByteArray(0), 403), backup to Response(sourceBytes))))
        val content = ParsedVideo("9999999999999999961", "Self made red and blue video", expired, 0.0, 640, 480,
            mediaSources = listOf(MediaSource(expired, WatermarkMode.CLEAN), MediaSource(marked, WatermarkMode.WATERMARKED),
                MediaSource(backup, WatermarkMode.CLEAN)))
        val options = DownloadOptions(albumMode = AlbumMode.GIF, fileName = scope.prefix,
            gifStartSeconds = 0.25f, gifDurationSeconds = 6f)
        var saving = false
        stage("video_download")
        val result = downloader.download(content, null, options, { _, _ -> }, { saving = true }, {
            scope.createdUris.addAll(it.uris)
            scope.records.save(it)
        })
        assertTrue(saving)
        assertEquals(listOf(expired, backup), requests)
        assertEquals(listOf(result.uri), result.uris)
        assertEquals(result.uri, result.coverUri)
        assertEquals("image/gif", result.mimeType)
        assertEquals(AlbumMode.GIF.name, result.exportMode)
        assertEquals(WatermarkMode.CLEAN, result.watermarkMode)
        assertEquals(250L, result.gifStartMs)
        assertEquals(sourceDuration - 250L, result.gifDurationMs)
        assertFalse(result.isAlbum)
        assertTrue(result.albumAssets.isEmpty())
        val bytes = readPublished(result.uri)
        assertEquals(bytes.size.toLong(), result.bytes)
        assertPublished(result.uri, scope.prefix, ".gif", "image/gif")
        assertAnimatedGif(bytes, result.gifDurationMs, bothColors = true)
        assertEquals(listOf(result), DownloadRecords(context, scope.namespace).history())

        // Publication succeeds before the history callback. A callback failure must not roll it back.
        var publishedDespiteCallback: SavedVideo? = null
        stage("video_record_callback_failure")
        try {
            downloader.download(content, null, options.copy(gifStartSeconds = 1.25f, gifDurationSeconds = 0.25f),
                { _, _ -> }, {}, {
                    scope.createdUris.addAll(it.uris)
                    publishedDespiteCallback = it
                    throw FixtureRecordFailure()
                })
            fail("The injected record failure did not propagate")
        } catch (_: FixtureRecordFailure) { }
        val retained = checkNotNull(publishedDespiteCallback)
        assertPublished(retained.uri, scope.prefix, ".gif", "image/gif")
        assertAnimatedGif(readPublished(retained.uri), 250L)
        assertEquals("A failed callback must not replace the isolated history", listOf(result), scope.records.history())
        assertEquals(2, ownedRows(scope.prefix).size)
        assertTrue(sourceBytes.contentEquals(source.readBytes()))
        stage("video_verified")
    }

    @Test fun mixedGalleryConvertsLiveClipToGifAndPreservesStaticPngOrder() = isolated { scope ->
        val motion = motionFixture(scope.directory)
        val cover = fixture("first_red.png", scope.directory).readBytes()
        val still = fixture("second_blue.png", scope.directory).readBytes()
        val coverUrl = "https://p3.douyinpic.com/gif_test_cover.png"
        val stillUrl = "https://p3.douyinpic.com/gif_test_still.png"
        val motionUrl = "https://v3.douyinvod.com/gif_test_motion.mp4"
        val requests = mutableListOf<String>()
        val parsed = ParsedVideo("9999999999999999962", "Self made mixed gallery", "", 0.0, 640, 480,
            images = listOf(
                ParsedImage(coverUrl, mediaSources = listOf(MediaSource(coverUrl, WatermarkMode.CLEAN)), kind = AlbumAssetKind.LIVE,
                    motion = ParsedMotion(motionUrl, mediaSources = listOf(MediaSource(motionUrl, WatermarkMode.CLEAN)))),
                ParsedImage(stillUrl, mediaSources = listOf(MediaSource(stillUrl, WatermarkMode.CLEAN)))))
        stage("mixed_gallery_download")
        val result = AlbumDownloader(context, transfer(requests, mapOf(
            coverUrl to Response(cover), motionUrl to Response(motion.readBytes()), stillUrl to Response(still))))
            .download(parsed, null, DownloadOptions(albumMode = AlbumMode.GIF, fileName = scope.prefix,
                // Albums preserve each complete short motion, independently of ordinary-video clip settings.
                gifStartSeconds = 40f, gifDurationSeconds = 0.25f), { _, _ -> }, {}, {
                scope.createdUris.addAll(it.uris)
                scope.records.save(it)
            })
        assertEquals(listOf(coverUrl, motionUrl, stillUrl), requests)
        assertEquals(2, result.uris.size)
        assertEquals(result.uris, result.albumAssets.map { it.uri })
        assertEquals(listOf("image/gif", "image/png"), result.albumAssets.map { it.mimeType })
        assertEquals(listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.STATIC), result.albumAssets.map { it.kind })
        assertTrue(result.albumAssets.all { it.motionUri.isEmpty() })
        assertEquals("image/*", result.mimeType)
        assertEquals(AlbumMode.GIF.name, result.exportMode)
        assertTrue(result.isAlbum)
        assertEquals(result.uris.first(), result.coverUri)
        assertPublished(result.uris[0], scope.prefix, "_001.gif", "image/gif")
        assertPublished(result.uris[1], scope.prefix, "_002.png", "image/png")
        val gif = readPublished(result.uris[0])
        val png = readPublished(result.uris[1])
        assertAnimatedGif(gif, minOf(durationMs(motion), 15_000L), bothColors = true)
        assertTrue("The static item must retain its exact original bytes", still.contentEquals(png))
        assertEquals((gif.size + png.size).toLong(), result.bytes)
        assertEquals(listOf(result), DownloadRecords(context, scope.namespace).history())
        assertEquals(2, ownedRows(scope.prefix).size)
        assertTrue(ownedRows(scope.prefix).none { it.mime == "video/mp4" })
        stage("mixed_gallery_verified")
    }

    @Test fun invalidLiveMotionFailsBeforePublicationAndLeavesNoRecordOrOwnedFile() = isolated { scope ->
        val coverUrl = "https://p3.douyinpic.com/gif_test_invalid_cover.png"
        val motionUrl = "https://v3.douyinvod.com/gif_test_invalid_motion.mp4"
        val cover = fixture("first_red.png", scope.directory).readBytes()
        val requests = mutableListOf<String>()
        val parsed = ParsedVideo("9999999999999999963", "Invalid self made motion", "", 0.0, 640, 480,
            images = listOf(ParsedImage(coverUrl, mediaSources = listOf(MediaSource(coverUrl, WatermarkMode.CLEAN)),
                kind = AlbumAssetKind.LIVE, motion = ParsedMotion(motionUrl,
                    mediaSources = listOf(MediaSource(motionUrl, WatermarkMode.CLEAN))))))
        var saving = false
        var saved = false
        stage("invalid_gallery_download")
        try {
            AlbumDownloader(context, transfer(requests, mapOf(coverUrl to Response(cover),
                motionUrl to Response("<html>not an MP4 video</html>".toByteArray()))))
                .download(parsed, null, DownloadOptions(albumMode = AlbumMode.GIF, fileName = scope.prefix),
                    { _, _ -> }, { saving = true }, {
                        scope.createdUris.addAll(it.uris)
                        saved = true
                        scope.records.save(it)
                    })
            fail("A cover with invalid motion must not become a successful GIF")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("第 1 项动态素材下载失败"))
        }
        assertEquals(listOf(coverUrl, motionUrl), requests)
        assertFalse(saving)
        assertFalse(saved)
        assertTrue(scope.records.history().isEmpty())
        assertTrue("Invalid motion must leave neither a published nor pending file", ownedRows(scope.prefix).isEmpty())
        stage("invalid_gallery_verified")
    }

    private data class Scope(val prefix: String, val namespace: String, val directory: File,
        val records: DownloadRecords, val createdUris: MutableSet<String> = linkedSetOf())
    private data class Response(val body: ByteArray, val status: Int = 200)
    private data class OwnedRow(val uri: Uri, val mime: String)
    private class FixtureRecordFailure : IllegalStateException("Injected isolated record failure")

    private fun isolated(body: suspend (Scope) -> Unit) = runBlocking(Dispatchers.IO) {
        val token = UUID.randomUUID().toString().replace("-", "")
        val prefix = "gifpipeline$token"
        val namespace = "gif_pipeline_${token}_"
        val directory = File(context.cacheDir, "gif_pipeline_$token").also { check(it.mkdir()) }
        val scope = Scope(prefix, namespace, directory, DownloadRecords(context, namespace))
        try {
            assertTrue("The random namespace must begin empty", scope.records.history().isEmpty())
            assertTrue("The random output prefix must begin empty", ownedRows(prefix).isEmpty())
            withTimeout(180_000) { body(scope) }
        } finally {
            stage("cleanup_owned_media")
            // Captured callbacks are exact created URIs. Querying this unique prefix also finds a
            // pending file if an assertion failed before a callback; owner-package matching is mandatory.
            ownedRows(prefix).forEach { scope.createdUris.add(it.uri.toString()) }
            scope.createdUris.forEach { value ->
                val uri = Uri.parse(value)
                check(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
                context.contentResolver.delete(uri, null, null)
            }
            context.deleteSharedPreferences(namespace + "downloads")
            context.deleteSharedPreferences(namespace + "download_tasks")
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
            stage("cleanup_complete")
        }
        Unit
    }

    private suspend fun motionFixture(directory: File): File {
        stage("copy_fixed_private_red_blue_fixture")
        val output = File(directory, "fixed_red_blue_2s.mp4").also { file ->
            instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val sourceHash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("Fixed original motion source differs from its independent manifest",
                "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", sourceHash)
        }
        AlbumMediaValidation.motionVideo(output)
        stage("private_fixture_ready")
        return output
    }

    private fun fixture(name: String, directory: File) = File(directory, name).also { file ->
        instrumentation.context.assets.open("album_test/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun durationMs(source: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
        } finally { retriever.release() }
    }

    private fun transfer(requests: MutableList<String>, responses: Map<String, Response>) = MediaTransfer(
        connections = { uri ->
            requests += uri.toString()
            val response = responses[uri.toString()] ?: error("This fixture forbids all other requests: ${uri.host}")
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = response.status
                override fun getContentType() = "application/octet-stream"
                override fun getContentLengthLong() = response.body.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(response.body)
            }
        }, cookies = { null })

    private fun readPublished(value: String): ByteArray = context.contentResolver.openInputStream(Uri.parse(value))!!.use { it.readBytes() }

    private fun assertPublished(value: String, prefix: String, suffix: String, mime: String) {
        val uri = Uri.parse(value)
        assertEquals(mime, context.contentResolver.getType(uri))
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
            assertTrue("A published output was removed", it.moveToFirst())
            assertTrue(it.getString(0).startsWith(prefix))
            assertTrue(it.getString(0).endsWith(suffix))
            assertEquals(0, it.getInt(1))
            assertEquals(mime, it.getString(2))
        }
    }

    private fun ownedRows(prefix: String): List<OwnedRow> = buildList {
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf("$prefix*", context.packageName), null)!!.use { cursor ->
                while (cursor.moveToNext()) add(OwnedRow(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1)))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun assertAnimatedGif(bytes: ByteArray, expectedDurationMs: Long, bothColors: Boolean = false) {
        assertEquals("GIF89a", bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII))
        assertEquals(0x3b, bytes.last().toInt() and 255)
        val delays = gifDelays(bytes)
        assertTrue("A GIF must contain actual multiple frames", delays.size >= 2)
        assertTrue("The GIF delay must cover the chosen clip", abs(delays.sum() * 10L - expectedDurationMs) <= 10L)
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        assertTrue("Android must recognize the exported GIF as animated", drawable is AnimatedImageDrawable)
        (drawable as AnimatedImageDrawable).stop()
        if (bothColors) {
            // Android's independent GIF decoder demonstrates visible source changes, not a fake
            // multi-frame container containing repeated copies of one video-cover bitmap.
            val movie = checkNotNull(Movie.decodeByteArray(bytes, 0, bytes.size))
            val bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
            try {
                fun pixelAt(time: Int): Int {
                    bitmap.eraseColor(Color.BLACK)
                    movie.setTime(time)
                    movie.draw(Canvas(bitmap), 0f, 0f)
                    return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                }
                val first = pixelAt(0)
                val second = pixelAt(minOf(1_200, movie.duration() - 20))
                assertTrue("Early frame must retain the red source", Color.red(first) > Color.blue(first) + 80)
                assertTrue("Later frame must retain the blue source", Color.blue(second) > Color.red(second) + 80)
            } finally { bitmap.recycle() }
        }
    }

    /** Traverse GIF blocks instead of counting byte patterns that might also occur in LZW data. */
    private fun gifDelays(bytes: ByteArray): List<Int> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        fun ushort(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun skip(count: Int) { repeat(count) { input.readUnsignedByte() } }
        fun blocks() { while (true) { val count = input.readUnsignedByte(); if (count == 0) return; skip(count) } }
        skip(6)
        assertTrue(ushort() in 1..GifConversionPolicy.MAX_EDGE)
        assertTrue(ushort() in 1..GifConversionPolicy.MAX_EDGE)
        val packed = input.readUnsignedByte()
        skip(2)
        if (packed and 0x80 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        val delays = mutableListOf<Int>()
        var frames = 0
        while (true) {
            when (val block = input.readUnsignedByte()) {
                0x21 -> if (input.readUnsignedByte() == 0xf9) {
                    assertEquals(4, input.readUnsignedByte())
                    skip(1)
                    delays += ushort()
                    skip(1)
                    assertEquals(0, input.readUnsignedByte())
                } else blocks()
                0x2c -> {
                    skip(8)
                    val framePacked = input.readUnsignedByte()
                    if (framePacked and 0x80 != 0) skip(3 * (1 shl ((framePacked and 7) + 1)))
                    input.readUnsignedByte()
                    blocks()
                    frames++
                }
                0x3b -> break
                else -> fail("Unexpected GIF block $block")
            }
        }
        assertEquals("Each real frame must have a delay", frames, delays.size)
        delays
    }

    private fun stage(value: String) { Log.i("GifDownloadPipeline", value) }
}
