package com.local.douyinsaver

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Isolated generated fixtures: no third-party photos, network, or existing app records. */
@RunWith(AndroidJUnit4::class)
class AlbumCompositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun composesOrderedImagesWithLoopingShortAudio() = runBlocking(Dispatchers.IO) {
        withTimeout(180_000L) {
            val directory = temporaryDirectory()
            val images = localImages(directory)
            val audio = fixture("short_bgm.m4a", directory)
            val bgmDuration = AlbumMediaValidation.audioDurationMs(audio)
            assertTrue("Fixture BGM must be shorter than the four-second slideshow", bgmDuration in 1L..1_500L)
            var lastProgress = -1
            val video = AlbumVideoComposer(context).compose(images, audio, bgmDuration, 2, directory) {
                lastProgress = it
            }
            assertTrue(video.isFile && video.length() > 0)
            assertEquals(100, lastProgress)
            AlbumMediaValidation.composedVideo(context, Uri.fromFile(video), 4_000)

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                assertTrue("Expected four-second slideshow, got $duration ms", abs(duration - 4_000) < 500)
                val first = retriever.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
                val second = retriever.getScaledFrameAtTime(2_500_000, MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
                assertNotNull("First photo cannot be decoded", first)
                assertNotNull("Second photo cannot be decoded", second)
                try {
                    val firstPixel = first!!.getPixel(first.width / 2, first.height / 2)
                    val secondPixel = second!!.getPixel(second.width / 2, second.height / 2)
                    assertTrue("First frame should show the red photo", Color.red(firstPixel) > Color.blue(firstPixel) + 80)
                    assertTrue("Second frame should show the blue photo", Color.blue(secondPixel) > Color.red(secondPixel) + 80)
                } finally { first?.recycle(); second?.recycle() }

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(video.absolutePath)
                    val audioTrack = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                    extractor.selectTrack(audioTrack)
                    var lastAudioUs = -1L
                    var samples = 0
                    while (extractor.sampleTrackIndex == audioTrack && extractor.sampleSize > 0) {
                        lastAudioUs = extractor.sampleTime
                        samples++
                        if (!extractor.advance()) break
                    }
                    assertTrue("Looped audio should extend to the end, last=$lastAudioUs us", lastAudioUs >= 3_800_000)
                    assertTrue("Looped audio has too few encoded packets", samples > 100)
                    Log.i(TAG, "composition_verified duration_ms=$duration bgm_ms=$bgmDuration audio_last_us=$lastAudioUs samples=$samples bytes=${video.length()} output=${video.absolutePath}")
                } finally { extractor.release() }
            } finally { retriever.release() }
            // Kept in this newly-created private cache directory for independent full-file decoding.
            Unit
        }
    }

    @Test fun savesOriginalImagesToDefaultMediaStore() = runBlocking(Dispatchers.IO) {
        val directory = temporaryDirectory()
        val images = localImages(directory)
        val content = ParsedVideo("9999999999999999991", "图集本地验证 ${UUID.randomUUID()}", "", 0.0, 640, 480,
            images = images.map { ParsedImage("https://p3.douyinpic.com/fixture", it.width, it.height) })
        var saved: SavedVideo? = null
        try {
            val result = AlbumDownloader(context).saveLocalImages(content, images, DownloadStorage(context), null,
                DownloadOptions(albumMode = AlbumMode.IMAGES), { _, _ -> }, { saved = it })
            assertTrue(result.isAlbum)
            assertEquals("image/png", result.mimeType)
            assertEquals(2, result.uris.size)
            assertEquals(result.uris.first(), result.coverUri)
            assertEquals(DownloadStorage.DEFAULT_IMAGE_LOCATION, result.locationLabel)
            assertEquals(images.sumOf { it.file.length() }, result.bytes)
            for ((index, value) in result.uris.withIndex()) {
                val uri = Uri.parse(value)
                val original = images[index].file.readBytes()
                val actual = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue("Saved image bytes changed", original.contentEquals(actual))
                assertEquals("image/png", context.contentResolver.getType(uri))
                context.contentResolver.query(uri,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("${DownloadStorage.DEFAULT_IMAGE_LOCATION}/", it.getString(0))
                    assertEquals(0, it.getInt(1))
                    assertEquals(original.size.toLong(), it.getLong(2))
                }
                Log.i(TAG, "image_verified index=$index bytes=${actual.size} uri=$uri")
            }
        } finally {
            // Delete only these two just-created fixtures; no history or pre-existing files are touched.
            saved?.uris?.forEach { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
        }
        Unit
    }

    @Test fun cancelledImageSaveRemovesOnlyItsNewPendingFiles() = runBlocking(Dispatchers.IO) {
        val directory = temporaryDirectory()
        val images = localImages(directory)
        val prefix = "dycancel${UUID.randomUUID().toString().replace("-", "")}" // Unique plain filename prefix.
        val content = ParsedVideo("9999999999999999992", "取消图集保存验证", "", 0.0, 640, 480,
            images = images.map { ParsedImage("https://p3.douyinpic.com/fixture", it.width, it.height) })
        try {
            AlbumDownloader(context).saveLocalImages(content, images, DownloadStorage(context), null,
                DownloadOptions(fileName = prefix), { _, _ -> throw CancellationException("Isolated test cancellation") }, {})
            fail("Image save should have been cancelled")
        } catch (_: CancellationException) {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?", arrayOf("$prefix*"), null)!!.use {
                assertEquals("Cancelled download left a new pending image", 0, it.count)
            }
            Log.i(TAG, "image_cancel_verified pending_files=0")
        }
        Unit
    }

    @Test fun savesOriginalImagesToUserSelectedTestFolder() = runBlocking(Dispatchers.IO) {
        // Opt-in only after choosing this isolated test folder through the actual system picker.
        assumeTrue(InstrumentationRegistry.getArguments().getString("album_saf_test") == "true")
        val storage = DownloadStorage(context)
        val folder = storage.loadFolder()
        assumeTrue(folder?.label?.endsWith("/Screenshots/DouyinValidation030") == true)
        storage.validateAccess(folder!!)
        val directory = temporaryDirectory()
        val images = localImages(directory)
        val content = ParsedVideo("9999999999999999993", "SAF 图集验证 ${UUID.randomUUID()}", "", 0.0, 640, 480,
            images = images.map { ParsedImage("https://p3.douyinpic.com/fixture", it.width, it.height) })
        var saved: SavedVideo? = null
        try {
            val result = AlbumDownloader(context).saveLocalImages(content, images, storage, folder,
                DownloadOptions(fileName = "album_saf_validation"), { _, _ -> }, { saved = it })
            assertEquals(folder.label, result.locationLabel)
            assertEquals(2, result.uris.size)
            for ((index, value) in result.uris.withIndex()) {
                val uri = Uri.parse(value)
                val actual = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue("SAF saved image bytes changed", images[index].file.readBytes().contentEquals(actual))
                context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertTrue("Temporary file was not renamed", !it.getString(0).startsWith(".") && !it.getString(0).contains(".pending"))
                    assertEquals("image/png", it.getString(1))
                }
                Log.i(TAG, "saf_image_verified index=$index bytes=${actual.size} uri=$uri")
            }
        } finally {
            saved?.uris?.forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(it)) } }
        }
        Unit
    }

    private fun temporaryDirectory(): File = File(context.cacheDir, "album_validation_${UUID.randomUUID()}").also {
        check(it.mkdir())
    }

    private fun localImages(directory: File): List<LocalAlbumImage> = listOf("first_red.png", "second_blue.png").map {
        AlbumMediaValidation.image(fixture(it, directory))
    }

    private fun fixture(name: String, directory: File): File = File(directory, name).also { file ->
        instrumentation.context.assets.open("album_test/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    companion object { private const val TAG = "DyAlbumTest" }
}
