package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class QueuePolicyTest {
    private fun task(key: String, status: QueueStatus = QueueStatus.QUEUED) = QueueTask(key, "source/$key", status = status)

    @Test fun dragKeepsStableIdentityAndOnlyCrossesUnstartedTasks() {
        val tasks = listOf(task("saved", QueueStatus.DONE), task("a"), task("b"), task("c"))
        assertEquals(listOf("saved", "b", "c", "a"), QueuePolicy.moveTo(tasks, "a", 3).map { it.key })
        assertEquals(tasks, QueuePolicy.moveTo(tasks, "c", 0))
        assertEquals(tasks, QueuePolicy.moveTo(tasks, "saved", 3))
        assertEquals(emptyList<QueueTask>(), QueuePolicy.moveTo(emptyList(), "a", 0))
    }

    @Test fun availableParsedResultsIncludeSavedTasksForSavingAgainAndIgnoreUnknownKeys() {
        val tasks = listOf(task("video", QueueStatus.READY), task("album", QueueStatus.READY),
            task("saved", QueueStatus.DONE), task("unparsed"), task("retry", QueueStatus.FAILED))
        val parsed = setOf("album", "saved", "retry", "video", "removed")
        assertEquals(listOf("video", "album", "saved", "retry"), QueuePolicy.keysToSave(tasks, parsed))
        assertEquals(listOf("album", "retry"), QueuePolicy.keysToSave(tasks, parsed, setOf("retry", "album")))
    }

    @Test fun restartExpiresOnlyTransientStatesAndNeverReopensSavedTasks() {
        val ready = QueuePolicy.recover(task("ready", QueueStatus.READY))
        assertEquals(QueueStatus.FAILED, ready.status)
        assertTrue(ready.message.contains("重新解析"))
        assertEquals(QueueStatus.FAILED, QueuePolicy.recover(task("parsing", QueueStatus.PARSING)).status)
        assertEquals(QueueStatus.FAILED, QueuePolicy.recover(task("saving", QueueStatus.RUNNING)).status)
        listOf(QueueStatus.QUEUED, QueueStatus.DONE, QueueStatus.CANCELLED).forEach {
            val original = task("stable", it)
            assertEquals(original, QueuePolicy.recover(original))
        }
    }

    @Test fun nextParsingSkipsReadyAlbumsFailuresAndSavedItems() {
        val pending = task("next")
        assertEquals(pending, QueuePolicy.nextToParse(listOf(task("album", QueueStatus.READY),
            task("failed", QueueStatus.FAILED), task("saved", QueueStatus.DONE), pending)))
        assertNull(QueuePolicy.nextToParse(listOf(task("album", QueueStatus.READY))))
    }

    @Test fun restoringObsoleteVersionHintsKeepsTaskIdentityAndStatus() {
        val old = task("failed", QueueStatus.FAILED).copy(title = "旧任务", message = "暂未提供完整的无水印来源，可切换版本后保存")
        val recovered = QueuePolicy.recover(old)
        assertEquals(old.copy(message = "暂未读取到可下载地址，请重新解析"), recovered)
        val completed = old.copy(status = QueueStatus.DONE)
        assertEquals(completed.copy(message = "已保存，可在下载记录中查看"), QueuePolicy.recover(completed))
        val unrelated = old.copy(message = "无法写入保存文件夹，请重新授权")
        assertEquals(unrelated, QueuePolicy.recover(unrelated))
    }
}
