package com.local.douyinsaver

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Rejected paths never enter a cleanup branch that owns either caller's existing file. */
@RunWith(AndroidJUnit4::class)
class SilentVideoRemuxerOwnershipTest {
    @Test fun guardsSourceAndPreexistingDestinationWithoutDeletingOrChangingBytes() = runBlocking(Dispatchers.IO) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(cache, "silent_remux_guard_${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            val source = File(directory, "source.mp4").apply { writeText("preserve this input") }
            val destination = File(directory, "existing.mp4").apply { writeText("preserve this unrelated output") }
            assertTrue(runCatching { SilentVideoRemuxer.copy(source, source) }.isFailure)
            assertEquals("preserve this input", source.readText())
            assertTrue(runCatching { SilentVideoRemuxer.copy(source, destination) }.isFailure)
            assertEquals("preserve this input", source.readText())
            assertEquals("preserve this unrelated output", destination.readText())
            val failed = File(directory, "failed_new.mp4")
            assertTrue(runCatching { SilentVideoRemuxer.copy(source, failed) }.isFailure)
            assertFalse(failed.exists())
            assertEquals("preserve this input", source.readText())
            assertEquals("preserve this unrelated output", destination.readText())
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun preservesEveryCompressedSampleAndExactVideoTailWhileRemovingAudio() = runBlocking(Dispatchers.IO) {
        withOwnedDirectory { directory ->
            val source = fixedVideo(directory)
            val originalHash = hash(source.readBytes())
            val before = inspect(source)
            assertTrue("The fixture must actually exercise audio removal", before.trackTypes.any { it.startsWith("audio/") })
            assertEquals(60, before.samples.size)
            assertTrue("The controlled video track must be two seconds", abs(before.durationUs - 2_000_000L) <= 100L)
            val saved = SilentVideoRemuxer.copy(source, File(directory, "silent.mp4"))
            assertPreserved(before, inspect(saved))
            assertEquals("The original audiovisual input must remain untouched", originalHash, hash(source.readBytes()))
        }
    }

    @Test fun preservesUnequalFinalFrameDurationAndRotationWithoutFollowingLongerAudio() = runBlocking(Dispatchers.IO) {
        withOwnedDirectory { directory ->
            val original = fixedVideo(directory)
            val originalHash = hash(original.readBytes())
            // Two different tails expose both clipping and unwanted padding. The AAC
            // track remains about two seconds while each retimed video is under 0.7s.
            for ((tailUs, rotation) in listOf(73_000L to 90, 3_700L to 270)) {
                val source = withUnequalVideoTail(original, File(directory, "tail_${tailUs}_$rotation.mp4"), tailUs, rotation)
                val sourceHash = hash(source.readBytes())
                val before = inspect(source)
                val finalPts = before.samples.maxOf { it.presentationTimeUs }
                assertTrue("The source fixture must retain its explicit unequal final duration", abs(before.durationUs - finalPts - tailUs) <= 100L)
                assertEquals(rotation, before.rotation)
                assertTrue("A longer audio track must not determine the video endpoint", longestAudioDurationUs(source) > before.durationUs + 500_000L)
                val saved = SilentVideoRemuxer.copy(source, File(directory, "silent_${tailUs}_$rotation.mp4"))
                val after = inspect(saved)
                assertPreserved(before, after)
                assertTrue("The exact last picture display interval must survive, without a duplicate frame",
                    abs((after.durationUs - after.samples.maxOf { it.presentationTimeUs }) - tailUs) <= 100L)
                assertEquals("Remuxing must retain the caller's source", sourceHash, hash(source.readBytes()))
            }
            assertEquals("Creating tail fixtures must also retain the original asset", originalHash, hash(original.readBytes()))
        }
    }

    private suspend fun withOwnedDirectory(block: suspend (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(cache, "silent_remux_timing_${UUID.randomUUID()}").also { check(it.mkdir()) }
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun fixedVideo(directory: File): File = File(directory, "fixed_source.mp4").also { output ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
            output.outputStream().use { input.copyTo(it) }
        }
        assertEquals("The fixed AVC/AAC fixture changed", "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(output.readBytes()))
    }

    private data class EncodedSample(val presentationTimeUs: Long, val flags: Int, val bytes: Int, val sha256: String)
    private data class VideoSnapshot(
        val durationUs: Long,
        val trackTypes: List<String>,
        val width: Int,
        val height: Int,
        val mime: String,
        val codecData: List<String>,
        val rotation: Int,
        val samples: List<EncodedSample>,
    )

    private fun inspect(file: File): VideoSnapshot {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val types = formats.map { it.getString(MediaFormat.KEY_MIME).orEmpty() }
            val index = types.indexOfFirst { it.startsWith("video/") }.also { assertTrue(it >= 0) }
            val format = formats[index]
            val codecData = (0..2).mapNotNull { i ->
                if (!format.containsKey("csd-$i")) null else format.getByteBuffer("csd-$i")!!.duplicate().let { data ->
                    data.position(0)
                    "csd-$i:${hash(ByteArray(data.remaining()).also(data::get))}"
                }
            }
            extractor.selectTrack(index)
            val samples = mutableListOf<EncodedSample>()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            while (extractor.sampleTrackIndex == index) {
                buffer.clear()
                val bytes = extractor.readSampleData(buffer, 0)
                assertTrue("An EOS marker must not become an extra encoded picture", bytes > 0)
                buffer.position(0); buffer.limit(bytes)
                samples += EncodedSample(extractor.sampleTime, extractor.sampleFlags, bytes,
                    hash(ByteArray(bytes).also(buffer::get)))
                if (!extractor.advance()) break
            }
            return VideoSnapshot(format.getLong(MediaFormat.KEY_DURATION), types,
                format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), types[index],
                codecData, rotation(file), samples)
        } finally { extractor.release() }
    }

    private fun assertPreserved(before: VideoSnapshot, after: VideoSnapshot) {
        assertEquals("The exported file must contain only its original video track", listOf(before.mime), after.trackTypes)
        assertEquals("Every compressed packet, PTS and flag must remain identical", before.samples, after.samples)
        assertEquals("No re-encoding or codec configuration changes are allowed", before.codecData, after.codecData)
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        assertEquals("The orientation hint must survive", before.rotation, after.rotation)
        assertTrue("The VIDEO track endpoint changed: ${before.durationUs} -> ${after.durationUs} us",
            abs(before.durationUs - after.durationUs) <= 100L)
    }

    private fun rotation(file: File): Int {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
        } finally { reader.release() }
    }

