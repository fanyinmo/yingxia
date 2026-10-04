package com.local.douyinsaver

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** One entry point for videos, image albums, and local image + BGM composition. */
class ContentDownloader(context: Context) {
    private val videos = VideoDownloader(context.applicationContext)
    private val albums = AlbumDownloader(context.applicationContext)
    private val probe = MediaProbe()
    @Volatile private var job: Job? = null

    fun cancel() {
        job?.cancel(CancellationException("用户取消下载"))
        videos.cancel()
        albums.cancel()
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
    ): SavedVideo {
        job = currentCoroutineContext()[Job]
        return try {
            val cleanOptions = options.copy(watermarkMode = WatermarkMode.CLEAN)
            val selected = WatermarkSources.select(content, WatermarkMode.CLEAN)
            if (selected.isAlbum) albums.download(selected, folder, cleanOptions, onProgress, onSaving, onSaved)
            else {
                // Preview uses this same refresh API; stale CDN URLs can only fall back within this rendition.
                val verified = probe.verifySelected(content, WatermarkMode.CLEAN, onDiagnostic)
                val playable = WatermarkSources.select(verified, WatermarkMode.CLEAN)
                videos.download(playable, folder, onProgress, onSaving, onSaved, cleanOptions, onDiagnostic)
            }
        } finally {
            job = null
        }
    }
}
