package com.local.douyinsaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/** Controlled private fixtures. Xiaomi gallery recognition is a separate real-device check. */
@RunWith(AndroidJUnit4::class)
class MotionPhotoExporterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun singleJpegPreservesFullCoverAndOriginalVideoAudioSamplesAndDecodesChangingFrames() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val video = video(directory)
            val cover = jpeg(directory)
            val sourceHash = hash(video); val coverHash = hash(cover)
            val output = MotionPhotoFixtureWriter.create(cover, video, File(directory, "live_MP.jpg"), 0)
            val verified = EmbeddedMotionReader.validate(output)
            assertTrue(verified.hasAudio); assertTrue(verified.durationMs >= 2_000L)
            assertEquals(sourceHash, hash(video)); assertEquals(coverHash, hash(cover))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            assertEquals(960, bounds.outWidth); assertEquals(720, bounds.outHeight)
            val extracted = EmbeddedMotionReader.extractForPreview(output, File(directory, "private-preview.mp4"))
            assertEquals(sourceHash, hash(extracted))
            assertEquals(trackTypes(video), trackTypes(extracted))
            val retriever = MediaMetadataRetriever()
            try {
                FileInputStream(output).use { retriever.setDataSource(it.fd, verified.container.videoOffset, verified.container.videoLength) }
                val first = retriever.getFrameAtTime(250_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                val second = retriever.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                try {
                    val a = first.getPixel(first.width / 2, first.height / 2)
                    val b = second.getPixel(second.width / 2, second.height / 2)
                    assertTrue("Embedded video lost the first original frame", Color.red(a) > Color.blue(a) + 80)
                    assertTrue("Embedded video repeated only the cover", Color.blue(b) > Color.red(b) + 80)
                } finally { first.recycle(); second.recycle() }
            } finally { retriever.release() }
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun mediaStorePublishesOneImageAndExtractsItsRealEmbeddedVideoWithoutASeparateVideoRow() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        val storage = DownloadStorage(context)
        var pending: DownloadStorage.PendingDownload? = null
        try {
            val video = video(directory); val cover = jpeg(directory)
            val output = MotionPhotoFixtureWriter.create(cover, video, File(directory, "save_MP.jpg"))
            pending = storage.createPending("yingxia_motion_photo_test_${UUID.randomUUID()}_MP.jpg", null, "image/jpeg")
            context.contentResolver.openOutputStream(pending.uri, "w")!!.use { destination -> output.inputStream().use { it.copyTo(destination) } }
            val uri = pending.publish()
            assertTrue(uri.path.orEmpty().contains("images"))
            assertEquals("image/jpeg", context.contentResolver.getType(uri))
            val copy = EmbeddedMotionReader.extractForPreview(context, uri, File(directory, "uri-preview.mp4"))
            assertEquals(hash(video), hash(copy))
            val bytes = context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
            val embedded = context.contentResolver.openInputStream(uri)!!.use { MotionPhotoContainer.inspect(it, bytes) }
            assertNotNull(embedded); assertEquals(video.length(), embedded!!.videoLength)
        } finally {
            // Only the URI created by this controlled test is rolled back.
            pending?.rollback(); pending?.cleanup(); directory.deleteRecursively()
        }
        Unit
    }

    @Test fun invalidMotionOrCoverTimestampDoesNotPublishOrLoseSourceFiles() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val video = video(directory); val cover = jpeg(directory)
            val sourceHash = hash(video); val coverHash = hash(cover)
            val bad = File(directory, "invalid.mp4").apply { writeText("HTML is not real motion") }
            val failed = File(directory, "failed_MP.jpg")
            assertTrue(runCatching { MotionPhotoFixtureWriter.create(cover, bad, failed) }.isFailure)
            assertFalse(failed.exists())
            val timestamp = File(directory, "timestamp_MP.jpg")
            assertTrue(runCatching { MotionPhotoFixtureWriter.create(cover, video, timestamp, 999_000_000) }.isFailure)
            assertFalse(timestamp.exists())
            assertEquals(sourceHash, hash(video)); assertEquals(coverHash, hash(cover))
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun explicitAnimationConversionHasMatchingFullSizeCoverKnownTimestampAndSilentOriginalMotion() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val original = video(directory); val originalHash = hash(original)
            val output = MotionPhotoFixtureWriter.convertAnimation(original, File(directory, "converted_MP.jpg"), MotionPhotoContainer.Format.XIAOMI)
            val verified = EmbeddedMotionReader.validate(output)
            assertEquals(0L, verified.container.presentationTimestampUs)
            assertFalse(verified.hasAudio)
            assertEquals(1280, verified.width); assertEquals(720, verified.height)
            val cover = BitmapFactory.decodeFile(output.absolutePath)!!
            try {
                assertEquals(1280, cover.width); assertEquals(720, cover.height)
                assertTrue("Generated cover must be the actual red first frame", Color.red(cover.getPixel(640, 360)) > 200)
            } finally { cover.recycle() }
            val xml = output.inputStream().use { input -> ByteArray(minOf(output.length(), 4_096).toInt()).also { input.read(it) } }
                .toString(Charsets.UTF_8)
            assertTrue(xml.contains("http://ns.xiaomi.com/photos/1.0/camera/"))
            assertEquals(originalHash, hash(original))
            assertTrue(directory.listFiles()!!.none { it.name.startsWith(".live-") })
            val extracted = EmbeddedMotionReader.extractForPreview(output, File(directory, "converted.mp4"))
            assertEquals(listOf("video/avc"), trackTypes(extracted))
            // Compare compressed frame samples, not only duration or the MIME string.
            assertEquals(videoSampleHashes(original), videoSampleHashes(extracted))
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun galleryProfileMatchesActualMidClipCoverAndRejectsUnrelatedCoverWithoutPublishing() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val original = video(directory)
            val retriever = MediaMetadataRetriever()
            val cover = File(directory, "blue-cover.jpg")
            try {
                retriever.setDataSource(original.absolutePath)
                val frame = retriever.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                try { cover.outputStream().use { assertTrue(frame.compress(Bitmap.CompressFormat.JPEG, 98, it)) } }
                finally { frame.recycle() }
            } finally { retriever.release() }
            val saved = MotionPhotoFixtureWriter.createForGallery(cover, original, File(directory, "matched_MP.jpg"), MotionPhotoContainer.Format.XIAOMI)
            val verified = EmbeddedMotionReader.validate(saved)
            assertTrue("A blue cover cannot be assigned to the red first frame", verified.container.presentationTimestampUs >= 1_000_000L)
            assertTrue(verified.hasAudio)
            val invalid = File(directory, "unrelated.jpg")
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            try { invalid.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) } }
            finally { bitmap.recycle() }
            val rejected = File(directory, "rejected_MP.jpg")
            assertTrue(runCatching { MotionPhotoFixtureWriter.createForGallery(invalid, original, rejected, MotionPhotoContainer.Format.XIAOMI) }.isFailure)
            assertFalse(rejected.exists())
            assertTrue(cover.exists()); assertTrue(original.exists())
        } finally { directory.deleteRecursively() }
        Unit
    }

    private fun directory(): File = File(context.cacheDir, "motion_photo_test_${UUID.randomUUID()}").also { check(it.mkdir()) }
    private fun jpeg(directory: File): File {
        val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return File(directory, "cover.jpg").also { file ->
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) } }
            finally { bitmap.recycle() }
        }
    }
    private suspend fun video(directory: File): File {
        return File(directory, "fixed_red_blue_2s.mp4").also { output ->
            instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
            assertEquals("Fixed original motion source differs from its independent manifest",
                "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(output))
        }
    }
    private fun fixture(name: String, directory: File): File = File(directory, name).also { file ->
        instrumentation.context.assets.open("album_test/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun trackTypes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally { extractor.release() }
    }
    private fun videoSampleHashes(file: File): List<Pair<Long, String>> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            extractor.selectTrack(track)
            val result = mutableListOf<Pair<Long, String>>()
            val buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
            do {
                buffer.clear()
                val bytes = extractor.readSampleData(buffer, 0)
                if (bytes < 0) break
                val payload = ByteArray(bytes); buffer.position(0); buffer.get(payload)
                result += extractor.sampleTime to MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            } while (extractor.advance())
            result
        } finally { extractor.release() }
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
