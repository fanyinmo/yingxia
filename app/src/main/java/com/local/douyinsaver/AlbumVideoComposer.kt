package com.local.douyinsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Android's public Media3 codec pipeline; all inputs and the MP4 remain on this phone. */
@OptIn(UnstableApi::class)
internal class AlbumVideoComposer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var transformer: Transformer? = null
    @Volatile private var completion: CompletableDeferred<Unit>? = null

    fun cancel() {
        completion?.cancel(CancellationException("用户取消合成"))
        val current = transformer
        if (current != null) main.post { if (transformer === current) current.cancel() }
    }

    suspend fun compose(
        images: List<LocalAlbumImage>,
        audio: File,
        audioDurationMs: Long,
        imageSeconds: Int,
        directory: File,
        onProgress: suspend (Int) -> Unit,
    ): File {
        return composeEntries(images.map { LocalAlbumEntry(it) }, audio, audioDurationMs,
            images.map { imageSeconds * 1000L }, directory, onProgress)
    }

    suspend fun composeEntries(
        entries: List<LocalAlbumEntry>, audio: File, audioDurationMs: Long,
        durationsMs: List<Long>, directory: File, onProgress: suspend (Int) -> Unit,
    ): File {
        require(directory.isDirectory) { "合成临时目录不可用" }
        val ownedDirectory = File(directory, "composition_${UUID.randomUUID()}")
        check(ownedDirectory.mkdir()) { "无法创建合成临时目录" }
        var result: File? = null
        try {
            return composeOwnedEntries(entries, audio, audioDurationMs, durationsMs, ownedDirectory, onProgress)
                .also { result = it }
        } finally {
            ownedDirectory.listFiles()?.filter { it != result }?.forEach { it.delete() }
            if (result == null) ownedDirectory.delete()
        }
    }

    private suspend fun composeOwnedEntries(
        entries: List<LocalAlbumEntry>, audio: File, audioDurationMs: Long,
        durationsMs: List<Long>, directory: File, onProgress: suspend (Int) -> Unit,
    ): File {
        require(entries.size == durationsMs.size && entries.isNotEmpty()) { "素材时长与顺序不完整" }
        require(durationsMs.all { it > 0 } && durationsMs.sum() <= 900_000L) { "合成视频时长无效或超过 15 分钟" }
        val durationMs = durationsMs.sum()
        // A bounded, oriented JPEG source prevents huge originals from exhausting decoder/GL memory.
        val inputs = entries.mapIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            if (entry.motionVideo != null) entry.motionVideo
            else if (entry.image.animated) AnimatedImageVideoConverter(context).convert(entry.image,
                File(directory, "animation_$index.mp4"))
            else prepareImage(entry.image.file, File(directory, "frame_$index.jpg"))
        }
        val (width, height) = AlbumMediaPolicy.canvas(entries.first().image.width, entries.first().image.height)
        val presentation = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
        val imageItems = inputs.flatMapIndexed { index, file ->
            val isDynamic = entries[index].motionVideo != null || entries[index].image.animated
            val durations = if (isDynamic) AlbumTiming.segments(VideoGifConverter().readDurationMs(file), durationsMs[index])
                else listOf(durationsMs[index])
            durations.map { duration ->
                val builder = MediaItem.Builder().setUri(Uri.fromFile(file))
                if (isDynamic) builder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0).setEndPositionMs(duration).build())
                else builder.setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(duration)
                EditedMediaItem.Builder(builder.build()).setFrameRate(30).setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), listOf(presentation))).build()
            }
        }
        val audioItem = MediaItem.Builder().setUri(Uri.fromFile(audio))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(0).setEndPositionMs(minOf(audioDurationMs, durationMs)).build())
            .build()
        val audioSequence = EditedMediaItemSequence.Builder(
            EditedMediaItem.Builder(audioItem).setRemoveVideo(true).build(),
        ).setIsLooping(true).build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(imageItems).build(), audioSequence).build()
        val output = File(directory, "composed.mp4")
        val finished = CompletableDeferred<Unit>()
        completion = finished
        var current: Transformer? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                current = Transformer.Builder(context).setLooper(Looper.getMainLooper())
                    // Keep SDR stills and clips in their original transfer space. The default
                    // linear working space visibly darkens static sections on this pipeline.
                    .setVideoFrameProcessorFactory(DefaultVideoFrameProcessor.Factory.Builder()
                        .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL).build())
                    .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, AlbumDecoderFactory(context), Clock.DEFAULT, null))
                    .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setMaxDelayBetweenMuxerSamplesMs(60_000)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finished.complete(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            finished.completeExceptionally(IllegalStateException(
                                "图片与 BGM 合成失败（${exportException.errorCodeName}），可选择保存图片", exportException,
                            ))
                        }
                    }).build()
                transformer = current
                current!!.start(composition, output.absolutePath)
            }
            onProgress(0)
            // The entire export is bounded, independently of the library's no-output watchdog.
            withTimeout(maxOf(300_000L, durationMs * 4).coerceAtMost(3_600_000L)) {
                while (!finished.isCompleted) {
                    currentCoroutineContext().ensureActive()
                    delay(300)
                    require(output.length() <= AlbumMediaPolicy.MAX_VIDEO_BYTES) { "合成视频超过 2 GB 上限" }
                    val percent = withContext(Dispatchers.Main.immediate) {
                        val progress = ProgressHolder()
                        if (current!!.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) progress.progress else -1
                    }
                    if (percent >= 0) onProgress(percent.coerceIn(0, 99))
                }
                finished.await()
            }
            require(AlbumMediaPolicy.fitsBytes(output.length(), AlbumMediaPolicy.MAX_VIDEO_BYTES)) { "合成视频为空或超过 2 GB 上限" }
            currentCoroutineContext().ensureActive()
            AlbumMediaValidation.composedVideo(context, Uri.fromFile(output), durationMs)
            onProgress(100)
            return output
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("图片与 BGM 合成超时，可选择保存图片或稍后重试")
        } finally {
            completion = null
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                current?.cancel()
                transformer = null
            }
        }
    }

    private suspend fun prepareImage(source: File, output: File): File {
        currentCoroutineContext().ensureActive()
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > 1600) {
                val scale = 1600.0 / largest
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            output.outputStream().buffered().use {
                require(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "无法处理图集图片，可选择保存图片" }
            }
        } finally { bitmap.recycle() }
        return output
    }
}
