package com.local.douyinsaver

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses synthetic metadata and isolated preferences; does not play or download media. */
@RunWith(AndroidJUnit4::class)
class WatermarkInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val markedUrl = "https://v3.douyinvod.com/fixture-watermarked.mp4"
    private val cleanUrl = "https://v3.douyinvod.com/fixture-clean.mp4"
    private var fixture: SaverEngine? = null

    @Test fun cleanSourceUsesOneOrdinaryDownloadButtonAndIgnoresLegacyVersionPreferences() = withIsolatedEngine { engine, namespace ->
        val preferences = context.getSharedPreferences(namespace + "download_options", 0)
        assertTrue(preferences.edit().putString("watermark_mode", WatermarkMode.WATERMARKED.name)
            .putString("default_watermark_mode", WatermarkMode.ORIGINAL.name).commit())
        seedReady(engine, listOf(MediaSource(markedUrl, WatermarkMode.WATERMARKED), MediaSource(cleanUrl, WatermarkMode.CLEAN)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            assertTrue(downloadButton("下载视频").isEnabled)
            assertNoVersionControls()
            assertEquals(cleanUrl, engine.selectedContent?.mediaUrl)
            assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
            assertEquals(WatermarkMode.CLEAN, engine.batchWatermarkMode)
            val restored = newEngine(namespace)
            try {
                assertEquals(WatermarkMode.CLEAN, restored.watermarkMode)
                assertEquals(WatermarkMode.CLEAN, restored.defaultWatermarkMode)
            } finally { dispose(restored) }
            assertEquals(WatermarkMode.WATERMARKED.name, preferences.getString("watermark_mode", null))
            assertEquals(WatermarkMode.ORIGINAL.name, preferences.getString("default_watermark_mode", null))
            assertEquals(TaskStage.READY, engine.stage)
            assertTrue(engine.history.isEmpty())
        }
    }

    @Test fun aMarkedOnlySourceCannotEnableDownloadOrOfferAFallback() = withIsolatedEngine { engine, _ ->
        seedReady(engine, listOf(MediaSource(markedUrl, WatermarkMode.WATERMARKED)))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            assertFalse(downloadButton("下载视频").isEnabled)
            assertNoVersionControls()
            assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
            assertNull(engine.selectedContent)
            assertEquals(TaskStage.READY, engine.stage)
            assertTrue(engine.history.isEmpty())
        }
    }

    @Test fun anUnclassifiedPlayableSourceCannotEnableDownloadOrOfferAnOriginalFallback() = withIsolatedEngine { engine, _ ->
        seedReady(engine, emptyList())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            assertFalse(downloadButton("下载视频").isEnabled)
            assertNoVersionControls()
            assertNull(engine.selectedContent)
            assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
            assertEquals(TaskStage.READY, engine.stage)
            assertTrue(engine.history.isEmpty())
        }
    }

    private fun assertNoVersionControls() {
        val forbiddenLabels = setOf("有水印", "无水印", "原始版本", "下载版本")
        val labels = nodes().map { it.text?.toString().orEmpty() }
        assertFalse("Version choices should not remain in the download UI: $labels",
            labels.any { it in forbiddenLabels || it.contains("使用可用的") ||
                it.startsWith("下载无水印") || it.startsWith("下载有水印") || it.startsWith("下载原始版本") })
    }

    private fun seedReady(engine: SaverEngine, sources: List<MediaSource>) {
        instrumentation.runOnMainSync {
            engine.acceptShare("https://www.douyin.com/video/7692079601103867505")
            setState(engine, "video", ParsedVideo("7692079601103867505", "自制下载界面验证", markedUrl,
                12.0, 640, 480, mediaSources = sources))
            setState(engine, "stage", TaskStage.READY)
            setState(engine, "message", "解析完成，可以下载视频")
        }
    }

    private fun newEngine(namespace: String): SaverEngine = SaverEngine::class.java
        .getDeclaredConstructor(Application::class.java, String::class.java).apply { isAccessible = true }
        .newInstance(context.applicationContext as Application, namespace)

    private fun withIsolatedEngine(body: (SaverEngine, String) -> Unit) {
        val namespace = "watermark_ui_${UUID.randomUUID()}_"
        val isolated = newEngine(namespace)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        instrumentation.runOnMainSync { singleton.set(null, isolated) }
        fixture = isolated
        try { body(isolated, namespace) }
        finally {
            fixture = null
            instrumentation.runOnMainSync {
                singleton.set(null, previous)
                dispose(isolated)
            }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics").forEach {
                context.deleteSharedPreferences(namespace + it)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(engine: SaverEngine, name: String, value: T) {
        val field = SaverEngine::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(engine) as MutableState<T>).value = value
    }

    private fun dispose(engine: SaverEngine) {
        (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
    }

    private fun downloadButton(text: String): AccessibilityNodeInfo {
        val label = revealText(text)
        // These Material buttons expose a generic clickable View, not android.widget.Button.
        val button = ownAncestors(label).firstOrNull { it.isClickable }
            ?: run { dumpUi("missing_button_control"); error("Expected a clickable own-app download control: $text") }
        val labelBounds = Rect().also(label::getBoundsInScreen)
        val buttonBounds = Rect().also(button::getBoundsInScreen)
        try {
            assertTrue("Download control must contain its observed label", buttonBounds.contains(labelBounds))
            val offersClick = button.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            assertEquals("Download click action must match the control's enabled state", button.isEnabled, offersClick)
        } catch (error: Throwable) {
            dumpUi("button_state_mismatch")
            throw error
        }
        return button
    }

    private fun ownAncestors(label: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        var node: AccessibilityNodeInfo? = label
        while (node != null && node.packageName?.toString() == context.packageName) {
            result += node
            node = node.parent
        }
        return result
    }

    private fun revealText(text: String, partial: Boolean = false): AccessibilityNodeInfo {
        fun visible() = nodes().firstOrNull { node ->
            val label = node.text?.toString().orEmpty()
            node.isVisibleToUser && (if (partial) label.contains(text) else label == text)
        }
        waitFor { nodes().any { it.isScrollable } || visible() != null }
        visible()?.let { return it }
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(8) {
                visible()?.let { return it }
                val scroll = scrollViewport(action)
                if (scroll != null) Log.i("WatermarkUiTest", "scroll action=$action accepted=${scroll.performAction(action)} target=$text")
                SystemClock.sleep(200)
            }
        }
        // Large single-card items can expose only part of their descendants to the accessibility tree.
        // A bounded touch scroll reproduces the user's gesture within the observed own-app list viewport.
        for (forward in listOf(true, false)) repeat(10) {
            visible()?.let { return it }
            val viewport = scrollViewport() ?: run {
                dumpUi("missing_scroll_viewport")
                error("Expected own-app list viewport for $text")
            }
            swipeViewport(viewport, forward)
            SystemClock.sleep(200)
        }
        visible()?.let { return it }
        dumpUi("missing_control")
        error("Expected own-app control after scrolling: $text")
    }

    private fun scrollViewport(action: Int? = null): AccessibilityNodeInfo? = nodes().filter { node ->
        node.isScrollable && node.className?.toString() != "android.widget.EditText" && node.isVisibleToUser &&
            (action == null || node.actionList.any { it.id == action })
    }.maxByOrNull { node ->
        val rect = Rect().also(node::getBoundsInScreen)
        rect.width().coerceAtLeast(0).toLong() * rect.height().coerceAtLeast(0)
    }

    private fun swipeViewport(viewport: AccessibilityNodeInfo, forward: Boolean) {
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: error("No active own-app window")
        check(root.packageName?.toString() == context.packageName) { "Refusing touch outside own-app window" }
        check(viewport.packageName?.toString() == context.packageName) { "Refusing foreign scroll viewport" }
        val window = Rect().also(root::getBoundsInScreen)
        val bounds = Rect().also(viewport::getBoundsInScreen)
        check(bounds.intersect(window) && bounds.width() > 80 && bounds.height() > 120) { "Invalid observed scroll viewport" }
        val x = bounds.left + bounds.width() * 0.85f
        val top = bounds.top + bounds.height() * 0.22f
        val bottom = bounds.top + bounds.height() * 0.78f
        val start = if (forward) bottom else top
        val end = if (forward) top else bottom
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Own-app touch injection rejected" } }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, start)
        try {
            for (step in 1..8) {
                SystemClock.sleep(40)
                inject(MotionEvent.ACTION_MOVE, start + (end - start) * step / 8f)
            }
            inject(MotionEvent.ACTION_UP, end)
        } catch (error: Throwable) {
            inject(MotionEvent.ACTION_CANCEL, end)
            throw error
        }
    }

    private fun dumpUi(reason: String) {
        val diagnostic = buildString {
            appendLine("reason=$reason stage=${fixture?.stage} mode=${fixture?.watermarkMode} id=${fixture?.video?.id}")
            nodes().forEachIndexed { index, node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                val state = if (Build.VERSION.SDK_INT >= 30) node.stateDescription else null
                appendLine("$index class=${node.className} text=${node.text} desc=${node.contentDescription} state=$state " +
                    "bounds=$bounds visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} " +
                    "selected=${node.isSelected} checkable=${node.isCheckable} checked=${node.isChecked} " +
                    "scrollable=${node.isScrollable} actions=${node.actionList.map { it.id }}")
            }
        }
        val basename = "watermark_ui_${reason}_${System.currentTimeMillis()}"
        File(context.cacheDir, "$basename.txt").writeText(diagnostic)
        Log.i("WatermarkUiTest", "$basename\n$diagnostic")
        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(context.cacheDir, "$basename.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh()) return
            if (node.packageName?.toString() == context.packageName) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        dumpUi("wait_timeout")
        fail("Expected own-app download UI state was not reached")
    }
}
