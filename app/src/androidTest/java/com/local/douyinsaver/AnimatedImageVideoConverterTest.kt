package com.local.douyinsaver

import android.graphics.Color
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.abs

/** Original self-made red/blue fixtures; all sources and exports are private temporary files. */
@RunWith(AndroidJUnit4::class)
class AnimatedImageVideoConverterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun readsActualGifWebpAndApngTimelinesAndFrameOrderWithoutClockCapture() = isolated { directory ->
        for (name in listOf("two_frames.gif", "two_frames.webp", "two_frames.png")) {
            val image = fixture(name, directory)
            if (name.endsWith(".gif")) {
                val blocks = GifAnimationInspector.frames(image.file.readBytes())
                assertEquals(listOf(250L, 250L), blocks.map { it.durationMs })
                assertEquals(Color.RED, blocks[0].pixel(24, 24))
                assertEquals(Color.BLUE, blocks[1].pixel(24, 24))
            }
            AnimatedImageFrames(image).use { animation ->
                assertEquals(name, 500L, animation.durationMs)
                assertEquals(name, 2, animation.frameCount)
                assertEquals(48, animation.width); assertEquals(48, animation.height)
                assertEquals("$name first frame", Color.RED, animation.frameAt(0L).getPixel(24, 24))
                assertEquals("$name first duration", Color.RED, animation.frameAt(249L).getPixel(24, 24))
                assertEquals("$name second frame", Color.BLUE, animation.frameAt(250L).getPixel(24, 24))
                assertEquals("$name second duration", Color.BLUE, animation.frameAt(499L).getPixel(24, 24))
            }
        }
    }

    @Test fun gifFramesSwitchAtExactShortDelayBoundariesWithoutMovieNormalization() = isolated { directory ->
        val file = File(directory, "ten_ms.gif")
        file.outputStream().use { output ->
            val encoder = GifEncoder(output, 4, 4)
            encoder.addFrame(IntArray(16) { Color.RED }, 1)
            encoder.addFrame(IntArray(16) { Color.BLUE }, 1)
            encoder.finish()
        }
        val image = AlbumMediaValidation.image(file)
        val originalHash = hash(file)
        assertEquals(listOf(10L, 10L), GifAnimationInspector.frames(file.readBytes()).map { it.durationMs })
        AnimatedImageFrames(image).use { frames ->
            assertEquals(20L, frames.durationMs)
            assertEquals(Color.RED, frames.frameAt(0L).getPixel(2, 2))
            assertEquals(Color.RED, frames.frameAt(9L).getPixel(2, 2))
            assertEquals(Color.BLUE, frames.frameAt(10L).getPixel(2, 2))
            assertEquals(Color.BLUE, frames.frameAt(19L).getPixel(2, 2))
        }
        assertEquals(originalHash, hash(file))
    }

    @Test fun apngFractionalDelaysUseCumulativeBoundariesWithoutTimelineDrift() = isolated { directory ->
        val image = fractionalApng(directory, 300, 1, 30)
        val originalHash = hash(image.file)
        AnimatedImageFrames(image).use { frames ->
            assertEquals("300 frames at 1/30 second must keep the full ten seconds", 10_000L, frames.durationMs)
            assertEquals(300, frames.frameCount)
            assertEquals(Color.RED, frames.frameAt(0L).getPixel(1, 1))
            assertEquals(Color.BLUE, frames.frameAt(90L).getPixel(1, 1))
            assertEquals("The fourth frame must not start at the old rounded boundary of 99 ms", Color.BLUE,
                frames.frameAt(99L).getPixel(1, 1))
            assertEquals(Color.YELLOW, frames.frameAt(100L).getPixel(1, 1))
            assertEquals(Color.YELLOW, frames.frameAt(9_999L).getPixel(1, 1))
        }
        assertEquals(originalHash, hash(image.file))
    }

    @Test fun apngPositiveSubMillisecondDelaysAccumulateWithoutPerFrameMinimum() = isolated { directory ->
        val image = fractionalApng(directory, 200, 1, 2_000)
        AnimatedImageFrames(image).use { frames ->
            assertEquals("200 half-millisecond frames must not become 200 milliseconds", 100L, frames.durationMs)
            assertEquals(200, frames.frameCount)
            assertEquals(Color.RED, frames.frameAt(0L).getPixel(1, 1))
            assertEquals("Instantaneous rounded frames must be applied in order", Color.BLUE,
                frames.frameAt(1L).getPixel(1, 1))
            assertEquals(Color.BLUE, frames.frameAt(99L).getPixel(1, 1))
        }
    }

    @Test fun apngZeroNumeratorKeepsExplicitMinimumWhileZeroDenominatorUsesHundredths() = isolated { directory ->
        AnimatedImageFrames(fractionalApng(directory, 2, 0, 30)).use { frames ->
            assertEquals(2L, frames.durationMs)
            assertEquals(Color.RED, frames.frameAt(0L).getPixel(1, 1))
            assertEquals(Color.GREEN, frames.frameAt(1L).getPixel(1, 1))
        }
        AnimatedImageFrames(fractionalApng(directory, 2, 1, 0)).use { frames ->
            assertEquals(20L, frames.durationMs)
            assertEquals(Color.RED, frames.frameAt(9L).getPixel(1, 1))
            assertEquals(Color.GREEN, frames.frameAt(10L).getPixel(1, 1))
        }
    }

    @Test fun gifSubframesKeepTransparencyOffsetsAndRestoreBackgroundOrPreviousPixels() = isolated { directory ->
        for (disposal in listOf(2, 3)) {
            val file = partialGif(directory, disposal)
            val originalHash = hash(file)
            AnimatedImageFrames(AlbumMediaValidation.image(file)).use { frames ->
                assertEquals(300L, frames.durationMs)
                assertEquals(3, frames.frameCount)
                assertEquals(Color.RED, frames.frameAt(99L).getPixel(0, 0))
                val bluePatch = frames.frameAt(100L)
                assertEquals(Color.BLUE, bluePatch.getPixel(0, 0))
                assertEquals("Transparent patch pixels must retain the previous frame", Color.RED, bluePatch.getPixel(1, 1))
                assertEquals("A patch must not replace the rest of the logical screen", Color.RED, bluePatch.getPixel(3, 3))
                val greenPatch = frames.frameAt(200L)
                assertEquals("GIF disposal $disposal was ignored", if (disposal == 2) Color.TRANSPARENT else Color.RED,
                    greenPatch.getPixel(0, 0))
                assertEquals("GIF patch offset was ignored", Color.GREEN, greenPatch.getPixel(3, 3))
                assertEquals(Color.GREEN, frames.frameAt(299L).getPixel(3, 3))
            }
            assertEquals(originalHash, hash(file))
        }
    }

    @Test fun convertsEveryNativeAnimationToDecodableSilentMp4AndKeepsSourceBytes() = isolated { directory ->
        for (name in listOf("two_frames.gif", "two_frames.webp", "two_frames.png")) {
            val image = fixture(name, directory)
            val sourceHash = hash(image.file)
            val converter = AnimatedImageVideoConverter(context)
            assertEquals(500L, converter.durationMs(image))
            val output = converter.convert(image, File(directory, "$name.mp4"))
            assertEquals(sourceHash, hash(image.file))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals("$name must have one silent video track", 1, extractor.trackCount)
                assertTrue(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/"))
                assertColorMetadata(extractor.getTrackFormat(0))
                extractor.selectTrack(0)
                var count = 0
                do { if (extractor.sampleTrackIndex == 0 && extractor.sampleSize > 0L) count++ } while (extractor.advance())
                assertTrue("$name lost dynamic encoded frames", count >= 15)
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                assertEquals(48, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt())
                assertEquals(48, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt())
                assertTrue("$name changed its timeline", abs(500L - retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()) <= 20L)
                val colors = listOf(100_000L, 400_000L).map { time ->
                    val bitmap = checkNotNull(retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST))
                    try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) } finally { bitmap.recycle() }
                }
                assertTrue("$name has no actual red frame", Color.red(colors[0]) > 200 && Color.blue(colors[0]) < 60)
                assertTrue("$name has no actual blue frame", Color.blue(colors[1]) > 200 && Color.red(colors[1]) < 60)
            } finally { retriever.release() }
        }
    }

    @Test fun preservesHdAnimationGeometryAndDecodedColorsBeforeBgmComposition() = isolated { directory ->
        val image = hdApng(directory)
        val sourceHash = hash(image.file)
        AnimatedImageFrames(image).use { frames ->
            val picture = frames.frameAt(0L)
            assertEquals(1280, picture.width)
            assertEquals(720, picture.height)
            assertEquals(Color.RED, picture.getPixel(640, 360))
        }
        val output = AnimatedImageVideoConverter(context).convert(image, File(directory, "hd.mp4"))
        assertEquals(sourceHash, hash(image.file))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            assertColorMetadata(extractor.getTrackFormat(0))
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            assertEquals(1280, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt())
            assertEquals(720, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt())
            for ((timeUs, red) in listOf(100_000L to true, 750_000L to false)) {
                val bitmap = checkNotNull(retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 128, 128))
                try {
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    if (red) assertTrue("BT.601 red frame was shifted", Color.red(color) > 230 && Color.blue(color) < 30)
                    else assertTrue("BT.601 blue frame was shifted", Color.blue(color) > 230 && Color.red(color) < 30)
                } finally { bitmap.recycle() }
            }
        } finally { retriever.release() }
    }

    @Test fun cancellationDeletesOnlyItsPartialMp4AndRetainsAnimationSource() = isolated { directory ->
        val file = File(directory, "long_animation.gif")
        file.outputStream().use { stream ->
            val encoder = GifEncoder(stream, 48, 48)
            repeat(50) { encoder.addFrame(IntArray(48 * 48) { _ -> if (it % 2 == 0) Color.RED else Color.BLUE }, 100) }
            encoder.finish()
        }
        val image = AlbumMediaValidation.image(file)
        val sourceHash = hash(file)
        val output = File(directory, "cancelled.mp4")
        coroutineScope {
            val job = async(Dispatchers.IO) { AnimatedImageVideoConverter(context).convert(image, output) }
            withTimeout(10_000L) { while (!output.exists() && job.isActive) delay(10L) }
            assertTrue("Fixture completed before cancellation could be exercised", job.isActive)
            job.cancelAndJoin()
        }
        assertFalse("Cancelled native animation conversion left a partial MP4", output.exists())
        assertEquals(sourceHash, hash(file))
    }

    @Test fun rejectsStaticOrTruncatedContainersWithoutOverwritingExistingOutput() = isolated { directory ->
        val still = fixture("static_cover.png", directory)
        val existing = File(directory, "keep.mp4").apply { writeText("preserve original output") }
        assertTrue(runCatching { AnimatedImageVideoConverter(context).convert(still, existing) }.isFailure)
        assertEquals("preserve original output", existing.readText())
        val original = fixture("two_frames.webp", directory)
        val truncated = File(directory, "truncated.webp").apply { writeBytes(original.file.readBytes().dropLast(12).toByteArray()) }
        val output = File(directory, "invalid.mp4")
        val candidate = original.copy(file = truncated)
        assertTrue(runCatching { AnimatedImageVideoConverter(context).convert(candidate, output) }.isFailure)
        assertFalse(output.exists())
        assertTrue(original.file.isFile && truncated.isFile)
    }

    private fun isolated(body: suspend (File) -> Unit) = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "native_animation_${UUID.randomUUID()}").also { check(it.mkdir()) }
        try { body(directory) } finally { directory.deleteRecursively() }
        Unit
    }
    private fun fixture(name: String, directory: File): LocalAlbumImage {
        val file = File(directory, name)
        instrumentation.context.assets.open("dynamic_album_test/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return AlbumMediaValidation.image(file)
    }
    private fun assertColorMetadata(format: MediaFormat) {
        for ((key, expected) in listOf(MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT601_NTSC,
            MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED,
            MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO)) {
            assertTrue("Encoded MP4 lacks $key", format.containsKey(key))
            assertEquals("Encoded MP4 changed $key", expected, format.getInteger(key))
        }
    }

    /** Independent assembly of full and partial image blocks with explicit GIF89a controls. */
    private fun partialGif(directory: File, disposal: Int): File {
        fun frame(width: Int, height: Int, pixels: IntArray): GifAnimationInspector.Frame {
            val bytes = ByteArrayOutputStream()
            val encoder = GifEncoder(bytes, width, height)
            repeat(2) { encoder.addFrame(pixels, 10) }
            encoder.finish()
            return GifAnimationInspector.frames(bytes.toByteArray()).first()
        }
        val full = frame(4, 4, IntArray(16) { Color.RED })
        fun patch(source: GifAnimationInspector.Frame, left: Int, top: Int, dispose: Int, transparent: Boolean): ByteArray {
            // These self-made streams have a 13-byte logical screen and local palettes.
            return source.encoded.copyOfRange(13, source.encoded.lastIndex).apply {
                check((this[0].toInt() and 255) == 0x21 && (this[8].toInt() and 255) == 0x2c)
                this[3] = ((dispose shl 2) or (if (transparent) 1 else 0)).toByte()
                this[6] = 1 // Blue is palette entry zero; black is transparent entry one.
                this[9] = left.toByte(); this[10] = 0
                this[11] = top.toByte(); this[12] = 0
            }
        }
        val blue = frame(2, 2, IntArray(4) { if (it == 0) Color.BLUE else Color.BLACK })
        val green = frame(2, 2, IntArray(4) { Color.GREEN })
        return File(directory, "partial_disposal_$disposal.gif").apply {
            writeBytes(full.encoded.copyOfRange(0, 13) + patch(full, 0, 0, 1, false) +
                patch(blue, 0, 0, disposal, true) + patch(green, 2, 2, 1, false) + byteArrayOf(0x3b))
        }
    }

    /** Standard two-frame APNG assembled from independently compressed Android PNG images. */
    private fun hdApng(directory: File): LocalAlbumImage {
        fun png(color: Int): List<Pair<String, ByteArray>> {
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            try {
                bitmap.eraseColor(color)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } finally { bitmap.recycle() }
            return DataInputStream(ByteArrayInputStream(output.toByteArray())).use { input ->
                check(input.skipBytes(8) == 8)
                val chunks = mutableListOf<Pair<String, ByteArray>>()
                while (input.available() > 0) {
                    val count = input.readInt()
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    val data = ByteArray(count).also(input::readFully)
                    input.readInt()
                    chunks += type to data
                    if (type == "IEND") break
                }
                chunks
            }
        }
        val pictures = listOf(png(Color.RED), png(Color.BLUE))
        val file = File(directory, "hd_animation.png")
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                output.writeInt(data.size); output.write(typeBytes); output.write(data)
                output.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
            }
            fun payload(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().let { bytes ->
                DataOutputStream(bytes).use(write); bytes.toByteArray()
            }
            chunk("IHDR", pictures[0].first { it.first == "IHDR" }.second)
            chunk("acTL", payload { it.writeInt(2); it.writeInt(0) })
            var sequence = 0
            pictures.forEachIndexed { index, picture ->
                chunk("fcTL", payload { data ->
                    data.writeInt(sequence++); data.writeInt(1280); data.writeInt(720); data.writeInt(0); data.writeInt(0)
                    data.writeShort(1); data.writeShort(2); data.writeByte(0); data.writeByte(0)
                })
                picture.filter { it.first == "IDAT" }.forEach { (_, data) ->
                    if (index == 0) chunk("IDAT", data)
                    else chunk("fdAT", payload { it.writeInt(sequence++); it.write(data) })
                }
            }
            chunk("IEND", byteArrayOf())
        }
        return AlbumMediaValidation.image(file)
    }

    /** Independent PNG blocks and explicit fcTL fractions form a small, controlled APNG. */
    private fun fractionalApng(directory: File, count: Int, numerator: Int, denominator: Int): LocalAlbumImage {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val pictures = colors.map { color ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            try {
                bitmap.eraseColor(color)
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            } finally { bitmap.recycle() }
            DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
                check(input.skipBytes(8) == 8)
                val chunks = mutableListOf<Pair<String, ByteArray>>()
                while (input.available() > 0) {
                    val size = input.readInt()
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    val data = ByteArray(size).also(input::readFully)
                    input.readInt()
                    chunks += type to data
                    if (type == "IEND") break
                }
                chunks
            }
        }
        val file = File(directory, "fraction_${count}_${numerator}_${denominator}.png")
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                output.writeInt(data.size); output.write(typeBytes); output.write(data)
                output.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
            }
            fun payload(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().let { bytes ->
                DataOutputStream(bytes).use(write); bytes.toByteArray()
            }
            chunk("IHDR", pictures[0].first { it.first == "IHDR" }.second)
            pictures[0].filter { it.first in listOf("PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP") }
                .forEach { (type, data) -> chunk(type, data) }
            chunk("acTL", payload { it.writeInt(count); it.writeInt(0) })
            var sequence = 0
            repeat(count) { index ->
                chunk("fcTL", payload { data ->
                    data.writeInt(sequence++); data.writeInt(2); data.writeInt(2); data.writeInt(0); data.writeInt(0)
                    data.writeShort(numerator); data.writeShort(denominator); data.writeByte(0); data.writeByte(0)
                })
                pictures[index % pictures.size].filter { it.first == "IDAT" }.forEach { (_, data) ->
                    if (index == 0) chunk("IDAT", data)
                    else chunk("fdAT", payload { it.writeInt(sequence++); it.write(data) })
                }
            }
            chunk("IEND", byteArrayOf())
        }
        return AlbumMediaValidation.image(file)
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
