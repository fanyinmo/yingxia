package com.local.douyinsaver

import java.net.URI
import java.util.Locale

/** The parser and downloader must accept the same HTTPS media origins. */
object MediaUrls {
    private val roots = listOf(
        "douyinvod.com", "douyin.com", "iesdouyin.com",
        "bytecdn.com", "byteimg.com", "bytedance.com",
        "douyinpic.com", "douyinstatic.com",
    )

    fun requireAllowed(url: String): URI {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw IllegalArgumentException("视频地址格式无效，请重新解析")
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        // Public mobile share pages return this playback entry before redirecting to a CDN.
        // Allow only the video playback paths, rather than every snssdk.com service.
        val playbackEntry = host == "aweme.snssdk.com" &&
            uri.rawPath in listOf("/aweme/v1/play/", "/aweme/v1/playwm/")
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.isOpaque && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                host != null && (playbackEntry || roots.any { host == it || host.endsWith(".$it") })
        ) { "视频地址域名不支持，请重新解析" }
        return uri
    }

    fun isAllowed(url: String): Boolean = runCatching { requireAllowed(url) }.isSuccess

    fun redirect(current: URI, location: String?): String {
        require(!location.isNullOrBlank() && location.length <= 16_384) { "视频地址跳转异常，请重新解析" }
        val target = try {
            // URI.resolve drops the last path segment for query-only references on some JDKs.
            if (location.startsWith("?")) URI(current.toString().substringBefore('#').substringBefore('?') + location).toString()
            else current.resolve(location).toString()
        }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("视频地址跳转格式无效，请重新解析") }
        requireAllowed(target)
        return target
    }
}
