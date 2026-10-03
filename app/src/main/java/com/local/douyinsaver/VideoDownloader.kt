package com.local.douyinsaver

import android.content.Context
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.io.DataInputStream

class VideoDownloader(private val context: Context) {
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun cancel() { activeConnection?.disconnect() }

    suspend fun download(
        video: ParsedVideo,
        folder: DownloadFolder? = null,
        onProgress: suspend (Long, Long) -> Unit,
        onSaving: suspend () -> Unit,
        onSaved: (SavedVideo) -> Unit,
        options: DownloadOptions = DownloadOptions(),
    ): SavedVideo {
        val resolver = context.contentResolver
        val storage = DownloadStorage(context)
        var pending: DownloadStorage.PendingDownload? = null
        var connection: HttpURLConnection? = null
        try {
            folder?.let { storage.validateAccess(it) }
            var url = video.mediaUrl
            for (redirect in 0..6) {
                currentCoroutineContext().ensureActive()
                val uri = MediaUrls.requireAllowed(url)
                connection = uri.toURL().openConnection() as HttpURLConnection
                activeConnection = connection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 25_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                connection.setRequestProperty("Referer", "https://www.douyin.com/")
                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                val code = connection.responseCode
                currentCoroutineContext().ensureActive()
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
            currentCoroutineContext().ensureActive()
            val total = response.contentLengthLong
            val type = response.contentType.orEmpty().lowercase()
            require(!type.contains("text/") && !type.contains("json")) { "服务器返回了网页，没有返回视频" }
            require(total <= MAX_BYTES) { "视频超过首版 2 GB 上限" }
            val name = FileNames.build(video, options, "mp4")
            pending = storage.createPending(name, folder)
            var downloaded = 0L
            val destination = pending.uri
            response.inputStream.use { input ->
                val header = ByteArray(32)
                DataInputStream(input).readFully(header)
                require(header.size >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "收到的文件不是支持的 MP4 视频" }
                resolver.openOutputStream(destination, "w")?.use { output ->
                    output.write(header)
                    downloaded += header.size
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= MAX_BYTES) { "视频超过首版 2 GB 上限" }
                        output.write(buffer, 0, count)
                        onProgress(downloaded, total)
                    }
                    output.flush()
                } ?: error("无法写入视频文件，请检查可用空间")
            }
            require(total < 0 || downloaded == total) { "视频下载不完整，请重试" }
            currentCoroutineContext().ensureActive()
            onSaving()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, destination)
                require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0) { "下载文件中没有有效视频轨道" }
                require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0) { "视频时长无效，请重试" }
            } finally { retriever.release() }
            currentCoroutineContext().ensureActive()
            val published = pending.publish()
            val result = SavedVideo(video.id, video.title, published.toString(), downloaded, pending.locationLabel, fileName = name)
            onSaved(result)
            return result
        } finally {
            connection?.disconnect()
            activeConnection = null
            runCatching { pending?.cleanup() }
        }
    }

    companion object {
        private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    }
}
