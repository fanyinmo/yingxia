package com.local.douyinsaver

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import android.net.Uri
import java.util.UUID

/** Explicitly supplied, locally staged public-work assets. Never sends a message itself. */
class ShareGifSampleExportTest {
    @Test fun exportSuppliedAlbumUsingProductionShareComposer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit sample export", args.getString("run_share_sample") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directoryName = args.getString("sample_directory") ?: "share_sample_7695376760892314330"
        require(directoryName.matches(Regex("share_sample_[a-z0-9_]+")))
        val directory = File(context.cacheDir, directoryName)
        val images = (1..2).map { AlbumMediaValidation.image(File(directory, "image_$it.webp")) }
        assertTrue(images.all { !it.animated })
        val outputName = args.getString("output_name") ?: "original-share.gif"
        require(outputName.matches(Regex("[a-z-]+\\.gif")))
        val output = File(directory, outputName)
        if (output.exists()) error("Previous sample exists; use a fresh test directory")
        AlbumGifComposer(GifExportQuality.SHARE).compose(images, 3_000L, output) { _, _ -> }
        val verified = AlbumMediaValidation.image(output)
        assertTrue(verified.animated)
        AnimatedImageFrames(verified).use { assertEquals(6_000L, it.durationMs) }
        println("share_sample_bytes=${output.length()} dimensions=${verified.width}x${verified.height}")
    }

    @Test fun suppliedAlbumIsPublishedOnceWithOneRecord() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit sample export", args.getString("run_share_sample") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directoryName = args.getString("sample_directory") ?: "share_sample_7695376760892314330"
        require(directoryName.matches(Regex("share_sample_[a-z0-9_]+")))
        val directory = File(context.cacheDir, directoryName)
        val namespace = "share_sample_record_${UUID.randomUUID()}_"
        val bytes = (1..2).map { File(directory, "image_$it.webp").readBytes() }
        val images = (1..2).map { index ->
            val url = "https://p3.douyinpic.com/owned-share-fixture/$index.webp"
            ParsedImage(url, mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)))
        }
        val transfer = MediaTransfer(connections = { uri ->
            val input = bytes[uri.path.substringAfterLast('/').substringBefore('.').toInt()-1]
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = input.size.toLong()
                override fun getContentType() = "image/webp"
                override fun getInputStream() = ByteArrayInputStream(input)
            }
        }, cookies = { null })
        var saved: SavedVideo? = null
        var callbacks = 0
        try {
            val result = AlbumDownloader(context, transfer).download(
                ParsedVideo("7695376760892314330", "指定图文分享测试", "", 0.0, 0, 0, images = images),
                null, DownloadOptions(albumMode = AlbumMode.GIF, gifExportQuality = GifExportQuality.SHARE),
                { _, _ -> }, {}, { record -> saved = record; callbacks++; DownloadRecords(context, namespace).save(record) })
            assertEquals(1, callbacks)
            assertEquals(1, result.uris.size)
            assertEquals(1, result.albumAssets.size)
            assertEquals(listOf(result), DownloadRecords(context, namespace).history())
            assertTrue(result.bytes in 1..GifConversionPolicy.MAX_GIF_BYTES)
            assertEquals("image/gif", context.contentResolver.getType(Uri.parse(result.uri)))
            val output = File(directory, "single-saved.gif")
            require(!output.exists())
            context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { input -> output.outputStream().use { input.copyTo(it) } }
            AnimatedImageFrames(AlbumMediaValidation.image(output)).use { assertEquals(6_000L, it.durationMs) }
            println("share_sample_single_save uri_count=${result.uris.size} record_count=1 bytes=${result.bytes}")
        } finally {
            saved?.uris?.forEach { context.contentResolver.delete(Uri.parse(it), null, null) }
            context.deleteSharedPreferences(namespace + "downloads")
        }
    }
}
