package com.local.douyinsaver

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class PageNetworkFailure { DNS, CONNECTION, TIMEOUT, TLS }

/** Keeps a transport failure separate from a missing/expired work, without exposing URLs. */
internal object PageNetworkFailures {
    private const val NO_DATA = "抖音网页暂未返回这个作品的可下载数据，请稍后重试"

    fun browserKind(code: Int): PageNetworkFailure? = when (code) {
        -2 -> PageNetworkFailure.DNS
        -6 -> PageNetworkFailure.CONNECTION
        -8 -> PageNetworkFailure.TIMEOUT
        -11 -> PageNetworkFailure.TLS
        else -> null
    }

    fun message(kind: PageNetworkFailure): String = when (kind) {
        PageNetworkFailure.DNS -> "抖音域名暂时无法解析，请检查网络或切换网络后重试"
        PageNetworkFailure.CONNECTION -> "暂时无法连接抖音，请检查网络或稍后重试"
        PageNetworkFailure.TIMEOUT -> "连接抖音超时，请检查网络或稍后重试"
        PageNetworkFailure.TLS -> "与抖音的安全连接失败，请检查网络与设备时间后重试"
    }

    fun browserMessage(code: Int): String = browserKind(code)?.let(::message)
        ?: "抖音页面连接失败（错误码 $code）"

    fun exceptionKind(error: Throwable): PageNetworkFailure? {
        val causes = generateSequence(error) { it.cause?.takeUnless { next -> next === it } }.take(8).toList()
        return when {
            causes.any { it is SSLException } -> PageNetworkFailure.TLS
            causes.any { it is UnknownHostException } -> PageNetworkFailure.DNS
            causes.any { it is SocketTimeoutException } -> PageNetworkFailure.TIMEOUT
            causes.any { it is ConnectException } -> PageNetworkFailure.CONNECTION
            else -> null
        }
    }

    /** One retry of the exact same HTTPS work; never change hosts, paths, cookies or TLS policy. */
    fun canRetry(code: Int, attempts: Int, expectedId: String, url: String): Boolean {
        if (attempts != 0 || browserKind(code) !in setOf(PageNetworkFailure.DNS,
                PageNetworkFailure.CONNECTION, PageNetworkFailure.TIMEOUT)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
            ShareLinks.isShareHost(uri.host) && ShareLinks.videoId(url) == expectedId
    }

    fun canRetryEmptyPage(expectedId: String, url: String, attempts: Int,
                          itemMatches: Boolean, knownErrors: List<String>, captcha: Boolean): Boolean =
        itemMatches && !captcha && knownErrors.none { it in setOf("作品已删除", "私密视频", "验证码") } &&
            knownErrors.any { it in setOf("抱歉出错了", "请尝试在抖音内观看") } &&
            canRetry(-2, attempts, expectedId, url)

    fun unavailableMessage(pageReason: String, apiFailure: PageNetworkFailure? = null,
                           desktopReason: String = ""): String {
        if (apiFailure != null) return message(apiFailure)
        val known = PageNetworkFailure.entries.map(::message)
        return known.firstOrNull { it == desktopReason || it == pageReason } ?: NO_DATA
    }
}
