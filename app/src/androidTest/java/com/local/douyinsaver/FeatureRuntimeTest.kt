package com.local.douyinsaver

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.app.Application
import android.app.ActivityManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeatureRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun appearanceSlidersDragPersistWithoutSamplePreviewOrUserChanges() {
        val prefix = "appearance_controls_${UUID.randomUUID()}_"
        val temporary = File(context.cacheDir, prefix)
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences(prefix + name, mode)
            override fun getFilesDir(): File = temporary
        }
        lateinit var store: AppearanceStore
        lateinit var engine: SaverEngine
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        instrumentation.runOnMainSync {
            store = AppearanceStore(isolatedContext)
            // Expose real background controls using isolated metadata; no photo import or user file read by the control.
            store.update(store.options.copy(backgroundRevision = 1L, position = BackgroundPosition.TOP))
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(context.applicationContext as Application, prefix)
            singleton.set(null, engine)
        }
        val userBackground = digest(File(context.filesDir, "appearance/background.jpg"))
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { AppearanceControls(store) }
                    }
                } }
                await { ownNodes().any { it.text?.toString() == "外观" } }
                val userAppearance = context.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE).all.toMap()
                fun options(): AppearanceOptions {
                    lateinit var value: AppearanceOptions
                    instrumentation.runOnMainSync { value = store.options }
                    return value
                }
                fun positionChoicesVisible() = ownNodes().any { it.text?.toString() in listOf("顶部", "居中", "底部") }
                assertEquals(BackgroundFit.CROP, options().fit)
                assertFalse("Crop mode must hide background-position choices", positionChoicesVisible())
                clickAppearanceText("完整显示")
                await { options().fit == BackgroundFit.FIT && positionChoicesVisible() }
                assertEquals("Showing position choices lost the saved selection", BackgroundPosition.TOP, options().position)
                clickAppearanceText("底部")
                await { options().position == BackgroundPosition.BOTTOM }
                clickAppearanceText("铺满裁剪")
                await { options().fit == BackgroundFit.CROP && !positionChoicesVisible() }
                assertEquals("Hiding position choices changed the saved selection", BackgroundPosition.BOTTOM, options().position)
                clickAppearanceText("完整显示")
                await { options().fit == BackgroundFit.FIT && positionChoicesVisible() }
                assertEquals(BackgroundPosition.BOTTOM, options().position)
                adjustAppearance("整页背景模糊", 0.3f, 0f..24f) { options().blur }
                adjustAppearance("背景暗度", 0.45f, 0f..0.8f) { options().darkness }
                adjustAppearance("板块背景不透明度", 0.68f, 0f..1f) { options().cardOpacity }
                adjustAppearance("文字保护强度", 0.4f, 0f..0.85f) { options().textProtection }
                assertFalse("The removed example preview is still exposed", ownNodes().any {
                    it.text?.toString() in listOf("效果预览", "你的下载空间", "主色")
                })
                assertEquals("Appearance drag values did not persist", options(), AppearanceStore(isolatedContext).options)
                assertEquals("Appearance controls changed user preferences", userAppearance,
                    context.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE).all.toMap())
                assertEquals("Appearance controls changed the user background", userBackground, digest(File(context.filesDir, "appearance/background.jpg")))
                if (InstrumentationRegistry.getArguments().getString("capture_appearance") == "true") {
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(context.cacheDir, "appearance_round_sliders_validation.png").outputStream().use {
                        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { screenshot.recycle() }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
                singleton.set(null, previous)
            }
            listOf("appearance_v1", "downloads", "download_tasks", "download_options", "parse_diagnostics").forEach {
                context.deleteSharedPreferences(prefix + it)
            }
            if (temporary.exists()) {
                check(temporary.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                temporary.deleteRecursively()
            }
        }
    }

    private fun clickAppearanceText(value: String) {
        var target: AccessibilityNodeInfo? = null
        for (attempt in 0 until 12) {
            val text = ownNodes().firstOrNull { it.text?.toString() == value && it.isVisibleToUser }
            var candidate = text
            while (candidate != null && candidate.packageName?.toString() == context.packageName) {
                if (candidate.refresh() && candidate.isClickable && candidate.isEnabled && candidate.isVisibleToUser) {
                    target = candidate
                    break
                }
                candidate = candidate.parent
            }
            if (target != null) break
            ownNodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        assertTrue("No clickable own-app '$value' control", checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun adjustAppearance(label: String, fraction: Float, range: ClosedFloatingPointRange<Float>, read: () -> Float) {
        fun slider(): AccessibilityNodeInfo? {
            val labels = ownNodes().filter { it.contentDescription?.toString() == label && it.isVisibleToUser }
            for (labelNode in labels) {
                var candidate: AccessibilityNodeInfo? = labelNode
                while (candidate != null && candidate.packageName?.toString() == context.packageName) {
                    if (!candidate.refresh()) break
                    if (candidate.rangeInfo != null && candidate.isVisibleToUser) return candidate
                    candidate = candidate.parent
                }
            }
            return null
        }
        val density = context.resources.displayMetrics.density
        val minimumHeight = 47.5f * density
        // A visible Compose node may expose only the small part intersecting the viewport.
        ownNodes().firstOrNull { it.contentDescription?.toString() == label }
            ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync()
        for (attempt in 0 until 12) {
            val node = slider()
            val area = node?.let { Rect().also(it::getBoundsInScreen) }
            val viewport = ownNodes().firstOrNull { it.isScrollable && it.rangeInfo == null }
                ?.let { Rect().also(it::getBoundsInScreen) }
            if (area != null && area.height() >= minimumHeight && (viewport == null || viewport.contains(area))) break
            ownNodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        val node = checkNotNull(slider()) { "No visible native range control for '$label'; ${appearanceRangeDiagnostic(label)}" }
        val bounds = Rect().also(node::getBoundsInScreen)
        val viewport = ownNodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.let { Rect().also(it::getBoundsInScreen) }
        val diagnostic = "label=$label bounds=${bounds.toShortString()} heightDp=${bounds.height() / density} density=$density " +
            "viewport=${viewport?.toShortString()} range=${node.rangeInfo?.current} state=${node.stateDescription} " +
            "actions=${node.actionList.map { it.id }}; ${appearanceRangeDiagnostic(label)}"
        println("appearance_slider_bounds $diagnostic")
        if (bounds.height() < minimumHeight) {
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { File(context.cacheDir, "appearance_slider_bounds_failed.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { screenshot.recycle() }
            }
        }
        assertTrue("'$label' lost its 48dp touch area after scrolling: $diagnostic", bounds.height() >= minimumHeight)
        assertNotNull("'$label' lost native slider semantics", node.rangeInfo)
        val old = read()
        val current = ((old - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val inset = 8f * density
        fun x(value: Float) = bounds.left + inset + (bounds.width() - inset * 2f) * value
        // Exercise the advertised touch region outside the 16dp circle on the first control.
        val touchY = bounds.exactCenterY() + if (label == "整页背景模糊") 14f * density else 0f
        val start = SystemClock.uptimeMillis()
        fun pointer(action: Int, target: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x(target), touchY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Own-app appearance gesture was rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, current)
        try {
            repeat(10) { index ->
                SystemClock.sleep(25)
                pointer(MotionEvent.ACTION_MOVE, current + (fraction - current) * (index + 1) / 10f)
            }
            pointer(MotionEvent.ACTION_UP, fraction)
        } catch (failure: Throwable) {
            pointer(MotionEvent.ACTION_CANCEL, fraction)
            throw failure
        }
        val expected = range.start + (range.endInclusive - range.start) * fraction
        await { kotlin.math.abs(read() - expected) <= (range.endInclusive - range.start) * 0.05f }
        assertTrue("'$label' did not actually change", kotlin.math.abs(read() - old) > (range.endInclusive - range.start) * 0.05f)
        await { slider()?.stateDescription?.toString()?.isNotBlank() == true }
    }

    private fun appearanceRangeDiagnostic(label: String): String {
        fun describe(node: AccessibilityNodeInfo): String {
            val bounds = Rect().also(node::getBoundsInScreen)
            return "class=${node.className} desc=${node.contentDescription} bounds=${bounds.toShortString()} " +
                "range=${node.rangeInfo?.current} state=${node.stateDescription} actions=${node.actionList.map { it.id }}"
        }
        val nodes = ownNodes()
        val ancestorDescriptions = mutableListOf<String>()
        var current = nodes.firstOrNull { it.contentDescription?.toString() == label }
        repeat(5) {
            current?.let { ancestor ->
                if (ancestor.packageName?.toString() == context.packageName && ancestor.refresh()) {
                    ancestorDescriptions += describe(ancestor)
                    current = ancestor.parent
                } else current = null
            }
        }
        return "labelAncestors=${ancestorDescriptions.joinToString(" -> ")}; nativeRanges=${nodes.filter { it.rangeInfo != null }.joinToString("; ") { describe(it) }}"
    }

    private fun ownNodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh() || node.packageName?.toString() != context.packageName) return
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(80) }
        fail("Appearance condition timed out; nodes=${ownNodes().map { it.text?.toString().orEmpty() + ':' + it.contentDescription }}")
    }

    private fun digest(file: File): String? {
        if (!file.isFile) return null
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val read = input.read(buffer); if (read < 0) break; sha.update(buffer, 0, read) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun oldVideoRecordsAndNewAlbumsSurviveRoundTripWithoutTouchingUserData() {
        val prefix = "validation_${UUID.randomUUID()}_"
        val prefs = context.getSharedPreferences("${prefix}downloads", 0)
        val old = JSONObject().put("id", "7689301063664454962").put("title", "旧视频")
            .put("uri", "content://media/external/video/media/987654321").put("bytes", 42L)
        assertTrue(prefs.edit().putString("completed", JSONArray().put(old).toString()).commit())
        try {
            val records = DownloadRecords(context, prefix)
            val previous = records.history().single()
            assertEquals("video/mp4", previous.mimeType)
            assertEquals(listOf(previous.uri), previous.uris)
            val album = SavedVideo("9999999999999999990", "测试图集", "content://media/external/images/media/987654321",
                100, "Pictures/DouyinDownloads", mimeType = "image/png",
                uris = listOf("content://media/external/images/media/987654321", "content://media/external/images/media/987654322"), isAlbum = true)
            records.save(album)
            assertEquals(2, records.history().size)
            assertEquals(album.uris, records.history().first().uris)
            assertEquals(previous, records.history().last())
            records.writeQueue(listOf(QueueTask("test", "https://v.douyin.com/test/", status = QueueStatus.READY)))
            assertEquals(QueueStatus.FAILED, records.queue().single().status)
            assertTrue(records.queue().single().message.contains("重新解析"))
            val rawQueue = context.getSharedPreferences("${prefix}download_tasks", 0).getString("queue", "")!!
            assertFalse(rawQueue.contains("mediaSources"))
            assertFalse(rawQueue.contains("mediaUrl"))
        } finally {
            context.deleteSharedPreferences("${prefix}downloads")
            context.deleteSharedPreferences("${prefix}download_tasks")
        }
    }

    @Test fun foregroundServiceSurvivesLeavingActivityAndStopsCleanly() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { DownloadForegroundService.start(it, false) }
            val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
            fun running() = manager.getRunningServices(50).any {
                it.service.className == DownloadForegroundService::class.java.name && it.foreground
            }
            val timeout = SystemClock.elapsedRealtime() + 5000
            while (!running() && SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(100)
            assertTrue("Foreground service did not start", running())
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            SystemClock.sleep(500)
            assertTrue("Foreground service stopped when leaving the activity", running())
            instrumentation.runOnMainSync { DownloadForegroundService.stop(context) }
            val stopped = SystemClock.elapsedRealtime() + 5000
            while (running() && SystemClock.elapsedRealtime() < stopped) SystemClock.sleep(100)
            assertFalse("Foreground service did not stop", running())
        }
    }
}
