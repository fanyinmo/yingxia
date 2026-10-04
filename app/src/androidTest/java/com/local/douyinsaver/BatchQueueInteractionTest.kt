package com.local.douyinsaver

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

/** Real own-app gestures with synthetic task metadata: never parses or downloads a remote work. */
@RunWith(AndroidJUnit4::class)
class BatchQueueInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tasks = mutableStateOf<List<QueueTask>>(emptyList())
    private val running = mutableStateOf(false)
    private val results = mutableStateOf<Map<String, ParsedVideo>>(emptyMap())
    private val previews = mutableListOf<ParsedVideo>()
    private val saves = mutableListOf<Pair<String, AlbumMode>>()
    private val retries = mutableListOf<String>()
    private var observedList: LazyListState? = null
    private var fixtureEngine: SaverEngine? = null

    @Test fun buttonsLongPressAndBothSwipeDirectionsPreserveTaskIdentity() = isolated {
        mount(waiting(4)) { _ ->
            clickDescription("下移任务 1")
            waitFor { tasks.value.map { it.key } == listOf("q2", "q1", "q3", "q4") }
            val from = bounds(description("拖动排序任务 2"))
            val to = bounds(description("拖动排序任务 1"))
            drag(from.centerX().toFloat(), from.centerY().toFloat(), to.centerX().toFloat(), to.centerY().toFloat(), longPress = true)
            waitFor { tasks.value.first().key == "q1" }
            val first = bounds(text("排序样本 1"))
            drag(first.centerX().toFloat(), first.centerY().toFloat(), first.centerX() - 230f, first.centerY().toFloat())
            clickDescription("移除任务 1")
            waitFor { textOrNull("移除这条任务？") != null }
            clickText("取消")
            waitFor { textOrNull("移除这条任务？") == null && textOrNull("排序样本 1") != null }
            assertEquals(4, tasks.value.size)
            val restored = bounds(text("排序样本 1"))
            drag(restored.centerX().toFloat(), restored.centerY().toFloat(), restored.centerX() + 230f, restored.centerY().toFloat())
            clickDescription("移除任务 1")
            clickText("移除任务")
            waitFor { tasks.value.map { it.key } == listOf("q2", "q3", "q4") }
        }
    }

    @Test fun resultCardsKeepAllSourcesAndLockSavingDuringParsing() = isolated {
        val first = waiting(1).single().copy(status = QueueStatus.READY, title = "已解析视频")
        val albumTask = QueueTask("album", "https://v.douyin.com/album/", "已解析图集", QueueStatus.READY)
        val failed = QueueTask("failed", "https://v.douyin.com/fail/", "失败样本", QueueStatus.FAILED, "请重试")
        val video = ParsedVideo("10000000001", "已解析视频", "https://v3.douyinvod.com/marked.mp4", 6.0, 640, 480,
            mediaSources = listOf(MediaSource("https://v3.douyinvod.com/clean.mp4", WatermarkMode.CLEAN),
                MediaSource("https://v3.douyinvod.com/clean-backup.mp4", WatermarkMode.CLEAN)))
        val album = ParsedVideo("10000000002", "已解析图集", "", 0.0, 0, 0,
            images = listOf(ParsedImage("https://p3.douyinpic.com/sample.jpg",
                mediaSources = listOf(MediaSource("https://p3.douyinpic.com/sample.jpg", WatermarkMode.CLEAN)))),
            bgmUrl = "https://sf3-cdn-tos.douyinstatic.com/sample.mp3")
        instrumentation.runOnMainSync { results.value = mapOf(first.key to video, albumTask.key to album); running.value = true }
        mount(listOf(first, albumTask, failed)) { _ ->
            assertFalse(clickable(description("保存任务 1")).isEnabled)
            assertFalse(clickable(description("保存任务 2")).isEnabled)
            assertTrue(nodes().none { it.contentDescription?.toString()?.startsWith("拖动排序任务") == true })
            clickDescription("预览任务 1")
            waitFor { previews.singleOrNull()?.id == video.id }
            assertEquals(video.mediaSources, previews.single().mediaSources)
            instrumentation.runOnMainSync { running.value = false }
            waitFor { clickable(description("保存任务 2")).isEnabled }
            clickDescription("保存任务 2")
            waitFor { saves == listOf(albumTask.key to AlbumMode.IMAGES) }
            clickText("图片 + BGM 合成视频")
            waitFor { saves.lastOrNull() == (albumTask.key to AlbumMode.VIDEO) }
            reveal("重试任务 3")
            clickDescription("重试任务 3")
            waitFor { retries == listOf("failed") }
            assertEquals(listOf(first.key, albumTask.key, failed.key), tasks.value.map { it.key })
        }
        captureFullHome()
    }

    @Test fun heldDragAtListEdgeScrollsAndMovesBeyondInitialViewport() = isolated {
        mount(waiting(14)) { _ ->
            val first = bounds(description("拖动排序任务 1"))
            val viewport = bounds(nodes().first { it.isScrollable })
            drag(first.centerX().toFloat(), first.centerY().toFloat(), first.centerX().toFloat(),
                (viewport.bottom - 12).toFloat(), longPress = true, holdAtEnd = 1_200L)
            waitFor { tasks.value.indexOfFirst { it.key == "q1" } >= 4 }
            assertEquals(14, tasks.value.map { it.key }.toSet().size)
            assertTrue("Dragging never scrolled the production lazy list", (observedList?.firstVisibleItemIndex ?: 0) > 0)
        }
    }

    @Test fun clearingAllTasksRequiresConfirmationAndPreservesSingleContentAndHistory() = isolated {
        val engine = checkNotNull(fixtureEngine)
        val records = SaverEngine::class.java.getDeclaredField("records").apply { isAccessible = true }.get(engine) as DownloadRecords
        val single = ParsedVideo("10000000201", "独立单链接", "https://v3.douyinvod.com/own-fixture.mp4", 4.0, 640, 480)
        val saved = SavedVideo(single.id, "已保存的自制记录", "content://com.local.douyinsaver.fixture/clear-ui-keeps-file", 42)
        val originalTasks = waiting(4).mapIndexed { index, task -> task.copy(status = when (index) {
            0 -> QueueStatus.READY; 1 -> QueueStatus.DONE; 2 -> QueueStatus.FAILED; else -> QueueStatus.QUEUED
        }) }
        instrumentation.runOnMainSync {
            engine.updateInput("单链接内容 https://v.douyin.com/PreservedUiFixture/")
            setState(engine, "video", single)
            setState(engine, "stage", TaskStage.READY)
            engine.fileName = "保留的文件名"
            setState(engine, "queue", originalTasks)
            setState(engine, "queueResults", mapOf(originalTasks.first().key to single))
            records.writeQueue(originalTasks)
            records.save(saved)
            setState(engine, "history", listOf(saved))
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { activity ->
                val model = SaverViewModel(context.applicationContext as Application)
                activity.setContent { MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(20.dp)) { BatchQueueHeader(model, onAdd = {}) }
                    }
                } }
            }
            waitFor { descriptionOrNull("清空全部任务") != null }
            assertTrue(clickable(description("清空全部任务")).isEnabled)
            listOf("queueRunning", "batchSaving").forEach { flag ->
                instrumentation.runOnMainSync { setState(engine, flag, true) }
                waitFor { !clickable(description("清空全部任务")).isEnabled }
                assertNull(textOrNull("清空全部任务？"))
                instrumentation.runOnMainSync { setState(engine, flag, false) }
                waitFor { clickable(description("清空全部任务")).isEnabled }
            }
            instrumentation.runOnMainSync { setState(engine, "stage", TaskStage.VERIFYING) }
            waitFor { !clickable(description("清空全部任务")).isEnabled }
            instrumentation.runOnMainSync { setState(engine, "stage", TaskStage.READY) }
            waitFor { clickable(description("清空全部任务")).isEnabled }
            clickDescription("清空全部任务")
            waitFor { textOrNull("清空全部任务？") != null }
            assertNotNull(textOrNull("仅移除全部任务及解析结果，单链接内容、已保存文件和下载记录都会保留。"))
            assertEquals(originalTasks, engine.queue)
            clickText("取消")
            waitFor { textOrNull("清空全部任务？") == null }
            assertEquals(originalTasks, engine.queue)
            assertEquals(1, engine.queueResults.size)
            clickDescription("清空全部任务")
            clickDescription("确认清空全部任务")
            waitFor { engine.queue.isEmpty() && textOrNull("清空全部任务？") == null }
            assertNotNull(textOrNull("批量任务 · 0"))
            assertFalse(clickable(description("清空全部任务")).isEnabled)
            assertTrue(engine.queueResults.isEmpty())
            assertEquals(single, engine.video)
            assertEquals(TaskStage.READY, engine.stage)
            assertEquals("单链接内容 https://v.douyin.com/PreservedUiFixture/", engine.input)
            assertEquals("保留的文件名", engine.fileName)
            assertEquals(listOf(saved), engine.history)
            assertEquals(listOf(saved), records.history())
            assertTrue(records.queue().isEmpty())
        }
    }

    @Test fun missingCleanSourcesDisableSavingWithoutOfferingOtherModes() = isolated {
        val task = waiting(1).single().copy(status = QueueStatus.READY, title = "来源校验样本")
        val source = "https://v3.douyinvod.com/own-unclassified-fixture.mp4"
        val original = ParsedVideo("10000000202", task.title, source, 4.0, 640, 480)
        instrumentation.runOnMainSync { results.value = mapOf(task.key to original) }
        mount(listOf(task)) { _ ->
            assertFalse(clickable(description("保存任务 1")).isEnabled)
            assertFalse(clickable(description("预览任务 1")).isEnabled)
            assertNotNull(textOrNull("暂未读取到可下载地址，请重新解析。"))
            assertTrue(nodes().none { it.text?.toString() in listOf("有水印", "无水印", "原始版本") })
            assertNull(descriptionOrNull("使用可用版本任务 1"))
            instrumentation.runOnMainSync { results.value = mapOf(task.key to original.copy(
                mediaSources = listOf(MediaSource(source, WatermarkMode.WATERMARKED)))) }
            waitFor { !clickable(description("保存任务 1")).isEnabled }
            assertTrue(saves.isEmpty())
        }
    }

    @Test fun batchHeaderShowsOnlyDownloadActionsAndLocksWhileProcessing() = isolated {
        val engine = checkNotNull(fixtureEngine)
        instrumentation.runOnMainSync {
            engine.enqueueInput("https://v.douyin.com/BatchVersionUiFixture/")
            val task = engine.queue.single().copy(status = QueueStatus.DONE)
            val clean = "https://v3.douyinvod.com/own-clean-fixture.mp4"
            setState(engine, "queue", listOf(task))
            setState(engine, "queueResults", mapOf(task.key to ParsedVideo("10000000203", "已保存样本", clean, 4.0, 640, 480,
                mediaSources = listOf(MediaSource(clean, WatermarkMode.CLEAN)))))
        }
        mountHeader {
            assertTrue(clickable(text("保存已解析")).isEnabled)
            assertTrue(nodes().none { it.text?.toString() in listOf("有水印", "无水印", "原始版本", "本次批量下载版本") })
            instrumentation.runOnMainSync { setState(engine, "batchSaving", true) }
            waitFor { textOrNull("正在保存…") != null && !clickable(text("正在保存…")).isEnabled }
            instrumentation.runOnMainSync { setState(engine, "batchSaving", false) }
            waitFor { textOrNull("保存已解析") != null && clickable(text("保存已解析")).isEnabled }
        }
    }

    @Test fun completedCardsCanSaveAgainWithoutReparsing() = isolated {
        val task = waiting(1).single().copy(status = QueueStatus.DONE, title = "已保存的自制作品")
        val clean = "https://v3.douyinvod.com/own-clean-fixture.mp4"
        val parsed = ParsedVideo("10000000204", task.title, clean, 4.0, 640, 480,
            mediaSources = listOf(MediaSource(clean, WatermarkMode.CLEAN)))
        instrumentation.runOnMainSync { results.value = mapOf(task.key to parsed) }
        mount(listOf(task)) { _ ->
            assertNotNull(textOrNull("查看记录"))
            assertNotNull(textOrNull("再次保存"))
            assertTrue(clickable(description("保存任务 1")).isEnabled)
            assertTrue(clickable(description("预览任务 1")).isEnabled)
            clickDescription("保存任务 1")
            waitFor { saves == listOf(task.key to AlbumMode.IMAGES) }
            assertTrue(retries.isEmpty())
            assertEquals(QueueStatus.DONE, tasks.value.single().status)
        }
    }

    private fun mountHeader(body: () -> Unit) {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { activity ->
                val model = SaverViewModel(context.applicationContext as Application)
                activity.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) { BatchQueueHeader(model, onAdd = {}) }
                } } }
            }
            waitFor { textOrNull("保存已解析") != null }
            body()
        }
    }
    private fun versionSelected(description: String): Boolean = clickable(this.description(description)).let { it.isChecked || it.isSelected }

    private fun waiting(count: Int) = (1..count).map {
        QueueTask("q$it", "https://v.douyin.com/sample$it/", "排序样本 $it")
    }

    private fun captureFullHome() {
        if (InstrumentationRegistry.getArguments().getString("capture_queue") != "true") return
        val engine = checkNotNull(fixtureEngine)
        val source = "https://v3.douyinvod.com/isolated-ui-fixture.mp4"
        val first = ParsedVideo("10000000101", "把喜欢的片段留在手机里", source, 32.0, 1080, 1920,
            mediaSources = listOf(MediaSource(source, WatermarkMode.CLEAN)))
        val second = first.copy(id = "10000000102", title = "整理分享链接，一次解析多个作品", durationSeconds = 48.0)
        instrumentation.runOnMainSync {
            engine.clearInput()
            setState(engine, "queue", listOf(
                QueueTask("home1", "https://v.douyin.com/ui1/", first.title, QueueStatus.READY),
                QueueTask("home2", "https://v.douyin.com/ui2/", second.title, QueueStatus.READY),
                QueueTask("home3", "https://v.douyin.com/ui3/", "稍后解析的作品"),
                QueueTask("home4", "https://v.douyin.com/ui4/", "暂未读取的作品", QueueStatus.FAILED, "暂时未能读取，可以重试")))
            setState(engine, "queueResults", mapOf("home1" to first, "home2" to second))
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { activity ->
                val model = SaverViewModel(context.applicationContext as Application)
                activity.setContent {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2499DB), onPrimary = Color.White,
                        primaryContainer = Color(0xFFDFF3FF), surface = Color(0xFFF8FBFD),
                        surfaceContainer = Color(0xFFEDF5FA), background = Color(0xFFF4F9FC), onSurface = Color(0xFF1F2A30))) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { HomeScreen(model) {} }
                    }
                }
            }
            waitFor { textOrNull("批量任务 · 4") != null }
            capture("queue-home-top.png")
            reveal("预览任务 2")
            capture("queue-home-results.png")
        }
    }

    private fun capture(name: String) {
        assertTrue(nodes().any { it.isVisibleToUser })
        SystemClock.sleep(160)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "runtime-test-captures").apply { mkdirs() }
        try { File(directory, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(engine: SaverEngine, name: String, value: T) {
        val field = SaverEngine::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(engine) as MutableState<T>).value = value
    }

    private fun mount(initial: List<QueueTask>, body: (ActivityScenario<MainActivity>) -> Unit) {
        instrumentation.runOnMainSync { tasks.value = initial }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val list = rememberLazyListState()
                    SideEffect { observedList = list }
                    val drag = rememberBatchQueueDragState(list, tasks.value, !running.value,
                        with(LocalDensity.current) { 72.dp.toPx() }) { key, index ->
                        val changed = tasks.value.toMutableList()
                        val old = changed.indexOfFirst { it.key == key }
                        if (old >= 0) { val value = changed.removeAt(old); changed.add(index, value); tasks.value = changed }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = list, userScrollEnabled = drag.key == null,
                        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(tasks.value, key = { _, task -> task.key }) { index, task ->
                            val sortable = !running.value && task.status == QueueStatus.QUEUED
                            BatchQueueTaskCard(task, index, tasks.value.size, results.value[task.key],
                                operationsEnabled = !running.value, canRemove = task.status !in listOf(QueueStatus.PARSING, QueueStatus.RUNNING),
                                canReorder = sortable, canMoveUp = sortable && index > 0,
                                canMoveDown = sortable && index < tasks.value.lastIndex, drag = drag,
                                modifier = Modifier.batchQueueDragPlacement(drag, task.key),
                                onMove = { delta ->
                                    val changed = tasks.value.toMutableList()
                                    val target = index + delta
                                    if (target in changed.indices) { val value = changed.removeAt(index); changed.add(target, value); tasks.value = changed }
                                }, onRemove = { tasks.value = tasks.value.filterNot { it.key == task.key } },
                                onRetry = { retries += task.key }, onPreview = { previews += it },
                                onSave = { saves += task.key to it }, onHistory = {})
                        }
                    }
                }
            } }
            waitFor { nodes().any { it.text?.toString() == initial.first().title } }
            body(scenario)
        }
    }

    private fun reveal(description: String) {
        repeat(8) {
            if (descriptionOrNull(description) != null) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(120)
        }
        error("Missing own-app queue action: $description")
    }
    private fun description(value: String): AccessibilityNodeInfo = checkNotNull(descriptionOrNull(value)) { "Missing $value" }
    private fun descriptionOrNull(value: String) = nodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString() == value }
    private fun text(value: String): AccessibilityNodeInfo = checkNotNull(textOrNull(value)) { "Missing $value" }
    private fun textOrNull(value: String) = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == value }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && current.packageName?.toString() == context.packageName) {
            current.refresh()
            if (current.isClickable) return current
            current = current.parent
        }
        error("Expected an own-app clickable queue control")
    }
    private fun clickDescription(value: String) { waitFor { descriptionOrNull(value) != null }; assertTrue(clickable(description(value)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private fun clickText(value: String) { waitFor { textOrNull(value) != null }; assertTrue(clickable(text(value)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val visited = mutableSetOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !visited.add(node) || !node.refresh() || node.packageName?.toString() != context.packageName) return
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        instrumentation.uiAutomation.windows.forEach { visit(it.root) }
        return result
    }
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, longPress: Boolean = false, holdAtEnd: Long = 0L) {
        assertTrue("Only own-app observed queue coordinates may receive touch",
            nodes().any { it.isVisibleToUser && bounds(it).contains(x0.toInt(), y0.toInt()) })
        val start = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, x0, y0)
        if (longPress) SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 120L)
        for (step in 1..12) {
            val fraction = step / 12f
            inject(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * fraction, y0 + (y1 - y0) * fraction)
            SystemClock.sleep(24)
        }
        if (holdAtEnd > 0) {
            val deadline = SystemClock.uptimeMillis() + holdAtEnd
            while (SystemClock.uptimeMillis() < deadline) { inject(MotionEvent.ACTION_MOVE, x1, y1); SystemClock.sleep(40) }
        }
        inject(MotionEvent.ACTION_UP, x1, y1)
        SystemClock.sleep(180)
    }
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(50) }
        fail("Queue interaction condition not reached; order=${tasks.value.map { it.key }}, nodes=${nodes().map { it.text?.toString().orEmpty() + ":" + it.contentDescription }}")
    }
    private fun isolated(body: () -> Unit) {
        val namespace = "batch_queue_ui_${UUID.randomUUID()}_"
        val engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(context.applicationContext as Application, namespace)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        instrumentation.runOnMainSync {
            singleton.set(null, engine); running.value = false; results.value = emptyMap()
        }
        fixtureEngine = engine
        try { body() } finally {
            fixtureEngine = null
            instrumentation.runOnMainSync {
                val scope = SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope
                scope.cancel()
                singleton.set(null, previous)
            }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics").forEach { context.deleteSharedPreferences(namespace + it) }
        }
    }
}
