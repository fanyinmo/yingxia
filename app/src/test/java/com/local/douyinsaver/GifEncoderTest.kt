package com.local.douyinsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.Random
import org.w3c.dom.Node

/** The JDK's independent GIF reader validates every compressed frame, including LZW width changes. */
class GifEncoderTest {
    @Test fun writesLoopingTrueRedAndBlueFramesWithExactDelays() {
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 16, 12)
        encoder.addFrame(IntArray(16 * 12) { 0xffff0000.toInt() }, 13)
        encoder.addFrame(IntArray(16 * 12) { 0xff0000ff.toInt() }, 12)
        encoder.finish()
        val data = output.toByteArray()
        assertEquals("GIF89a", data.copyOfRange(0, 6).toString(Charsets.US_ASCII))
        assertEquals(0x3b, data.last().toInt() and 255)
        assertTrue(data.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertEquals(2, encoder.frameCount)
        assertEquals(25, encoder.durationCentiseconds)
        assertEquals(data.size.toLong(), encoder.bytesWritten)
        withReader(data) { reader ->
            assertEquals(2, reader.getNumImages(true))
            assertEquals(0xffff0000.toInt(), reader.read(0).getRGB(8, 6))
            assertEquals(0xff0000ff.toInt(), reader.read(1).getRGB(8, 6))
            listOf(13, 12).forEachIndexed { index, delay ->
                val metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                val controls = (0 until metadata.childNodes.length).map { metadata.childNodes.item(it) }
                    .first { it.nodeName == "GraphicControlExtension" }
                assertEquals(delay.toString(), controls.attributes.getNamedItem("delayTime").nodeValue)
            }
        }
    }

