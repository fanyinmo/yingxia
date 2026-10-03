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

/** Bounded, cancellation-aware download to an app-owned temporary file. */
internal class MediaTransfer {
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() { connection?.disconnect() }

    suspend fun fetch(
        url: String,
        destination: File,
        maximumBytes: Long,
        noun: String,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long = try {
        withTimeout(180_000L) {
            var currentUrl = url
            var downloaded = 0L
            try {
                for (hop in 0..6) {
                    currentCoroutineContext().ensureActive()
                    val uri = MediaUrls.requireAllowed(currentUrl)
                    val response = (uri.toURL().openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 25_000
                        readTimeout = 25_000
                        setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                        setRequestProperty("Referer", "https://www.douyin.com/")
                        setRequestProperty("Accept-Encoding", "identity")
                        CookieManager.getInstance().getCookie(currentUrl)?.let { setRequestProperty("Cookie", it) }
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
                destination.delete()
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
