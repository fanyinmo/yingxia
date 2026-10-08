package com.local.douyinsaver

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.DataInputStream
import java.io.RandomAccessFile
import kotlin.math.abs

internal data class LocalAlbumImage(
    val file: File,
    val mimeType: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val animated: Boolean = false,
)

/** The cover and optional original MP4 are one ordered album item. */
internal data class LocalAlbumEntry(val image: LocalAlbumImage, val motionVideo: File? = null)

internal object AlbumMediaValidation {
    /** Verify the actual header and a bounded frame; never trust URL extensions or server MIME. */
    fun image(file: File, maximumBytes: Long = AlbumMediaPolicy.MAX_IMAGE_BYTES): LocalAlbumImage {
        require(AlbumMediaPolicy.fitsBytes(file.length(), maximumBytes)) { "图片文件为空或过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val mime = bounds.outMimeType.orEmpty().lowercase()
        val extension = AlbumMediaPolicy.imageExtension(mime, bounds.outWidth, bounds.outHeight)
        // Decode one bounded frame as well, so valid-looking headers do not publish corrupt media.
        var animated = false
        val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            animated = info.isAnimated
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 128.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
        preview.recycle()
        // Android may decode only the default PNG frame. Preserve the original APNG
        // bytes and classify its acTL container so composition cannot flatten it.
        animated = animated || (mime == "image/png" && isAnimatedPng(file))
        return LocalAlbumImage(file, mime, extension, bounds.outWidth, bounds.outHeight, animated)
    }

    private fun isAnimatedPng(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 8) return@use false
        val signature = ByteArray(8).also(input::readFully)
        if (!signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return@use false
        var chunks = 0
        while (input.filePointer + 12 <= input.length()) {
            require(++chunks <= 100_000) { "PNG 图片结构异常，请重新解析" }
            val length = input.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            require(length <= input.length() - input.filePointer - 4) { "PNG 图片数据不完整，请重新解析" }
            if (type == "acTL") {
                require(length == 8L) { "动图信息无效，请重新解析" }
                return@use (input.readInt().toLong() and 0xffffffffL) > 0
            }
            if (type == "IEND") return@use false
            input.seek(input.filePointer + length + 4)
        }
        false
    }

    /** A live-photo sidecar must contain actual, decodable MP4 video rather than HTML or audio. */
    fun motionVideo(file: File) {
        require(AlbumMediaPolicy.fitsBytes(file.length(), AlbumMediaPolicy.MAX_MOTION_BYTES)) { "动态视频文件为空或超过 128 MB 上限" }
        DataInputStream(file.inputStream()).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            require(header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
                "动态图片没有返回有效 MP4 视频，请重新解析"
            }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: error("动态文件中没有有效视频轨道，请重新解析")
            extractor.selectTrack(video)
            require(AlbumMediaPolicy.samplePresent(video, extractor.sampleTrackIndex, extractor.sampleSize)) {
                "动态视频没有动态内容，请重新解析"
            }
            require(extractor.advance() && AlbumMediaPolicy.samplePresent(video, extractor.sampleTrackIndex, extractor.sampleSize)) {
                "动态视频缺少连续视频帧，请重新解析"
            }
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(width > 0 && height > 0 && duration in 1..AlbumMediaPolicy.MAX_MOTION_DURATION_MS) {
                "动态视频信息无效或时长超过 60 秒，请重新解析"
            }
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128)
                ?: error("动态视频无法解码，请重新解析")
            frame.recycle()
        } finally { retriever.release() }
    }

    fun audioDurationMs(file: File): Long {
        require(AlbumMediaPolicy.fitsBytes(file.length(), AlbumMediaPolicy.MAX_AUDIO_BYTES)) { "BGM 文件为空或过大，可选择保存图片" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audio = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: error("该图集的 BGM 没有有效音轨，可选择保存图片")
            extractor.selectTrack(audio)
            // AAC encoder priming may legitimately carry negative PTS. End-of-stream is
            // identified by sampleTrackIndex == -1, not by a negative sample timestamp.
            require(AlbumMediaPolicy.samplePresent(audio, extractor.sampleTrackIndex, extractor.sampleSize)) {
                "该图集的 BGM 没有音频内容，可选择保存图片"
            }
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
                .also { require(it > 0) { "无法读取 BGM 时长，可选择保存图片" } }
        } finally { retriever.release() }
    }

    fun composedVideo(context: Context, uri: Uri, expectedDurationMs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val formats = (0 until extractor.trackCount).associateWith { extractor.getTrackFormat(it) }
            val video = formats.entries.firstOrNull { it.value.getString(MediaFormat.KEY_MIME) == "video/avc" }
                ?: error("合成文件中没有有效的 H.264 视频轨道")
            val audio = formats.entries.firstOrNull { it.value.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                ?: error("合成文件中没有有效的 AAC 音轨，可选择保存图片")
            for (track in listOf(video.key, audio.key)) {
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                require(AlbumMediaPolicy.samplePresent(track, extractor.sampleTrackIndex, extractor.sampleSize)) {
                    "合成文件中的音视频轨道没有内容"
                }
                extractor.unselectTrack(track)
            }
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(width > 0 && height > 0 && duration > 0) { "合成视频的信息无效，请重试" }
            require(abs(duration - expectedDurationMs) <= maxOf(1_500L, expectedDurationMs / 50)) {
                "合成视频时长与图片序列不符，请重试"
            }
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                ?: error("无法解码合成视频，请选择保存图片或重试")
            frame.recycle()
        } finally { retriever.release() }
    }
}