    @Test fun independentDecoderRecoversNoisyFramesAcrossCodeWidthGrowthAndDictionaryResets() {
        val width = 128
        val height = 96
        val random = Random(0x671bad)
        val frames = (0..1).map { IntArray(width * height) { paletteColor(random.nextInt(256)) } }
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, width, height)
        frames.forEach { encoder.addFrame(it, 13) }
        encoder.finish()
        withReader(output.toByteArray()) { reader ->
            assertEquals(2, reader.getNumImages(true))
            frames.forEachIndexed { frame, pixels ->
                val decoded = reader.read(frame)
                for (index in pixels.indices) assertEquals("Pixel $index in frame $frame", pixels[index], decoded.getRGB(index % width, index / width))
            }
        }
    }

    @Test fun independentDecoderRecoversEveryLocalPaletteSizeAndSmallCodeWidthResets() {
        val width = 256
        val height = 192
        for (colorCount in listOf(1, 2, 3, 4, 8, 16, 32, 64, 128, 256)) {
            val random = Random(0x671badL + colorCount)
            val pixels = IntArray(width * height) { index ->
                paletteColor(if (index < colorCount) index else random.nextInt(colorCount))
            }
            val second = pixels.reversedArray()
            val output = ByteArrayOutputStream()
            val encoder = GifEncoder(output, width, height)
            encoder.addFrame(pixels, 7)
            encoder.addFrame(second, 11)
            encoder.finish()
            withReader(output.toByteArray()) { reader ->
                assertEquals(2, reader.getNumImages(true))
                for ((frameIndex, expected) in listOf(pixels, second).withIndex()) {
                    val frame = reader.read(frameIndex)
                    for (index in expected.indices) {
                        assertEquals("$colorCount colors, frame $frameIndex, pixel $index",
                            expected[index], frame.getRGB(index % width, index / width))
                    }
                    val metadata = reader.getImageMetadata(frameIndex).getAsTree("javax_imageio_gif_image_1.0")
                    val table = children(metadata).first { it.nodeName == "LocalColorTable" }
                    val expectedSize = if (colorCount <= 2) 2 else Integer.highestOneBit(colorCount - 1) * 2
                    assertEquals(expectedSize.toString(), table.attributes.getNamedItem("sizeOfLocalColorTable").nodeValue)
                    val control = children(metadata).first { it.nodeName == "GraphicControlExtension" }
                    assertEquals(if (frameIndex == 0) "7" else "11", control.attributes.getNamedItem("delayTime").nodeValue)
                }
            }
        }
    }

    @Test fun compactColorTablesReduceSimpleAnimationWithoutChangingPixelsOrTiming() {
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 100, 100)
        encoder.addFrame(IntArray(10_000) { 0xffff0000.toInt() }, 10)
        encoder.addFrame(IntArray(10_000) { 0xff0000ff.toInt() }, 10)
        encoder.finish()
        assertTrue("Two one-color frames should not carry two padded 256-color tables", output.size() < 500)
        assertEquals(20L, encoder.durationCentiseconds)
        withReader(output.toByteArray()) { reader ->
            assertEquals(0xffff0000.toInt(), reader.read(0).getRGB(50, 50))
            assertEquals(0xff0000ff.toInt(), reader.read(1).getRGB(50, 50))
        }
    }

    @Test fun shareModeProducesSmallerDecodedAnimationWithCompleteSelectedTimeline() {
        val duration = 1_610L
        fun encode(quality: GifExportQuality): ByteArray {
            val (width, height) = GifConversionPolicy.canvas(960, 540, quality)
            val plan = GifConversionPolicy.plan(duration, 0L, duration, quality = quality)
            val output = ByteArrayOutputStream()
            val encoder = GifEncoder(output, width, height, maximumColors = quality.maximumColors)
            for (specification in plan.frames) {
                val pixels = IntArray(width * height) { index ->
                    val x = index % width * 960 / width
                    val y = index / width * 540 / height
                    val phase = (specification.timestampMs / 100L).toInt()
                    0xff000000.toInt() or (((x * 3 + phase * 7) and 255) shl 16) or
                        (((y * 2 + phase * 11) and 255) shl 8) or ((x + y + phase * 17) and 255)
                }
                encoder.addFrame(pixels, specification.delayCentiseconds)
            }
            encoder.finish()
            return output.toByteArray()
        }
        val high = encode(GifExportQuality.HIGH_QUALITY)
        val share = encode(GifExportQuality.SHARE)
        assertTrue("Controlled motion should become meaningfully smaller in share mode: ${share.size}/${high.size}",
            share.size.toLong() * 3 < high.size)
        for ((quality, data) in listOf(GifExportQuality.HIGH_QUALITY to high, GifExportQuality.SHARE to share)) {
            val plan = GifConversionPolicy.plan(duration, 0L, duration, quality = quality)
            val (width, height) = GifConversionPolicy.canvas(960, 540, quality)
            withReader(data) { reader ->
                assertEquals(plan.frames.size, reader.getNumImages(true))
                var totalDelay = 0L
                var firstPixel = 0
                var lastPixel = 0
                repeat(plan.frames.size) { index ->
                    val frame = reader.read(index)
                    assertEquals(width, frame.width)
                    assertEquals(height, frame.height)
                    if (index == 0) firstPixel = frame.getRGB(width / 2, height / 2)
                    if (index == plan.frames.lastIndex) lastPixel = frame.getRGB(width / 2, height / 2)
                    val metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                    val control = children(metadata).first { it.nodeName == "GraphicControlExtension" }
                    totalDelay += control.attributes.getNamedItem("delayTime").nodeValue.toLong()
                    val table = children(metadata).first { it.nodeName == "LocalColorTable" }
                    assertTrue(table.attributes.getNamedItem("sizeOfLocalColorTable").nodeValue.toInt() <= quality.maximumColors)
                }
                assertEquals(plan.durationMs, totalDelay * 10L)
                assertTrue("The complete animation must change through its tail", firstPixel != lastPixel)
            }
        }
    }

    @Test fun compressesRepeatedColorsInsteadOfMerelyWrappingRawPixels() {
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 100, 100)
        encoder.addFrame(IntArray(10_000) { 0xffff0000.toInt() }, 10)
        encoder.addFrame(IntArray(10_000) { 0xff0000ff.toInt() }, 10)
        encoder.finish()
        assertTrue("LZW failed to compress simple frames", output.size() < 3_000)
        withReader(output.toByteArray()) { assertEquals(2, it.getNumImages(true)) }
    }

    @Test fun rejectsSingleFrameBadGeometryDelaysAndFileGrowth() {
        assertThrows(IllegalArgumentException::class.java) { GifEncoder(ByteArrayOutputStream(), GifConversionPolicy.MAX_EDGE + 1, 1) }
        val encoder = GifEncoder(ByteArrayOutputStream(), 1, 1)
        assertThrows(IllegalArgumentException::class.java) { encoder.addFrame(intArrayOf(1, 2), 10) }
        assertThrows(IllegalArgumentException::class.java) { encoder.addFrame(intArrayOf(1), 0) }
        encoder.addFrame(intArrayOf(0xffff0000.toInt()), 10)
        assertThrows(IllegalArgumentException::class.java) { encoder.finish() }
        encoder.addFrame(intArrayOf(0xff0000ff.toInt()), 10)
        encoder.finish()
        assertThrows(IllegalStateException::class.java) { encoder.addFrame(intArrayOf(1), 10) }
        assertThrows(IllegalArgumentException::class.java) {
            val limited = GifEncoder(ByteArrayOutputStream(), 128, 128, maximumBytes = 900)
            limited.addFrame(IntArray(128 * 128) { paletteColor(it % 256) }, 10)
        }
    }

    @Test fun invokesCancellationCheckpointsDuringPixelQuantizationAndLzwWork() {
        var calls = 0
        val encoder = GifEncoder(ByteArrayOutputStream(), 100, 100, checkpoint = {
            if (++calls == 4) throw IllegalStateException("Stop this isolated encoding")
        })
        assertThrows(IllegalStateException::class.java) { encoder.addFrame(IntArray(10_000), 10) }
        assertEquals(4, calls)
    }

    @Test fun retainsAllDarkRedGradientShadesThatFixedRgb332WouldCrush() {
        val pixels = IntArray(256) { 0xff000000.toInt() or (it shl 16) }
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 256, 1)
        encoder.addFrame(pixels, 10)
        encoder.addFrame(pixels.reversedArray(), 10)
        encoder.finish()
        withReader(output.toByteArray()) { reader ->
            val first = reader.read(0)
            val second = reader.read(1)
            for (index in pixels.indices) {
                assertEquals(pixels[index], first.getRGB(index, 0))
                assertEquals(pixels[pixels.lastIndex - index], second.getRGB(index, 0))
            }
        }
    }

    @Test fun adaptivePaletteReducesPhotographColorErrorComparedToFixedRgb332() {
        val pixels = IntArray(64 * 64) { index ->
            val red = index % 64 * 4
            val green = index / 64 * 2
            val blue = (index / 64 + index % 64) / 4
            0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 64, 64)
        encoder.addFrame(pixels, 10)
        encoder.addFrame(pixels, 10)
        encoder.finish()
        withReader(output.toByteArray()) { reader ->
            val decoded = reader.read(0)
            var adaptiveError = 0L
            var fixedError = 0L
            fun error(source: Int, target: Int): Long = listOf(16, 8, 0).sumOf { shift ->
                val difference = ((source ushr shift) and 255) - ((target ushr shift) and 255)
                difference.toLong() * difference
            }
            for (index in pixels.indices) {
                val pixel = pixels[index]
                adaptiveError += error(pixel, decoded.getRGB(index % 64, index / 64))
                val red = (((pixel ushr 16) and 255) * 7 + 127) / 255
                val green = (((pixel ushr 8) and 255) * 7 + 127) / 255
                val blue = ((pixel and 255) * 3 + 127) / 255
                fixedError += error(pixel, paletteColor((red shl 5) or (green shl 2) or blue))
            }
            assertTrue("Adaptive palette error $adaptiveError should improve RGB332 $fixedError", adaptiveError * 2 < fixedError)
        }
    }

    @Test fun streamsMoreThanFormerFrameAndDurationCapsWithAccurateDelays() {
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 1, 1)
        repeat(481) { encoder.addFrame(intArrayOf(if (it % 2 == 0) 0xff010101.toInt() else 0xff020202.toInt()), 4) }
        encoder.finish()
        assertEquals(481, encoder.frameCount)
        assertEquals(1_924L, encoder.durationCentiseconds)
        withReader(output.toByteArray()) { assertEquals(481, it.getNumImages(true)) }
    }

    private fun paletteColor(index: Int): Int = 0xff000000.toInt() or
        ((((index ushr 5) and 7) * 255 / 7) shl 16) or ((((index ushr 2) and 7) * 255 / 7) shl 8) or ((index and 3) * 255 / 3)
    private fun children(node: Node): List<Node> = (0 until node.childNodes.length).map { node.childNodes.item(it) }
    // Android's compile boot classpath omits java.desktop; the JVM test runtime provides it.
    // Invoke methods declared on the public API classes, never the module-private GIF implementation.
    private fun withReader(data: ByteArray, action: (IndependentGifReader) -> Unit) {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val input = imageIo.getMethod("createImageInputStream", Any::class.java)
            .invoke(null, ByteArrayInputStream(data)) as Closeable
        input.use {
            val readers = imageIo.getMethod("getImageReadersByFormatName", String::class.java)
                .invoke(null, "gif") as Iterator<*>
            val reader = IndependentGifReader(requireNotNull(readers.next()))
            try { reader.setInput(input); action(reader) } finally { reader.dispose() }
        }
    }

    private class IndependentGifReader(private val instance: Any) {
        private val api = Class.forName("javax.imageio.ImageReader")
        fun setInput(input: Any) { api.getMethod("setInput", Any::class.java).invoke(instance, input) }
        fun getNumImages(scan: Boolean): Int = api.getMethod("getNumImages", Boolean::class.javaPrimitiveType)
            .invoke(instance, scan) as Int
        fun read(index: Int): IndependentGifFrame = IndependentGifFrame(
            api.getMethod("read", Int::class.javaPrimitiveType).invoke(instance, index)
        )
        fun getImageMetadata(index: Int): IndependentGifMetadata = IndependentGifMetadata(
            api.getMethod("getImageMetadata", Int::class.javaPrimitiveType).invoke(instance, index)
        )
        fun dispose() { api.getMethod("dispose").invoke(instance) }
    }

    private class IndependentGifFrame(private val instance: Any) {
        private val api = Class.forName("java.awt.image.BufferedImage")
        val width: Int get() = api.getMethod("getWidth").invoke(instance) as Int
        val height: Int get() = api.getMethod("getHeight").invoke(instance) as Int
        fun getRGB(x: Int, y: Int): Int = api.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(instance, x, y) as Int
    }

    private class IndependentGifMetadata(private val instance: Any) {
        private val api = Class.forName("javax.imageio.metadata.IIOMetadata")
        fun getAsTree(format: String): Node = api.getMethod("getAsTree", String::class.java)
            .invoke(instance, format) as Node
    }
}
