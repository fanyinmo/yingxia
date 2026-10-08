package com.local.douyinsaver

import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/** Bounded, cancellation-aware download to an app-owned temporary file. */
internal class MediaTransfer(
    private val connections: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
) {
    @Volatile private var connection: HttpURLConnection? = null
    private val ranges = VideoRangeTransfer(connections, cookies)

    fun cancel() { connection?.disconnect(); ranges.cancel() }

    suspend fun fetch(
        url: String,
        destination: File,
        maximumBytes: Long,
        noun: String,
        validateUrl: (String) -> Unit = {},
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        require(!destination.exists()) { "下载必须使用新的临时文件，不能覆盖已有文件" }
        var ownsFile = false
        return try {
            withTimeout(600_000L) {
                var currentUrl = url
                var downloaded = 0L
                try {
                    // Reserve the path atomically before opening any network connection. An
                    // existing empty file belongs to its caller just as much as a full file.
                    require(destination.createNewFile()) { "下载必须使用新的临时文件，不能覆盖已有文件" }
                    ownsFile = true
                    for (hop in 0..6) {
                        currentCoroutineContext().ensureActive()
                        val uri = MediaUrls.requireAllowed(currentUrl)
                        validateUrl(currentUrl)
                        if (VideoRangeTransfer.supports(currentUrl)) {
                            try {
                                return@withTimeout ranges.fetch(currentUrl, destination, maximumBytes,
                                    validateUrl = validateUrl, onProgress = onProgress)
                            } catch (_: RangeIdentityUnavailableException) {
                                currentCoroutineContext().ensureActive()
                                if (ownsFile) destination.delete()
                                ownsFile = false
                                require(destination.createNewFile()) { "下载临时文件已被占用，请重试" }
                                ownsFile = true
                            }
                        }
                        val response = connections(uri).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 25_000
                            readTimeout = 25_000
                            setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                            setRequestProperty("Referer", "https://www.douyin.com/")
                            setRequestProperty("Accept-Encoding", "identity")
                            cookies(currentUrl)?.let { setRequestProperty("Cookie", it) }
                        }
                        connection = response
                        val status = response.responseCode
                        currentCoroutineContext().ensureActive()
                        if (status in listOf(301, 302, 303, 307, 308)) {
                            require(hop < 6) { "${noun}地址跳转次数过多，请重新解析" }
                            currentUrl = MediaUrls.redirect(uri, response.getHeaderField("Location"))
                            response.disconnect()
                            connection = null
                            continue
                        }
                        require(status == 200) { "${noun}下载失败（HTTP $status），请重新解析后再试" }
                        val type = response.contentType.orEmpty().lowercase()
                        require(!type.contains("text/") && !type.contains("json") && !type.contains("html")) {
                            "服务器返回了网页，没有返回${noun}"
                        }
                        val total = response.contentLengthLong
                        require(total < 0 || AlbumMediaPolicy.fitsBytes(total, maximumBytes)) { "${noun}大小无效或超过下载上限" }
                        response.inputStream.use { input ->
                            destination.outputStream().buffered(128 * 1024).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    downloaded += count
                                    require(downloaded <= maximumBytes) { "${noun}超过下载上限" }
                                    output.write(buffer, 0, count)
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                        require(downloaded > 0 && (total < 0 || downloaded == total)) { "${noun}下载不完整，请重试" }
                        return@withTimeout downloaded
                    }
                    error("${noun}地址跳转次数过多，请重新解析")
                } catch (error: Exception) {
                    if (ownsFile) destination.delete()
                    if (error is CancellationException) throw error
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally {
                    connection?.disconnect()
                    connection = null
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Keep actual user/parent cancellation distinct from this transfer's own deadline.
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("${noun}下载超时，请检查网络后重试")
        }
    }
}
