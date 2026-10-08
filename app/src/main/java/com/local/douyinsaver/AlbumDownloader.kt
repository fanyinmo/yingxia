package com.local.douyinsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

internal class AlbumDownloader(private val context: Context, private val transfer: MediaTransfer = MediaTransfer()) {
    private val composer = AlbumVideoComposer(context)
    private val gifConverter = VideoGifConverter()
    fun cancel() { transfer.cancel(); composer.cancel() }

    suspend fun download(content: ParsedVideo, folder: DownloadFolder?, options: DownloadOptions,
        onProgress: suspend (Long, Long) -> Unit, onSaving: suspend () -> Unit, onSaved: (SavedVideo) -> Unit,
        onDurationMismatch: suspend (List<DurationAdjustment>) -> Boolean = { true }): SavedVideo {
        require(options.albumMode.isSupportedExport()) { "此保存格式已移除" }
        AlbumMediaPolicy.validateImages(content.images.size)
        val storage = DownloadStorage(context)
        folder?.let(storage::validateAccess)
        val directory = File(context.cacheDir, "album_${UUID.randomUUID()}")
        check(directory.mkdir()) { "无法创建临时文件，请检查可用空间" }
        try {
            var downloaded = 0L
            val entries = content.images.mapIndexed { index, image ->
                currentCoroutineContext().ensureActive()
                val local = File(directory, "source_$index")
                val before = downloaded
                downloaded += transfer.fetch(image.url, local, AlbumMediaPolicy.MAX_IMAGE_BYTES, "第 ${index + 1} 项图片",
                    validateUrl = { WatermarkSources.requireSelectedUrl(it, options.watermarkMode, image.mediaSources) }) { read, _ ->
                    require(before + read <= AlbumMediaPolicy.MAX_ALBUM_BYTES) { "图集素材超过 512 MB 上限" }
                    onProgress(before + read, -1)
                }
                val verified = AlbumMediaValidation.image(local)
                require(options.albumMode == AlbumMode.COVERS || image.kind != AlbumAssetKind.ANIMATED || image.motion != null || verified.animated) {
                    "第 ${index + 1} 项只返回了静态封面，未取得动图内容"
                }
                val embedded = options.albumMode != AlbumMode.COVERS && verified.mimeType == "image/jpeg" &&
                    MotionPhotoContainer.inspect(local) != null
                val motion = if (embedded) {
                    EmbeddedMotionReader.validate(local)
                    EmbeddedMotionReader.extractForPreview(local, File(directory, "motion_$index.mp4"))
                } else image.motion?.takeIf { options.albumMode != AlbumMode.COVERS }?.let { source ->
                    val video = File(directory, "motion_$index.mp4")
                    val beforeMotion = downloaded
                    downloaded += fetchMotion(source, video, options.watermarkMode, index) { read ->
                        require(beforeMotion + read <= AlbumMediaPolicy.MAX_ALBUM_BYTES) { "图集素材超过 512 MB 上限" }
                        onProgress(beforeMotion + read, -1)
                    }
                    video
                }
                // A native motion JPEG can carry its complete clip without a separate
                // motion URL. Only decide after decoding the downloaded image and
                // validating either its embedded MP4 or its explicit external clip.
                require(options.albumMode == AlbumMode.COVERS || image.kind != AlbumAssetKind.LIVE || motion != null) {
                    "第 ${index + 1} 项缺少动态内容，请检查动态资源；不会仅保存封面"
                }
                LocalAlbumEntry(verified, motion)
            }
            return when (options.albumMode) {
                AlbumMode.IMAGES, AlbumMode.COVERS, AlbumMode.MOTION_VIDEOS -> {
                    onSaving(); saveLocalEntries(content, entries, storage, folder, options, onProgress, onSaved)
                }
                AlbumMode.LIVE_PHOTOS, AlbumMode.CONVERT_TO_LIVE -> error("此保存格式已移除")
                AlbumMode.GIF -> {
                    onSaving()
                    if (entries.all { !it.image.animated && it.motionVideo == null }) {
                        val duration = AlbumTiming.staticMilliseconds(options)
                        val output = AlbumGifComposer(options.gifExportQuality).compose(entries.map { it.image }, duration, File(directory, "sequence.gif"), onProgress)
                        publishFiles(content, listOf(AlbumOutput(output, "image/gif", "gif", AlbumAssetKind.ANIMATED)),
                            storage, folder, options, onProgress, onSaved)
                    } else {
                        val videos = entries.mapIndexed { index, entry -> entry.motionVideo ?:
                            if (entry.image.animated && (entry.image.mimeType != "image/gif" || options.itemDurationSeconds != null ||
                                options.gifExportQuality == GifExportQuality.SHARE))
                                AnimatedImageVideoConverter(context).convert(entry.image, File(directory, "native_$index.mp4")) else null }
                        val originals = videos.map { file -> file?.let { gifConverter.readDurationMs(it) } }
                        val durations = AlbumTiming.durations(originals, options)
                        val changes = if (options.itemDurationSeconds == null) emptyList() else AlbumTiming.adjustments(originals, durations, options.selectedImageIndices)
                        if (changes.isNotEmpty() && !onDurationMismatch(changes)) throw CancellationException("返回修改素材时长")
                        val outputs = entries.mapIndexed { index, entry ->
                            currentCoroutineContext().ensureActive()
                            val video = videos[index]
                            val sourceIndex = options.selectedImageIndices.getOrNull(index) ?: index
                            if (video != null) {
                                val output = gifConverter.convertLooped(video, File(directory, "motion_$index.gif"), durations[index],
                                    quality = options.gifExportQuality, onProgress = onProgress)
                                val verified = AlbumMediaValidation.image(output, GifConversionPolicy.MAX_GIF_BYTES)
                                require(verified.animated && verified.mimeType == "image/gif") { "第 ${index + 1} 项没有生成有效 GIF" }
                                AlbumOutput(output, "image/gif", "gif", AlbumAssetKind.ANIMATED, sourceIndex = sourceIndex)
                            } else AlbumOutput(entry.image.file, entry.image.mimeType, entry.image.extension,
                                if (entry.image.animated) AlbumAssetKind.ANIMATED else AlbumAssetKind.STATIC, sourceIndex = sourceIndex)
                        }
                        publishFiles(content, outputs, storage, folder, options, onProgress, onSaved)
                    }
                }
                AlbumMode.VIDEO -> {
                    require(content.bgmUrl.isNotBlank()) { "这个作品未提供可读取的 BGM" }
                    val prepared = entries.mapIndexed { index, entry ->
                        if (entry.motionVideo == null && entry.image.animated) entry.copy(motionVideo =
                            AnimatedImageVideoConverter(context).convert(entry.image, File(directory, "native_$index.mp4"))) else entry
                    }
                    val originals = prepared.map { entry -> entry.motionVideo?.let { gifConverter.readDurationMs(it) } }
                    val durations = AlbumTiming.durations(originals, options)
                    val changes = if (options.itemDurationSeconds == null) emptyList() else AlbumTiming.adjustments(originals, durations, options.selectedImageIndices)
                    if (changes.isNotEmpty() && !onDurationMismatch(changes)) throw CancellationException("返回修改素材时长")
                    val audio = File(directory, "bgm")
                    transfer.fetch(content.bgmUrl, audio, AlbumMediaPolicy.MAX_AUDIO_BYTES, "BGM", onProgress = onProgress)
                    val audioDuration = AlbumMediaValidation.audioDurationMs(audio)
                    onSaving()
                    val output = composer.composeEntries(prepared, audio, audioDuration, durations, directory) {
                        onProgress(it.toLong(), 100)
                    }
                    saveVideo(content, output, durations.sum(), storage, folder, options, onProgress, onSaved)
                }
            }
        } finally { transfer.cancel(); directory.deleteRecursively() }
    }

