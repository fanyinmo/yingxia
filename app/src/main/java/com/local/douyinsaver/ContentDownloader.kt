package com.local.douyinsaver

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** One entry point for videos, image albums, and local image + BGM composition.
 * Internal album/GIF transport injection retains the real codecs and publication path.
 */
class ContentDownloader internal constructor(context: Context, transfer: MediaTransfer?, sourceProbe: MediaProbe?) {
    constructor(context: Context) : this(context, null, null)

    private val videos = VideoDownloader(context.applicationContext)
    private val albums = if (transfer == null) AlbumDownloader(context.applicationContext)
        else AlbumDownloader(context.applicationContext, transfer)
    private val gifs = if (transfer == null) VideoGifDownloader(context.applicationContext)
        else VideoGifDownloader(context.applicationContext, transfer)
    private val probe = sourceProbe ?: MediaProbe()
    @Volatile private var job: Job? = null

    fun cancel() {
        job?.cancel(CancellationException("用户取消下载"))
        videos.cancel()
        albums.cancel()
        gifs.cancel()
        probe.cancel()
    }

    suspend fun download(
        content: ParsedVideo,
        folder: DownloadFolder?,
        options: DownloadOptions,
        onProgress: suspend (Long, Long) -> Unit,
        onSaving: suspend () -> Unit,
        onSaved: (SavedVideo) -> Unit,
        onDiagnostic: (String) -> Unit = {},
        onDurationMismatch: suspend (List<DurationAdjustment>) -> Boolean = { true },
    ): SavedVideo {
        require(options.albumMode.isSupportedExport()) { "此保存格式已移除" }
        job = currentCoroutineContext()[Job]
        return try {
            val cleanOptions = options.copy(watermarkMode = WatermarkMode.CLEAN,
                selectedImageIndices = options.selectedImageIndices.sorted())
            val subset = AlbumSelection.select(content, options.selectedImageIndices)
            val input = if (options.albumMode == AlbumMode.COVERS) subset.copy(images = subset.images.map {
                it.copy(kind = AlbumAssetKind.STATIC, motion = null)
            }) else subset
            val selected = if (input.isAlbum) WatermarkSources.selectForDownload(input, WatermarkMode.CLEAN)
                else WatermarkSources.select(input, WatermarkMode.CLEAN)
            if (selected.isAlbum) albums.download(selected, folder, cleanOptions, onProgress, onSaving, onSaved, onDurationMismatch)
            else {
                // Preview uses this same refresh API; stale CDN URLs can only fall back within this rendition.
                val verified = probe.verifySelected(content, WatermarkMode.CLEAN, onDiagnostic)
                val playable = WatermarkSources.select(verified, WatermarkMode.CLEAN)
                if (cleanOptions.albumMode == AlbumMode.GIF)
                    gifs.download(playable, folder, cleanOptions, onProgress, onSaving, onSaved, onDiagnostic)
                else videos.download(playable, folder, onProgress, onSaving, onSaved, cleanOptions, onDiagnostic)
            }
        } finally {
            job = null
        }
    }
}
