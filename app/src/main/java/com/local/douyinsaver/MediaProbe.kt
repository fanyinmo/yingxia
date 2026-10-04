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
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/** Confirms a candidate is an MP4 without downloading the complete video. */
class MediaProbe(
    private val connections: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
) {
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        activeConnection?.disconnect()
    }

    suspend fun verify(video: ParsedVideo, onDiagnostic: (String) -> Unit = {}): ParsedVideo =
        verifySources(video, WatermarkMode.CLEAN, onDiagnostic)

    /** Refresh only the selected rendition before preview/download, never another watermark mode. */
    suspend fun verifySelected(video: ParsedVideo, mode: WatermarkMode,
                               onDiagnostic: (String) -> Unit = {}): ParsedVideo =
        verifySources(video, mode, onDiagnostic)

    private data class Verified(val original: String, val finalUrl: String)
    private class ConflictingSource : IllegalStateException(
        "该地址与其他来源指向同一个文件，暂时无法确认可用的视频来源，请重新解析")

    private suspend fun verifySources(video: ParsedVideo, selected: WatermarkMode,
                                      onDiagnostic: (String) -> Unit): ParsedVideo = withContext(Dispatchers.IO) {
        if (video.isAlbum) return@withContext video
        check(running.compareAndSet(false, true)) { "正在校验另一个视频，请稍后重试" }
        cancelled.set(false)
        val choices = probeChoices(WatermarkSources.candidates(video, selected))
        if (choices.isEmpty()) {
            running.set(false)
            throw IllegalArgumentException("暂未读取到可用的视频地址，请重新解析")
        }
        val otherVersions = if (selected == WatermarkMode.ORIGINAL) emptySet() else video.mediaSources
            .filter { it.mode != selected && it.mode != WatermarkMode.ORIGINAL }.map { it.url }.toSet()
        val failed = mutableSetOf<String>()
        var verified: Verified? = null
        var lastFailure: Exception? = null
        try {
            try {
                withTimeout(60_000) {
                    // A single checked source is enough; unused sources stay as same-version refresh backups.
                    for (original in choices) {
                        ensureNotCancelled()
                        val result = try {
                            val resolved = withTimeout(CANDIDATE_TIMEOUT_MS) {
                                verifyCandidate(video.copy(mediaUrl = original), selected, otherVersions) { event ->
                                    onDiagnostic("media_choice mode=${selected.name} $event")
                                }.mediaUrl
                            }
                            Result.success(resolved)
                        } catch (_: TimeoutCancellationException) {
                            ensureNotCancelled()
                            Result.failure<String>(SocketTimeoutException("视频地址检查超时"))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            ensureNotCancelled()
                            Result.failure<String>(error)
                        }
                        val resolved = result.getOrNull()
                        if (resolved != null) {
                            if (resolved in otherVersions) {
                                failed += original
                                lastFailure = ConflictingSource()
                                onDiagnostic("media_rendition_conflict finals=1")
                                continue
                            }
                            verified = Verified(original, resolved)
                            onDiagnostic("media_choice_pass mode=${selected.name} host=${MediaUrls.requireAllowed(resolved).host}")
                            break
                        } else {
                            failed += original
                            lastFailure = result.exceptionOrNull() as? Exception
                            if (lastFailure is ConflictingSource) onDiagnostic("media_rendition_conflict finals=1")
                            onDiagnostic("media_choice_rejected mode=${selected.name} kind=${lastFailure?.javaClass?.simpleName}")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                ensureNotCancelled()
                onDiagnostic("media_choice_deadline verified=${if (verified == null) 0 else 1}")
                if (verified == null) lastFailure = SocketTimeoutException("视频地址检查超时")
            }
            ensureNotCancelled()
            val checked = verified ?: throw IllegalStateException(sourceFailureMessage(lastFailure), lastFailure)
            val checkedSources = listOf(MediaSource(checked.finalUrl, selected), MediaSource(checked.original, selected))
            val backups = video.mediaSources.filter {
                it.mode == selected && it.url !in failed && it.url !in otherVersions && MediaUrls.isAllowed(it.url)
            }
            val untouched = video.mediaSources.filter { it.mode != selected }
            onDiagnostic("media_choices_verified marked=${if (selected == WatermarkMode.WATERMARKED) 1 else 0} " +
                "clean=${if (selected == WatermarkMode.CLEAN) 1 else 0} original=${if (selected == WatermarkMode.ORIGINAL) 1 else 0}")
            video.copy(mediaUrl = checked.finalUrl,
                mediaSources = (checkedSources + backups + untouched).distinctBy { it.url to it.mode })
        } finally {
            activeConnection?.disconnect()
            activeConnection = null
            running.set(false)
        }
    }

    private suspend fun verifyCandidate(video: ParsedVideo, mode: WatermarkMode, conflictingUrls: Set<String>,
        onDiagnostic: (String) -> Unit): ParsedVideo = withContext(Dispatchers.IO) {
        try {
            var url = video.mediaUrl
            for (redirect in 0..MAX_REDIRECTS) {
                ensureNotCancelled()
                if (url in conflictingUrls) throw ConflictingSource()
                val uri = WatermarkSources.requireSelectedUrl(url, mode, video.mediaSources)
                val connection = connections(uri)
                activeConnection = connection
                try {
                    ensureNotCancelled()
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 7_000
                    connection.readTimeout = 7_000
                    connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                    connection.setRequestProperty("Referer", "https://www.douyin.com/")
                    connection.setRequestProperty("Range", "bytes=0-4095")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    cookies(url)?.let {
                        connection.setRequestProperty("Cookie", it)
                    }
                    val code = connection.responseCode
                    onDiagnostic("media_http hop=$redirect host=${uri.host} status=$code")
                    ensureNotCancelled()
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        require(redirect < MAX_REDIRECTS) { "视频地址跳转次数过多，请重新解析" }
                        val location = connection.getHeaderField("Location")
                        url = MediaUrls.redirect(uri, location, onDiagnostic)
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
            activeConnection?.disconnect()
            activeConnection = null
        }
    }

    private fun sourceFailureMessage(error: Exception?): String {
        val reason = when (error) {
            is SocketTimeoutException -> "连接超时，请检查网络后重新解析"
            is UnknownHostException -> "无法连接视频服务器，请检查网络后重新解析"
            is SSLException -> "HTTPS 连接校验失败，请检查网络后重新解析"
            is EOFException -> "服务器返回的视频内容不完整，请重新解析"
            is IllegalArgumentException, is IllegalStateException -> error.message.orEmpty().take(180)
            else -> "连接被中断或服务器没有返回视频，请重新解析"
        }
        return "视频地址暂不可用：$reason"
    }

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("视频校验已取消")
    }

    /** Leave room for a public entry when several signed CDN addresses have expired together. */
    private fun probeChoices(urls: List<String>): List<String> {
        val direct = urls.filterNot(MediaUrls::isPlaybackEntry)
        val entries = urls.filter(MediaUrls::isPlaybackEntry)
        if (entries.isEmpty()) return direct.take(MAX_CANDIDATES_PER_MODE)
        return (direct.take(MAX_CANDIDATES_PER_MODE - 1) + entries.take(1) + urls)
            .distinct().take(MAX_CANDIDATES_PER_MODE)
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
        const val MAX_CANDIDATES_PER_MODE = 3
        const val CANDIDATE_TIMEOUT_MS = 15_000L
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_HEADER_LENGTH = 16_384
        val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
