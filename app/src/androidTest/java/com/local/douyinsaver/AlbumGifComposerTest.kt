package com.local.douyinsaver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Self-made colors and private temporary files only; no preferences or user's gallery. */
@RunWith(AndroidJUnit4::class)
class AlbumGifComposerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportsTwoStaticImagesAtOneTenthSecondWithCorrectOrderAndAspectRatio() = isolated { directory ->
        val images = listOf(image(directory, "wide_red.png", 8, 4, Color.RED),
            image(directory, "tall_blue.png", 4, 8, Color.BLUE))
        val hashes = images.map { hash(it.file) }
        val progress = mutableListOf<Long>()
        val output = AlbumGifComposer().compose(images, 100L, File(directory, "sequence.gif")) { done, total ->
            assertEquals(2L, total)
            progress += done
        }
        assertEquals(listOf(0L, 1L, 2L), progress)
        assertEquals(hashes, images.map { hash(it.file) })
        val verified = AlbumMediaValidation.image(output)
        assertTrue(verified.animated)
        assertEquals("image/gif", verified.mimeType)
        assertEquals(8, verified.width)
        assertEquals(4, verified.height)
        assertEquals(200L, GifAnimationInspector.durationMs(output))
        val blocks = GifAnimationInspector.frames(output.readBytes())
        assertEquals(listOf(100L, 100L), blocks.map { it.durationMs })
        assertEquals(Color.RED, blocks[0].pixel(4, 2))
        assertEquals(Color.BLUE, blocks[1].pixel(4, 2))
        assertEquals("Portrait source must be letterboxed instead of stretched", Color.BLACK, blocks[1].pixel(0, 2))
        // Movie uses an inclusive previous-frame boundary. Compatibility playback
        // checks sample interiors; the source decoder must still switch exactly at 100 ms.
        val movie = movie(output)
        assertEquals(Color.RED, center(movie, 50))
        assertEquals(Color.BLUE, center(movie, 150))
        AnimatedImageFrames(verified).use { animation ->
            assertEquals(Color.RED, animation.frameAt(99L).getPixel(4, 2))
            assertEquals(Color.BLUE, animation.frameAt(100L).getPixel(4, 2))
            assertEquals(Color.BLUE, animation.frameAt(199L).getPixel(4, 2))
        }
    }

    @Test fun retainsDifferentPerImageTimingsAndDoesNotOverwriteExistingOutput() = isolated { directory ->
        val images = listOf(image(directory, "red.png", 4, 4, Color.RED), image(directory, "blue.png", 4, 4, Color.BLUE))
        val output = AlbumGifComposer().compose(images, listOf(100L, 300L), File(directory, "custom.gif")) { _, _ -> }
        assertEquals(400L, GifAnimationInspector.durationMs(output))
        val preserved = File(directory, "preserved.gif").apply { writeText("preserve original bytes") }
        assertTrue(runCatching { AlbumGifComposer().compose(images, 100L, preserved) { _, _ -> } }.isFailure)
        assertEquals("preserve original bytes", preserved.readText())
    }

    @Test fun cancellationRemovesOnlyItsPartialOutputAndRetainsBothSources() = isolated { directory ->
        val images = listOf(image(directory, "red.png", 4, 4, Color.RED), image(directory, "blue.png", 4, 4, Color.BLUE))
        val hashes = images.map { hash(it.file) }
        val output = File(directory, "cancel.gif")
        try {
            AlbumGifComposer().compose(images, 100L, output) { done, _ ->
                if (done == 1L) throw CancellationException("Cancel this private sequence")
            }
            fail("GIF sequence did not cancel")
        } catch (_: CancellationException) { }
        assertFalse(output.exists())
        assertEquals(hashes, images.map { hash(it.file) })
    }

    @Test fun rejectsAnimatedSourcesAndOneStillWithoutCreatingFakeAnimation() = isolated { directory ->
        val images = listOf(image(directory, "red.png", 4, 4, Color.RED), image(directory, "blue.png", 4, 4, Color.BLUE))
        val animated = AlbumGifComposer().compose(images, 100L, File(directory, "real.gif")) { _, _ -> }
        val destination = File(directory, "bad.gif")
        assertTrue(runCatching { AlbumGifComposer().compose(images.take(1), 100L, destination) { _, _ -> } }.isFailure)
        assertFalse(destination.exists())
        assertTrue(runCatching { AlbumGifComposer().compose(listOf(AlbumMediaValidation.image(animated), images[0]),
            100L, destination) { _, _ -> } }.isFailure)
        assertFalse(destination.exists())
        assertTrue(animated.isFile)
    }

    private fun isolated(body: suspend (File) -> Unit) = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "static_gif_${UUID.randomUUID()}").also { check(it.mkdir()) }
        try { body(directory) } finally { directory.deleteRecursively() }
        Unit
    }
    private fun image(directory: File, name: String, width: Int, height: Int, color: Int): LocalAlbumImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File(directory, name)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return AlbumMediaValidation.image(file)
    }
    @Suppress("DEPRECATION")
    private fun movie(file: File): Movie = checkNotNull(Movie.decodeFile(file.absolutePath))
    @Suppress("DEPRECATION")
    private fun center(movie: Movie, milliseconds: Int): Int {
        val bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        return try {
            movie.setTime(milliseconds)
            movie.draw(Canvas(bitmap), 0f, 0f)
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally { bitmap.recycle() }
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

/** Independent block inspection validates timing rather than trusting converter bookkeeping. */
internal object GifAnimationInspector {
    data class Frame(val durationMs: Long, val encoded: ByteArray) {
        fun pixel(x: Int, y: Int): Int {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return try { bitmap.getPixel(x, y) } finally { bitmap.recycle() }
        }
    }

    /** Independent byte-level frame reconstruction, unrelated to the production decoder's metadata. */
    fun frames(data: ByteArray): List<Frame> {
        check(data.copyOfRange(0, 6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a"))
        val packed = data[10].toInt() and 255
        val headerEnd = 13 + if (packed and 128 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0
        val header = data.copyOfRange(0, headerEnd)
        var position = headerEnd; var delay = 0L; var control = byteArrayOf()
        val result = mutableListOf<Frame>()
        fun byte(): Int = (data[position++].toInt() and 255)
        fun blocks() { while (true) { val size = byte(); if (size == 0) break; position += size; check(position <= data.size) } }
        while (position < data.size) when (data[position].toInt() and 255) {
            0x21 -> {
                val start = position; position++
                val type = byte()
                if (type == 0xf9) delay = ((data[position + 2].toInt() and 255) or
                    ((data[position + 3].toInt() and 255) shl 8)) * 10L
                blocks()
                if (type == 0xf9) control = data.copyOfRange(start, position)
            }
            0x2c -> {
                val start = position; val local = data[position + 9].toInt() and 255; position += 10
                if (local and 128 != 0) position += 3 * (1 shl ((local and 7) + 1))
                byte(); blocks()
                result += Frame(delay, header + control + data.copyOfRange(start, position) + byteArrayOf(0x3b))
                delay = 0L; control = byteArrayOf()
            }
            0x3b -> return result
            else -> error("Invalid GIF block")
        }
        error("GIF trailer missing")
    }

    fun durationMs(file: File): Long = file.inputStream().buffered().use { input ->
        fun byte(): Int = input.read().also { check(it >= 0) }
        fun skip(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped == 0L) { byte(); remaining-- } else remaining -= skipped
            }
        }
        fun subBlocks() { while (true) { val count = byte(); if (count == 0) break; skip(count.toLong()) } }
        val header = ByteArray(13)
        check(input.read(header) == 13)
        check(header.copyOfRange(0, 6).toString(Charsets.US_ASCII).startsWith("GIF8"))
        val packed = header[10].toInt() and 255
        if (packed and 128 != 0) skip((3 * (1 shl ((packed and 7) + 1))).toLong())
        var duration = 0L
        var delay = 0L
        while (true) when (byte()) {
            0x21 -> {
                if (byte() == 0xf9) {
                    check(byte() == 4)
                    byte()
                    delay = byte().toLong() or (byte().toLong() shl 8)
                    byte(); check(byte() == 0)
                } else subBlocks()
            }
            0x2c -> {
                skip(8L)
                val local = byte()
                if (local and 128 != 0) skip((3 * (1 shl ((local and 7) + 1))).toLong())
                byte(); subBlocks()
                duration += delay * 10L
            }
            0x3b -> return@use duration
            else -> error("Invalid GIF block")
        }
        @Suppress("UNREACHABLE_CODE") 0L
    }
}
