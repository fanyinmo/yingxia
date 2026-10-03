package com.local.douyinsaver

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated state regressions: no singleton, real media, network, or foreground service. */
@RunWith(AndroidJUnit4::class)
class HomeInputStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val firstSource = "https://www.douyin.com/video/7689301063664454962"
    private val secondSource = "https://www.douyin.com/video/7689301063664454963"
    private val thirdSource = "https://www.douyin.com/video/7689301063664454964"
    private val parsed = ParsedVideo("7689301063664454962", "测试作品", "https://example.invalid/video.mp4", 4.0, 640, 480)

    @Test fun editingReadyInputInvalidatesItsResultAndPreservesOtherTasksAndHistory() = isolated { engine, records, _, saved ->
        val current = QueueTask("current", firstSource, status = QueueStatus.READY)
        val waiting = QueueTask("waiting", secondSource)
        val completed = QueueTask("completed", thirdSource, status = QueueStatus.DONE)
        main {
            engine.updateInput(firstSource)
            ready(engine, records, saved, listOf(current, waiting, completed), current.key)
            val previousGeneration = engine.generation
            engine.updateInput("新的分享文案 $secondSource")
            assertEquals("新的分享文案 $secondSource", engine.input)
            assertEquals(previousGeneration + 1, engine.generation)
            assertFreshInput(engine)
            assertEquals(QueueStatus.CANCELLED, engine.queue.first().status)
            assertEquals(waiting, engine.queue[1])
            assertEquals(completed, engine.queue[2])
            assertEquals(listOf(saved), engine.history)
        }
        assertEquals(listOf(saved), records.history())
        assertEquals(QueueStatus.CANCELLED, records.queue().first().status)
    }

    @Test fun clearingPersistsAnEmptyInputAndStaleParserCallbacksCannotRestoreTheOldResult() = isolated { engine, records, prefix, saved ->
        main {
            engine.updateInput("旧分享文案 $firstSource")
            ready(engine, records, saved)
            val previousGeneration = engine.generation
            engine.clearInput()
            assertEquals("", engine.input)
            assertFreshInput(engine)
            val clearedMessage = engine.message
            callParsed(engine, previousGeneration, parsed.copy(images = listOf(ParsedImage("https://example.invalid/image.jpg"))))
            callParseFailed(engine, previousGeneration, "过期的解析错误")
            assertFreshInput(engine)
            assertEquals(clearedMessage, engine.message)
            assertFalse(application.getSharedPreferences("${prefix}parse_diagnostics", 0).contains("last_url"))
            val reloaded = newEngine(prefix)
            try {
                assertEquals("", reloaded.input)
                assertEquals(listOf(saved), reloaded.history)
            } finally { closeEngine(reloaded) }
        }
        assertEquals(listOf(saved), records.history())
    }

    @Test fun busyInputActionsAreIgnoredAndBatchAdditionDoesNotOverwriteTaskProgress() = isolated { engine, records, _, saved ->
        main {
            engine.updateInput(firstSource)
            ready(engine, records, saved)
            val generation = engine.generation
            for (stage in listOf(TaskStage.BROWSING, TaskStage.DOWNLOADING, TaskStage.SAVING)) {
                state(engine, "stage", stage)
                state(engine, "message", "当前任务进度")
                engine.updateInput(secondSource)
                engine.clearInput()
                engine.acceptShare(thirdSource)
                engine.enqueueInput("$secondSource\n$thirdSource")
                assertEquals(firstSource, engine.input)
                assertEquals(generation, engine.generation)
                assertEquals(parsed, engine.video)
                assertEquals(stage, engine.stage)
                assertEquals("当前任务进度", engine.message)
                assertEquals(listOf(secondSource, thirdSource), engine.queue.map { it.source })
            }
        }
        assertEquals(listOf(saved), records.history())
    }

    @Test fun aSingleResolvePreservesTheShareTextAndDoesNotCreateBatchQueueRows() = isolated { engine, records, _, saved ->
        val share = "测试分享文案 $firstSource 复制链接后观看"
        main { engine.updateInput(share); engine.resolve() }
        awaitStage(engine, TaskStage.BROWSING)
        main {
            assertEquals(share, engine.input)
            assertEquals(parsed.id, engine.browsingId)
            assertTrue(engine.queue.isEmpty())
            assertNull(field(engine, "activeTaskKey"))
            engine.cancel()
        }
        assertTrue(records.queue().isEmpty())
        assertEquals(listOf(saved), records.history())
    }

    @Test fun resolvingAgainRetiresThePreviousReadyQueueBindingWithoutAddingAnotherRow() = isolated { engine, records, _, saved ->
        val current = QueueTask("current", firstSource, status = QueueStatus.READY)
        val waiting = QueueTask("waiting", secondSource)
        main {
            engine.updateInput(firstSource)
            ready(engine, records, saved, listOf(current, waiting), current.key)
            engine.resolve()
            assertEquals(2, engine.queue.size)
            assertEquals(QueueStatus.CANCELLED, engine.queue.first().status)
            assertEquals(waiting, engine.queue.last())
            assertNull(field(engine, "activeTaskKey"))
            assertNull(engine.video)
        }
        awaitStage(engine, TaskStage.BROWSING)
        main { engine.cancel() }
        assertEquals(listOf(saved), records.history())
        assertEquals(2, records.queue().size)
    }

    @Test fun addingBatchLinksPreservesReadyInputAndRemovingItsActiveRowClearsOnlyThatResult() = isolated { engine, records, _, saved ->
        val current = QueueTask("current", firstSource, status = QueueStatus.READY)
        main {
            engine.updateInput(firstSource)
            ready(engine, records, saved, listOf(current), current.key)
            val generation = engine.generation
            engine.enqueueInput(secondSource)
            assertEquals(firstSource, engine.input)
            assertEquals(generation, engine.generation)
            assertEquals(TaskStage.READY, engine.stage)
            assertEquals(parsed, engine.video)
            assertEquals(saved, engine.duplicate)
            engine.removeTask(current.key)
            assertFreshInput(engine)
            assertEquals(firstSource, engine.input)
            assertEquals(listOf(secondSource), engine.queue.map { it.source })
            assertEquals(QueueStatus.QUEUED, engine.queue.single().status)
            assertNull(field(engine, "activeTaskKey"))
        }
        assertEquals(listOf(saved), records.history())
        assertEquals(secondSource, records.queue().single().source)
    }

    @Test fun startingBatchQueueDoesNotDownloadOrReplaceAnUnqueuedReadyResult() = isolated { engine, records, _, saved ->
        main {
            engine.updateInput(firstSource)
            ready(engine, records, saved)
            state(engine, "duplicateCandidates", emptyList<SavedVideo>())
            engine.enqueueInput(secondSource)
            val generation = engine.generation
            engine.startQueue()
            assertEquals("请先下载或清空当前作品，再开始队列", engine.message)
            assertEquals(TaskStage.READY, engine.stage)
            assertFalse(engine.busy)
            assertEquals(false, field(engine, "queueRunning"))
            assertNull(field(engine, "activeTaskKey"))
            assertEquals(generation, engine.generation)
            assertEquals(firstSource, engine.input)
            assertEquals(parsed, engine.video)
            assertEquals(123L, engine.downloaded)
            assertEquals(456L, engine.total)
            assertEquals(QueueStatus.QUEUED, engine.queue.single().status)
        }
        assertEquals(secondSource, records.queue().single().source)
        assertEquals(listOf(saved), records.history())
    }

    private fun assertFreshInput(engine: SaverEngine) {
        assertEquals(TaskStage.IDLE, engine.stage)
        assertFalse(engine.busy)
        assertNull(engine.video)
        assertNull(engine.duplicate)
        assertNull(engine.browsingId)
        assertEquals("", engine.fileName)
        assertEquals(0L, engine.downloaded)
        assertEquals(-1L, engine.total)
    }

    private fun ready(engine: SaverEngine, records: DownloadRecords, saved: SavedVideo,
                      tasks: List<QueueTask> = emptyList(), activeKey: String? = null) {
        state(engine, "video", parsed)
        state(engine, "stage", TaskStage.READY)
        state(engine, "browsingId", parsed.id)
        state(engine, "duplicateCandidates", listOf(saved))
        state(engine, "downloaded", 123L)
        state(engine, "total", 456L)
        state(engine, "queue", tasks)
        field(engine, "activeTaskKey", activeKey)
        engine.fileName = "上一作品文件名"
        records.writeQueue(tasks)
    }

    private fun isolated(block: (SaverEngine, DownloadRecords, String, SavedVideo) -> Unit) {
        val prefix = "home_input_validation_${UUID.randomUUID()}_"
        val records = DownloadRecords(application, prefix)
        val saved = SavedVideo(parsed.id, "已完成记录", "content://com.local.douyinsaver.validation/completed/${UUID.randomUUID()}",
            456, savedAt = 1234)
        records.save(saved)
        var engine: SaverEngine? = null
        try {
            main { engine = newEngine(prefix) }
            block(engine!!, records, prefix, saved)
        } finally {
            main { engine?.let(::closeEngine) }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics").forEach {
                application.deleteSharedPreferences(prefix + it)
            }
        }
    }

    private fun newEngine(prefix: String): SaverEngine = SaverEngine::class.java
        .getDeclaredConstructor(Application::class.java, String::class.java)
        .apply { isAccessible = true }.newInstance(application, prefix)

    private fun closeEngine(engine: SaverEngine) { (field(engine, "scope") as CoroutineScope).cancel() }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }
    private fun awaitStage(engine: SaverEngine, expected: TaskStage) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            var actual: TaskStage? = null
            main { actual = engine.stage }
            if (actual == expected) return
            SystemClock.sleep(20)
        }
        main { assertEquals(expected, engine.stage) }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> state(engine: SaverEngine, name: String, value: T) {
        (field(engine, "${name}\$delegate") as MutableState<T>).value = value
    }
    private fun field(engine: SaverEngine, name: String): Any? = SaverEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(engine)
    private fun field(engine: SaverEngine, name: String, value: Any?) = SaverEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(engine, value)
    private fun callParsed(engine: SaverEngine, generation: Int, content: ParsedVideo) = SaverEngine::class.java
        .getDeclaredMethod("parsed", Int::class.javaPrimitiveType, ParsedVideo::class.java)
        .apply { isAccessible = true }.invoke(engine, generation, content)
    private fun callParseFailed(engine: SaverEngine, generation: Int, detail: String) = SaverEngine::class.java
        .getDeclaredMethod("parseFailed", Int::class.javaPrimitiveType, String::class.java)
        .apply { isAccessible = true }.invoke(engine, generation, detail)
}
