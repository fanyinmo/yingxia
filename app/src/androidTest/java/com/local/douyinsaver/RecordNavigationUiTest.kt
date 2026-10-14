package com.local.douyinsaver

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    @Test fun bothVisibleRecordButtonsReturnToHomeAndResolveWithoutSaving() {
        val prefix = "record_ui_${UUID.randomUUID()}_"
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val id = "7695376760892314330"
        ParseRecords(app, prefix).save(ParseRecord(id, "UI 解析记录样例", true))
        val saved = SavedVideo(id, "UI 下载记录样例", "content://owned_ui_fixture/untouched", 100)
        DownloadRecords(app, prefix).save(saved)
        lateinit var engine: SaverEngine
        instrumentation.runOnMainSync {
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(app, prefix)
            engine.selectedPage = AppPage.PARSE_HISTORY
            singleton.set(null, engine)
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val host = MainActivity::class.java.getDeclaredField("parserHost").apply { isAccessible = true }.get(activity) as FrameLayout
                    engine.detachParserHost(host) // Actual click uses the fresh resolver; no public WebView requests.
                }
                await { nodes().any { it.text?.toString() == "UI 解析记录样例" } }
                capture("rc4-parse-history.png")
                clickReparse()
                await { nodes().any { it.text?.toString()?.contains("https://www.douyin.com/note/$id") == true } }
                instrumentation.runOnMainSync {
                    assertEquals(AppPage.HOME, engine.selectedPage)
                    engine.cancel()
                    engine.selectedPage = AppPage.HISTORY
                }
                await { nodes().any { it.text?.toString() == "UI 下载记录样例" } }
                capture("rc4-download-history.png")
                clickReparse()
                await { nodes().any { it.text?.toString()?.contains("https://www.douyin.com/video/$id") == true } }
                instrumentation.runOnMainSync {
                    assertEquals(AppPage.HOME, engine.selectedPage)
                    assertEquals(listOf(saved), DownloadRecords(app, prefix).history())
                    assertFalse(engine.stage in listOf(TaskStage.DOWNLOADING, TaskStage.SAVING))
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                engine.cancel()
                (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
                singleton.set(null, previous)
            }
            listOf("parse_records", "downloads", "download_tasks", "download_options", "parse_diagnostics").forEach { app.deleteSharedPreferences(prefix + it) }
        }
    }
    private fun clickReparse() {
        var node = nodes().first { it.text?.toString() == "重新解析" }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val output = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || node.packageName?.toString() != app.packageName) return
            output += node; repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return output
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(70) }
        fail("Expected record UI was not reached: ${nodes().map { it.text }}")
    }
    private fun capture(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Cannot capture record screen")
        try { File(app.cacheDir, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
}
