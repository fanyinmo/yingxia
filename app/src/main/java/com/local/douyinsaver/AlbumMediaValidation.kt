package com.local.douyinsaver

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlin.math.abs

internal data class LocalAlbumImage(
    val file: File,
    val mimeType: String,
    val extension: String,
    val width: Int,
    val height: Int,
)

internal object AlbumMediaValidation {
    /** Decode only the header here; never trust a URL extension or a server MIME assertion. */
    fun image(file: File): LocalAlbumImage {
        require(AlbumMediaPolicy.fitsBytes(file.length(), AlbumMediaPolicy.MAX_IMAGE_BYTES)) { "图片文件为空或过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val mime = bounds.outMimeType.orEmpty().lowercase()
        val extension = AlbumMediaPolicy.imageExtension(mime, bounds.outWidth, bounds.outHeight)
        // Decode one bounded frame as well, so valid-looking headers do not publish corrupt media.
        val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 128.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
        preview.recycle()
        return LocalAlbumImage(file, mime, extension, bounds.outWidth, bounds.outHeight)
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
