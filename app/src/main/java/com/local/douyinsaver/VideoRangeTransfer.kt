package com.local.douyinsaver

import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** A missing strong validator cannot safely bind separate responses to one representation. */
internal class RangeIdentityUnavailableException : IllegalStateException(
    "视频服务器未提供可确认同一文件的标识，无法安全分段下载")

/**
 * An opt-in workaround for the confirmed numeric cold relay. Download into an app-owned
 * temporary file, then let the existing video pipeline decode and publish it. No transcoding.
 * Each complete 1 MiB segment is committed once; a failed segment never contributes bytes.
 */
internal class VideoRangeTransfer(
    private val connections: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val cookies: (String) -> String? = { CookieManager.getInstance().getCookie(it) },
) {
    @Volatile private var connection: HttpURLConnection? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    fun cancel() { cancelled.set(true); connection?.disconnect() }

    suspend fun fetch(
        url: String,
        destination: File,
        maximumBytes: Long,
        validateUrl: (String) -> Unit = {},
        onDiagnostic: (String) -> Unit = {},
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        require(supports(url)) { "当前视频来源不支持分段下载" }
        require(maximumBytes in 32L..MAX_BYTES) { "视频下载上限无效" }
        require(!destination.exists() || (destination.isFile && destination.length() == 0L)) {
            "分段下载必须使用新的临时文件"
        }
        check(running.compareAndSet(false, true)) { "正在分段下载另一个视频" }
        cancelled.set(false)
        var ownsFile = false
        try {
            return try {
                withTimeout(600_000L) {
                    RandomAccessFile(destination, "rw").use { output ->
                        ownsFile = true
                        output.setLength(0)
                        var position = 0L
                        var total: Long? = null
                        var identity: String? = null
                        while (total == null || position < total!!) {
                            ensureNotCancelled()
                            val requestedEnd = minOf(position + CHUNK_BYTES - 1, (total ?: maximumBytes) - 1)
                            var completeSegment: ByteArray? = null
                            var successfulAttempt = 0
                            for (attempt in 0..2) {
                                var fullResponse = false
                                try {
                                    val response = openRange(url, position, requestedEnd, identity, validateUrl, onDiagnostic)
                                    try {
                                        val status = response.responseCode
                                        validateMediaHeaders(response)
                                        if (status == 200) {
                                            // An ignored Range or failed If-Range is a new complete representation.
                                            // Never append it to previously committed partial responses.
                                            fullResponse = true
                                            val length = response.contentLengthLong
                                            require(length in 32L..maximumBytes) { "完整视频响应长度无效或超过上限" }
                                            output.setLength(0)
                                            output.seek(0)
                                            onDiagnostic("video_range_full_restart offset=$position total=$length")
                                            val size = copyFull(response, output, length, onProgress)
                                            checkMp4(destination)
                                            return@withTimeout size
                                        }
                                        require(status == 206) { "分段下载失败（HTTP $status）" }
                                        val range = parseRange(response.getHeaderField("Content-Range"))
                                        require(range.total in 32L..maximumBytes && range.start == position &&
                                            range.end == minOf(requestedEnd, range.total - 1)) { "视频分段范围不匹配" }
                                        require(total == null || total == range.total) { "视频文件长度发生变化，请重新解析" }
                                        require(response.contentLengthLong == range.end - range.start + 1) {
                                            "视频分段长度不匹配"
                                        }
                                        val etag = strongTag(response.getHeaderField("ETag"))
                                            ?: throw RangeIdentityUnavailableException()
                                        require(identity == null || identity == etag) { "视频文件标识发生变化，请重新解析" }
                                        // Bind identity as soon as it is observed, including a truncated first segment.
                                        total = range.total
                                        identity = etag
                                        completeSegment = readSegment(response, (range.end - range.start + 1).toInt())
                                        successfulAttempt = attempt
                                        break
                                    } finally {
                                        response.disconnect()
                                        connection = null
                                    }
                                } catch (error: IOException) {
                                    ensureNotCancelled()
                                    // A whole-response retry cannot retain earlier segments after truncating the file.
                                    if (fullResponse || attempt == 2) throw error
                                    onDiagnostic("video_range_retry offset=$position attempt=${attempt + 1} kind=${error.javaClass.simpleName}")
                                }
                            }
                            val segment = checkNotNull(completeSegment) { "视频分段下载未完成" }
                            ensureNotCancelled()
                            if (position == 0L) checkMp4(segment)
                            output.seek(position)
                            output.write(segment)
                            val segmentStart = position
                            position += segment.size
                            onProgress(position, checkNotNull(total))
                            onDiagnostic("video_range_segment start=$segmentStart end=${position - 1} total=$total attempt=$successfulAttempt")
                        }
                        check(output.length() == total) { "视频分段文件不完整" }
                        checkMp4(destination)
                        position
                    }
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw SocketTimeoutException("视频分段下载超时，请检查网络后重试")
            }
        } catch (error: Throwable) {
            if (ownsFile) destination.delete()
            throw error
        } finally {
            connection?.disconnect()
            connection = null
            running.set(false)
        }
    }

    private suspend fun openRange(source: String, start: Long, end: Long, identity: String?,
        validateUrl: (String) -> Unit, onDiagnostic: (String) -> Unit): HttpURLConnection {
        var url = source
        for (hop in 0..6) {
            ensureNotCancelled()
            val uri = MediaUrls.requireAllowed(url)
            validateUrl(url)
            val response = connections(uri)
            connection = response
            try {
                response.instanceFollowRedirects = false
                response.connectTimeout = 25_000
                response.readTimeout = 25_000
                response.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                response.setRequestProperty("Referer", "https://www.douyin.com/")
                response.setRequestProperty("Accept-Encoding", "identity")
                response.setRequestProperty("Range", "bytes=$start-$end")
                identity?.let { response.setRequestProperty("If-Range", it) }
                cookies(url)?.let { response.setRequestProperty("Cookie", it) }
                val status = response.responseCode
                onDiagnostic("video_range_http hop=$hop host=${uri.host} port=${uri.port} status=$status")
                ensureNotCancelled()
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(hop < 6) { "视频分段地址跳转次数过多" }
                    url = MediaUrls.redirect(uri, response.getHeaderField("Location"), onDiagnostic)
                    response.disconnect()
                    connection = null
                    continue
                }
                return response
            } catch (error: Exception) {
                response.disconnect()
                connection = null
                throw error
            }
        }
        error("视频分段地址跳转次数过多")
    }

    private fun validateMediaHeaders(response: HttpURLConnection) {
        val type = response.contentType.orEmpty().substringBefore(';').trim().lowercase()
        require(type.isEmpty() || type.startsWith("video/") ||
            type in listOf("application/octet-stream", "binary/octet-stream", "application/mp4")) {
            "服务器没有返回视频分段"
        }
        val encoding = response.getHeaderField("Content-Encoding").orEmpty().trim()
        require(encoding.isEmpty() || encoding.equals("identity", true)) { "视频分段编码不支持" }
    }

    private data class Range(val start: Long, val end: Long, val total: Long)
    private fun parseRange(value: String?): Range {
        require(value != null && value.length <= 256) { "视频分段响应范围无效" }
        val match = CONTENT_RANGE.matchEntire(value.trim()) ?: error("视频分段响应范围无效")
        val numbers = match.groupValues.drop(1).map { it.toLongOrNull() ?: error("视频分段响应范围无效") }
        val range = Range(numbers[0], numbers[1], numbers[2])
        require(range.start <= range.end && range.end < range.total) { "视频分段响应范围无效" }
        return range
    }

    private fun strongTag(value: String?): String? = value?.takeIf {
        it.length <= 1_024 && STRONG_ETAG.matches(it)
    }

    private suspend fun readSegment(response: HttpURLConnection, length: Int): ByteArray {
        require(length in 1..CHUNK_BYTES.toInt()) { "视频分段超过上限" }
        return ByteArray(length).also { bytes ->
            response.inputStream.use { input ->
                var count = 0
                while (count < length) {
                    ensureNotCancelled()
                    val read = input.read(bytes, count, length - count)
                    if (read <= 0) throw EOFException("视频分段传输不完整")
                    count += read
                }
                ensureNotCancelled()
                require(input.read() == -1) { "视频分段响应超出范围" }
            }
        }
    }

    private suspend fun copyFull(response: HttpURLConnection, output: RandomAccessFile, expected: Long,
        onProgress: suspend (Long, Long) -> Unit): Long {
        var count = 0L
        response.inputStream.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                ensureNotCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) throw EOFException("完整视频传输不完整")
                count += read
                require(count <= expected) { "完整视频响应超出范围" }
                output.write(buffer, 0, read)
                onProgress(count, expected)
            }
        }
        if (count != expected) throw EOFException("完整视频传输不完整")
        return count
    }

    private fun checkMp4(file: File) = file.inputStream().use { input ->
        val header = ByteArray(32)
        var read = 0
        while (read < header.size) {
            val count = input.read(header, read, header.size - read)
            require(count > 0) { "视频文件头不完整" }
            read += count
        }
        checkMp4(header)
    }
    private fun checkMp4(bytes: ByteArray) {
        require(bytes.size >= 32 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") {
            "分段下载结果不是 MP4 视频"
        }
    }
    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("用户取消分段下载")
    }

    companion object {
        const val CHUNK_BYTES = 1_048_576L
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        private val NUMERIC_RELAY = Regex("[0-9]{8}\\.ydycdn\\.com")
        private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        private val STRONG_ETAG = Regex("\"[!#-~\\u0080-\\u00ff]*\"")
        fun supports(url: String): Boolean = runCatching {
            val uri = MediaUrls.requireAllowed(url)
            uri.port == 58001 && NUMERIC_RELAY.matches(uri.host.lowercase())
        }.getOrDefault(false)
    }
}
