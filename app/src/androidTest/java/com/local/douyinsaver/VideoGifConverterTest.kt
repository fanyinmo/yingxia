package com.local.douyinsaver

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Original colors, private MP4/GIF files only: no network, MediaStore, preferences, or user files. */
@RunWith(AndroidJUnit4::class)
class VideoGifConverterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun gifDefaultDurationFollowsVideoTrackWhenOriginalAudioIsTwiceAsLong() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val original = source(directory)
            val originalHash = hash(original)
            val extended = withLongerAudio(original, File(directory, "video2s-audio4s.mp4"))
            val extendedHash = hash(extended)
            assertEquals(60, videoSampleTimesUs(extended).size)
            assertEquals(2_000_000L, videoTrackDurationUs(extended))
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(extended.absolutePath)
                assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 3_990L)
            } finally { retriever.release() }
            val converter = VideoGifConverter()
            val actualDuration = converter.readDurationMs(extended)
            assertEquals("Long original audio must not stretch a motion's default timeline", 2_000L, actualDuration)
            val gif = converter.convertLooped(extended, File(directory, "video-only-timing.gif"), actualDuration,
                GifExportQuality.SHARE) { _, _ -> }
            val timeline = frames(gif.readBytes())
            assertEquals(20, timeline.frames.size)
            assertEquals(200, timeline.frames.sumOf { it.delayCs })
            val colors = decodeEverySharingFrame(gif, timeline, 480, 270)
            assertEquals(List(10) { "red" } + List(10) { "blue" }, colors)
            assertEquals(originalHash, hash(original))
            assertEquals(extendedHash, hash(extended))
        } finally { directory.deleteRecursively() }
        Unit
    }

    /** Preserve the real 60 AVC samples, and loop original valid AAC packets twice. */
    private fun withLongerAudio(source: File, output: File): File {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val tracks = (0 until extractor.trackCount).associateWith { index ->
                muxer.addTrack(extractor.getTrackFormat(index))
            }
            assertEquals(2, tracks.size)
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val information = MediaCodec.BufferInfo()
            for ((track, destination) in tracks) {
                val format = extractor.getTrackFormat(track)
                val audio = format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
                val cycles = if (audio) 2 else 1
                extractor.selectTrack(track)
                repeat(cycles) { cycle ->
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (extractor.sampleTrackIndex == track && extractor.sampleSize > 0) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        require(size > 0)
                        information.set(0, size, cycle * 2_000_000L + extractor.sampleTime, extractor.sampleFlags)
                        muxer.writeSampleData(destination, buffer, information)
                        if (!extractor.advance()) break
                    }
                }
                information.set(0, 0, cycles * 2_000_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                muxer.writeSampleData(destination, ByteBuffer.allocate(0), information)
                extractor.unselectTrack(track)
            }
            return output
        } finally {
            extractor.release()
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
    }

    @Test fun convertsRealVideoToLoopingGifAndDecodesRedAndBlueFrames() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val source = source(directory)
            val sourceHash = hash(source)
            val converter = VideoGifConverter()
            val sourceDuration = converter.readDurationMs(source)
            assertTrue(sourceDuration >= 2_000L)
            var progress = 0L
            val result = converter.convert(source, File(directory, "motion.gif"), 0, 2_000) { done, total ->
                assertTrue(done in progress..total)
                assertEquals(60L, total)
                progress = done
            }
            assertEquals(60L, progress)
            assertEquals(sourceHash, hash(source))
            assertTrue(result.length() in 1..GifConversionPolicy.MAX_GIF_BYTES)
            var animated = false
            val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(result)) { decoder, info, _ ->
                animated = info.isAnimated
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                assertTrue("Output is not an animated GIF", animated)
                assertEquals(GifConversionPolicy.MAX_EDGE, maxOf(preview.width, preview.height))
            } finally { preview.recycle() }
            val data = result.readBytes()
            assertTrue(data.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
            val structure = frames(data)
            assertEquals(60, structure.frames.size)
            assertEquals(200, structure.frames.sumOf { it.delayCs })
            assertEquals(0x3b, data.last().toInt() and 255)
            // Independently decode each actual GIF image block with Android's decoder.
            // A converter accidentally repeating only the cover cannot pass this check.
            val colors = structure.frames.map { frame ->
                val single = structure.header + frame.graphicControl + frame.imageBlock + byteArrayOf(0x3b)
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(single))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    when { Color.red(color) > Color.blue(color) + 80 -> "red"; Color.blue(color) > Color.red(color) + 80 -> "blue"; else -> "other" }
                } finally { bitmap.recycle() }
            }.toSet()
            assertTrue("Actual converted animation lost one video segment: $colors", "red" in colors && "blue" in colors)
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun shareAndHighQualityDecodeTheSameCompleteTwoSecondVideo() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val source = source(directory)
            val sourceHash = hash(source)
            assertEquals(60, videoSampleTimesUs(source).size)
            assertEquals(2_000_000L, videoTrackDurationUs(source))
            val converter = VideoGifConverter()
            assertEquals(2_000L, converter.readDurationMs(source))
            val results = mutableMapOf<GifExportQuality, File>()
            val observations = org.json.JSONArray()
            for (quality in listOf(GifExportQuality.HIGH_QUALITY, GifExportQuality.SHARE)) {
                val expectedFrames = if (quality == GifExportQuality.HIGH_QUALITY) 60 else 20
                val width = if (quality == GifExportQuality.HIGH_QUALITY) 960 else 480
                val height = if (quality == GifExportQuality.HIGH_QUALITY) 540 else 270
                var completedFrames = 0L
                val result = converter.convert(source, File(directory, "${quality.name.lowercase()}_2s.gif"),
                    0L, 2_000L, quality) { done, total ->
                    assertEquals(expectedFrames.toLong(), total)
                    assertTrue(done in completedFrames..total)
                    completedFrames = done
                }
                assertEquals(expectedFrames.toLong(), completedFrames)
                val structure = frames(result.readBytes())
                assertEquals(expectedFrames, structure.frames.size)
                assertEquals(200, structure.frames.sumOf { it.delayCs })
                assertTrue(structure.frames.all { it.delayCs > 0 })
                val colors = decodeEverySharingFrame(result, structure, width, height)
                assertEquals(listOf("red", "blue"), colors.distinct())
                assertEquals("red", colors.first())
                assertEquals("blue", colors.last())
                assertEquals(fixedFixtureVideoColorAt(source, 0L), colors.first())
                assertEquals(fixedFixtureVideoColorAt(source, videoSampleTimesUs(source).last()), colors.last())
                results[quality] = result
                observations.put(org.json.JSONObject().put("quality", quality.name)
                    .put("width", width).put("height", height).put("frames", structure.frames.size)
                    .put("durationMs", 2_000).put("bytes", result.length()).put("sha256", hash(result)))
            }
            assertTrue("The explicit share export must be smaller for the same controlled range",
                results.getValue(GifExportQuality.SHARE).length() < results.getValue(GifExportQuality.HIGH_QUALITY).length())
            assertEquals(sourceHash, hash(source))
            File(directory, "sharing-inspection.json").writeText(org.json.JSONObject()
                .put("scope", "CONTROLLED_COMPONENT_NOT_CHAT_APP_PLAYBACK_ACCEPTANCE")
                .put("sourceSha256", sourceHash).put("sourceFrames", 60).put("outputs", observations).toString(2))
        } finally { cleanupSharingFixture(directory) }
        Unit
    }

    @Test fun shareModeDecodesTheCompleteLongVideoIncludingItsRealTailTransition() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val source = source(directory)
            val sourceHash = hash(source)
            // Repeat the fixed AVC samples with MediaMuxer; no production video composer is used.
            val long = remuxLoops(source, File(directory, "sharing_long_video.mp4"), 16_100_000L)
            val longHash = hash(long)
            val sampleTimes = videoSampleTimesUs(long)
            assertEquals("The long source must contain every real 30 fps sample", 483, sampleTimes.size)
            assertEquals(0L, sampleTimes.first())
            assertTrue(sampleTimes.zipWithNext().all { (first, next) -> next - first in 32_000L..35_000L })
            val converter = VideoGifConverter()
            val duration = converter.readDurationMs(long)
            assertTrue("Controlled long source must remain 16.1 seconds", abs(duration - 16_100L) <= 5L)
            assertTrue(duration * 1000L - sampleTimes.last() in 1L..50_000L)
            // 16,100 ms is 161 frames at 10 fps; a muxer's rounded 16,101 ms needs 162.
            val expectedFrames = ((duration * 10L + 999L) / 1000L).toInt()
            assertTrue(expectedFrames in 161..162)
            var completedFrames = 0L
            val result = converter.convert(long, File(directory, "share_long.gif"), 0L, duration,
                GifExportQuality.SHARE) { done, total ->
                assertEquals(expectedFrames.toLong(), total)
                assertTrue(done in completedFrames..total)
                completedFrames = done
            }
            assertEquals(expectedFrames.toLong(), completedFrames)
            val structure = frames(result.readBytes())
            assertEquals(expectedFrames, structure.frames.size)
            val outputDuration = structure.frames.sumOf { it.delayCs.toLong() } * 10L
            assertTrue(abs(outputDuration - duration) <= 5L)
            assertTrue(structure.frames.all { it.delayCs > 0 })
            val colors = decodeEverySharingFrame(result, structure, 480, 270)
            val segments = colors.fold(mutableListOf<String>()) { resultColors, color ->
                if (resultColors.lastOrNull() != color) resultColors += color
                resultColors
            }
            assertEquals("All eight red/blue cycles and the final real red segment must survive",
                List(17) { if (it % 2 == 0) "red" else "blue" }, segments)
            assertEquals("blue", fixedFixtureVideoColorAt(long, 15_900_000L))
            assertEquals("red", fixedFixtureVideoColorAt(long, sampleTimes.last()))
            assertEquals(fixedFixtureVideoColorAt(long, 0L), colors.first())
            assertEquals("A frozen blue tail must not replace the last actual source segment",
                fixedFixtureVideoColorAt(long, sampleTimes.last()), colors.last())
            assertEquals(sourceHash, hash(source))
            assertEquals(longHash, hash(long))
            File(directory, "sharing-inspection.json").writeText(org.json.JSONObject()
                .put("scope", "CONTROLLED_COMPONENT_NOT_CHAT_APP_PLAYBACK_ACCEPTANCE")
                .put("sourceSha256", longHash).put("sourceFrames", sampleTimes.size).put("sourceDurationMs", duration)
                .put("sourceLastSampleUs", sampleTimes.last()).put("quality", GifExportQuality.SHARE.name)
                .put("width", 480).put("height", 270).put("frames", structure.frames.size)
                .put("durationMs", outputDuration).put("bytes", result.length()).put("sha256", hash(result))
                .put("segments", org.json.JSONArray(segments)).toString(2))
        } finally { cleanupSharingFixture(directory) }
        Unit
    }

    @Test fun respectsSelectedRangeAndRotationWithoutStretchingFrames() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val source = source(directory)
            val rotated = remuxRotation(source, File(directory, "rotated_source.mp4"))
            val metadata = MediaMetadataRetriever()
            var width = 0
            var height = 0
            try {
                metadata.setDataSource(rotated.absolutePath)
                assertEquals("90", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
                width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            } finally { metadata.release() }
            val result = VideoGifConverter().convert(rotated, File(directory, "slice.gif"), 500L, 1_000L) { _, _ -> }
            val structure = frames(result.readBytes())
            assertEquals(30, structure.frames.size)
            assertEquals(100, structure.frames.sumOf { it.delayCs })
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(result))
            try {
                assertEquals(GifConversionPolicy.MAX_EDGE, bitmap.height)
                assertTrue("Rotation was ignored or video stretched", abs(bitmap.width.toDouble() / bitmap.height - height.toDouble() / width) < 0.01)
            } finally { bitmap.recycle() }
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun cancellationRemovesOnlyNewGifAndLeavesOriginalVideoUnchanged() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val source = source(directory)
            val sourceHash = hash(source)
            val destination = File(directory, "cancelled.gif")
            try {
                VideoGifConverter().convert(source, destination, 0, 2_000) { done, _ ->
                    if (done >= 1) throw CancellationException("Cancel this isolated converter after the first real frame")
                }
                fail("Conversion did not cancel")
            } catch (_: CancellationException) {
                assertFalse("Cancellation left a partial GIF", destination.exists())
                assertTrue(source.isFile)
                assertEquals(sourceHash, hash(source))
            }
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun exportsOneTenthSecondAndWholeVideoLongerThanFifteenSeconds() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val source = source(directory)
            val sourceHash = hash(source)
            val converter = VideoGifConverter()
            val short = converter.convert(source, File(directory, "tenth_second.gif"), 100L, 100L) { _, _ -> }
            val shortFrames = frames(short.readBytes()).frames
            assertEquals(3, shortFrames.size)
            assertEquals(10, shortFrames.sumOf { it.delayCs })
            val long = remuxLoops(source, File(directory, "long_video.mp4"), 16_100_000L)
            val longHash = hash(long)
            val duration = converter.readDurationMs(long)
            assertTrue(duration > 15_000L)
            // Opt-in observations only: retain the complete HD export and every original assertion.
            // Progress and this worker's stack distinguish a slow encoder/seek from a paused process.
            val capture = InstrumentationRegistry.getArguments().getString("capture_video_gif") == "true"
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val worker = java.util.concurrent.atomic.AtomicReference<Thread?>()
            val observer = if (capture) java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "GifTestDiagnostic").apply { isDaemon = true }
            } else null
            observer?.scheduleAtFixedRate({
                val thread = worker.get()
                File(directory, "whole-video-worker-stack.txt").appendText(
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} thread=${thread?.name} state=${thread?.state}\n" +
                        thread?.stackTrace?.joinToString("\n") + "\n\n")
            }, 2L, 5L, java.util.concurrent.TimeUnit.SECONDS)
            val processingService = InstrumentationRegistry.getArguments().getString("media_processing_test") == "true"
            val full = try {
                if (processingService) {
                    // Exercise the same public foreground-service mode used by normal App conversion.
                    // No Activity launch, permission grant, power override or user-preference writes.
                    instrumentation.runOnMainSync { DownloadForegroundService.start(context, processing = true) }
                    val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
                    while (manager.getRunningServices(20).none {
                            it.service.className == DownloadForegroundService::class.java.name && it.foreground
                        } && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(20L)
                    assertTrue("The normal media-processing foreground service did not become active",
                        manager.getRunningServices(20).any {
                            it.service.className == DownloadForegroundService::class.java.name && it.foreground
                        })
                }
                converter.convert(long, File(directory, "whole_video.gif"), 0L, duration) { done, total ->
                    if (capture) {
                        worker.set(Thread.currentThread())
                        File(directory, "whole-video-progress.tsv").appendText(
                            "${android.os.SystemClock.elapsedRealtime() - startedAt}\t$done\t$total\n")
                    }
                }
            } finally {
                observer?.shutdownNow()
                if (processingService) instrumentation.runOnMainSync { DownloadForegroundService.stop(context) }
            }
            val fullStructure = frames(full.readBytes())
            val fullFrames = fullStructure.frames
            assertTrue(fullFrames.size > 450)
            assertTrue(abs(fullFrames.sumOf { it.delayCs.toLong() } * 10L - duration) <= 5L)
            val lastSourceTimeUs = videoSampleTimesUs(long).last()
            assertTrue("The source's final video sample must reach its actual end",
                duration * 1000L - lastSourceTimeUs in 1L..50_000L)
            assertEquals("Full GIF must start at the source beginning", videoColorAt(long, 0L),
                gifFrameColor(fullStructure, fullFrames.first()))
            assertEquals("Full GIF must retain the source's final color segment",
                videoColorAt(long, lastSourceTimeUs), gifFrameColor(fullStructure, fullFrames.last()))
            assertTrue(long.isFile && source.isFile)
            assertEquals(sourceHash, hash(source))
            assertEquals(longHash, hash(long))
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun exportsFromMiddleToRealEndAndFinalTenthSecondWithTheActualTailFrames() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            // The final three 30 fps samples have unique yellow/magenta/cyan markers.
            // A frame from an earlier part of the tail cannot masquerade as the last source frame.
            val source = distinctTailSource(directory)
            val sourceHash = hash(source)
            val converter = VideoGifConverter()
            val sourceDuration = converter.readDurationMs(source)
            assertTrue("All source segments including the final 100 ms must be present", abs(sourceDuration - 2_000L) <= 5L)
            assertEquals("The AVC track itself must retain the complete two-second fixture", 2_000_000L, videoTrackDurationUs(source))
            val sampleTimes = videoSampleTimesUs(source)
            assertEquals("The source fixture must contain all 60 real video samples", 60, sampleTimes.size)
            assertEquals(0L, sampleTimes.first())
            assertTrue("A gap in the source must not be hidden by closest-frame sampling",
                sampleTimes.zipWithNext().all { (first, next) -> next - first in 32_000L..35_000L })
            assertTrue("The source's last encoded frame must reach the real end",
                sourceDuration * 1000L - sampleTimes.last() in 1L..50_000L)
            assertTrue(abs(sampleTimes[57] - 1_900_000L) <= 1L)
            assertTrue(abs(sampleTimes[58] - 1_933_333L) <= 1L)
            assertTrue(abs(sampleTimes[59] - 1_966_667L) <= 1L)
            assertEquals("red", videoColorAt(source, 0L))
            assertEquals("green", videoColorAt(source, 600_000L))
            assertEquals("blue", videoColorAt(source, 1_250_000L))
            assertEquals("yellow", videoColorAt(source, sampleTimes[57]))
            assertEquals("magenta", videoColorAt(source, sampleTimes[58]))
            val sourceLastColor = videoColorAt(source, sampleTimes.last())
            assertEquals("cyan", sourceLastColor)

            val startMs = 600L
            val remainingMs = sourceDuration - startMs
            val remainder = converter.convert(source, File(directory, "middle_to_end.gif"), startMs, remainingMs) { _, _ -> }
            val remainderBytes = remainder.readBytes()
            assertEquals(0x3b, remainderBytes.last().toInt() and 255)
            val remainderStructure = frames(remainderBytes)
            assertTrue(remainderStructure.frames.size >= 42)
            assertTrue(remainderStructure.frames.all { it.delayCs > 0 })
            assertTrue("The GIF must last exactly the selected remainder within GIF's 10 ms precision",
                abs(remainderStructure.frames.sumOf { it.delayCs.toLong() } * 10L - remainingMs) <= 5L)
            // Decode the actual GIF image blocks rather than using a nearest-frame video query.
            val remainderColors = remainderStructure.frames.map { gifFrameColor(remainderStructure, it) }
            val segments = remainderColors.fold(mutableListOf<String>()) { result, color ->
                if (result.lastOrNull() != color) result += color
                result
            }
            assertEquals("The selected middle must continue through the original ending without looping from zero",
                listOf("green", "blue", "yellow", "magenta", "cyan"), segments)
            assertEquals(videoColorAt(source, startMs * 1000L), remainderColors.first())
            assertEquals("The final GIF image must correspond to the source's final encoded frame",
                sourceLastColor, remainderColors.last())
            assertFalse("The nonzero selection must not include the source beginning", "red" in remainderColors)

            val tailStartMs = sourceDuration - 100L
            val tail = converter.convert(source, File(directory, "last_tenth_second.gif"), tailStartMs, 100L) { _, _ -> }
            val tailBytes = tail.readBytes()
            assertEquals(0x3b, tailBytes.last().toInt() and 255)
            val tailStructure = frames(tailBytes)
            assertEquals(3, tailStructure.frames.size)
            assertEquals(10, tailStructure.frames.sumOf { it.delayCs })
            assertTrue(tailStructure.frames.all { it.delayCs > 0 })
            val tailColors = tailStructure.frames.map { gifFrameColor(tailStructure, it) }
            assertEquals(videoColorAt(source, tailStartMs * 1000L), tailColors.first())
            assertEquals("The last 0.1 seconds must preserve the source's distinct final three frames in order",
                listOf("yellow", "magenta", "cyan"), tailColors)
            assertEquals("The 0.1-second export must include the source's last real frame", sourceLastColor, tailColors.last())
            assertEquals(sourceHash, hash(source))
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Suppress("DEPRECATION")
    @Test fun galleryOverridesTrimBeginningOrLoopTheActualVideoFrames() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val source = source(directory)
            val sourceHash = hash(source)
            val converter = VideoGifConverter()
            val sourceDuration = converter.readDurationMs(source)
            val trimmed = converter.convertLooped(source, File(directory, "trimmed.gif"), 100L) { _, _ -> }
            assertEquals(10, frames(trimmed.readBytes()).frames.sumOf { it.delayCs })
            val looped = converter.convertLooped(source, File(directory, "looped.gif"), sourceDuration * 2L) { _, _ -> }
            assertTrue(abs(frames(looped.readBytes()).frames.sumOf { it.delayCs.toLong() } * 10L - sourceDuration * 2L) <= 5L)
            val movie = checkNotNull(Movie.decodeFile(looped.absolutePath))
            val bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
            try {
                fun color(time: Long): Int {
                    bitmap.eraseColor(Color.BLACK)
                    movie.setTime(time.toInt())
                    movie.draw(Canvas(bitmap), 0f, 0f)
                    return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                }
                val first = color(500L)
                val repeated = color(sourceDuration + 500L)
                val repeatedBlue = color(sourceDuration + 1_500L)
                assertTrue(Color.red(first) > 200 && Color.blue(first) < 60)
                assertTrue(Color.red(repeated) > 200 && Color.blue(repeated) < 60)
                assertTrue("Looped tail must be the clip's blue segment, not a frozen final frame", Color.blue(repeatedBlue) > 200 && Color.red(repeatedBlue) < 60)
            } finally { bitmap.recycle() }
            assertEquals(sourceHash, hash(source))
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun neverOverwritesExistingDestinationOrSourceAndCleansInvalidInput() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val invalid = File(directory, "invalid.mp4").apply { writeText("not a video") }
            val existing = File(directory, "existing.gif").apply { writeText("preserve existing data") }
            val converter = VideoGifConverter()
            assertTrue(runCatching { converter.convert(invalid, existing, 0, 250) { _, _ -> } }.isFailure)
            assertEquals("preserve existing data", existing.readText())
            assertTrue(runCatching { converter.convert(invalid, invalid, 0, 250) { _, _ -> } }.isFailure)
            assertEquals("not a video", invalid.readText())
            val destination = File(directory, "failed.gif")
            assertTrue(runCatching { converter.convert(invalid, destination, 0, 250) { _, _ -> } }.isFailure)
            assertFalse(destination.exists())
            assertEquals("not a video", invalid.readText())
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun rejectsOneRealVideoSampleDespiteUsableDurationAndPreservesAllInputs() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val fullVideo = source(directory)
            val source = remuxOneSample(fullVideo, File(directory, "one_sample.mp4"))
            val sourceHash = hash(source)
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(source.absolutePath)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 250L)
                val frame = metadata.getFrameAtTime(0L)
                assertTrue("Fixture must have a genuine decodable video frame", frame != null)
                frame?.recycle()
            } finally { metadata.release() }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(source.absolutePath)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
                }
                extractor.selectTrack(track)
                var validSamples = 0
                do { if (extractor.sampleTrackIndex == track && extractor.sampleSize > 0) validSamples++ } while (extractor.advance())
                assertEquals("Fixture must contain exactly one nonempty encoded sample", 1, validSamples)
            } finally { extractor.release() }

            val converter = VideoGifConverter()
            val durationFailure = runCatching { converter.readDurationMs(source) }.exceptionOrNull()
            assertTrue(durationFailure is IllegalArgumentException)
            assertTrue(durationFailure?.message.orEmpty().contains("不足两帧"))
            val destination = File(directory, "should_not_publish.gif")
            val conversionFailure = runCatching { converter.convert(source, destination, 0, 250) { _, _ -> } }.exceptionOrNull()
            assertTrue(conversionFailure is IllegalArgumentException)
            assertTrue(conversionFailure?.message.orEmpty().contains("不足两帧"))
            assertFalse("A single video sample must not publish a repeated-cover GIF", destination.exists())
            val existing = File(directory, "preserved.gif").apply { writeText("keep this existing file") }
            assertTrue(runCatching { converter.convert(source, existing, 0, 250) { _, _ -> } }.isFailure)
            assertEquals("keep this existing file", existing.readText())
            assertEquals(sourceHash, hash(source))
        } finally { directory.deleteRecursively() }
        Unit
    }

    private suspend fun source(directory: File): File {
        // Fixed original AVC/AAC input; GIF/remux operations still run their production paths.
        return File(directory, "fixed_red_blue_2s.mp4").also { output ->
            instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
            assertEquals("Controlled motion fixture differs from its independent manifest",
                "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(output))
        }
    }
    private suspend fun distinctTailSource(directory: File): File {
        val animation = File(directory, "unique_tail_source.gif")
        animation.outputStream().buffered().use { output ->
            val encoder = GifEncoder(output, 128, 96)
            listOf(Color.RED to 50, Color.GREEN to 50, Color.BLUE to 90,
                Color.YELLOW to 3, Color.MAGENTA to 3, Color.CYAN to 4).forEach { (color, delayCs) ->
                encoder.addFrame(IntArray(128 * 96) { color }, delayCs)
            }
            encoder.finish()
        }
        val animationHash = hash(animation)
        val image = AlbumMediaValidation.image(animation)
        assertTrue(image.animated)
        AnimatedImageFrames(image).use { original ->
            assertEquals(6, original.frameCount)
            assertEquals(2_000L, original.durationMs)
            assertEquals(Color.YELLOW, original.frameAt(1_900L).getPixel(64, 48))
            assertEquals(Color.MAGENTA, original.frameAt(1_930L).getPixel(64, 48))
            assertEquals(Color.CYAN, original.frameAt(1_960L).getPixel(64, 48))
        }
        return AnimatedImageVideoConverter(context).convert(image, File(directory, "unique_tail_source.mp4"))
            .also { assertEquals("Creating the video fixture must retain its original animation", animationHash, hash(animation)) }
    }
    private fun videoTrackDurationUs(source: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            return (0 until extractor.trackCount).map(extractor::getTrackFormat).first {
                it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }.getLong(MediaFormat.KEY_DURATION)
        } finally { extractor.release() }
    }
    private fun videoSampleTimesUs(source: File): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            extractor.selectTrack(track)
            val result = mutableListOf<Long>()
            while (extractor.sampleTrackIndex == track) {
                assertTrue("The video fixture contains an empty encoded sample", extractor.sampleSize > 0L)
                result += extractor.sampleTime
                if (!extractor.advance()) break
            }
            assertTrue("A video fixture must have at least two genuine samples", result.size >= 2)
            return result
        } finally { extractor.release() }
    }
    private fun videoColorAt(source: File, timestampUs: Long): String {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = checkNotNull(retriever.getScaledFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST, 32, 32))
            try { return markerColor(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
            finally { bitmap.recycle() }
        } finally { retriever.release() }
    }
    private fun gifFrameColor(structure: GifStructure, frame: EncodedFrame): String {
        val single = structure.header + frame.graphicControl + frame.imageBlock + byteArrayOf(0x3b)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(single))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try { return markerColor(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
        finally { bitmap.recycle() }
    }
    private fun decodeEverySharingFrame(file: File, structure: GifStructure, width: Int, height: Int): List<String> {
        val bytes = file.readBytes()
        assertEquals(0x3b, bytes.last().toInt() and 255)
        assertTrue("GIF must request an infinite animation loop",
            bytes.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0\u0003\u0001\u0000\u0000\u0000"))
        var animated = false
        val full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            animated = info.isAnimated
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            assertTrue("Android did not recognize the complete GIF as animated", animated)
            assertEquals(width, full.width)
            assertEquals(height, full.height)
        } finally { full.recycle() }
        return structure.frames.mapIndexed { index, frame ->
            val single = structure.header + frame.graphicControl + frame.imageBlock + byteArrayOf(0x3b)
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(single))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                assertEquals("Decoded GIF frame $index width", width, bitmap.width)
                assertEquals("Decoded GIF frame $index height", height, bitmap.height)
                assertEquals("Original pillarbox geometry must remain black", Color.BLACK, bitmap.getPixel(width / 16, height / 2))
                fixedFixtureColor(bitmap.getPixel(width / 2, height / 2))
            } finally { bitmap.recycle() }
        }
    }
    private fun cleanupSharingFixture(directory: File) {
        // Opt-in retention contains only these synthetic files; normal runs remove their private cache.
        if (InstrumentationRegistry.getArguments().getString("capture_gif_sharing") != "true") directory.deleteRecursively()
    }
    private fun fixedFixtureVideoColorAt(source: File, timestampUs: Long): String {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val bitmap = checkNotNull(retriever.getScaledFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST, 32, 32))
            try { return fixedFixtureColor(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
            finally { bitmap.recycle() }
        } finally { retriever.release() }
    }
    private fun fixedFixtureColor(color: Int): String {
        // This fixed asset encodes (220,40,40)/(40,60,220), not pure RGB primary colors.
        // The independently decoded MuMu GIF/source blue is (40,60,219); green<60
        // would misclassify that correct blue. Six levels allow AVC/YUV rounding only.
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val isRed = abs(red - 220) <= 6 && abs(green - 40) <= 6 && abs(blue - 40) <= 6
        val isBlue = abs(red - 40) <= 6 && abs(green - 60) <= 6 && abs(blue - 220) <= 6
        assertEquals("Controlled fixture frame must stay opaque", 255, Color.alpha(color))
        assertTrue("Decoded fixed fixture RGB ($red,$green,$blue) differs from its red/blue source contract", isRed || isBlue)
        return if (isRed) "red" else "blue"
    }
    private fun markerColor(color: Int): String = when {
        Color.red(color) > 180 && Color.green(color) > 180 && Color.blue(color) < 60 -> "yellow"
        Color.red(color) > 180 && Color.blue(color) > 180 && Color.green(color) < 60 -> "magenta"
        Color.green(color) > 180 && Color.blue(color) > 180 && Color.red(color) < 60 -> "cyan"
        Color.red(color) > 180 && Color.green(color) < 60 && Color.blue(color) < 60 -> "red"
        Color.green(color) > 180 && Color.red(color) < 60 && Color.blue(color) < 60 -> "green"
        Color.blue(color) > 180 && Color.red(color) < 60 && Color.green(color) < 60 -> "blue"
        else -> "other"
    }
    private fun fixture(name: String, directory: File): File = File(directory, name).also { file ->
        instrumentation.context.assets.open("album_test/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun directory(): File = File(context.cacheDir, "gif_converter_validation_${UUID.randomUUID()}").also { check(it.mkdir()) }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun remuxRotation(source: File, output: File): File {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            extractor.selectTrack(track)
            val destination = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.setOrientationHint(90)
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val information = MediaCodec.BufferInfo()
            while (true) {
                val bytes = extractor.readSampleData(buffer, 0)
                if (bytes < 0) break
                information.set(0, bytes, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(destination, buffer, information)
                extractor.advance()
            }
            return output
        } finally {
            extractor.release()
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
    }

    private fun remuxOneSample(source: File, output: File): File {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            extractor.selectTrack(track)
            val destination = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val size = extractor.readSampleData(buffer, 0)
            require(size > 0)
            val information = MediaCodec.BufferInfo().apply { set(0, size, 0L, extractor.sampleFlags) }
            muxer.writeSampleData(destination, buffer, information)
            // The documented zero-size EOS buffer gives the one real sample a one-second duration.
            // It contributes timing, not another encoded picture.
            information.set(0, 0, 1_000_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(destination, ByteBuffer.allocate(0), information)
            return output
        } finally {
            extractor.release()
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
    }

    private fun remuxLoops(source: File, output: File, desiredDurationUs: Long): File {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            val format = extractor.getTrackFormat(track)
            val cycleUs = format.getLong(MediaFormat.KEY_DURATION)
            val destination = muxer.addTrack(format)
            extractor.selectTrack(track)
            muxer.start(); started = true
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val information = MediaCodec.BufferInfo()
            var base = 0L
            while (base < desiredDurationUs) {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (extractor.sampleTrackIndex == track && base + extractor.sampleTime < desiredDurationUs) {
                    val bytes = extractor.readSampleData(buffer, 0)
                    if (bytes < 0) break
                    information.set(0, bytes, base + extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(destination, buffer, information)
                    if (!extractor.advance()) break
                }
                base += cycleUs
            }
            return output
        } finally {
            extractor.release()
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
    }

    private data class EncodedFrame(val delayCs: Int, val graphicControl: ByteArray, val imageBlock: ByteArray)
    private data class GifStructure(val header: ByteArray, val frames: List<EncodedFrame>)
    private fun frames(data: ByteArray): GifStructure {
        assertEquals("GIF89a", data.copyOfRange(0, 6).toString(Charsets.US_ASCII))
        val packed = data[10].toInt() and 255
        val headerEnd = 13 + if (packed and 128 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0
        var position = headerEnd
        var control = ByteArray(0)
        var delay = 0
        val result = mutableListOf<EncodedFrame>()
        fun subBlocks() {
            while (true) {
                val bytes = data[position++].toInt() and 255
                if (bytes == 0) break
                position += bytes
                require(position <= data.size)
            }
        }
        while (position < data.size) {
            when (data[position].toInt() and 255) {
                0x21 -> {
                    val start = position
                    val type = data[position + 1].toInt() and 255
                    position += 2
                    if (type == 0xf9) {
                        assertEquals(4, data[position].toInt() and 255)
                        delay = (data[position + 2].toInt() and 255) or ((data[position + 3].toInt() and 255) shl 8)
                    }
                    subBlocks()
                    if (type == 0xf9) control = data.copyOfRange(start, position)
                }
                0x2c -> {
                    val start = position
                    val imagePacked = data[position + 9].toInt() and 255
                    position += 10
                    if (imagePacked and 128 != 0) position += 3 * (1 shl ((imagePacked and 7) + 1))
                    position++ // Minimum LZW code size.
                    subBlocks()
                    result += EncodedFrame(delay, control, data.copyOfRange(start, position))
                }
                0x3b -> break
                else -> error("Invalid generated GIF structure")
            }
        }
        return GifStructure(data.copyOfRange(0, headerEnd), result)
    }
}
