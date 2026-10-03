package com.local.douyinsaver

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Real pointer gestures exercise the pure picker with an isolated engine and an in-memory color. */
@RunWith(AndroidJUnit4::class)
class CustomColorPickerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun paletteGesturesKeepHueAtWhiteAndBlackWithoutHexEditor() = withIsolatedEngine {
        val selected = mutableStateOf("#7650A5")
        val intent = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color(android.graphics.Color.parseColor(selected.value)))) {
                        Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 20.dp, vertical = 40.dp)) {
                            Text("自选主题色")
                            CustomColorPicker(colorHex = selected.value, onColorChanged = { selected.value = it })
                        }
                    }
                }
            }
            fun readHex(): String {
                var hex = ""
                scenario.onActivity { hex = selected.value }
                return hex
            }
            waitFor { described("选择色相") != null && described("选择饱和度和亮度") != null }
            // MainActivity may initialize the normal settings schema on a fresh installation.
            // Establish the baseline after mounting the pure component; only gestures are under test.
            val appearance = context.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE)
            val previousAppearance = appearance.all.toMap()
            assertFalse("A palette must not expose a color-code editor", nodes().any { it.isEditable })
            assertFalse(nodes().any { it.text?.toString()?.contains("HEX", ignoreCase = true) == true })

            val original = readHex()
            gesture(bounds("选择色相"), 0.12f, 0.5f, 0.48f, 0.5f)
            waitFor { readHex() != original }
            tap(bounds("选择饱和度和亮度"), 0.88f, 0.14f)
            val colorful = readHex()
            val chosenHue = hueOf(colorful)
            assertTrue("Dragging the palette should produce a saturated color", saturationOf(colorful) > 0.75f)

            // Coordinates remain just inside the gradient. RGB rounding reaches the actual endpoints.
            tapEdge(bounds("选择饱和度和亮度"), atWhite = true)
            waitFor { readHex() == "#FFFFFF" }
            tap(bounds("选择饱和度和亮度"), 0.88f, 0.14f)
            waitFor { readHex() != "#FFFFFF" }
            assertHueNear(chosenHue, hueOf(readHex()))

            tapEdge(bounds("选择饱和度和亮度"), atWhite = false)
            waitFor { readHex() == "#000000" }
            tap(bounds("选择饱和度和亮度"), 0.88f, 0.14f)
            waitFor { readHex() != "#000000" }
            assertHueNear(chosenHue, hueOf(readHex()))
            assertEquals("Pure-picker gestures must not change user appearance preferences", previousAppearance, appearance.all.toMap())
        }
    }

    @Test fun selectedColorsAndLegacyHexPersistOnlyInAnIsolatedStore() {
        val prefix = "palette_validation_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefix + name, mode)
            override fun getFilesDir(): File = File(context.cacheDir, prefix)
        }
        val preferences = isolated.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("palette", "CUSTOM").putString("hex", "#AbCdEf")
            .putString("mode", "LIGHT").putInt("profile_version", 2).commit())
        try {
            instrumentation.runOnMainSync {
                val oldStore = AppearanceStore(isolated)
                assertEquals(ThemePalette.CUSTOM, oldStore.options.palette)
                assertEquals("#ABCDEF", oldStore.options.customHex)
                oldStore.update(oldStore.options.copy(customHex = "#4285F4"))
                assertEquals("#4285F4", AppearanceStore(isolated).options.customHex)
                assertEquals("#4285F4", AppearanceStore(isolated).options.primaryHex)
                oldStore.update(oldStore.options.copy(customHex = "#000000"))
                assertEquals("#000000", AppearanceStore(isolated).options.customHex)
                oldStore.update(oldStore.options.copy(customHex = "#FFFFFF"))
                assertEquals("#FFFFFF", AppearanceStore(isolated).options.customHex)
            }
        } finally {
            context.deleteSharedPreferences(prefix + "appearance_v1")
        }
    }

    private fun hueOf(hex: String): Float = FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), it) }[0]
    private fun saturationOf(hex: String): Float = FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), it) }[1]
    private fun assertHueNear(expected: Float, actual: Float) {
        val difference = abs(expected - actual).let { minOf(it, 360f - it) }
        assertTrue("Achromatic endpoints lost the previously chosen hue: expected=$expected, actual=$actual", difference < 2f)
    }

    private fun withIsolatedEngine(body: () -> Unit) {
        val namespace = "palette_ui_${UUID.randomUUID()}_"
        val constructor = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java).apply { isAccessible = true }
        val isolated = constructor.newInstance(context.applicationContext as Application, namespace)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        instrumentation.runOnMainSync { singleton.set(null, isolated) }
        try { body() }
        finally {
            instrumentation.runOnMainSync { singleton.set(null, previous) }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics").forEach {
                context.deleteSharedPreferences(namespace + it)
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val ownPackages = setOf(context.packageName, instrumentation.context.packageName)
        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() in ownPackages) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun described(label: String) = nodes().firstOrNull { it.contentDescription?.toString() == label }
    private fun bounds(label: String): Rect = Rect().also { rectangle ->
        val node = described(label) ?: error("The own-app palette '$label' is not visible")
        node.getBoundsInScreen(rectangle)
        check(rectangle.width() > 0 && rectangle.height() > 0)
    }

    private fun tap(rectangle: Rect, x: Float, y: Float) =
        gesture(rectangle, x, y, x, y)

    private fun tapEdge(rectangle: Rect, atWhite: Boolean) {
        val x = if (atWhite) rectangle.left + 0.05f else rectangle.left + rectangle.width() * 0.88f
        val y = if (atWhite) rectangle.top + 0.05f else rectangle.bottom - 0.05f
        val now = SystemClock.uptimeMillis()
        // Start inside the rounded clip, then keep the captured gesture through the corner endpoint.
        sendPointer(now, now, MotionEvent.ACTION_DOWN, rectangle.exactCenterX(), rectangle.exactCenterY())
        sendPointer(now, now + 50, MotionEvent.ACTION_MOVE, x, y)
        sendPointer(now, now + 80, MotionEvent.ACTION_UP, x, y)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(80)
    }

    private fun gesture(rectangle: Rect, startX: Float, startY: Float, endX: Float, endY: Float) {
        val now = SystemClock.uptimeMillis()
        fun x(fraction: Float) = rectangle.left + rectangle.width() * fraction
        fun y(fraction: Float) = rectangle.top + rectangle.height() * fraction
        sendPointer(now, now, MotionEvent.ACTION_DOWN, x(startX), y(startY))
        repeat(8) { step ->
            val t = (step + 1) / 8f
            sendPointer(now, now + (step + 1) * 25L, MotionEvent.ACTION_MOVE,
                x(startX + (endX - startX) * t), y(startY + (endY - startY) * t))
        }
        sendPointer(now, now + 225, MotionEvent.ACTION_UP, x(endX), y(endY))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(80)
    }

    private fun sendPointer(downTime: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertTrue("The palette pointer event was not delivered", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Expected palette UI state was not reached")
    }
}
