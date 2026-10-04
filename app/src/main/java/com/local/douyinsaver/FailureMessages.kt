package com.local.douyinsaver

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** User-facing reasons without exposing signed URLs, cookies, or platform exception dumps. */
object FailureMessages {
    fun describe(error: Throwable): String {
        if (error is IllegalArgumentException || error is IllegalStateException) {
            error.message?.takeIf(String::isNotBlank)?.let { return DiagnosticText.clean(it, 220) }
        }
        val causes = generateSequence(error) { it.cause?.takeUnless { next -> next === it } }.take(8).toList()
        return when {
            causes.any { it is UnknownHostException } -> "无法找到视频服务器，请检查网络或切换网络后重试"
            causes.any { it is SSLException } -> "与视频服务器的安全连接失败，请重新解析或切换网络后重试"
            causes.any { it is SocketTimeoutException } -> "连接视频服务器超时，请检查网络后重试"
            causes.any { it is ConnectException } -> "无法连接视频服务器，请切换网络或稍后重试"
            causes.any { it is EOFException } -> "视频传输提前中断，请重新解析后重试"
            causes.any { it is SecurityException } -> "无法访问保存位置，请在设置中重新选择文件夹"
            causes.any { it is IOException } -> "视频传输或文件读写失败，请重试；具体原因可在设置的诊断信息中查看"
            else -> "本次处理未完成，请重试；具体原因可在设置的诊断信息中查看"
        }
    }
}
