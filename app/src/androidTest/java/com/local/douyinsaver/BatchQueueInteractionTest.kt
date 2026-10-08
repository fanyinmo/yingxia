package com.local.douyinsaver

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import org.json.JSONObject

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
    private var activityDecor: View? = null

    @Test fun buttonsLongPressAndBothSwipeDirectionsPreserveTaskIdentity() = isolated {
        mount(waiting(4)) { _ ->
            clickDescription("下移任务 1")
            waitFor { tasks.value.map { it.key } == listOf("q2", "q1", "q3", "q4") }
            // LazyColumn retains the old first visible identity after button reordering.
            // Reveal the new first row before using either complete 48dp drag handle.
            reveal("拖动排序任务 1")
            reveal("拖动排序任务 2")
            val from = visibleControlBounds(description("拖动排序任务 2"))
            val to = visibleControlBounds(description("拖动排序任务 1"))
            drag(from.centerX().toFloat(), from.centerY().toFloat(), to.centerX().toFloat(), to.centerY().toFloat(), longPress = true)
            waitFor { tasks.value.first().key == "q1" }
            revealControl("排序样本 1", false)
            swipeVisibleTaskTitle("排序样本 1", left = true)
            clickDescription("移除任务 1")
            waitFor { textOrNull("移除这条任务？") != null }
            clickText("取消")
            waitFor { textOrNull("移除这条任务？") == null && textOrNull("排序样本 1") != null }
            assertEquals(4, tasks.value.size)
            revealControl("排序样本 1", false)
            swipeVisibleTaskTitle("排序样本 1", left = false)
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
            reveal("保存任务 1")
            assertFalse(clickable(description("保存任务 1")).isEnabled)
            clickDescription("预览任务 1")
            waitFor { previews.singleOrNull()?.id == video.id }
            assertEquals(video.mediaSources, previews.single().mediaSources)
            // This fixture contains exactly one album; its shared production controls
            // use unique visible labels, unlike the video-specific task description.
            assertEquals(1, tasks.value.count { results.value[it.key]?.isAlbum == true })
            revealControl("保存 1 张图片", false)
            assertFalse(uniqueAlbumAction("保存 1 张图片").isEnabled)
            assertTrue(nodes().none { it.contentDescription?.toString()?.startsWith("拖动排序任务") == true })
            instrumentation.runOnMainSync { running.value = false }
            waitFor { uniqueAlbumAction("保存 1 张图片").isEnabled }
            clickText("保存 1 张图片")
            waitFor { saves == listOf(albumTask.key to AlbumMode.IMAGES) }
            clickText("素材 + BGM 合成视频")
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
            reveal("拖动排序任务 1")
            val first = visibleControlBounds(description("拖动排序任务 1"))
            val viewport = scrollViewport()
            val initiallyVisibleLastIndex = checkNotNull(observedList).layoutInfo.visibleItemsInfo.maxOf { it.index }
            println("queue_held_drag_geometry handle=${first.toShortString()} safeViewport=${viewport.toShortString()} " +
                "initialVisibleLastIndex=$initiallyVisibleLastIndex")
            drag(first.centerX().toFloat(), first.centerY().toFloat(), first.centerX().toFloat(),
                viewport.bottom - 12f * context.resources.displayMetrics.density, longPress = true, holdAtEnd = 1_200L)
            waitFor { tasks.value.indexOfFirst { it.key == "q1" }.let { it >= 4 && it > initiallyVisibleLastIndex } }
            assertEquals(14, tasks.value.map { it.key }.toSet().size)
            assertTrue("The dragged task never moved beyond the initially visible identities",
                tasks.value.indexOfFirst { it.key == "q1" } > initiallyVisibleLastIndex)
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
            try {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            scenario.onActivity { activity ->
                activityDecor = activity.window.decorView
                val model = SaverViewModel(context.applicationContext as Application)
                activity.setContent { MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) { BatchQueueHeader(model, onAdd = {}) }
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
            } catch (failure: Throwable) {
                captureQueueFailure(failure)
                throw failure
            }
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
                activityDecor = activity.window.decorView
                val model = SaverViewModel(context.applicationContext as Application)
                activity.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) { BatchQueueHeader(model, onAdd = {}) }
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
                activityDecor = activity.window.decorView
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
            scenario.onActivity { activity -> activityDecor = activity.window.decorView; activity.setContent {
                MaterialTheme {
                    val list = rememberLazyListState()
                    SideEffect { observedList = list }
                    val drag = rememberBatchQueueDragState(list, tasks.value, !running.value,
                        with(LocalDensity.current) { 72.dp.toPx() }) { key, index ->
                        val changed = tasks.value.toMutableList()
                        val old = changed.indexOfFirst { it.key == key }
                        if (old >= 0) { val value = changed.removeAt(old); changed.add(index, value); tasks.value = changed }
                    }
                    // Keep whole row controls inside the same drawing/gesture area
                    // that the touch guards validate; no system-edge gestures.
                    LazyColumn(Modifier.fillMaxSize().safeContentPadding(), state = list, userScrollEnabled = drag.key == null,
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
            try { body(scenario) } catch (failure: Throwable) {
                captureQueueFailure(failure)
                throw failure
            }
        }
    }

    private fun reveal(description: String) = revealControl(description, true)
    private fun revealControl(value: String, byDescription: Boolean) {
        var forward = true
        repeat(32) { attempt ->
            assertOwnForeground()
            val target = nodes().firstOrNull { if (byDescription) it.contentDescription?.toString() == value else it.text?.toString() == value }
            val scroll = verticalScroller(target)
            val viewport = if (scroll != null) scrollViewport(scroll) else safeViewport()
            if (target != null) {
                val control = clickableOrNull(target) ?: target
                val area = bounds(control)
                val minimumHeight = if (byDescription && value.startsWith("拖动排序任务")) 47.5f * context.resources.displayMetrics.density else 0f
                if (control.isVisibleToUser && !area.isEmpty && area.height() >= minimumHeight && viewport.contains(area)) return
                control.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.waitForIdleSync()
                val refreshed = if (byDescription) descriptionOrNull(value) else textOrNull(value)
                if (refreshed != null) {
                    val refreshedArea = bounds(clickableOrNull(refreshed) ?: refreshed)
                    if (!refreshedArea.isEmpty && refreshedArea.height() >= minimumHeight && viewport.contains(refreshedArea)) return
                }
                if (!area.isEmpty) forward = area.exactCenterY() >= viewport.exactCenterY()
            }
            if (scroll == null || (target == null && attempt < 2)) {
                // A newly requested Dialog can take another frame to become the active
                // own window. Wait for its controls; never scroll the background fixture.
                instrumentation.waitForIdleSync()
                SystemClock.sleep(120)
                return@repeat
            }
            if (!scrollRevealStep(scroll, forward)) forward = !forward
            instrumentation.waitForIdleSync()
            assertOwnForeground()
            SystemClock.sleep(120)
        }
        error("Missing fully visible own-app queue action: $value; ${queueGeometryDiagnostic()}")
    }
    private fun description(value: String): AccessibilityNodeInfo = checkNotNull(descriptionOrNull(value)) { "Missing $value" }
    private fun descriptionOrNull(value: String) = nodes().firstOrNull { it.isVisibleToUser && it.contentDescription?.toString() == value }
    private fun text(value: String): AccessibilityNodeInfo = checkNotNull(textOrNull(value)) { "Missing $value" }
    private fun textOrNull(value: String) = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == value }
    private fun uniqueAlbumAction(value: String): AccessibilityNodeInfo {
        val matches = nodes().filter { it.isVisibleToUser && it.text?.toString() == value }
        assertEquals("Expected one album action in the sole album fixture: $value", 1, matches.size)
        return clickable(matches.single())
    }
    private fun clickableOrNull(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && current.packageName?.toString() == context.packageName) {
            current.refresh()
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo = checkNotNull(clickableOrNull(node)) { "Expected an own-app clickable queue control" }
    private fun clickDescription(value: String) { reveal(value); assertOwnForeground(); assertTrue(clickable(description(value)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private fun clickText(value: String) { revealControl(value, false); assertOwnForeground(); assertTrue(clickable(text(value)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun visibleControlBounds(node: AccessibilityNodeInfo): Rect {
        assertOwnForeground()
        val viewport = scrollViewport()
        val area = bounds(node)
        assertTrue("Queue gesture requires a completely visible own control: desc=${node.contentDescription} " +
            "bounds=${area.toShortString()} viewport=${viewport.toShortString()}",
            node.packageName?.toString() == context.packageName && node.isVisibleToUser && !area.isEmpty && viewport.contains(area))
        if (node.contentDescription?.toString()?.startsWith("拖动排序任务") == true) {
            assertTrue("Queue drag handle is clipped below its 48dp touch height",
                area.height() >= 47.5f * context.resources.displayMetrics.density)
        }
        return area
    }
    private fun swipeVisibleTaskTitle(value: String, left: Boolean) {
        val title = visibleControlBounds(text(value))
        val viewport = scrollViewport()
        val density = context.resources.displayMetrics.density
        val start = title.exactCenterX()
        val inset = 8f * density
        val available = if (left) start - viewport.left - inset else viewport.right - inset - start
        val travel = (120f * density).coerceAtMost(available)
        // The production reveal threshold is 46dp. Allow for touch slop without
        // changing it, and require enough real safe space for an actual swipe.
        assertTrue("Task swipe has insufficient safe travel: title=${title.toShortString()} viewport=${viewport.toShortString()}",
            travel >= 64f * density)
        drag(start, title.exactCenterY(), start + if (left) -travel else travel, title.exactCenterY())
    }
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
        return result
    }
    private fun assertOwnForeground() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertEquals("Queue test lost its active app window; system overlays cannot receive these gestures",
            context.packageName, root?.packageName?.toString())
        val focused = instrumentation.uiAutomation.windows.filter { it.isFocused }
        assertTrue("Queue test lost its focused app window: ${focused.map { it.root?.packageName }}",
            focused.isEmpty() || focused.any { it.root?.packageName?.toString() == context.packageName })
    }
    private fun safeViewport(): Rect {
        val result = Rect()
        instrumentation.runOnMainSync {
            val decor = checkNotNull(activityDecor) { "Queue activity decor is unavailable" }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            checkNotNull(decor.display).getRealMetrics(metrics)
            // Decor can already start below the status bar. Apply physical display
            // insets once, then intersect the actual active own window (Dialog or app).
            result.set(0, 0, metrics.widthPixels, metrics.heightPixels)
            val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemGestures())
            if (insets != null) result.set(result.left + insets.left, result.top + insets.top,
                result.right - insets.right, result.bottom - insets.bottom)
            check(result.intersect(Rect().also(decor::getWindowVisibleDisplayFrame))) { "Queue visible window has no safe display intersection" }
        }
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        assertEquals(context.packageName, root.packageName?.toString())
        assertTrue("Active queue window has no safe drawing area", result.intersect(bounds(root)))
        assertFalse("Queue safe viewport is empty", result.isEmpty)
        return result
    }
    private fun verticalScroller(target: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        fun eligible(node: AccessibilityNodeInfo): Boolean {
            if (!node.refresh() || node.packageName?.toString() != context.packageName || !node.isVisibleToUser ||
                !node.isScrollable || node.rangeInfo != null) return false
            if (node.actionList.any { it.id == up || it.id == down }) return true
            val area = bounds(node)
            return area.height() >= area.width() && node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
        }
        var parent = target?.parent
        while (parent != null && parent.packageName?.toString() == context.packageName) {
            if (eligible(parent)) return parent
            parent = parent.parent
        }
        // When an offscreen LazyColumn row is not mounted, use the largest real
        // vertical scroll container in the active own window, never a horizontal row.
        return nodes().filter(::eligible).maxByOrNull { bounds(it).height().toLong() * bounds(it).width() }
    }
    private fun scrollViewport(scroll: AccessibilityNodeInfo? = verticalScroller()): Rect {
        assertOwnForeground()
        val viewport = safeViewport()
        val actualScroll = checkNotNull(scroll) {
            "No visible own-app queue scroll container"
        }
        assertTrue("Queue list does not intersect its safe app viewport", viewport.intersect(bounds(actualScroll)))
        val margin = (4f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        viewport.inset(margin, margin)
        return viewport
    }
    private fun scrollRevealStep(scroll: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val viewport = scrollViewport(scroll)
        val before = queueGeometryDiagnostic()
        val x = viewport.left + 4f * context.resources.displayMetrics.density
        val from = viewport.top + viewport.height() * (if (forward) 0.72f else 0.32f)
        val to = viewport.top + viewport.height() * (if (forward) 0.46f else 0.58f)
        val start = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            if (action != MotionEvent.ACTION_CANCEL) {
                assertOwnForeground()
                assertTrue("Queue reveal scroll left its safe own viewport", viewport.contains(x.toInt(), y.toInt()))
            }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, from)
        try {
            repeat(12) { step -> SystemClock.sleep(20); inject(MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 12f) }
            inject(MotionEvent.ACTION_UP, to)
        } catch (failure: Throwable) { inject(MotionEvent.ACTION_CANCEL, to); throw failure }
        instrumentation.waitForIdleSync()
        assertOwnForeground()
        return before != queueGeometryDiagnostic()
    }
    private fun queueGeometryDiagnostic(): String = "safeViewport=${runCatching { safeViewport().toShortString() }.getOrNull()}; " +
        nodes().filter { it.isVisibleToUser && (!it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() || it.isScrollable) }
            .joinToString("; ") { "text=${it.text} desc=${it.contentDescription} bounds=${bounds(it).toShortString()} " +
                "scrollable=${it.isScrollable} enabled=${it.isEnabled} actions=${it.actionList.map { action -> action.id }}" }
    private fun captureQueueFailure(failure: Throwable) {
        runCatching {
            val directory = File(context.cacheDir, "queue_ui_failure_${UUID.randomUUID()}").apply { check(mkdir()) }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            File(directory, "failure.json").writeText(JSONObject().put("scope", "OWN_ISOLATED_QUEUE_FIXTURE_FAILURE")
                .put("version", InstalledTestTarget.versionName).put("instrumentationCompiledVersion", BuildConfig.VERSION_NAME).put("failureType", failure.javaClass.simpleName)
                .put("message", failure.message.orEmpty()).put("order", tasks.value.map { it.key })
                .put("activePackage", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                .put("nodes", queueGeometryDiagnostic()).toString(2))
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { File(directory, "failure.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { screenshot.recycle() }
            }
            failure.addSuppressed(AssertionError("captureRoot=${directory.absolutePath}"))
        }.onFailure { failure.addSuppressed(AssertionError("Queue failure capture failed: ${it.javaClass.simpleName}")) }
    }
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, longPress: Boolean = false, holdAtEnd: Long = 0L) {
        assertOwnForeground()
        val viewport = scrollViewport()
        assertTrue("Queue gesture crossed a system edge: safeViewport=${viewport.toShortString()} start=($x0,$y0) end=($x1,$y1)",
            viewport.contains(x0.toInt(), y0.toInt()) && viewport.contains(x1.toInt(), y1.toInt()))
        assertTrue("Only own-app observed queue coordinates may receive touch",
            nodes().any { it.isVisibleToUser && viewport.contains(bounds(it)) && bounds(it).contains(x0.toInt(), y0.toInt()) })
        val start = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float, y: Float) {
            if (action != MotionEvent.ACTION_CANCEL) {
                assertOwnForeground()
                assertTrue("Queue pointer left its safe app viewport", viewport.contains(x.toInt(), y.toInt()))
            }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, x0, y0)
        try {
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
        } catch (failure: Throwable) {
            inject(MotionEvent.ACTION_CANCEL, x1, y1)
            throw failure
        }
        SystemClock.sleep(180)
        assertOwnForeground()
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
            activityDecor = null
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
