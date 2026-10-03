package com.local.douyinsaver

import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.EOFException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Confirms a candidate is an MP4 without downloading the complete video. */
class MediaProbe {
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        activeConnection?.disconnect()
    }

    suspend fun verify(video: ParsedVideo, onDiagnostic: (String) -> Unit = {}): ParsedVideo {
        try {
            return withTimeout(60_000) { verifyCandidate(video, onDiagnostic) }
        } catch (_: TimeoutCancellationException) {
            cancel()
            throw SocketTimeoutException("视频地址检查超时，请重新解析")
        }
    }

    private suspend fun verifyCandidate(video: ParsedVideo, onDiagnostic: (String) -> Unit): ParsedVideo = withContext(Dispatchers.IO) {
        check(running.compareAndSet(false, true)) { "正在校验另一个视频，请稍后重试" }
        cancelled.set(false)
        try {
            var url = video.mediaUrl
            for (redirect in 0..MAX_REDIRECTS) {
                ensureNotCancelled()
                val uri = MediaUrls.requireAllowed(url)
                val connection = uri.toURL().openConnection() as HttpURLConnection
                activeConnection = connection
                try {
                    ensureNotCancelled()
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                    connection.setRequestProperty("Referer", "https://www.douyin.com/")
                    connection.setRequestProperty("Range", "bytes=0-4095")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    CookieManager.getInstance().getCookie(url)?.let {
                        connection.setRequestProperty("Cookie", it)
                    }
                    val code = connection.responseCode
                    onDiagnostic("media_http hop=$redirect host=${uri.host} path=${uri.path} status=$code")
                    ensureNotCancelled()
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        require(redirect < MAX_REDIRECTS) { "视频地址跳转次数过多，请重新解析" }
                        val location = connection.getHeaderField("Location")
                        url = MediaUrls.redirect(uri, location)
                        val target = runCatching { java.net.URI(url) }.getOrNull()
                        onDiagnostic("media_redirect scheme=${target?.scheme} host=${target?.host} allowed=${MediaUrls.isAllowed(url)}")
                        // Check immediately, before making any request to the redirect target.
                        MediaUrls.requireAllowed(url)
                        continue
                    }
                    require(code == 200 || code == 206) {
                        "视频地址暂时不可下载（HTTP $code），请重新解析后再试"
                    }
                    validateHeaders(connection, code)
                    connection.inputStream.use { input ->
                        val header = ByteArray(32)
                        var count = 0
                        while (count < header.size) {
                            ensureNotCancelled()
                            val read = input.read(header, count, header.size - count)
                            if (read < 0) throw EOFException("视频地址返回的内容不完整，请重新解析")
                            if (read > 0) count += read
                        }
                        require(String(header, 4, 4, Charsets.US_ASCII) == "ftyp") {
                            "当前地址不是可下载的 MP4 视频，请重新解析"
                        }
                    }
                    ensureNotCancelled()
                    onDiagnostic("media_verified host=${uri.host} type=${connection.contentType.orEmpty().substringBefore(';')} bytes=${connection.contentLengthLong}")
                    return@withContext video.copy(mediaUrl = url)
                } finally {
                    // Also closes an ignored Range response after only 32 bytes were read.
                    connection.disconnect()
                    activeConnection = null
                }
            }
            error("视频地址跳转次数过多，请重新解析")
        } finally {
            running.set(false)
        }
    }

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("视频校验已取消")
    }

    private fun validateHeaders(connection: HttpURLConnection, code: Int) {
        val contentType = connection.contentType.orEmpty()
        require(contentType.length <= MAX_HEADER_LENGTH) { "视频服务器响应异常，请重试" }
        val type = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        require(
            type.isEmpty() || type.startsWith("video/") ||
                type in listOf("application/octet-stream", "binary/octet-stream", "application/mp4")
        ) { "服务器返回了网页或其他内容，没有返回视频" }

        val length = connection.contentLengthLong
        require(length <= MAX_BYTES) { "视频超过首版 2 GB 上限" }
        require(length < 0 || length >= 32) { "视频地址返回的内容不完整，请重新解析" }
        if (code == 206) {
            val range = connection.getHeaderField("Content-Range").orEmpty()
            require(range.length <= MAX_HEADER_LENGTH) { "视频服务器响应异常，请重试" }
            val match = CONTENT_RANGE.matchEntire(range.trim())
                ?: error("视频服务器返回了无效的分段内容，请重新解析")
            val start = match.groupValues[1].toLongOrNull()
            val end = match.groupValues[2].toLongOrNull()
            val total = match.groupValues[3].toLongOrNull()
            require(start == 0L && end != null && end in 31L..4095L && total != null && total > end) {
                "视频服务器返回了无效的分段内容，请重新解析"
            }
            require(total <= MAX_BYTES) { "视频超过首版 2 GB 上限" }
            require(length < 0 || length == end + 1) { "视频服务器返回的内容长度不一致，请重新解析" }
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 6
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_HEADER_LENGTH = 16_384
        val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
