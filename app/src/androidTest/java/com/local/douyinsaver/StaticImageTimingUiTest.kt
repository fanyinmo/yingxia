package com.local.douyinsaver

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Production controls and ViewModel on an isolated engine, with a plain self-made visible screen. */
@RunWith(AndroidJUnit4::class)
class StaticImageTimingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as Application
    private val marker = "自制静图时长设置测试"
    private val namespace = "static_timing_ui_${UUID.randomUUID()}_"
    private val captures = File(context.cacheDir, namespace + "captures")

    @Test fun decimalInputRoundSliderInvalidRecoveryAndFreshEngineRestoreUseOnlyIsolatedPreferences() {
        // MainActivity initially reads AppearanceStore before mounting our plain screen.
        // Reject an unmigrated environment rather than migrating any user appearance data.
        assertTrue("The UI fixture requires an already-migrated user appearance profile; no migration is permitted",
            context.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE).getInt("profile_version", 0) >= 2)
        val userPreferences = snapshotExistingUserPreferences()
        val background = File(context.filesDir, "appearance/background.jpg")
        val originalBackground = hash(background)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val engines = mutableListOf<SaverEngine>()
        val preferences = context.getSharedPreferences(namespace + "download_options", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putInt("image_seconds", 7).putString("naming", "TITLE").commit())
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val first = createEngine().also(engines::add)
            main { singleton.set(null, first) }
            scenario = mount()
            await { editable()?.text?.toString() == "7" && slider() != null }
            assertEquals(7.0, seconds(first), 0.0)

            setText("0.1")
            await { seconds(first) == 0.1 && preferences.getInt("static_image_duration_tenths", -1) == 1 && sliderNear(0.1f) }
            capture("input-0.1-round-slider")

            // Exercise the intermediate decimal state as well as complete replacement text.
            setText(""); setText("1")
            await { seconds(first) == 1.0 }
            setText("1.")
            await { editable()?.text?.toString() == "1." && hasError() }
            assertEquals(1.0, seconds(first), 0.0)
            assertEquals(10, preferences.getInt("static_image_duration_tenths", -1))
            assertTrue(hasError())
            assertTrue("The production input could not receive input focus",
                checkNotNull(editable()).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            setText("1.2")
            await { seconds(first) == 1.2 && editable()?.text?.toString() == "1.2" && sliderNear(1.2f) }
            clickText("收起输入焦点")
            assertEquals(1.2, seconds(first), 0.0)
            assertEquals(12, preferences.getInt("static_image_duration_tenths", -1))
            capture("input-1.2-after-focus-cleared")

            setProgress(2.74f)
            await { seconds(first) == 2.7 && editable()?.text?.toString() == "2.7" && sliderNear(2.7f) }
            assertEquals(27, preferences.getInt("static_image_duration_tenths", -1))
            capture("slider-rounded-to-2.7")

            for (invalid in listOf("", "0", "120.1")) {
                val accepted = preferences.all.toMap()
                setText(invalid)
                await { editable()?.text?.toString() == invalid && hasError() }
                assertEquals("An invalid UI input overwrote the saved default", accepted, preferences.all)
                assertEquals(2.7, seconds(first), 0.0)
                assertTrue(sliderNear(2.7f))
            }
            capture("invalid-120.1-keeps-saved-default")
            setText("1.2")
            await { seconds(first) == 1.2 && !hasError() && sliderNear(1.2f) }
            assertEquals(7, preferences.getInt("image_seconds", -1))
            assertEquals("TITLE", preferences.getString("naming", null))
            scenario.close(); scenario = null
            main { cancel(first) }

            // Close the old composition and engine. Restore solely from the same isolated preferences.
            val restored = createEngine().also(engines::add)
            main { singleton.set(null, restored) }
            scenario = mount()
            await { editable()?.text?.toString() == "1.2" && sliderNear(1.2f) }
            assertEquals(1.2, seconds(restored), 0.0)
            assertEquals(1.2, AlbumTimingPreferences(context, namespace).readStaticSeconds(), 0.0)
            capture("fresh-activity-and-engine-restored-1.2")
        } catch (failure: Throwable) {
            runCatching { capture("failure") }
            throw failure
        } finally {
            scenario?.close()
            main { engines.forEach(::cancel); singleton.set(null, previous) }
            listOf("download_options", "downloads", "download_tasks", "parse_diagnostics").forEach {
                context.deleteSharedPreferences(namespace + it)
            }
            assertEquals("The test changed a user setting or record", userPreferences,
                userPreferences.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() })
            assertEquals("The test changed the user background", originalBackground, hash(background))
            // Retain only this run's self-made screenshots as reviewable test evidence.
            println("static_timing_ui_evidence=${captures.absolutePath}")
        }
    }

    private fun mount(): ActivityScenario<MainActivity> {
        lateinit var model: SaverViewModel
        main { model = SaverViewModel(application) }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) {
                val focus = LocalFocusManager.current
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(marker, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { focus.clearFocus() }) { Text("收起输入焦点") }
                    StaticImageDurationSettingsControls(model.staticImageSeconds) { model.staticImageSeconds = it }
                }
            } }
        } }
        instrumentation.waitForIdleSync()
        return scenario
    }

    private fun createEngine(): SaverEngine {
        lateinit var engine: SaverEngine
        main { engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(application, namespace) }
        return engine
    }
    private fun cancel(engine: SaverEngine) {
        (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
    }
    private fun seconds(engine: SaverEngine): Double {
        var value = 0.0
        main { value = engine.staticImageSeconds }
        return value
    }
    private fun setText(value: String) {
        val field = checkNotNull(editable()) { "Missing the production static duration input" }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        instrumentation.waitForIdleSync()
    }
    private fun setProgress(value: Float) {
        assertTrue(checkNotNull(slider()).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
        instrumentation.waitForIdleSync()
    }
    private fun editable() = nodes().singleOrNull { it.isVisibleToUser && it.isEditable &&
        it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
    private fun slider() = nodes().singleOrNull { it.isVisibleToUser && it.rangeInfo != null &&
        it.actionList.any { action -> action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id } }
    private fun sliderNear(value: Float) = slider()?.rangeInfo?.current?.let { abs(it - value) < 0.0001f } == true
    private fun hasError() = nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("未保存，仍使用") == true }
    private fun clickText(value: String) {
        var current = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == value }
        while (current != null && current.packageName?.toString() == context.packageName) {
            if (current.isClickable) {
                assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                return
            }
            current = current.parent
        }
        error("Missing self-made focus action '$value'")
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
        instrumentation.uiAutomation.windows.forEach { visit(it.root) }
        return result
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(70)
        }
        fail("Static duration UI condition timed out; nodes=${nodes().map { it.text?.toString() }}")
    }
    private fun capture(name: String) {
        assertTrue("Only the self-made settings screen may be captured", nodes().any {
            it.isVisibleToUser && it.text?.toString() == marker
        })
        instrumentation.waitForIdleSync()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val destination = File(captures, "$name.png")
        check(captures.isDirectory || captures.mkdirs())
        check(captures.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        try { destination.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
        println("static_timing_ui_capture=${destination.absolutePath}")
    }
    private fun snapshotExistingUserPreferences(): Map<String, Map<String, *>> {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") && !it.name.startsWith(namespace) }
            .map { it.name.removeSuffix(".xml") }.toSet() + setOf("appearance_v1", "download_options", "downloads",
                "download_tasks", "parse_diagnostics", "preview_options", "download_folder", "notification_prompt")
        // SharedPreferences memory values avoid false failures from asynchronous apply() disk writes.
        return names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    }
    private fun hash(file: File): String? = if (file.isFile)
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } else null
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
