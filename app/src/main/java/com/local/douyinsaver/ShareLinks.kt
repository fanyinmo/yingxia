package com.local.douyinsaver

import java.net.HttpURLConnection
import java.net.URI

object ShareLinks {
    private val urls = Regex("https?://[^\\s<>\\\"'，。！？、（）【】]+", RegexOption.IGNORE_CASE)
    private val videoPath = Regex("^/(?:share/)?(?:video|note|slides)/(\\d{15,22})(?:/|$)")
    private val trailing = charArrayOf('.', ',', ';', ':', ')', ']', '}', '!', '?', '；', '：')

    fun extract(text: String): String {
        require(text.length <= 16_384) { "分享文案过长，请只粘贴一条视频链接" }
        val found = urls.findAll(text).map { it.value.trimEnd(*trailing) }.distinct().toList()
        require(found.isNotEmpty()) { "没有找到链接，请粘贴抖音分享文案" }
        require(found.size == 1) { "检测到多个链接，请每次只保留一条" }
        val url = found.single()
        val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("链接格式不正确") }
        require(uri.scheme.equals("https", true)) { "请使用 HTTPS 抖音链接" }
        require(uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "链接格式不支持" }
        require(isShareHost(uri.host)) { "目前只支持抖音作品分享链接" }
        return url
    }

    fun isShareHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return value == "douyin.com" || value.endsWith(".douyin.com") ||
            value == "iesdouyin.com" || value.endsWith(".iesdouyin.com")
    }

    fun extractAll(text: String): List<String> {
        require(text.length <= 65_536) { "一次最多添加 100 条分享链接" }
        val found = urls.findAll(text).map { extract(it.value.trimEnd(*trailing)) }.distinct().toList()
        require(found.isNotEmpty()) { "没有找到抖音链接" }
        require(found.size <= 100) { "一次最多添加 100 条分享链接" }
        return found
    }

    fun videoId(url: String): String? {
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (!isShareHost(uri.host)) return null
        videoPath.find(uri.path.orEmpty())?.let { return it.groupValues[1] }
        return uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("modal_id=") }
            ?.substringAfter('=')?.takeIf { it.matches(Regex("\\d{15,22}")) }
    }

    fun resolveVideoId(text: String): String {
        var url = extract(text)
        repeat(7) {
            videoId(url)?.let { return it }
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", DESKTOP_UA)
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("分享链接没有返回跳转地址")
                    val next = URI(url).resolve(location)
                    require(next.scheme == "https" && isShareHost(next.host) && next.userInfo == null && (next.port == -1 || next.port == 443)) { "分享链接跳转到了不支持的网站" }
                    url = next.toString()
                } else {
                    videoId(connection.url.toString())?.let { return it }
                    error(if (status >= 400) "分享链接访问失败（HTTP $status）" else "未识别到作品，请确认是视频或图集分享链接")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("分享链接跳转次数过多，请重新复制链接")
    }

    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}
