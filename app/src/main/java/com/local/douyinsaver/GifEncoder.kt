package com.local.douyinsaver

import java.io.OutputStream

/**
 * Streaming GIF89a with the smallest local table that holds each frame's adaptive palette.
 * It retains one indexed frame, at most 32768 color bins and a bounded LZW dictionary.
 */
internal class GifEncoder(
    private val output: OutputStream,
    val width: Int,
    val height: Int,
    private val maximumBytes: Long = GifConversionPolicy.MAX_GIF_BYTES,
    private val maximumColors: Int = 256,
    private val checkpoint: () -> Unit = {},
) {
    var frameCount: Int = 0; private set
    var bytesWritten: Long = 0L; private set
    var durationCentiseconds: Long = 0; private set
    private var finished = false
    // Primitive open addressing avoids per-pixel Integer boxing and HashMap collision trees.
    // At most 4096 codes exist; 8192 slots keep the dictionary below half full.
    private val dictionaryKeys = IntArray(8192)
    private val dictionaryCodes = IntArray(8192)

    init {
        require(width in 1..GifConversionPolicy.MAX_EDGE && height in 1..GifConversionPolicy.MAX_EDGE) { "GIF 画面尺寸超过上限" }
        require(maximumBytes in 1..GifConversionPolicy.MAX_GIF_BYTES) { "GIF 文件大小上限无效" }
        require(maximumColors in 2..256 && maximumColors and (maximumColors - 1) == 0) { "GIF 色表上限无效" }
        bytes("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        byte(0x70); byte(0); byte(0) // No global palette; each frame declares its own table size.
        // NETSCAPE2.0 loop count 0 means repeat forever.
        byte(0x21); byte(0xff); byte(11)
        bytes("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        byte(3); byte(1); short(0); byte(0)
    }

    fun addFrame(argb: IntArray, delayCentiseconds: Int) {
        check(!finished) { "GIF 已结束，不能再写入画面" }
        require(frameCount < Int.MAX_VALUE) { "GIF 帧数超过可索引范围" }
        require(argb.size == width * height) { "GIF 画面像素数量不符" }
        require(delayCentiseconds in 1..65535 && durationCentiseconds <= Long.MAX_VALUE - delayCentiseconds) { "GIF 帧时长无效" }
        checkpoint()
        val quantized = GifPalette.quantize(argb, maximumColors, checkpoint)
        val paletteBits = Integer.numberOfTrailingZeros(quantized.colors.size)
        // Graphic Control Extension: disposal=1, no transparency, real frame-specific delay.
        byte(0x21); byte(0xf9); byte(4); byte(4)
        short(delayCentiseconds); byte(0); byte(0)
        byte(0x2c); short(0); short(0); short(width); short(height); byte(0x80 or (paletteBits - 1))
        for (color in quantized.colors) { byte(color ushr 16); byte(color ushr 8); byte(color) }
        val minimumCodeSize = maxOf(2, paletteBits) // GIF requires at least two bits, even for two colors.
        byte(minimumCodeSize)
        writeLzw(quantized.pixels, minimumCodeSize)
        frameCount++
        durationCentiseconds += delayCentiseconds
    }

    fun finish() {
        check(!finished) { "GIF 已结束" }
        require(frameCount >= 2) { "GIF 至少需要两帧，不能仅保存视频封面" }
        checkpoint()
        byte(0x3b)
        output.flush()
        finished = true
    }

    private fun writeLzw(pixels: ByteArray, minimumCodeSize: Int) {
        dictionaryKeys.fill(0)
        val clearCode = 1 shl minimumCodeSize
        val endCode = clearCode + 1
        var nextCode = clearCode + 2
        var codeSize = minimumCodeSize + 1
        var bits = 0
        var bitCount = 0
        val block = ByteArray(255)
        var blockSize = 0
        fun emitByte(value: Int) {
            block[blockSize++] = value.toByte()
            if (blockSize == block.size) {
                byte(blockSize); bytes(block, blockSize); blockSize = 0
            }
        }
        fun emitCode(code: Int) {
            bits = bits or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                emitByte(bits and 255)
                bits = bits ushr 8
                bitCount -= 8
            }
            // The decoder creates its dictionary entry after this emitted code.
            // Growing before emission would put the encoder one code ahead at each width change.
            if (nextCode == 1 shl codeSize && codeSize < 12) codeSize++
        }
        emitCode(clearCode)
        var prefix = pixels[0].toInt() and 255
        for (index in 1 until pixels.size) {
            if (index and 2047 == 0) checkpoint()
            val suffix = pixels[index].toInt() and 255
            val key = (prefix shl 8) or suffix
            val storedKey = key + 1 // Zero is the empty-slot marker, including after a reset.
            var slot = (key * -1640531527) ushr 19
            while (dictionaryKeys[slot] != 0 && dictionaryKeys[slot] != storedKey) slot = (slot + 1) and 8191
            if (dictionaryKeys[slot] != 0) prefix = dictionaryCodes[slot] else {
                emitCode(prefix)
                if (nextCode < 4096) {
                    dictionaryKeys[slot] = storedKey
                    dictionaryCodes[slot] = nextCode++
                } else {
                    emitCode(clearCode)
                    dictionaryKeys.fill(0)
                    nextCode = clearCode + 2
                    codeSize = minimumCodeSize + 1
                }
                prefix = suffix
            }
        }
        emitCode(prefix)
        emitCode(endCode) // End of this frame, at the decoder's current code width.
        if (bitCount > 0) emitByte(bits and 255)
        if (blockSize > 0) { byte(blockSize); bytes(block, blockSize) }
        byte(0) // Image-data sub-block terminator.
    }

    private fun byte(value: Int) {
        require(bytesWritten < maximumBytes) { "GIF 文件超过大小上限，请缩短时长或改为保存原始资源" }
        output.write(value and 255)
        bytesWritten++
    }
    private fun short(value: Int) { byte(value); byte(value ushr 8) }
    private fun bytes(value: ByteArray, count: Int = value.size) {
        require(count <= maximumBytes - bytesWritten) { "GIF 文件超过大小上限，请缩短时长或改为保存原始资源" }
        output.write(value, 0, count)
        bytesWritten += count
    }
}

/** Exact colors for simple artwork; weighted median-cut for photographs and video frames. */
private object GifPalette {
    data class Indexed(val colors: IntArray, val pixels: ByteArray)
    private data class Bin(val key: Int, val count: Int, val red: Int, val green: Int, val blue: Int)
    private data class Box(val bins: List<Bin>) {
        val count = bins.sumOf { it.count.toLong() }
        val ranges = intArrayOf(bins.maxOf { it.red } - bins.minOf { it.red },
            bins.maxOf { it.green } - bins.minOf { it.green }, bins.maxOf { it.blue } - bins.minOf { it.blue })
        val channel = ranges.indices.maxBy { ranges[it] }
        val priority = ranges[channel].toLong() * count
    }

    fun quantize(argb: IntArray, maximumColors: Int, checkpoint: () -> Unit): Indexed {
        val exact = LinkedHashMap<Int, Int>(maximumColors + 1)
        var allColorsFit = true
        for (index in argb.indices) {
            if (index and 2047 == 0) checkpoint()
            val color = argb[index] and 0x00ffffff
            if (color !in exact) {
                if (exact.size == maximumColors) { allColorsFit = false; break }
                exact[color] = exact.size
            }
        }
        if (allColorsFit) {
            val palette = IntArray(tableSize(exact.size))
            exact.forEach { (color, index) -> palette[index] = color }
            val pixels = ByteArray(argb.size)
            for (index in argb.indices) {
                if (index and 2047 == 0) checkpoint()
                pixels[index] = checkNotNull(exact[argb[index] and 0x00ffffff]).toByte()
            }
            return Indexed(palette, pixels)
        }

        val counts = IntArray(32768)
        val reds = LongArray(32768)
        val greens = LongArray(32768)
        val blues = LongArray(32768)
        for (index in argb.indices) {
            if (index and 2047 == 0) checkpoint()
            val color = argb[index]
            val key = key(color)
            counts[key]++
            reds[key] += (color ushr 16) and 255
            greens[key] += (color ushr 8) and 255
            blues[key] += color and 255
        }
        val populated = counts.indices.filter { counts[it] != 0 }.map { key ->
            Bin(key, counts[key], (reds[key] / counts[key]).toInt(), (greens[key] / counts[key]).toInt(), (blues[key] / counts[key]).toInt())
        }
        val boxes = mutableListOf(Box(populated))
        while (boxes.size < maximumColors) {
            checkpoint()
            val split = boxes.indices.filter { boxes[it].bins.size > 1 }.maxByOrNull { boxes[it].priority } ?: break
            val box = boxes.removeAt(split)
            val sorted = box.bins.sortedBy { channel(it, box.channel) }
            var accumulated = 0L
            var cut = 1
            for (index in 0 until sorted.lastIndex) {
                accumulated += sorted[index].count
                cut = index + 1
                if (accumulated >= (box.count + 1) / 2) break
            }
            boxes += Box(sorted.subList(0, cut))
            boxes += Box(sorted.subList(cut, sorted.size))
        }
        val palette = IntArray(tableSize(boxes.size))
        val lookup = IntArray(32768)
        boxes.forEachIndexed { index, box ->
            checkpoint()
            val red = (box.bins.sumOf { reds[it.key] } / box.count).toInt()
            val green = (box.bins.sumOf { greens[it.key] } / box.count).toInt()
            val blue = (box.bins.sumOf { blues[it.key] } / box.count).toInt()
            palette[index] = (red shl 16) or (green shl 8) or blue
            box.bins.forEach { lookup[it.key] = index }
        }
        val pixels = ByteArray(argb.size)
        for (index in argb.indices) {
            if (index and 2047 == 0) checkpoint()
            pixels[index] = lookup[key(argb[index])].toByte()
        }
        return Indexed(palette, pixels)
    }

    private fun key(color: Int): Int = (((color ushr 19) and 31) shl 10) or
        (((color ushr 11) and 31) shl 5) or ((color ushr 3) and 31)
    private fun tableSize(colors: Int): Int {
        var size = 2
        while (size < colors) size = size shl 1
        return size
    }
    private fun channel(bin: Bin, channel: Int): Int = when (channel) { 0 -> bin.red; 1 -> bin.green; else -> bin.blue }
}
