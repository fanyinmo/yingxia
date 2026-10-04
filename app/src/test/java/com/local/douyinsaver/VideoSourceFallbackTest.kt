package com.local.douyinsaver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class VideoSourceFallbackTest {
    private val first = "https://v3.douyinvod.com/first.mp4?signature=private_first"
    private val second = "https://v6.douyinvod.com/second.mp4?signature=private_second"
    private val marked = "https://v3.douyinvod.com/marked.mp4?signature=private_marked"

    @Test fun interruptedTransferCleansItsAttemptBeforeTryingAnotherUrlOfTheRequestedMode() = runBlocking {
        val content = ParsedVideo("7692292719339253135", "测试", first, 3.0, 720, 1280,
            mediaSources = listOf(MediaSource(first, WatermarkMode.CLEAN), MediaSource(second, WatermarkMode.CLEAN),
                MediaSource(marked, WatermarkMode.WATERMARKED)))
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val result = VideoSourceFallback.run(WatermarkSources.candidates(content, WatermarkMode.CLEAN),
            WatermarkMode.CLEAN, diagnostics::add) { url, _ ->
            events += "begin:$url"
            try {
                if (url == first) throw VideoSourceTransferException("读取视频服务器超时", SocketTimeoutException())
                "saved"
            } finally {
                events += "cleanup:$url"
            }
        }
        assertEquals("saved", result)
        assertEquals(listOf("begin:$first", "cleanup:$first", "begin:$second", "cleanup:$second"), events)
        assertFalse(events.any { it.contains(marked) })
        assertFalse(diagnostics.any { it.contains("signature=") || it.contains("private_") })
    }

    @Test fun outputWriteFailureDoesNotRepeatTheDownload() {
        val failure = IOException("无法写入保存位置")
        var attempts = 0
        val thrown = assertThrows(IOException::class.java) {
            runBlocking { VideoSourceFallback.run(listOf(first, second), WatermarkMode.CLEAN) { _, _ ->
                attempts++
                throw failure
            } }
        }
        assertSame(failure, thrown)
        assertEquals(1, attempts)
    }

    @Test fun directoryPermissionFailureDoesNotTryAnotherServer() {
        var attempts = 0
        assertThrows(SecurityException::class.java) {
            runBlocking { VideoSourceFallback.run(listOf(first, second), WatermarkMode.CLEAN) { _, _ ->
                attempts++
                throw SecurityException("目录授权失效")
            } }
        }
        assertEquals(1, attempts)
    }

    @Test fun cleanupFailureAfterAnInterruptedTransferStopsInsteadOfLeavingAnotherPartialFile() {
        var attempts = 0
        val cleanup = IOException("清理临时文件时目录不可写")
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { VideoSourceFallback.run(listOf(first, second), WatermarkMode.CLEAN) { _, _ ->
                attempts++
                try {
                    throw VideoSourceTransferException("服务器传输的视频不完整")
                } finally {
                    throw IllegalStateException("无法清理未完成的视频文件，请检查保存目录后重试", cleanup)
                }
            } }
        }
        assertSame(cleanup, error.cause)
        assertFalse(error is VideoSourceTransferException)
        assertEquals(1, attempts)
    }

    @Test fun failureAfterSavingHasStartedCannotPublishTheSameDownloadTwice() {
        var attempts = 0
        var published = 0
        val error = assertThrows(VideoSourceTransferException::class.java) {
            runBlocking { VideoSourceFallback.run(listOf(first, second), WatermarkMode.CLEAN) { _, attempt ->
                attempts++
                attempt.savingStarted()
                published++
                throw VideoSourceTransferException("保存之后不能再次开始传输")
            } }
        }
        assertTrue(error.message.orEmpty().contains("保存之后"))
        assertEquals(1, attempts)
        assertEquals(1, published)
    }

    @Test fun aSavedRecordFailureDoesNotStartAnotherTransfer() {
        var attempts = 0
        var published = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking { VideoSourceFallback.run(listOf(first, second), WatermarkMode.CLEAN) { _, attempt ->
                attempts++
                attempt.savingStarted()
                published++
                throw IllegalStateException("下载记录未能保存")
            } }
        }
        assertEquals(1, attempts)
        assertEquals(1, published)
    }

    @Test fun explicitCancellationStopsBeforeTheNextCandidate() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { VideoSourceFallback.run(listOf(first, second), WatermarkMode.CLEAN) { _, _ ->
                attempts++
                throw CancellationException("用户取消")
            } }
        }
        assertEquals(1, attempts)
    }

    @Test fun coroutineCancellationCannotBeHiddenBehindATransferError() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(Job()) {
                VideoSourceFallback.run(listOf(first, second), WatermarkMode.CLEAN) { _, _ ->
                    attempts++
                    currentCoroutineContext().cancel()
                    throw VideoSourceTransferException("被取消的连接已断开")
                }
            } }
        }
        assertEquals(1, attempts)
    }

    @Test fun exhaustedAlternativesKeepTheSelectedModeAndHttpReason() {
        val attempted = mutableListOf<String>()
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { VideoSourceFallback.run(listOf(first, first, second), WatermarkMode.CLEAN) { url, _ ->
                attempted += url
                throw VideoSourceTransferException("视频下载失败（HTTP 403）")
            } }
        }
        assertEquals(listOf(first, second), attempted)
        assertTrue(error.message.orEmpty().startsWith("视频传输失败"))
        assertTrue(error.message.orEmpty().contains("HTTP 403"))
        assertFalse(error.message.orEmpty().contains("private_"))
    }
}
