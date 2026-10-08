package com.local.douyinsaver

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Local video frames become a real, silent, looping GIF; the caller owns final publication. */
internal class VideoGifConverter {
    suspend fun readDurationMs(source: File): Long = withContext(Dispatchers.IO) {
        validateSource(source)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            videoDurationMs(source, retriever)
        } finally { retriever.release() }
    }

    suspend fun convert(
        source: File,
        destination: File,
        startMs: Long,
        durationMs: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = convert(source, destination, startMs, durationMs, GifExportQuality.HIGH_QUALITY, onProgress)

    suspend fun convert(
        source: File,
        destination: File,
        startMs: Long,
        durationMs: Long,
        quality: GifExportQuality,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = convertInternal(source, destination, startMs, durationMs, allowLoop = false, quality = quality, onProgress = onProgress)

    /** Gallery duration overrides trim from zero or repeat the real clip without stretching it. */
    suspend fun convertLooped(
        source: File,
        destination: File,
        requestedDurationMs: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = convertLooped(source, destination, requestedDurationMs, GifExportQuality.HIGH_QUALITY, onProgress)

    suspend fun convertLooped(
        source: File,
        destination: File,
        requestedDurationMs: Long,
        quality: GifExportQuality,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = convertInternal(source, destination, 0L, requestedDurationMs, allowLoop = true, quality = quality, onProgress = onProgress)

    private suspend fun convertInternal(
        source: File,
        destination: File,
        startMs: Long,
        durationMs: Long,
        allowLoop: Boolean,
        quality: GifExportQuality,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val declaredFrameRate = validateSource(source)
        require(source.canonicalFile != destination.canonicalFile) { "GIF 输出不能覆盖原视频" }
        require(!destination.exists()) { "GIF 输出文件已存在，请使用新的文件名" }
        require(destination.parentFile?.isDirectory == true) { "GIF 临时输出目录不可用" }
        var owned = false
        var completed = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val sourceDuration = videoDurationMs(source, retriever)
            val countedFrameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toDoubleOrNull()?.let { it * 1000.0 / sourceDuration }
            val frameRate = declaredFrameRate ?: countedFrameRate ?: GifConversionPolicy.FRAMES_PER_SECOND.toDouble()
            val timelineDuration = if (allowLoop) maxOf(sourceDuration, durationMs) else sourceDuration
            val plan = GifConversionPolicy.plan(timelineDuration, startMs, durationMs, frameRate, quality)
            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            GifConversionPolicy.canvas(sourceWidth, sourceHeight, quality) // Validate source geometry before codec work.
            val edge = minOf(quality.maximumEdge, maxOf(sourceWidth, sourceHeight))
            currentCoroutineContext().ensureActive()
            owned = destination.createNewFile()
            require(owned) { "GIF 输出文件已存在，请使用新的文件名" }
            destination.outputStream().buffered(64 * 1024).use { output ->
                val conversionContext = currentCoroutineContext()
                var encoder: GifEncoder? = null
                var pixels: IntArray? = null
                onProgress(0, plan.frames.size.toLong())
                for ((index, specification) in plan.frames.withIndex()) {
                    conversionContext.ensureActive()
                    // Android's retriever applies the video rotation and fits the frame inside
                    // this bound. OPTION_CLOSEST requests real decoded frames, not only keyframes.
                    val sourceTimeMs = if (allowLoop) specification.timestampMs % sourceDuration else specification.timestampMs
                    val frame = retriever.getScaledFrameAtTime(sourceTimeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST, edge, edge)
                        ?: error("无法读取第 ${index + 1} 帧视频画面，请重新解析或缩短 GIF 范围")
                    try {
                        conversionContext.ensureActive()
                        if (encoder == null) {
                            encoder = GifEncoder(output, frame.width, frame.height, maximumColors = quality.maximumColors,
                                checkpoint = { conversionContext.ensureActive() })
                            pixels = IntArray(frame.width * frame.height)
                        }
                        require(frame.width == encoder.width && frame.height == encoder.height) { "视频画面尺寸变化，无法转换 GIF" }
                        frame.getPixels(checkNotNull(pixels), 0, frame.width, 0, 0, frame.width, frame.height)
                        encoder.addFrame(checkNotNull(pixels), specification.delayCentiseconds)
                    } finally { frame.recycle() }
                    onProgress(index + 1L, plan.frames.size.toLong())
                }
                checkNotNull(encoder).finish()
            }
            currentCoroutineContext().ensureActive()
            require(destination.length() in 1..GifConversionPolicy.MAX_GIF_BYTES) { "GIF 文件为空或超过大小上限" }
            completed = true
            destination
        } finally {
            try { retriever.release() } finally {
                if (owned && !completed) destination.delete()
            }
        }
    }

    private suspend fun validateSource(source: File): Double? {
        val sourceBytes = source.length()
        require(source.isFile && sourceBytes in 1..GifConversionPolicy.MAX_SOURCE_BYTES) { "视频文件为空、已不存在或超过 2 GB 上限" }
        val context = currentCoroutineContext()
        context.ensureActive()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            context.ensureActive()
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            require(track != null) { "这个文件没有视频画面，无法转换 GIF" }
            val frameRate = if (extractor.getTrackFormat(track).containsKey(MediaFormat.KEY_FRAME_RATE))
                runCatching { extractor.getTrackFormat(track).getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() }.getOrNull()
                    ?.takeIf { it.isFinite() && it > 0 } else null
            extractor.selectTrack(track)
            repeat(2) { index ->
                context.ensureActive()
                require(extractor.sampleTrackIndex == track && extractor.sampleSize in 1L..sourceBytes) {
                    "这个视频不足两帧有效画面，无法转换 GIF"
                }
                if (index == 0) require(extractor.advance()) { "这个视频不足两帧有效画面，无法转换 GIF" }
            }
            context.ensureActive()
            return frameRate
        } finally { extractor.release() }
    }
    private fun videoDurationMs(source: File, retriever: MediaMetadataRetriever): Long {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        require(width > 0 && height > 0) { "这个文件没有视频画面，无法转换 GIF" }
        // A live photograph's original audio may run longer than its pictures.
        // Default animation timing follows the complete video track, including
        // its final presentation interval, rather than that longer container.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    if (durationUs > 0) return (durationUs / 1000L + if (durationUs % 1000L == 0L) 0L else 1L)
                        .also { require(it in 1..Long.MAX_VALUE / 1000L) { "视频轨时长无效，无法转换 GIF" } }
                }
                break
            }
        } finally { extractor.release() }
        return (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            .also { require(it in 1..Long.MAX_VALUE / 1000L) { "无法读取视频时长，不能转换 GIF" } }
    }
}
