package com.local.douyinsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/** Actual native animation frames become a private silent MP4 for composition, never its cover. */
internal class AnimatedImageVideoConverter(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun durationMs(image: LocalAlbumImage): Long = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        AnimatedImageFrames.durationMs(image)
    }

    suspend fun convert(image: LocalAlbumImage, destination: File): File = withContext(Dispatchers.IO) {
        require(image.file.canonicalFile != destination.canonicalFile && !destination.exists() && destination.parentFile?.isDirectory == true) {
            "动图合成输出不能覆盖现有文件"
        }
        val coroutine = currentCoroutineContext()
        val verified = AlbumMediaValidation.image(image.file)
        require(verified.animated) { "图片没有动态帧，不会用封面视频代替" }
        var owned = false
        var completed = false
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var outputTrack = -1
        var lastOutputTime = SystemClock.elapsedRealtime()
        val information = MediaCodec.BufferInfo()
        try {
            AnimatedImageFrames(verified) { coroutine.ensureActive() }.use { frames ->
                require(frames.durationMs <= 900_000L) { "动图时长超过合成上限，请保存原始资源" }
                val (scaledWidth, scaledHeight) = AnimatedImageVideoPolicy.canvas(frames.width, frames.height)
                coroutine.ensureActive()
                owned = destination.createNewFile()
                require(owned) { "动图输出文件已存在" }
                val configured = startEncoder(scaledWidth, scaledHeight) { coroutine.ensureActive() }
                val encoder = configured.codec
                codec = encoder; codecStarted = true
                val width = configured.width
                val height = configured.height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val pixels = IntArray(width * height)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                try {
                    coroutine.ensureActive()
                    val writer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = writer

                    fun drain(wait: Boolean): Boolean {
                        while (true) {
                            coroutine.ensureActive()
                            val index = encoder.dequeueOutputBuffer(information, if (wait) 10_000L else 0L)
                            when {
                                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                    require(SystemClock.elapsedRealtime() - lastOutputTime < 60_000L) { "手机视频编码器暂时没有输出，请稍后重试" }
                                    return false
                                }
                                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    require(!muxerStarted) { "动图视频编码格式意外变化" }
                                    outputTrack = writer.addTrack(encoder.outputFormat)
                                    writer.start(); muxerStarted = true
                                    lastOutputTime = SystemClock.elapsedRealtime()
                                }
                                index >= 0 -> {
                                    val eos = information.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    try {
                                        if (information.size > 0 && information.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                            require(muxerStarted) { "动图视频轨道尚未就绪" }
                                            val output = checkNotNull(encoder.getOutputBuffer(index))
                                            output.position(information.offset); output.limit(information.offset + information.size)
                                            writer.writeSampleData(outputTrack, output, information)
                                            require(destination.length() <= AlbumMediaPolicy.MAX_VIDEO_BYTES) { "动图视频超过大小上限" }
                                        }
                                        lastOutputTime = SystemClock.elapsedRealtime()
                                    } finally { encoder.releaseOutputBuffer(index, false) }
                                    if (eos) return true
                                }
                            }
                        }
                    }

                    fun inputIndex(): Int {
                        while (true) {
                            coroutine.ensureActive()
                            val index = encoder.dequeueInputBuffer(10_000L)
                            if (index >= 0) return index
                            drain(false)
                        }
                    }
                    val count = ceil(frames.durationMs * 30.0 / 1000.0).toInt().coerceAtLeast(2)
                    for (index in 0 until count) {
                        coroutine.ensureActive()
                        val timeUs = index.toLong() * frames.durationMs * 1000L / count
                        val picture = frames.frameAt(timeUs / 1000L)
                        canvas.drawColor(Color.BLACK)
                        // The encoder may need a larger/aligned canvas. Keep the original content
                        // size and aspect ratio inside black padding, including transparent pixels.
                        val scale = minOf(1f, width.toFloat() / picture.width, height.toFloat() / picture.height)
                        val left = (width - picture.width * scale) / 2f
                        val top = (height - picture.height * scale) / 2f
                        canvas.drawBitmap(picture, null, RectF(left, top, left + picture.width * scale, top + picture.height * scale), paint)
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        val input = inputIndex()
                        val inputCapacity = checkNotNull(encoder.getInputBuffer(input)) { "手机视频编码器无法接收动态画面" }.capacity()
                        val inputImage = checkNotNull(encoder.getInputImage(input)) { "手机视频编码器不支持动态图片的 YUV 输入" }
                        try { writeYuv(inputImage, pixels, width, height) { coroutine.ensureActive() } }
                        finally { inputImage.close() }
                        encoder.queueInputBuffer(input, 0, inputCapacity, timeUs, 0)
                        drain(false)
                    }
                    val eosInput = inputIndex()
                    encoder.queueInputBuffer(eosInput, 0, 0, frames.durationMs * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    while (!drain(true)) coroutine.ensureActive()
                    require(muxerStarted) { "动图没有生成有效视频画面" }
                    // Explicit timing keeps a non-30-divisible source interval intact.
                    information.set(0, 0, frames.durationMs * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    writer.writeSampleData(outputTrack, ByteBuffer.allocate(0), information)
                    writer.stop(); muxerStarted = false
                    encoder.stop(); codecStarted = false
                } finally { bitmap.recycle() }
            }
            coroutine.ensureActive()
            require(destination.length() in 1..AlbumMediaPolicy.MAX_VIDEO_BYTES) { "动图视频文件为空或过大" }
            VideoGifConverter().readDurationMs(destination) // Video track and at least two encoded pictures.
            completed = true
            destination
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (owned && !completed) destination.delete()
        }
    }

    private data class EncoderInput(val name: String, val width: Int, val height: Int, val format: MediaFormat)
    private data class StartedEncoder(val codec: MediaCodec, val width: Int, val height: Int)

    /** Even pixels alone do not meet a device encoder's minimum size or alignment requirements.
     * Try every encoder at the source geometry before accepting a larger padded canvas. A codec's
     * advertised capabilities are checked again by real configure/start, with failed codecs released.
     */
    private fun startEncoder(width: Int, height: Int, checkpoint: () -> Unit): StartedEncoder {
        val candidates = mutableListOf<EncoderInput>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            checkpoint()
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) continue
            val candidate = runCatching {
                val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in capabilities.colorFormats) return@runCatching null
                val video = capabilities.videoCapabilities ?: return@runCatching null
                var size = AnimatedImageVideoPolicy.encoderCanvas(width, height,
                    video.supportedWidths.lower, video.supportedHeights.lower, video.widthAlignment, video.heightAlignment)
                if (!video.supportedWidths.contains(size.first)) return@runCatching null
                val heights = video.getSupportedHeightsFor(size.first)
                size = AnimatedImageVideoPolicy.encoderCanvas(width, height,
                    video.supportedWidths.lower, heights.lower, video.widthAlignment, video.heightAlignment)
                if (!heights.contains(size.second) || !video.areSizeAndRateSupported(size.first, size.second, 30.0)) return@runCatching null
                val bitrate = video.bitrateRange.clamp((size.first.toLong() * size.second * 6)
                    .coerceIn(256_000L, 20_000_000L).toInt())
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.first, size.second).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    // writeYuv uses BT.601 limited-range RGB conversion, including for HD inputs.
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (!capabilities.isFormatSupported(format)) return@runCatching null
                EncoderInput(info.name, size.first, size.second, format)
            }.getOrNull()
            if (candidate != null) candidates += candidate
        }
        require(candidates.isNotEmpty()) { "手机没有支持此动图尺寸和 YUV 输入的视频编码器，可以保存原始资源" }
        val failures = mutableListOf<Exception>()
        // Smallest canvas first: e.g. a software encoder accepting 48x48 precedes hardware
        // requiring 64x64. Equal sizes keep the device's normal codec preference order.
        for (candidate in candidates.sortedBy { it.width.toLong() * it.height }) {
            checkpoint()
            var encoder: MediaCodec? = null
            try {
                encoder = MediaCodec.createByCodecName(candidate.name)
                encoder.configure(candidate.format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                checkpoint()
                encoder.start()
                Log.i("AnimatedImageVideo", "encoder=${candidate.name} size=${candidate.width}x${candidate.height}")
                return StartedEncoder(encoder, candidate.width, candidate.height)
            } catch (error: Exception) {
                runCatching { encoder?.release() }
                if (error is CancellationException) throw error
                checkpoint()
                failures += error
                Log.w("AnimatedImageVideo", "rejected encoder=${candidate.name} size=${candidate.width}x${candidate.height}")
            }
        }
        throw IllegalStateException("手机视频编码器无法处理此动图，请稍后重试或保存原始资源").apply {
            failures.forEach(::addSuppressed)
        }
    }

    private fun writeYuv(image: Image, pixels: IntArray, width: Int, height: Int, checkpoint: () -> Unit) {
        require(image.planes.size == 3 && image.width >= width && image.height >= height) { "手机视频编码器的 YUV 画面布局无效" }
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        for (row in 0 until height) {
            checkpoint()
            for (column in 0 until width) {
                val color = pixels[row * width + column]
                val red = color ushr 16 and 255; val green = color ushr 8 and 255; val blue = color and 255
                val luminance = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                y.buffer.put(row * y.rowStride + column * y.pixelStride, luminance.coerceIn(0, 255).toByte())
            }
        }
        for (row in 0 until height step 2) {
            checkpoint()
            for (column in 0 until width step 2) {
                var red = 0; var green = 0; var blue = 0
                repeat(2) { dy -> repeat(2) { dx ->
                    val color = pixels[(row + dy) * width + column + dx]
                    red += color ushr 16 and 255; green += color ushr 8 and 255; blue += color and 255
                } }
                red /= 4; green /= 4; blue /= 4
                val chromaU = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                val chromaV = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                u.buffer.put(row / 2 * u.rowStride + column / 2 * u.pixelStride, chromaU.coerceIn(0, 255).toByte())
                v.buffer.put(row / 2 * v.rowStride + column / 2 * v.pixelStride, chromaV.coerceIn(0, 255).toByte())
            }
        }
    }
}