    private fun longestAudioDurationUs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .filter { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
                .maxOf { it.getLong(MediaFormat.KEY_DURATION) }
        } finally { extractor.release() }
    }

    /** Fixture-only remux: retain real AVC packets, but deliberately use a nonuniform
     * final video interval, a rotation hint and the unchanged longer AAC track. */
    private fun withUnequalVideoTail(original: File, output: File, tailUs: Long, rotation: Int): File {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(original.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTracks = formats.map(muxer::addTrack)
            muxer.setOrientationHint(rotation)
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for (index in formats.indices) {
                extractor.selectTrack(index)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val video = formats[index].getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
                var samples = 0L
                var lastVideoPts = 0L
                while (extractor.sampleTrackIndex == index) {
                    buffer.clear()
                    val bytes = extractor.readSampleData(buffer, 0)
                    assertTrue(bytes > 0)
                    val timestamp = if (video) samples * 10_000L else extractor.sampleTime
                    info.set(0, bytes, timestamp, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(outputTracks[index], buffer, info)
                    if (video) lastVideoPts = timestamp
                    samples++
                    if (!extractor.advance()) break
                }
                if (video) {
                    assertEquals(60L, samples)
                    info.set(0, 0, lastVideoPts + tailUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    muxer.writeSampleData(outputTracks[index], ByteBuffer.allocate(0), info)
                }
                extractor.unselectTrack(index)
            }
            muxer.stop(); started = false
            return output
        } finally {
            extractor.release()
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
