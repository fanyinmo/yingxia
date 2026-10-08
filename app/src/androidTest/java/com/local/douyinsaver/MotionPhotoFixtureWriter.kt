package com.local.douyinsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID

/** Single-file live-photo export. Source JPEG and MP4 remain untouched, including embedded audio. */
internal object MotionPhotoFixtureWriter {
    data class Verified(
        val container: MotionPhotoContainer.Info,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val hasAudio: Boolean,
    )

    suspend fun create(
        jpeg: File,
        originalVideo: File,
        destination: File,
        presentationTimestampUs: Long = -1L,
        format: MotionPhotoContainer.Format = MotionPhotoContainer.Format.STANDARD,
    ): File {
        val job = currentCoroutineContext()
        job.ensureActive()
        val image = AlbumMediaValidation.image(jpeg)
        require(image.mimeType == "image/jpeg" && !image.animated) { "实况封面必须是原始 JPEG 图片" }
        AlbumMediaValidation.motionVideo(originalVideo)
        val info = MotionPhotoContainer.write(jpeg, originalVideo, destination, presentationTimestampUs, format,
            checkActive = { job.ensureActive() })
        try {
            job.ensureActive()
            val verified = validate(destination)
            require(info == verified.container) { "实况照片写入后的媒体位置发生变化" }
            if (presentationTimestampUs >= 0) require(presentationTimestampUs <= verified.durationMs * 1_000L) {
                "实况封面时间戳超出原片段范围"
            }
            job.ensureActive()
            return destination
        } catch (error: Exception) {
            // Destination belongs only to this invocation; originals are never deleted.
            destination.delete()
            throw error
        }
    }

