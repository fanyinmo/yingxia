package com.local.douyinsaver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

internal class AlbumDownloader(private val context: Context) {
    private val transfer = MediaTransfer()
    private val composer = AlbumVideoComposer(context)

    fun cancel() {
        transfer.cancel()
        composer.cancel()
    }

    suspend fun download(
        content: ParsedVideo,
        folder: DownloadFolder?,
        options: DownloadOptions,
        onProgress: suspend (Long, Long) -> Unit,
        onSaving: suspend () -> Unit,
        onSaved: (SavedVideo) -> Unit,
    ): SavedVideo {
        AlbumMediaPolicy.validateImages(content.images.size)
        if (options.albumMode == AlbumMode.VIDEO) {
            require(content.bgmUrl.isNotBlank()) { "这个图集没有可用的 BGM，请选择保存图片" }
            AlbumMediaPolicy.durationMs(content.images.size, options.imageSeconds)
        }
        val storage = DownloadStorage(context)
        folder?.let { storage.validateAccess(it) }
        val directory = File(context.cacheDir, "album_${UUID.randomUUID()}")
        check(directory.mkdir()) { "无法创建图集临时文件，请检查可用空间" }
        try {
            var downloaded = 0L
            val images = content.images.mapIndexed { index, image ->
                currentCoroutineContext().ensureActive()
                val local = File(directory, "source_$index")
                val before = downloaded
                val bytes = transfer.fetch(image.url, local, AlbumMediaPolicy.MAX_IMAGE_BYTES, "第 ${index + 1} 张图片",
                    validateUrl = { url -> WatermarkSources.requireSelectedUrl(url, options.watermarkMode, image.mediaSources) }) { bytes, _ ->
                    require(before + bytes <= AlbumMediaPolicy.MAX_ALBUM_BYTES) { "图集总大小超过 512 MB 上限" }
                    onProgress(before + bytes, -1L)
                }
                downloaded += bytes
                val verified = AlbumMediaValidation.image(local)
                // Original bytes are preserved when the customer selects image download.
                verified
            }
            return if (options.albumMode == AlbumMode.IMAGES) {
                onSaving()
                saveLocalImages(content, images, storage, folder, options, onProgress, onSaved)
            } else {
                val audio = File(directory, "bgm")
                val before = downloaded
                transfer.fetch(content.bgmUrl, audio, AlbumMediaPolicy.MAX_AUDIO_BYTES, "BGM") { bytes, _ ->
                    onProgress(before + bytes, -1L)
                }
                val audioDuration = AlbumMediaValidation.audioDurationMs(audio)
                onSaving()
                val output = composer.compose(images, audio, audioDuration, options.imageSeconds, directory) {
                    onProgress(it.toLong(), 100L)
                }
                saveVideo(content, output, storage, folder, options, onProgress, onSaved)
            }
        } finally {
            transfer.cancel()
            // This unique, app-private directory contains only files from this operation.
            directory.deleteRecursively()
        }
    }

    /** Shared by the normal validated download path and isolated local-media instrumentation. */
    internal suspend fun saveLocalImages(
        content: ParsedVideo,
        images: List<LocalAlbumImage>,
        storage: DownloadStorage,
        folder: DownloadFolder?,
        options: DownloadOptions,
        onProgress: suspend (Long, Long) -> Unit,
        onSaved: (SavedVideo) -> Unit,
    ): SavedVideo {
        AlbumMediaPolicy.validateImages(images.size)
        val pending = mutableListOf<DownloadStorage.PendingDownload>()
        val names = mutableListOf<String>()
        val total = images.sumOf { it.file.length() }
        var copied = 0L
        var committed = false
        try {
            val base = FileNames.build(content, options, "jpg").substringBeforeLast('.')
            for ((index, image) in images.withIndex()) {
                currentCoroutineContext().ensureActive()
                val name = "${base}_${(index + 1).toString().padStart(3, '0')}.${image.extension}"
                val item = storage.createPending(name, folder, image.mimeType)
                pending.add(item)
                names.add(name)
                val before = copied
                copy(image.file, item.uri) { progress -> onProgress(before + progress, total) }
                copied += image.file.length()
            }
            currentCoroutineContext().ensureActive()
            val uris = pending.map {
                currentCoroutineContext().ensureActive()
                it.publish().toString()
            }
            val result = SavedVideo(
                id = content.id, title = content.title, uri = uris.first(), bytes = total,
                locationLabel = pending.first().locationLabel,
                mimeType = images.map { it.mimeType }.distinct().singleOrNull() ?: "image/*",
                uris = uris, coverUri = uris.first(), fileName = names.first(), isAlbum = true,
                watermarkMode = WatermarkSources.actualMode(content, options.watermarkMode),
            )
            committed = true
            onSaved(result)
            return result
        } finally {
            if (!committed) pending.forEach { runCatching { it.rollback() } }
            pending.forEach { runCatching { it.cleanup() } }
        }
    }

    private suspend fun saveVideo(
        content: ParsedVideo,
        video: File,
        storage: DownloadStorage,
        folder: DownloadFolder?,
        options: DownloadOptions,
        onProgress: suspend (Long, Long) -> Unit,
        onSaved: (SavedVideo) -> Unit,
    ): SavedVideo {
        val name = FileNames.build(content, options, "mp4")
        val pending = storage.createPending(name, folder)
        try {
            copy(video, pending.uri) { onProgress(it, video.length()) }
            currentCoroutineContext().ensureActive()
            AlbumMediaValidation.composedVideo(context, pending.uri, AlbumMediaPolicy.durationMs(content.images.size, options.imageSeconds))
            currentCoroutineContext().ensureActive()
            val published = pending.publish()
            val result = SavedVideo(content.id, content.title, published.toString(), video.length(), pending.locationLabel,
                fileName = name, isAlbum = true, watermarkMode = WatermarkSources.actualMode(content, options.watermarkMode))
            onSaved(result)
            return result
        } finally { runCatching { pending.cleanup() } }
    }

    private suspend fun copy(file: File, destination: Uri, onProgress: suspend (Long) -> Unit) {
        var written = 0L
        file.inputStream().use { input ->
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    output.write(buffer, 0, bytes)
                    written += bytes
                    onProgress(written)
                }
                output.flush()
            } ?: error("无法写入下载文件，请检查权限和可用空间")
        }
        require(written == file.length()) { "保存文件不完整，请重试" }
    }
}
