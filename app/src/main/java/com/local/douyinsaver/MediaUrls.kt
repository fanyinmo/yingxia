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
    // Cold video CDNs redirect to rotating ByteDance scheduling hosts. Keep this
    // limited to one valid DNS label; the apex and arbitrary nested hosts are not media origins.
    private val coldSchedulingHost = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.bdcgslb\\.com")

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
                host != null && (playbackEntry || coldSchedulingHost.matches(host) ||
                    roots.any { host == it || host.endsWith(".$it") })
        ) { "视频地址域名不支持，请重新解析" }
        return uri
    }

    fun isAllowed(url: String): Boolean = runCatching { requireAllowed(url) }.isSuccess

    fun isPlaybackEntry(url: String): Boolean = runCatching {
        val uri = requireAllowed(url)
        uri.host.equals("aweme.snssdk.com", true) &&
            uri.rawPath in listOf("/aweme/v1/play/", "/aweme/v1/playwm/")
    }.getOrDefault(false)

    fun redirect(current: URI, location: String?, onDiagnostic: (String) -> Unit = {}): String {
        require(!location.isNullOrBlank() && location.length <= 16_384) { "视频地址跳转异常，请重新解析" }
        val target = try {
            // URI.resolve drops the last path segment for query-only references on some JDKs.
            if (location.startsWith("?")) URI(current.toString().substringBefore('#').substringBefore('?') + location)
            else current.resolve(location)
        } catch (_: Exception) {
            onDiagnostic("media_redirect scheme= host= port=-1 allowed=false reason=format")
            throw IllegalArgumentException("视频地址跳转格式无效，请重新解析")
        }
        // Log before validation so a rejected next hop is visible without its path, signature or credentials.
        onDiagnostic(redirectDiagnostic(target))
        val normalized = upgradeTrustedHttpRedirect(target)
        if (normalized != target) onDiagnostic(redirectDiagnostic(normalized) + " upgraded=true")
        requireAllowed(normalized.toString())
        return normalized.toString()
    }

    private fun redirectDiagnostic(target: URI): String =
        "media_redirect scheme=${target.scheme.orEmpty()} host=${target.host.orEmpty()} " +
            "port=${target.port} allowed=${isAllowed(target.toString())}"

    private fun upgradeTrustedHttpRedirect(target: URI): URI {
        if (!target.scheme.equals("http", true) || target.isOpaque || target.rawUserInfo != null ||
            target.host == null || target.port !in listOf(-1, 80)) return target
        val authority = if (target.port == 80) target.rawAuthority.substringBeforeLast(':') else target.rawAuthority
        // Compatibility for a trusted CDN's HTTP Location header: preserve every raw path/query byte,
        // request HTTPS only, and reuse the existing host/path policy. No new origin or TLS bypass.
        val https = runCatching { URI("https://$authority${target.rawPath.orEmpty()}" +
            (target.rawQuery?.let { "?$it" } ?: "") + (target.rawFragment?.let { "#$it" } ?: "")) }.getOrNull()
            ?: return target
        return https.takeIf { isAllowed(it.toString()) } ?: target
    }
}