/** Composition inputs retain their native geometry up to the same bound as still inputs. */
internal object AnimatedImageVideoPolicy {
    const val MAX_EDGE = 1600
    const val MAX_ENCODER_EDGE = 2048
    fun canvas(width: Int, height: Int): Pair<Int, Int> {
        require(width in 1..32_768 && height in 1..32_768 && width.toLong() * height <= 80_000_000L) {
            "动图画面尺寸无效或过大，无法合成视频；可以保存原始资源"
        }
        val edge = maxOf(width, height)
        if (edge <= MAX_EDGE) return width to height
        return maxOf(1, (width.toLong() * MAX_EDGE / edge).toInt()) to
            maxOf(1, (height.toLong() * MAX_EDGE / edge).toInt())
    }

    /** Pad only as much as a queried encoder requires; 4:2:0 also requires even dimensions. */
    fun encoderCanvas(width: Int, height: Int, minimumWidth: Int, minimumHeight: Int,
        widthAlignment: Int, heightAlignment: Int): Pair<Int, Int> {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE && minimumWidth > 0 && minimumHeight > 0)
        require(widthAlignment in 1..MAX_ENCODER_EDGE && heightAlignment in 1..MAX_ENCODER_EDGE &&
            widthAlignment and (widthAlignment - 1) == 0 && heightAlignment and (heightAlignment - 1) == 0)
        fun aligned(value: Int, minimum: Int, alignment: Int): Int {
            val step = maxOf(2, alignment)
            val result = (maxOf(value, minimum).toLong() + step - 1) / step * step
            require(result <= MAX_ENCODER_EDGE) { "手机编码器需要的动图画布过大，可以保存原始资源" }
            return result.toInt()
        }
        return aligned(width, minimumWidth, widthAlignment) to aligned(height, minimumHeight, heightAlignment)
    }
}
