package com.local.douyinsaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.zip.CRC32

/** Deterministic local decoding; no wall-clock capture or animation-flattening bitmap loader. */
internal class AnimatedImageFrames(private val image: LocalAlbumImage, private val checkpoint: () -> Unit = {}) : Closeable {
    private val metadata: Metadata = metadata(image)
    val durationMs: Long = metadata.durationMs
    val width: Int = metadata.width
    val height: Int = metadata.height
    val frameCount: Int = metadata.frames.size
    private val outputSize: Pair<Int, Int> = AnimatedImageVideoPolicy.canvas(width, height)
    private val canvasBitmap: Bitmap = Bitmap.createBitmap(outputSize.first, outputSize.second, Bitmap.Config.ARGB_8888)
    private val canvas: Canvas = Canvas(canvasBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var nextFrame = 0
    private var frameStartMs = 0L
    private var lastTimestampMs = -1L
    private var previous: Frame? = null

    init { canvasBitmap.eraseColor(metadata.background) }

    /** Returned bitmap is owned by this decoder and is reused; callers must copy/draw immediately. */
    fun frameAt(timestampMs: Long): Bitmap {
        require(timestampMs in 0 until durationMs && timestampMs >= lastTimestampMs) { "动图帧时间无效或顺序错误" }
        checkpoint()
        lastTimestampMs = timestampMs
        while (nextFrame < metadata.frames.size && frameStartMs <= timestampMs) {
            checkpoint()
            val frame = metadata.frames[nextFrame]
            previous?.let { old ->
                if (old.dispose == 1) clear(rectangle(old), old.clearColor ?: metadata.background)
                else if (old.dispose == 2) {
                    checkNotNull(previousCanvas) { "动图上一帧状态不完整" }.let {
                        canvasBitmap.eraseColor(Color.TRANSPARENT)
                        canvas.drawBitmap(it, 0f, 0f, null)
                    }
                }
            }
            previousCanvas?.recycle()
            previousCanvas = if (frame.dispose == 2) canvasBitmap.copy(Bitmap.Config.ARGB_8888, false) else null
            val encoded = when (image.mimeType) {
                "image/gif" -> gifFrame(image.file, metadata, frame)
                "image/webp" -> webpFrame(image.file, frame)
                "image/png" -> pngFrame(image.file, metadata, frame)
                else -> error("这种动图格式尚不支持合成，原始图片仍可保存")
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (frame.width / inSampleSize > canvasBitmap.width * 2 || frame.height / inSampleSize > canvasBitmap.height * 2) inSampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
                ?: error("第 ${nextFrame + 1} 帧动图无法解码，不会保存静态封面代替")
            try {
                checkpoint()
                paint.xfermode = if (frame.blend) null else PorterDuffXfermode(PorterDuff.Mode.SRC)
                canvas.drawBitmap(bitmap, null, rectangle(frame), paint)
                paint.xfermode = null
            } finally { bitmap.recycle() }
            previous = frame
            nextFrame++
            frameStartMs += frame.durationMs
        }
        return canvasBitmap
    }

    private var previousCanvas: Bitmap? = null
    private fun rectangle(frame: Frame) = RectF(
        frame.x.toFloat() / width * canvasBitmap.width, frame.y.toFloat() / height * canvasBitmap.height,
        (frame.x + frame.width).toFloat() / width * canvasBitmap.width, (frame.y + frame.height).toFloat() / height * canvasBitmap.height,
    )
    private fun clear(rect: RectF, color: Int) {
        paint.color = color
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        canvas.drawRect(rect, paint)
        paint.xfermode = null
        paint.color = Color.BLACK
    }
    override fun close() { previousCanvas?.recycle(); canvasBitmap.recycle() }

    private data class Piece(val offset: Long, val length: Int)
    private data class Frame(val width: Int, val height: Int, val x: Int = 0, val y: Int = 0,
        val durationMs: Long, val dispose: Int = 0, val blend: Boolean = false, val pieces: List<Piece> = emptyList(),
        val gifControl: ByteArray = byteArrayOf(), val clearColor: Int? = null)
    private data class Metadata(val width: Int, val height: Int, val frames: List<Frame>,
        val background: Int = Color.TRANSPARENT, val pngHeader: ByteArray = byteArrayOf(),
        val pngGlobals: List<Pair<String, ByteArray>> = emptyList(), val gifHeader: ByteArray = byteArrayOf()) {
        val durationMs = frames.sumOf { it.durationMs }
    }

    companion object {
        fun durationMs(image: LocalAlbumImage): Long = metadata(image).durationMs

        private fun metadata(image: LocalAlbumImage): Metadata {
            require(image.animated && image.file.isFile && image.file.length() in 1..AlbumMediaPolicy.MAX_IMAGE_BYTES) { "这个文件不是可用的原始动图" }
            val metadata = when (image.mimeType) {
                "image/gif" -> gifMetadata(image.file)
                "image/webp" -> webpMetadata(image.file)
                "image/png" -> pngMetadata(image.file)
                else -> error("这种动图格式暂不支持合成；可以保存原始资源")
            }
            AnimatedImageVideoPolicy.canvas(metadata.width, metadata.height)
            require(metadata.frames.size >= 2 && metadata.durationMs in 1..Int.MAX_VALUE.toLong()) { "动图缺少连续帧或时间信息无效，不会用封面替代" }
            return metadata
        }

        private fun gifMetadata(file: File): Metadata = RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(6).also(input::readFully).toString(Charsets.US_ASCII)
            require(signature == "GIF87a" || signature == "GIF89a") { "GIF 文件头无效" }
            val width = ushort(input); val height = ushort(input)
            val packed = input.readUnsignedByte()
            val backgroundIndex = input.readUnsignedByte(); input.readUnsignedByte()
            val palette = if (packed and 128 != 0) ByteArray(3 * (1 shl ((packed and 7) + 1))).also(input::readFully) else byteArrayOf()
            val background = if (palette.isNotEmpty() && backgroundIndex * 3 + 2 < palette.size)
                Color.rgb(palette[backgroundIndex * 3].toInt() and 255, palette[backgroundIndex * 3 + 1].toInt() and 255,
                    palette[backgroundIndex * 3 + 2].toInt() and 255) else Color.TRANSPARENT
            val headerEnd = input.filePointer
            input.seek(0)
            val header = ByteArray(headerEnd.toInt()).also(input::readFully)
            fun skipBlocks() {
                while (true) {
                    val length = input.readUnsignedByte()
                    if (length == 0) break
                    require(input.filePointer + length <= input.length()) { "GIF 动图数据不完整" }
                    input.seek(input.filePointer + length)
                }
            }
            val frames = mutableListOf<Frame>()
            var delay = 100L
            var control = byteArrayOf()
            var disposal = 0
            var transparent = false
            var finished = false
            while (!finished && input.filePointer < input.length()) when (input.readUnsignedByte()) {
                0x21 -> if (input.readUnsignedByte() == 0xf9) {
                    require(input.readUnsignedByte() == 4) { "GIF 帧信息无效" }
                    val flags = input.readUnsignedByte()
                    val centiseconds = ushort(input)
                    val transparentIndex = input.readUnsignedByte(); require(input.readUnsignedByte() == 0)
                    val gifDisposal = flags ushr 2 and 7
                    require(gifDisposal in 0..3) { "GIF 帧处置方式无效" }
                    disposal = when (gifDisposal) { 2 -> 1; 3 -> 2; else -> 0 }
                    transparent = flags and 1 != 0
                    delay = (centiseconds * 10L).takeIf { it > 0 } ?: 100L
                    control = byteArrayOf(0x21, 0xf9.toByte(), 4, flags.toByte(), centiseconds.toByte(),
                        (centiseconds ushr 8).toByte(), transparentIndex.toByte(), 0)
                } else skipBlocks()
                0x2c -> {
                    val start = input.filePointer - 1L
                    val x = ushort(input); val y = ushort(input)
                    val frameWidth = ushort(input); val frameHeight = ushort(input)
                    require(frameWidth > 0 && frameHeight > 0 && x.toLong() + frameWidth <= width && y.toLong() + frameHeight <= height) {
                        "GIF 动图帧超出画面"
                    }
                    val local = input.readUnsignedByte()
                    if (local and 128 != 0) input.skipBytes(3 * (1 shl ((local and 7) + 1)))
                    input.readUnsignedByte(); skipBlocks()
                    frames += Frame(frameWidth, frameHeight, x, y, delay, disposal, blend = true,
                        pieces = listOf(Piece(start, (input.filePointer - start).toInt())), gifControl = control,
                        clearColor = if (transparent) Color.TRANSPARENT else background)
                    require(frames.size <= 100_000) { "GIF 动图结构过大" }
                    delay = 100L
                    control = byteArrayOf(); disposal = 0; transparent = false
                }
                0x3b -> finished = true
                else -> error("GIF 动图结构无效")
            }
            require(finished) { "GIF 动图未下载完整" }
            val initialBackground = if (frames.firstOrNull()?.gifControl?.getOrNull(3)?.toInt()?.and(1) == 1)
                Color.TRANSPARENT else background
            Metadata(width, height, frames, background = initialBackground, gifHeader = header)
        }

        private fun webpMetadata(file: File): Metadata = RandomAccessFile(file, "r").use { input ->
            require(fourcc(input) == "RIFF") { "WebP 文件头无效" }
            val end = uint(input) + 8L
            require(end <= input.length() && fourcc(input) == "WEBP") { "WebP 动图数据不完整" }
            var width = 0; var height = 0; var background = Color.TRANSPARENT
            val frames = mutableListOf<Frame>()
            while (input.filePointer + 8L <= end) {
                val type = fourcc(input); val length = uint(input)
                val start = input.filePointer
                require(length <= end - start && length <= AlbumMediaPolicy.MAX_IMAGE_BYTES) { "WebP 动图块无效" }
                when (type) {
                    "VP8X" -> {
                        require(length == 10L)
                        input.skipBytes(4); width = uint24(input) + 1; height = uint24(input) + 1
                    }
                    "ANIM" -> { require(length == 6L); background = uint(input).toInt() }
                    "ANMF" -> {
                        require(length >= 16L)
                        val x = uint24(input) * 2; val y = uint24(input) * 2
                        val frameWidth = uint24(input) + 1; val frameHeight = uint24(input) + 1
                        val duration = uint24(input).toLong().takeIf { it > 0 } ?: 100L
                        val flags = input.readUnsignedByte()
                        require(x.toLong() + frameWidth <= width && y.toLong() + frameHeight <= height) { "WebP 动图帧超出画面" }
                        frames += Frame(frameWidth, frameHeight, x, y, duration, flags and 1, flags and 2 == 0,
                            listOf(Piece(start + 16L, (length - 16L).toInt())))
                        require(frames.size <= 100_000) { "WebP 动图结构过大" }
                    }
                }
                input.seek(start + length + (length and 1L))
            }
            require(input.filePointer == end) { "WebP 动图未下载完整" }
            Metadata(width, height, frames, background)
        }

        private fun pngMetadata(file: File): Metadata = RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(8).also(input::readFully)
            require(signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "PNG 文件头无效" }
            var header = byteArrayOf(); var width = 0; var height = 0; var declaredCount = 0L
            val globals = mutableListOf<Pair<String, ByteArray>>()
            val frames = mutableListOf<Frame>()
            var current: Frame? = null
            val pieces = mutableListOf<Piece>()
            var finished = false
            var exactBoundaryMs = BigDecimal.ZERO
            var roundedBoundaryMs = 0L
            fun finishFrame() { current?.let { require(pieces.isNotEmpty()); frames += it.copy(pieces = pieces.toList()) }; pieces.clear() }
            while (!finished && input.filePointer + 12 <= input.length()) {
                val length = input.readInt().toLong() and 0xffffffffL
                val type = fourcc(input)
                val start = input.filePointer
                require(length <= input.length() - start - 4 && length <= AlbumMediaPolicy.MAX_IMAGE_BYTES) { "APNG 动图块无效" }
                when (type) {
                    "IHDR" -> { require(length == 13L); header = ByteArray(13).also(input::readFully); width = bigInt(header, 0); height = bigInt(header, 4) }
                    "acTL" -> { require(length == 8L); declaredCount = input.readInt().toLong() and 0xffffffffL }
                    "fcTL" -> {
                        require(length == 26L)
                        finishFrame()
                        input.readInt()
                        val frameWidth = input.readInt(); val frameHeight = input.readInt(); val x = input.readInt(); val y = input.readInt()
                        val numerator = input.readUnsignedShort(); val denominator = input.readUnsignedShort().takeIf { it > 0 } ?: 100
                        // APNG delay is a fraction of a second. Round the accumulated boundary,
                        // rather than each delay: 300 frames at 1/30 s must remain 10,000 ms.
                        // A zero numerator uses our 1 ms decoding floor. Positive sub-ms delays
                        // may occupy zero whole milliseconds; frameAt processes those frames in
                        // order without stretching the animation by imposing a per-frame floor.
                        val exactDelayMs = if (numerator == 0) BigDecimal.ONE else
                            BigDecimal.valueOf(numerator * 1000L).divide(BigDecimal.valueOf(denominator.toLong()), 24, RoundingMode.HALF_EVEN)
                        exactBoundaryMs = exactBoundaryMs.add(exactDelayMs)
                        val boundary = exactBoundaryMs.setScale(0, RoundingMode.HALF_UP).longValueExact()
                        val duration = boundary - roundedBoundaryMs
                        roundedBoundaryMs = boundary
                        val dispose = input.readUnsignedByte(); val blend = input.readUnsignedByte()
                        require(frameWidth > 0 && frameHeight > 0 && x >= 0 && y >= 0 &&
                            x.toLong() + frameWidth <= width && y.toLong() + frameHeight <= height && dispose in 0..2 && blend in 0..1) { "APNG 动图帧信息无效" }
                        current = Frame(frameWidth, frameHeight, x, y, duration, dispose, blend == 1)
                    }
                    "IDAT" -> if (current != null) pieces += Piece(start, length.toInt())
                    "fdAT" -> { require(current != null && length >= 4L); pieces += Piece(start + 4L, (length - 4L).toInt()) }
                    "PLTE", "tRNS", "gAMA", "cHRM", "sRGB" -> {
                        require(length <= 1_048_576L) { "APNG 色彩信息过大" }
                        globals += type to ByteArray(length.toInt()).also(input::readFully)
                    }
                    "IEND" -> { require(length == 0L); finishFrame(); finished = true }
                }
                input.seek(start + length + 4L)
            }
            require(finished && header.size == 13 && declaredCount == frames.size.toLong() && frames.size <= 100_000) { "APNG 动图帧数量不完整" }
            Metadata(width, height, frames, pngHeader = header, pngGlobals = globals)
        }

        /** Decode a particular image block, rather than asking Movie to choose a time.
         * Movie treats exact boundaries inclusively and may normalize short delays.
         * Our GCE timeline remains [start, end), and original disposal/offsets are applied above.
         */
        private fun gifFrame(file: File, metadata: Metadata, frame: Frame): ByteArray {
            val piece = frame.pieces.single()
            val block = RandomAccessFile(file, "r").use { input -> input.seek(piece.offset); ByteArray(piece.length).also(input::readFully) }
            val header = metadata.gifHeader.copyOf()
            header[6] = frame.width.toByte(); header[7] = (frame.width ushr 8).toByte()
            header[8] = frame.height.toByte(); header[9] = (frame.height ushr 8).toByte()
            // A single patch decodes at its own origin, then the original image offset
            // determines where it is blended into the full logical screen.
            repeat(4) { block[1 + it] = 0 }
            return header + frame.gifControl + block + byteArrayOf(0x3b)
        }

        private fun webpFrame(file: File, frame: Frame): ByteArray {
            val piece = frame.pieces.single()
            val data = RandomAccessFile(file, "r").use { input -> input.seek(piece.offset); ByteArray(piece.length).also(input::readFully) }
            // VP8X permits ALPH+VP8 lossy frames too; the decoder never receives animation chunks.
            val vp8x = byteArrayOf(0x10, 0, 0, 0) + little24(frame.width - 1) + little24(frame.height - 1)
            val body = "WEBP".toByteArray() + "VP8X".toByteArray() + little32(vp8x.size) + vp8x + data
            return "RIFF".toByteArray() + little32(body.size) + body
        }

        private fun pngFrame(file: File, metadata: Metadata, frame: Frame): ByteArray {
            val output = ByteArrayOutputStream()
            val png = DataOutputStream(output)
            png.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            val header = metadata.pngHeader.copyOf()
            bigWrite(header, 0, frame.width); bigWrite(header, 4, frame.height)
            fun chunk(type: String, data: ByteArray) {
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                png.writeInt(data.size); png.write(typeBytes); png.write(data)
                val crc = CRC32().apply { update(typeBytes); update(data) }
                png.writeInt(crc.value.toInt())
            }
            chunk("IHDR", header)
            metadata.pngGlobals.forEach { (type, data) -> chunk(type, data) }
            RandomAccessFile(file, "r").use { input ->
                frame.pieces.forEach { piece -> input.seek(piece.offset); chunk("IDAT", ByteArray(piece.length).also(input::readFully)) }
            }
            chunk("IEND", byteArrayOf())
            return output.toByteArray()
        }

        private fun fourcc(input: RandomAccessFile) = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        private fun ushort(input: RandomAccessFile): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        private fun uint(input: RandomAccessFile): Long = ushort(input).toLong() or (ushort(input).toLong() shl 16)
        private fun uint24(input: RandomAccessFile): Int = ushort(input) or (input.readUnsignedByte() shl 16)
        private fun little32(value: Int) = ByteArray(4) { index -> (value ushr (index * 8)).toByte() }
        private fun little24(value: Int) = ByteArray(3) { index -> (value ushr (index * 8)).toByte() }
        private fun bigInt(data: ByteArray, offset: Int): Int = (0..3).fold(0) { value, index -> (value shl 8) or (data[offset + index].toInt() and 255) }
        private fun bigWrite(data: ByteArray, offset: Int, value: Int) { repeat(4) { index -> data[offset + index] = (value ushr ((3 - index) * 8)).toByte() } }
    }
}
