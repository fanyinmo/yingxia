package com.local.douyinsaver

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** One entry point for videos, image albums, and local image + BGM composition. */
class ContentDownloader(context: Context) {
    private val videos = VideoDownloader(context.applicationContext)
    private val albums = AlbumDownloader(context.applicationContext)
    @Volatile private var job: Job? = null

    fun cancel() {
        job?.cancel(CancellationException("用户取消下载"))
        videos.cancel()
        albums.cancel()
    }

    suspend fun download(
        content: ParsedVideo,
        folder: DownloadFolder?,
        options: DownloadOptions,
        onProgress: suspend (Long, Long) -> Unit,
        onSaving: suspend () -> Unit,
        onSaved: (SavedVideo) -> Unit,
    ): SavedVideo {
        job = currentCoroutineContext()[Job]
        return try {
            if (content.isAlbum) albums.download(content, folder, options, onProgress, onSaving, onSaved)
            else videos.download(content, folder, onProgress, onSaving, onSaved, options)
        } finally {
            job = null
        }
    }
}
