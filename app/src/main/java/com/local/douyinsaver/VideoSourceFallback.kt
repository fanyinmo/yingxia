package com.local.douyinsaver

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only remote response/reading failures may move to another URL of the same rendition. */
internal class VideoSourceTransferException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal object VideoSourceFallback {
    internal class Attempt {
        private var saving = false
        fun savingStarted() { saving = true }
        val canRetry: Boolean get() = !saving
    }

    suspend fun <T> run(
        urls: List<String>,
        mode: WatermarkMode,
        onDiagnostic: (String) -> Unit = {},
        transfer: suspend (String, Attempt) -> T,
    ): T {
        require(urls.isNotEmpty()) { "这个视频没有可用地址，请重新解析" }
        var lastFailure: VideoSourceTransferException? = null
        for ((index, url) in urls.distinct().take(4).withIndex()) {
            currentCoroutineContext().ensureActive()
            val attempt = Attempt()
            onDiagnostic("video_transfer_attempt mode=${mode.name} index=$index host=${MediaUrls.requireAllowed(url).host}")
            try {
                return transfer(url, attempt)
            } catch (error: VideoSourceTransferException) {
                currentCoroutineContext().ensureActive()
                if (!attempt.canRetry) throw error
                lastFailure = error
                onDiagnostic("video_transfer_rejected mode=${mode.name} index=$index kind=${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}")
            }
        }
        throw IllegalStateException("视频传输失败：${lastFailure?.message ?: "来源暂不可用"}，请重新解析后再试", lastFailure)
    }
}
