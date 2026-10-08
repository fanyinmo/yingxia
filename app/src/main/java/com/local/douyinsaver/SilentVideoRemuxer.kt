package com.local.douyinsaver

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/** Copy the compressed video track without decoding, resizing or including source audio. */
internal object SilentVideoRemuxer {
    suspend fun copy(source: File, destination: File): File {
        require(source.canonicalFile != destination.canonicalFile && !destination.exists() && destination.parentFile?.isDirectory == true) {
            "动图输出不能覆盖现有文件"
        }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var completed = false
        var owned = false
        try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: error("动态素材没有视频轨道")
            val format = extractor.getTrackFormat(index)
            // Use this video track's duration, never the longer movie/audio duration.
            val videoDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0L }
            } else null
            val maximum = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buffer = ByteBuffer.allocateDirect(maxOf(4 * 1024 * 1024, maximum).coerceAtMost(32 * 1024 * 1024))
            owned = destination.createNewFile()
            require(owned) { "动图输出文件已存在" }
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(source.absolutePath)
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                    ?.takeIf { it in listOf(0, 90, 180, 270) }?.let(muxer::setOrientationHint)
            } finally { reader.release() }
            val track = muxer.addTrack(format)
            muxer.start(); started = true
            extractor.selectTrack(index)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            var maximumPresentationTimeUs = Long.MIN_VALUE
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                require(extractor.sampleSize <= buffer.capacity()) { "动态素材的单帧过大" }
                buffer.clear()
                val bytes = extractor.readSampleData(buffer, 0)
                if (bytes < 0) break
                info.set(0, bytes, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buffer, info)
                maximumPresentationTimeUs = maxOf(maximumPresentationTimeUs, info.presentationTimeUs)
                samples++
                extractor.advance()
            }
            require(samples >= 2) { "动态素材缺少连续视频帧" }
            currentCoroutineContext().ensureActive()
            // A zero-byte EOS explicitly preserves the last picture's display interval.
            // It adds no encoded frame and changes none of the copied sample timestamps.
            // Android otherwise infers the final duration, which can clip a reordered tail.
            // A missing/inconsistent duration must not invent an endpoint from source audio.
            if (videoDurationUs != null && videoDurationUs > maximumPresentationTimeUs) {
                info.set(0, 0, videoDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                muxer.writeSampleData(track, ByteBuffer.allocate(0), info)
            }
            muxer.stop(); started = false
            AlbumMediaValidation.motionVideo(destination)
            completed = true
            return destination
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }; extractor.release()
            if (owned && !completed) destination.delete()
        }
    }
}
