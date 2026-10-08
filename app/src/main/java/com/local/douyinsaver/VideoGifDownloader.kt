package com.local.douyinsaver

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import kotlin.math.roundToLong

/** A conversion source stays private: only the verified animated GIF is published. */
internal class VideoGifDownloader(private val context: Context, private val transfer: MediaTransfer = MediaTransfer()) {
    private val converter = VideoGifConverter()
    fun cancel() = transfer.cancel()

    suspend fun download(content: ParsedVideo, folder: DownloadFolder?, options: DownloadOptions,
        onProgress: suspend (Long, Long) -> Unit, onSaving: suspend () -> Unit,
        onSaved: (SavedVideo) -> Unit, onDiagnostic: (String) -> Unit = {}): SavedVideo {
        require(options.gifStartSeconds.isFinite() && options.gifStartSeconds >= 0 &&
            options.gifDurationSeconds.isFinite() && options.gifDurationSeconds >= 0.1f) { "GIF 截取范围无效，时长至少为 0.1 秒" }
        val storage = DownloadStorage(context)
        folder?.let(storage::validateAccess)
        val directory = File(context.cacheDir, "gif_${UUID.randomUUID()}")
        check(directory.mkdir()) { "无法创建 GIF 临时文件，请检查可用空间" }
        try {
            val source = File(directory, "source.mp4")
            val urls = WatermarkSources.candidates(content, WatermarkMode.CLEAN)
            val actualDuration = VideoSourceFallback.run(urls, WatermarkMode.CLEAN, onDiagnostic) { url, _ ->
                try {
                    transfer.fetch(url, source, GifConversionPolicy.MAX_SOURCE_BYTES, "GIF 来源视频",
                        validateUrl = { WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, content.mediaSources) }, onProgress = onProgress)
                    converter.readDurationMs(source)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    source.delete()
                    throw VideoSourceTransferException(error.message ?: "GIF 来源下载失败", error)
                }
            }
            currentCoroutineContext().ensureActive()
            val start = (options.gifStartSeconds.toDouble() * 1000).roundToLong()
            val duration = minOf((options.gifDurationSeconds.toDouble() * 1000).roundToLong(), actualDuration - start)
            require(start >= 0 && duration >= GifConversionPolicy.MIN_DURATION_MS) { "GIF 起始时间已超出视频范围，请调整后重试" }
            onSaving()
            val output = converter.convert(source, File(directory, "output.gif"), start, duration,
                quality = options.gifExportQuality, onProgress = onProgress)
            return saveLocalGif(content, output, storage, folder, options, start, duration, onSaved)
        } finally {
            transfer.cancel()
            directory.deleteRecursively()
        }
    }

    internal suspend fun saveLocalGif(content: ParsedVideo, output: File, storage: DownloadStorage,
        folder: DownloadFolder?, options: DownloadOptions, startMs: Long, durationMs: Long,
        onSaved: (SavedVideo) -> Unit): SavedVideo {
        val verified = AlbumMediaValidation.image(output, maximumBytes = GifConversionPolicy.MAX_GIF_BYTES)
        require(verified.mimeType == "image/gif" && verified.animated) { "GIF 没有有效的动画帧，保存已停止" }
        val name = FileNames.build(content, options, "gif")
        val pending = storage.createPending(name, folder, "image/gif")
        var committed = false
        try {
            output.inputStream().use { input ->
                context.contentResolver.openOutputStream(pending.uri, "w")?.use { target ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                        copied += count
                    }
                    target.flush()
                    require(copied == output.length()) { "GIF 保存不完整，请重试" }
                } ?: error("无法写入 GIF，请检查保存位置和可用空间")
            }
            currentCoroutineContext().ensureActive()
            val uri = pending.publish().toString()
            val result = SavedVideo(content.id, content.title, uri, output.length(), pending.locationLabel,
                mimeType = "image/gif", coverUri = uri, fileName = name, watermarkMode = WatermarkMode.CLEAN,
                exportMode = AlbumMode.GIF.name, gifStartMs = startMs, gifDurationMs = durationMs,
                gifExportQuality = options.gifExportQuality.name)
            committed = true
            onSaved(result)
            return result
        } finally {
            if (!committed) runCatching { pending.rollback() }
            runCatching { pending.cleanup() }
        }
    }
}