    /** A conversion, not proof that the source was a captured live photo.
     * The cover is decoded from the actual first sync sample and uses that sample's PTS.
     * Animation conversions are silent by default; an original live capture keeps its
     * sound through [create]. Gallery recognition still needs a vendor-gallery test.
     */
    suspend fun convertAnimation(
        originalVideo: File,
        destination: File,
        format: MotionPhotoContainer.Format = preferredFormat(),
        removeAudio: Boolean = true,
    ): File {
        val job = currentCoroutineContext(); job.ensureActive()
        require(destination.canonicalFile != originalVideo.canonicalFile && !destination.exists()) {
            "实况输出不能覆盖原文件"
        }
        AlbumMediaValidation.motionVideo(originalVideo)
        val cover = File(destination.parentFile, ".live-cover-${UUID.randomUUID()}.jpg")
        val silent = if (removeAudio) File(destination.parentFile, ".live-motion-${UUID.randomUUID()}.mp4") else null
        var coverOwned = false
        var silentOwned = false
        try {
            val video = silent?.let { SilentVideoRemuxer.copy(originalVideo, it); silentOwned = true; it } ?: originalVideo
            job.ensureActive()
            val timestamp = firstSyncTimestamp(video)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.absolutePath)
                val frame = retriever.getFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: error("无法从动图读取实况封面")
                try {
                    job.ensureActive()
                    check(cover.createNewFile()) { "无法创建实况封面" }; coverOwned = true
                    cover.outputStream().use { check(frame.compress(Bitmap.CompressFormat.JPEG, 98, it)) { "实况封面生成失败" } }
                } finally { frame.recycle() }
            } finally { retriever.release() }
            job.ensureActive()
            return create(cover, video, destination, timestamp, format)
        } finally { if (coverOwned) cover.delete(); if (silentOwned) silent?.delete() }
    }

    /** Preserve the higher-resolution source cover when its correspondence is verifiable.
     * When it cannot be established, callers offer explicit conversion with a video-frame
     * cover rather than assigning a plausible but unconfirmed timestamp to the original.
     */
    suspend fun createForGallery(
        jpeg: File,
        originalVideo: File,
        destination: File,
        format: MotionPhotoContainer.Format = preferredFormat(),
        presentationTimestampUs: Long = -1,
    ): File {
        if (presentationTimestampUs >= 0 || format == MotionPhotoContainer.Format.STANDARD)
            return create(jpeg, originalVideo, destination, presentationTimestampUs, format)
        val job = currentCoroutineContext(); job.ensureActive()
        val cover = ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpeg)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(4)
        }
        val retriever = MediaMetadataRetriever()
        val timestamp: Long
        try {
            retriever.setDataSource(originalVideo.absolutePath)
            timestamp = matchCoverTimestamp(cover, originalVideo, retriever) { job.ensureActive() }
                ?: error("未能确认原封面对应的动态帧，请选择动图转实况；原图片和动态资源仍可分别保存")
        } finally { cover.recycle(); retriever.release() }
        return create(jpeg, originalVideo, destination, timestamp, format)
    }

    fun preferredFormat(): MotionPhotoContainer.Format {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        return if (listOf("xiaomi", "redmi", "poco").any(brand::contains)) MotionPhotoContainer.Format.XIAOMI
            else MotionPhotoContainer.Format.STANDARD
    }

    private fun firstSyncTimestamp(video: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(video.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: error("动态资源没有可用的视频轨道")
            extractor.selectTrack(track); extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            require(extractor.sampleTrackIndex == track && extractor.sampleSize > 0 && extractor.sampleTime >= 0) {
                "无法确认实况封面对应的动态帧"
            }
            extractor.sampleTime
        } finally { extractor.release() }
    }

    private data class CoverScore(val meanSquaredError: Double, val largeDifference: Int) {
        val accepted: Boolean get() = meanSquaredError <= 144 && largeDifference <= 48 * 48 / 20
    }

    private fun matchCoverTimestamp(cover: Bitmap, video: File, retriever: MediaMetadataRetriever,
        checkActive: () -> Unit): Long? {
        val timestamps = mutableListOf<Long>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: return null
            extractor.selectTrack(track)
            do {
                checkActive()
                if (extractor.sampleTrackIndex != track || extractor.sampleTime < 0 || extractor.sampleSize <= 0) break
                timestamps += extractor.sampleTime
                require(timestamps.size <= 100_000) { "动态资源的帧数过多" }
            } while (extractor.advance())
        } finally { extractor.release() }
        val samples = timestamps.distinct().sorted()
        if (samples.size < 2) return null
        var best: Pair<Int, CoverScore>? = null
        val examined = mutableSetOf<Int>()
        fun compare(index: Int) {
            if (!examined.add(index)) return
            checkActive()
            val frame = retriever.getScaledFrameAtTime(samples[index], MediaMetadataRetriever.OPTION_CLOSEST, 96, 96) ?: return
            try {
                val score = coverScore(cover, frame) ?: return
                if (best == null || score.meanSquaredError < best!!.second.meanSquaredError) best = index to score
            } finally { frame.recycle() }
        }
        compare(0)
        // Nearly identical first frames are common and need no complete scan.
        if (best?.second?.let { it.accepted && it.meanSquaredError <= 4.0 } == true) return samples[0]
        val coarse = (0..minOf(120, samples.lastIndex)).map {
            (it.toLong() * samples.lastIndex / minOf(120, samples.lastIndex)).toInt()
        }.distinct()
        coarse.forEach(::compare)
        val winner = best?.first ?: return null
        val before = coarse.lastOrNull { it < winner } ?: 0
        val after = coarse.firstOrNull { it > winner } ?: samples.lastIndex
        for (index in before..after) compare(index)
        return best?.takeIf { it.second.accepted }?.let { samples[it.first] }
    }

    private fun coverScore(cover: Bitmap, frame: Bitmap): CoverScore? {
        val ratio = cover.width.toDouble() / cover.height / (frame.width.toDouble() / frame.height)
        if (ratio !in 0.97..1.03) return null
        val a = Bitmap.createScaledBitmap(cover, 48, 48, true)
        val b = Bitmap.createScaledBitmap(frame, 48, 48, true)
        return try {
            var squaredError = 0L; var largeDifference = 0
            for (y in 0 until 48) for (x in 0 until 48) {
                val left = a.getPixel(x, y); val right = b.getPixel(x, y)
                val differences = intArrayOf(Color.red(left) - Color.red(right),
                    Color.green(left) - Color.green(right), Color.blue(left) - Color.blue(right))
                differences.forEach { squaredError += it * it }
                if (differences.any { kotlin.math.abs(it) > 32 }) largeDifference++
            }
            // Permit ordinary JPEG/video quantization; not a different illustration or crop.
            CoverScore(squaredError.toDouble() / (48 * 48 * 3), largeDifference)
        } finally {
            if (a !== cover) a.recycle()
            if (b !== frame) b.recycle()
        }
    }

    /** Verifies the actual embedded video via offset/length, rather than parsing XMP alone. */
    fun validate(file: File): Verified {
        val info = MotionPhotoContainer.inspect(file) ?: error("图片没有包含实况动态内容")
        require(info.videoOffset <= AlbumMediaPolicy.MAX_IMAGE_BYTES && info.videoLength <= AlbumMediaPolicy.MAX_MOTION_BYTES) {
            "实况照片文件过大"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outMimeType == "image/jpeg") { "实况照片封面无法解码" }
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
            val video = videoTracks.firstOrNull() ?: error("实况照片内没有视频轨道")
            videoTracks.forEach { track ->
                MotionPhotoCodecPolicy.requireCompatibleVideoMime(extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME))
            }
            hasAudio = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
            extractor.selectTrack(video)
            require(extractor.sampleTrackIndex == video && extractor.sampleSize > 0 && extractor.advance() &&
                extractor.sampleTrackIndex == video && extractor.sampleSize > 0) { "实况照片内缺少连续动态帧" }
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            FileInputStream(file).use { input -> retriever.setDataSource(input.fd, info.videoOffset, info.videoLength) }
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(width > 0 && height > 0 && duration > 0) { "实况照片内的动态信息无效" }
            val frame = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128)
                ?: error("实况照片内的动态内容无法解码")
            frame.recycle()
            return Verified(info, width, height, duration, hasAudio)
        } finally { retriever.release() }
    }

    /** App preview may use a private extracted MP4; the published record remains one JPEG URI. */
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

    /** Published live photos keep one image URI. A private clip is created only for playback. */
    suspend fun extractForPreview(context: Context, uri: Uri, destination: File): File {
        val job = currentCoroutineContext(); job.ensureActive()
        require(!destination.exists()) { "动态输出文件已经存在" }
        val resolver = context.contentResolver
        val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 }
            ?: resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            } ?: error("无法读取实况照片长度")
        require(bytes in 1..(AlbumMediaPolicy.MAX_IMAGE_BYTES + AlbumMediaPolicy.MAX_MOTION_BYTES)) { "实况照片文件为空或过大" }
        val info = resolver.openInputStream(uri)?.use { MotionPhotoContainer.inspect(it, bytes) }
            ?: error("照片没有包含实况动态内容")
        var owned = false
        var finished = false
        try {
            job.ensureActive(); check(destination.createNewFile()) { "无法创建实况预览文件" }; owned = true
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                var skip = info.videoOffset
                while (skip > 0) {
                    job.ensureActive()
                    val count = input.skip(minOf(skip, 64 * 1024L))
                    if (count > 0) skip -= count else { require(input.read() >= 0) { "实况照片数据不完整" }; skip-- }
                }
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024); var remaining = info.videoLength
                    while (remaining > 0) {
                        job.ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "实况动态内容不完整" }; output.write(buffer, 0, count); remaining -= count
                    }
                    require(input.read() < 0) { "实况照片包含未确认的尾部内容" }
                }
            } ?: error("无法读取实况照片")
            AlbumMediaValidation.motionVideo(destination)
            job.ensureActive(); finished = true
            return destination
        } finally { if (owned && !finished) destination.delete() }
    }
}
