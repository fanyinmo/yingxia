package com.local.douyinsaver

import android.content.Context
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class VideoDownloader(private val context: Context) {
    private val rangedTransfer = VideoRangeTransfer()
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; activeConnection?.disconnect(); rangedTransfer.cancel() }

    suspend fun download(
        video: ParsedVideo,
        folder: DownloadFolder? = null,
        onProgress: suspend (Long, Long) -> Unit,
        onSaving: suspend () -> Unit,
        onSaved: (SavedVideo) -> Unit,
        options: DownloadOptions = DownloadOptions(),
        onDiagnostic: (String) -> Unit = {},
    ): SavedVideo {
        cancelled = false
        val urls = WatermarkSources.candidates(video, options.watermarkMode)
        return VideoSourceFallback.run(urls, options.watermarkMode, onDiagnostic) { url, attempt ->
            ensureNotCancelled()
            downloadCandidate(video.copy(mediaUrl = url), folder, onProgress,
                onSaving = { attempt.savingStarted(); onSaving() }, onSaved = onSaved,
                options = options, onDiagnostic = onDiagnostic)
        }
    }

    private suspend fun downloadCandidate(
        video: ParsedVideo,
        folder: DownloadFolder?,
        onProgress: suspend (Long, Long) -> Unit,
        onSaving: suspend () -> Unit,
        onSaved: (SavedVideo) -> Unit,
        options: DownloadOptions,
        onDiagnostic: (String) -> Unit,
    ): SavedVideo {
        val resolver = context.contentResolver
        val storage = DownloadStorage(context)
        var pending: DownloadStorage.PendingDownload? = null
        var connection: HttpURLConnection? = null
        var requestingSource = false
        var sourceValidated = false
        try {
            folder?.let { storage.validateAccess(it) }
            requestingSource = true
            var url = video.mediaUrl
            for (redirect in 0..6) {
                ensureNotCancelled()
                val uri = WatermarkSources.requireSelectedUrl(url, options.watermarkMode, video.mediaSources)
                if (VideoRangeTransfer.supports(url)) {
                    val original = File.createTempFile("video_range_", ".mp4", context.cacheDir)
                    try {
                        try {
                            val bytes = rangedTransfer.fetch(url, original, MAX_BYTES,
                                validateUrl = { WatermarkSources.requireSelectedUrl(it, options.watermarkMode, video.mediaSources) },
                                onProgress = onProgress, onDiagnostic = onDiagnostic)
                            sourceValidated = true
                            return publishOriginalFile(original, bytes, video, folder, options, onSaving, onSaved)
                        } catch (_: RangeIdentityUnavailableException) {
                            onDiagnostic("video_range_unavailable reason=missing_strong_validator fallback=full_get")
                        }
                    } finally { original.delete() }
                }
                connection = uri.toURL().openConnection() as HttpURLConnection
                activeConnection = connection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 25_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                connection.setRequestProperty("Referer", "https://www.douyin.com/")
                connection.setRequestProperty("Accept-Encoding", "identity")
                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                val code = connection.responseCode
                onDiagnostic("video_transfer_http mode=${options.watermarkMode.name} hop=$redirect host=${uri.host} status=$code")
                ensureNotCancelled()
                if (code in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 6) { "视频下载跳转次数过多，请重新解析" }
                    url = MediaUrls.redirect(uri, connection.getHeaderField("Location"))
                    connection.disconnect()
                    connection = null
                    continue
                }
                require(code == 200) { "视频下载失败（HTTP $code），请重新解析后再试" }
                break
            }
            val response = connection ?: error("视频下载跳转次数过多")
            ensureNotCancelled()
            val total = response.contentLengthLong
            val type = response.contentType.orEmpty().lowercase()
            require(!type.contains("text/") && !type.contains("json")) { "服务器返回了网页，没有返回视频" }
            require(total <= MAX_BYTES) { "视频超过首版 2 GB 上限" }
            val name = FileNames.build(video, options, "mp4")
            var downloaded = 0L
            response.inputStream.use { input ->
                val header = ByteArray(32)
                DataInputStream(input).readFully(header)
                require(header.size >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "收到的文件不是支持的 MP4 视频" }
                sourceValidated = true
                ensureNotCancelled()
                pending = storage.createPending(name, folder)
                val destination = checkNotNull(pending).uri
                resolver.openOutputStream(destination, "w")?.use { output ->
                    output.write(header)
                    downloaded += header.size
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        ensureNotCancelled()
                        val count = try { input.read(buffer) } catch (error: IOException) {
                            ensureNotCancelled()
                            throw VideoSourceTransferException(sourceError(error), error)
                        }
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= MAX_BYTES) { "视频超过首版 2 GB 上限" }
                        output.write(buffer, 0, count)
                        onProgress(downloaded, total)
                    }
                    output.flush()
                } ?: error("无法写入视频文件，请检查可用空间")
            }
            if (total >= 0 && downloaded != total) throw VideoSourceTransferException("服务器传输的视频不完整")
            ensureNotCancelled()
            onSaving()
            val destination = checkNotNull(pending).uri
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, destination)
                require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0) { "下载文件中没有有效视频轨道" }
                require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0) { "视频时长无效，请重试" }
            } finally { retriever.release() }
            ensureNotCancelled()
            val saved = checkNotNull(pending)
            val published = saved.publish()
            val result = SavedVideo(video.id, video.title, published.toString(), downloaded, saved.locationLabel, fileName = name,
                watermarkMode = WatermarkSources.actualMode(video, options.watermarkMode))
            onSaved(result)
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ensureNotCancelled()
            // Folder/storage failures occur outside this response-validation phase.
            if (requestingSource && !sourceValidated && pending == null) {
                throw VideoSourceTransferException(sourceError(error), error)
            }
            throw error
        } finally {
            connection?.disconnect()
            activeConnection = null
            try {
                pending?.cleanup()
            } catch (error: Exception) {
                ensureNotCancelled()
                // Do not start another transfer when storage could not roll back its partial file.
                throw IllegalStateException("无法清理未完成的视频文件，请检查保存目录后重试", error)
            }
        }
    }

    private suspend fun publishOriginalFile(file: File, bytes: Long, video: ParsedVideo,
                                           folder: DownloadFolder?, options: DownloadOptions,
                                           onSaving: suspend () -> Unit,
                                           onSaved: (SavedVideo) -> Unit): SavedVideo {
        file.inputStream().use { input ->
            val header = ByteArray(32)
            DataInputStream(input).readFully(header)
            require(String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "收到的文件不是支持的 MP4 视频" }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0) { "下载文件中没有有效视频轨道" }
            require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0) { "视频时长无效，请重试" }
        } finally { retriever.release() }
        ensureNotCancelled()
        onSaving()
        val name = FileNames.build(video, options, "mp4")
        val pending = DownloadStorage(context).createPending(name, folder)
        try {
            context.contentResolver.openOutputStream(pending.uri, "w")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        ensureNotCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            } ?: error("无法写入视频文件，请检查可用空间")
            ensureNotCancelled()
            val published = pending.publish()
            return SavedVideo(video.id, video.title, published.toString(), bytes, pending.locationLabel,
                fileName = name, watermarkMode = WatermarkSources.actualMode(video, options.watermarkMode))
                .also(onSaved)
        } finally { pending.cleanup() }
    }

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled) throw CancellationException("视频下载已取消")
    }

    private fun sourceError(error: Exception): String = when (error) {
        is SocketTimeoutException -> "读取视频服务器超时"
        is UnknownHostException -> "无法连接视频服务器"
        is SSLException -> "视频服务器 HTTPS 连接校验失败"
        is EOFException -> "服务器返回的视频内容不完整"
        is IllegalArgumentException, is IllegalStateException -> error.message.orEmpty().take(180)
        else -> "视频服务器连接中断，请检查网络"
    }

    companion object {
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    }
}
