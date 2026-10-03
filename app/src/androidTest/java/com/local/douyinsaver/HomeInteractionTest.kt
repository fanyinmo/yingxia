package com.local.douyinsaver

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** UI regression using isolated, synthetic parsed metadata; this is not a public-link parsing test. */
@RunWith(AndroidJUnit4::class)
class HomeInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun clearControlRemovesReadyAlbumAndReturnsToSinglePasteAction() = withIsolatedEngine { engine ->
        instrumentation.runOnMainSync {
            engine.acceptShare("https://www.douyin.com/note/7692079601103867505")
            setState(engine, "video", ParsedVideo("7692079601103867505", "合成元数据界面验证", "", 0.0, 640, 480,
                images = listOf(ParsedImage(""), ParsedImage("")), bgmUrl = "https://sf1.douyinstatic.com/test-audio"))
            setState(engine, "stage", TaskStage.READY)
            setState(engine, "message", "已解析 2 张图片，请选择保存方式")
            engine.fileName = "discarded_name"
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            waitFor { nodes().any { n -> n.text?.toString() == "图集 · 2 张" } }
            assertFalse(nodes().any { n -> n.text?.toString() == "保存位置" })
            capture("home_album_before_clear")
            val clear = nodes().single { n -> n.contentDescription?.toString() == "清空链接" && n.isClickable }
            assertTrue(clear.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor { engine.video == null && engine.input.isEmpty() && nodes().any { n -> n.text?.toString() == "粘贴并解析" } }
            assertEquals(TaskStage.IDLE, engine.stage)
            assertEquals("", engine.fileName)
            assertNull(engine.duplicate)
            assertFalse(nodes().any { n -> n.text?.toString()?.contains("图集 ·") == true })
            capture("home_after_clear")
        }
    }

    @Test fun onePrimaryButtonAdaptsToInputWithoutHomeSaveLocation() = withIsolatedEngine { engine ->
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            waitFor { nodes().any { n -> n.text?.toString() == "粘贴并解析" } }
            assertEquals(1, nodes().count { n -> n.text?.toString() == "粘贴并解析" })
            assertFalse(nodes().any { n -> n.text?.toString() in listOf("只粘贴", "保存位置", "解析作品") })
            assertTrue(nodes().any { n -> n.text?.toString() == "把喜欢的视频下载到手机" })
            scenario.onActivity { engine.updateInput("https://v.douyin.com/aAvtq2_EGUA/") }
            waitFor { nodes().any { n -> n.text?.toString() == "解析作品" } }
            assertEquals(1, nodes().count { n -> n.text?.toString() == "解析作品" })
            assertFalse(nodes().any { n -> n.text?.toString() in listOf("只粘贴", "粘贴并解析", "保存位置") })
        }
    }

    private fun withIsolatedEngine(body: (SaverEngine) -> Unit) {
        val namespace = "home_ui_${UUID.randomUUID()}_"
        val constructor = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java).apply { isAccessible = true }
        val isolated = constructor.newInstance(context.applicationContext as Application, namespace)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        instrumentation.runOnMainSync { singleton.set(null, isolated) }
        try { body(isolated) }
        finally {
            instrumentation.runOnMainSync { singleton.set(null, previous) }
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

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
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
        fail("Expected own-app UI state was not reached")
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(context.cacheDir, "ui_validation_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