    private suspend fun fetchMotion(motion: ParsedMotion, destination: File, mode: WatermarkMode,
        index: Int, onProgress: suspend (Long) -> Unit): Long {
        val sources = motion.mediaSources.filter { it.mode == mode }.map { it.url }
        val urls = (listOf(motion.url).filter { it in sources || mode == WatermarkMode.ORIGINAL } + sources).distinct().filter(MediaUrls::isAllowed)
        require(urls.isNotEmpty()) { "第 ${index + 1} 项没有可用动态地址" }
        var last: Exception? = null
        for (url in urls) {
            try {
                currentCoroutineContext().ensureActive()
                val bytes = transfer.fetch(url, destination, AlbumMediaPolicy.MAX_MOTION_BYTES, "第 ${index + 1} 项动态素材",
                    validateUrl = { WatermarkSources.requireSelectedUrl(it, mode, motion.mediaSources) }) { read, _ -> onProgress(read) }
                AlbumMediaValidation.motionVideo(destination)
                return bytes
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); last = error; destination.delete() }
        }
        throw IllegalStateException("第 ${index + 1} 项动态素材下载失败，请重新解析", last)
    }

    internal suspend fun saveLocalImages(content: ParsedVideo, images: List<LocalAlbumImage>, storage: DownloadStorage,
        folder: DownloadFolder?, options: DownloadOptions, onProgress: suspend (Long, Long) -> Unit,
        onSaved: (SavedVideo) -> Unit): SavedVideo = saveLocalEntries(content, images.map { LocalAlbumEntry(it) }, storage, folder, options, onProgress, onSaved)

    internal suspend fun saveLocalEntries(content: ParsedVideo, entries: List<LocalAlbumEntry>, storage: DownloadStorage,
        folder: DownloadFolder?, options: DownloadOptions, onProgress: suspend (Long, Long) -> Unit,
        onSaved: (SavedVideo) -> Unit): SavedVideo {
        require(options.albumMode.isSupportedExport()) { "此保存格式已移除" }
        require(entries.size == content.images.size) { "素材顺序或数量不完整" }
        val prepared = mutableListOf<File>()
        try {
            val outputs = entries.mapIndexed { index, entry ->
                val source = content.images[index].let { image ->
                    if (image.kind != AlbumAssetKind.DYNAMIC || options.albumMode == AlbumMode.COVERS) image
                    else image.copy(kind = when (options.albumMode) {
                        AlbumMode.MOTION_VIDEOS -> AlbumAssetKind.ANIMATED
                        else -> error("网页未标注动态素材类型，请选择保存为无声动图或 GIF")
                    })
                }
                val sourceIndex = options.selectedImageIndices.getOrNull(index) ?: index
                if (options.albumMode == AlbumMode.COVERS) {
                    val output = File(entry.image.file.parentFile, "cover_${UUID.randomUUID()}.jpg").also(prepared::add)
                    jpegCover(entry.image, output)
                    AlbumOutput(output, "image/jpeg", "jpg", AlbumAssetKind.STATIC, sourceIndex = sourceIndex)
                } else if (entry.motionVideo != null || (entry.image.mimeType == "image/jpeg" && MotionPhotoContainer.inspect(entry.image.file) != null)) {
                    val motion = entry.motionVideo ?: File(entry.image.file.parentFile, "embedded_${UUID.randomUUID()}.mp4").also {
                        prepared.add(it); EmbeddedMotionReader.extractForPreview(entry.image.file, it)
                    }
                    val output = File(entry.image.file.parentFile, "silent_${UUID.randomUUID()}.mp4").also(prepared::add)
                    SilentVideoRemuxer.copy(motion, output)
                    AlbumOutput(output, "video/mp4", "mp4", AlbumAssetKind.ANIMATED, sourceIndex = sourceIndex)
                } else {
                    require(source.kind != AlbumAssetKind.ANIMATED || entry.image.animated) { "第 ${index + 1} 项动图缺少动态内容" }
                    AlbumOutput(entry.image.file, entry.image.mimeType, entry.image.extension,
                        if (entry.image.animated) AlbumAssetKind.ANIMATED else AlbumAssetKind.STATIC, sourceIndex = sourceIndex)
                }
            }
            return publishFiles(content, outputs, storage, folder, options, onProgress, onSaved)
        } finally { prepared.forEach { it.delete() } }
    }

    private data class AlbumOutput(val file: File, val mime: String, val extension: String,
        val kind: AlbumAssetKind, val embeddedMotion: Boolean = false, val sourceIndex: Int = -1)

    private suspend fun publishFiles(content: ParsedVideo, outputs: List<AlbumOutput>, storage: DownloadStorage,
        folder: DownloadFolder?, options: DownloadOptions, onProgress: suspend (Long, Long) -> Unit,
        onSaved: (SavedVideo) -> Unit): SavedVideo {
        AlbumMediaPolicy.validateImages(outputs.size)
        val total = outputs.sumOf { it.file.length() }
        require(total in 1..AlbumMediaPolicy.MAX_VIDEO_BYTES) { "保存文件总大小超过 2 GB 上限" }
        val pending = mutableListOf<DownloadStorage.PendingDownload>()
        val names = mutableListOf<String>()
        var copied = 0L
        var committed = false
        try {
            val base = FileNames.build(content, options, "jpg").substringBeforeLast('.')
            for ((index, output) in outputs.withIndex()) {
                currentCoroutineContext().ensureActive()
                val number = (output.sourceIndex.takeIf { it >= 0 } ?: index) + 1
                val suffix = if (output.embeddedMotion) "_MP" else ""
                val name = "${base}_${number.toString().padStart(3, '0')}$suffix.${output.extension}"
                val item = storage.createPending(name, folder, output.mime)
                pending.add(item); names.add(name)
                val before = copied
                copy(output.file, item.uri) { onProgress(before + it, total) }
                copied += output.file.length()
            }
            val uris = pending.map { currentCoroutineContext().ensureActive(); it.publish().toString() }
            currentCoroutineContext().ensureActive()
            val types = outputs.map { it.mime }.distinct()
            val result = SavedVideo(content.id, content.title, uris.first(), total,
                pending.map { it.locationLabel }.distinct().joinToString(" · "),
                mimeType = types.singleOrNull() ?: if (types.all { it.startsWith("image/") }) "image/*" else "*/*",
                uris = uris, coverUri = uris.first(), fileName = names.first(), isAlbum = true,
                watermarkMode = WatermarkSources.actualModeForValidatedDownload(content, options.watermarkMode),
                albumAssets = outputs.mapIndexed { index, output -> SavedAlbumAsset(uris[index], output.mime,
                    output.kind, embeddedMotion = output.embeddedMotion, sourceIndex = output.sourceIndex) },
                exportMode = options.albumMode.name, albumSourceCount = content.images.size,
                albumTimingSignature = if (options.albumMode == AlbumMode.GIF) AlbumTiming.signature(options) else "",
                gifExportQuality = if (options.albumMode == AlbumMode.GIF) options.gifExportQuality.name else "")
            committed = true; onSaved(result); return result
        } finally {
            if (!committed) pending.forEach { runCatching { it.rollback() } }
            pending.forEach { runCatching { it.cleanup() } }
        }
    }

    private suspend fun saveVideo(content: ParsedVideo, video: File, durationMs: Long, storage: DownloadStorage,
        folder: DownloadFolder?, options: DownloadOptions, onProgress: suspend (Long, Long) -> Unit, onSaved: (SavedVideo) -> Unit): SavedVideo {
        val name = FileNames.build(content, options, "mp4")
        val pending = storage.createPending(name, folder)
        try {
            copy(video, pending.uri) { onProgress(it, video.length()) }
            AlbumMediaValidation.composedVideo(context, pending.uri, durationMs)
            currentCoroutineContext().ensureActive()
            val uri = pending.publish()
            return SavedVideo(content.id, content.title, uri.toString(), video.length(), pending.locationLabel,
                fileName = name, isAlbum = true, watermarkMode = WatermarkSources.actualModeForValidatedDownload(content, options.watermarkMode),
                exportMode = AlbumMode.VIDEO.name, albumTimingSignature = AlbumTiming.signature(options)).also(onSaved)
        } finally { runCatching { pending.cleanup() } }
    }

    private suspend fun jpegCover(image: LocalAlbumImage, output: File) {
        currentCoroutineContext().ensureActive()
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(image.file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            output.outputStream().buffered().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) { "无法生成图片封面" } }
        } finally { bitmap.recycle() }
    }

    private suspend fun copy(file: File, destination: Uri, onProgress: suspend (Long) -> Unit) {
        var written = 0L
        file.inputStream().use { input ->
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer); if (read < 0) break
                    output.write(buffer, 0, read); written += read; onProgress(written)
                }
                output.flush()
            } ?: error("无法写入文件，请检查保存位置和可用空间")
        }
        require(written == file.length()) { "保存文件不完整" }
    }
}
