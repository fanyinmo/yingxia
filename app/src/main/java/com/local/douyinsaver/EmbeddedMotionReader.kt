package com.local.douyinsaver

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream

/** Read-only decoder for legacy embedded motion and dynamic conversion inputs. No photo export. */
internal object EmbeddedMotionReader {
    data class Verified(
        val container: MotionPhotoContainer.Info,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val hasAudio: Boolean,
    )

    fun validate(file: File): Verified {
        val info = MotionPhotoContainer.inspect(file) ?: error("图片没有包含动态内容")
        require(info.videoOffset <= AlbumMediaPolicy.MAX_IMAGE_BYTES && info.videoLength <= AlbumMediaPolicy.MAX_MOTION_BYTES) {
            "动态照片文件过大"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outMimeType == "image/jpeg") { "动态照片封面无法解码" }
        AlbumMediaPolicy.imageExtension(bounds.outMimeType, bounds.outWidth, bounds.outHeight)
        val preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 128.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
        preview.recycle()
        var hasAudio = false
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { input -> extractor.setDataSource(input.fd, info.videoOffset, info.videoLength) }
            val videoTracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            val video = videoTracks.firstOrNull() ?: error("动态照片内没有视频轨道")
            videoTracks.forEach { track ->
                MotionPhotoCodecPolicy.requireCompatibleVideoMime(extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME))
            }
            hasAudio = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
            extractor.selectTrack(video)
            require(extractor.sampleTrackIndex == video && extractor.sampleSize > 0 && extractor.advance() &&
                extractor.sampleTrackIndex == video && extractor.sampleSize > 0) { "动态照片内缺少连续动态帧" }
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            FileInputStream(file).use { input -> retriever.setDataSource(input.fd, info.videoOffset, info.videoLength) }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(width > 0 && height > 0 && duration > 0) { "动态照片内的动态信息无效" }
            val frame = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128)
                ?: error("动态照片内的动态内容无法解码")
            frame.recycle()
            return Verified(info, width, height, duration, hasAudio)
        } finally { retriever.release() }
    }

    /** Decode legacy embedded media into an owned temporary MP4. */
    suspend fun extractForPreview(file: File, destination: File): File {
        val job = currentCoroutineContext()
        job.ensureActive()
        MotionPhotoContainer.extractVideo(file, destination) { job.ensureActive() }
        try {
            AlbumMediaValidation.motionVideo(destination)
            job.ensureActive()
            return destination
        } catch (error: Exception) { destination.delete(); throw error }
    }

    /** Read existing embedded media without publishing another photo. */
    suspend fun extractForPreview(context: Context, uri: Uri, destination: File): File {
        val job = currentCoroutineContext(); job.ensureActive()
        require(!destination.exists()) { "动态输出文件已经存在" }
        val resolver = context.contentResolver
        val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 }
            ?: resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            } ?: error("无法读取动态照片长度")
        require(bytes in 1..(AlbumMediaPolicy.MAX_IMAGE_BYTES + AlbumMediaPolicy.MAX_MOTION_BYTES)) { "动态照片文件为空或过大" }
        val info = resolver.openInputStream(uri)?.use { MotionPhotoContainer.inspect(it, bytes) }
            ?: error("照片没有包含动态内容")
        var owned = false
        var finished = false
        try {
            job.ensureActive(); check(destination.createNewFile()) { "无法创建动态预览文件" }; owned = true
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                var skip = info.videoOffset
                while (skip > 0) {
                    job.ensureActive()
                    val count = input.skip(minOf(skip, 64 * 1024L))
                    if (count > 0) skip -= count else { require(input.read() >= 0) { "动态照片数据不完整" }; skip-- }
                }
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024); var remaining = info.videoLength
                    while (remaining > 0) {
                        job.ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "动态内容不完整" }; output.write(buffer, 0, count); remaining -= count
                    }
                    require(input.read() < 0) { "动态照片包含未确认的尾部内容" }
                }
            } ?: error("无法读取动态照片")
            AlbumMediaValidation.motionVideo(destination)
            job.ensureActive(); finished = true
            return destination
        } finally { if (owned && !finished) destination.delete() }
    }
}
