package com.local.douyinsaver

import kotlin.math.ceil
import java.util.Locale

/** Share exports trade spatial, temporal and color detail for a smaller GIF, never duration. */
enum class GifExportQuality(val maximumEdge: Int, val maximumFramesPerSecond: Int, val maximumColors: Int) {
    HIGH_QUALITY(960, 30, 256),
    SHARE(480, 10, 128),
}

/** GIF has no audio and stores frame delays in units of one hundredth of a second. */
internal object GifConversionPolicy {
    const val MAX_EDGE = 960
    const val FRAMES_PER_SECOND = 30
    const val MIN_DURATION_MS = 100L
    // A physical file limit protects free space; it does not restrict the selectable video range.
    const val MAX_GIF_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_SOURCE_BYTES = 2L * 1024 * 1024 * 1024

    data class Frame(val timestampMs: Long, val delayCentiseconds: Int)
    data class Plan(val frames: List<Frame>, val durationMs: Long)

    /** A lazy list retains only the current frame, even for a full-length video. */
    fun plan(sourceDurationMs: Long, startMs: Long, durationMs: Long,
        sourceFramesPerSecond: Double = FRAMES_PER_SECOND.toDouble(),
        quality: GifExportQuality = GifExportQuality.HIGH_QUALITY): Plan {
        require(sourceDurationMs in 1..Long.MAX_VALUE / 1000L) { "视频时长无效，无法转换 GIF" }
        require(startMs >= 0 && startMs < sourceDurationMs) { "GIF 开始时间超出视频范围" }
        require(durationMs >= MIN_DURATION_MS) { "GIF 时长至少为 0.1 秒" }
        require(durationMs <= sourceDurationMs - startMs) { "GIF 选定范围超出视频时长，请缩短时长" }
        val fps = if (sourceFramesPerSecond.isFinite() && sourceFramesPerSecond > 0)
            minOf(sourceFramesPerSecond, quality.maximumFramesPerSecond.toDouble()) else quality.maximumFramesPerSecond.toDouble()
        val totalCentiseconds = (durationMs + 5L) / 10L
        val countValue = ceil(durationMs.toDouble() * fps / 1000.0)
            .coerceAtLeast(ceil(totalCentiseconds.toDouble() / 65_535.0)).coerceAtLeast(2.0)
        require(countValue <= Int.MAX_VALUE) { "视频范围过大，无法索引 GIF 帧" }
        val count = countValue.toInt()
        val frames = object : AbstractList<Frame>() {
            override val size = count
            override fun get(index: Int): Frame {
                if (index !in 0 until count) throw IndexOutOfBoundsException(index.toString())
                val startCs = roundedFraction(totalCentiseconds, index, count)
                val endCs = roundedFraction(totalCentiseconds, index + 1, count)
                return Frame(startMs + fraction(durationMs, index, count), (endCs - startCs).toInt())
            }
        }
        return Plan(frames, totalCentiseconds * 10L)
    }

    /** Preserve aspect ratio and avoid upscaling small sources. */
    fun canvas(width: Int, height: Int, quality: GifExportQuality = GifExportQuality.HIGH_QUALITY): Pair<Int, Int> {
        require(width in 1..32_768 && height in 1..32_768 && width.toLong() * height <= 80_000_000L) {
            "视频画面尺寸无效或过大，无法转换 GIF"
        }
        val edge = maxOf(width, height)
        if (edge <= quality.maximumEdge) return width to height
        return maxOf(1, (width.toLong() * quality.maximumEdge / edge).toInt()) to
            maxOf(1, (height.toLong() * quality.maximumEdge / edge).toInt())
    }

    /** Actual bytes are useful; a universal chat-app playback size limit would be misleading. */
    fun sharingSummary(byteCount: Long): String {
        require(byteCount > 0) { "GIF 文件大小无效" }
        val size = String.format(Locale.ROOT, "%.2f MiB", byteCount / (1024.0 * 1024.0))
        return "GIF 文件：$size。聊天应用的播放支持取决于实际入口；若无法播放，可缩短片段或分享 MP4。"
    }

    // Quotient/remainder arithmetic avoids overflowing duration * frame index.
    private fun fraction(value: Long, numerator: Int, denominator: Int): Long =
        value / denominator * numerator + value % denominator * numerator / denominator

    private fun roundedFraction(value: Long, numerator: Int, denominator: Int): Long =
        value / denominator * numerator + (value % denominator * numerator + denominator / 2L) / denominator
}
