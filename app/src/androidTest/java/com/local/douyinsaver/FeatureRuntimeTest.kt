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
import android.util.DisplayMetrics
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var appearanceDecor: View? = null

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
                try {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                scenario.onActivity { activity -> appearanceDecor = activity.window.decorView; activity.setContent {
                    MaterialTheme {
                        // The fixture must keep the entire production control inside
                        // both drawing and gesture insets before checking its bounds.
                        Column(Modifier.fillMaxSize().safeContentPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { AppearanceControls(store) }
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
                } catch (failure: Throwable) {
                    captureAppearanceFailure(failure)
                    throw failure
                }
            }
        } finally {
            appearanceDecor = null
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
        var forward = true
        for (attempt in 0 until 24) {
            assertOwnForeground()
            // Newly inserted FIT choices may exist below the visible viewport. Do not
            // discard their node before asking the real scroll container to reveal it.
            val text = ownNodes().firstOrNull { it.text?.toString() == value }
            var candidate = text
            while (candidate != null && candidate.packageName?.toString() == context.packageName) {
                if (candidate.refresh() && candidate.isClickable && candidate.isEnabled) {
                    val area = Rect().also(candidate::getBoundsInScreen)
                    val viewport = appearanceScrollViewport()
                    if (candidate.isVisibleToUser && !area.isEmpty && viewport.contains(area)) {
                        target = candidate
                        break
                    }
                    candidate.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    if (!area.isEmpty) forward = area.exactCenterY() >= viewport.exactCenterY()
                    break
                }
                candidate = candidate.parent
            }
            if (target != null) break
            if (!scrollAppearanceStep(forward)) forward = !forward
            SystemClock.sleep(100)
        }
        assertOwnForeground()
        val clickable = checkNotNull(target) { "No fully visible clickable own-app '$value' control; ${appearanceControlDiagnostic()}" }
        assertTrue("No clickable own-app '$value' control", clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
        assertOwnForeground()
        ownNodes().firstOrNull { it.contentDescription?.toString() == label }
            ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync()
        var forward = true
        for (attempt in 0 until 12) {
            val node = slider()
            val area = node?.let { Rect().also(it::getBoundsInScreen) }
            val viewport = appearanceScrollViewport()
            if (area != null && area.height() >= minimumHeight && viewport.contains(area)) break
            if (area != null && !area.isEmpty) forward = area.exactCenterY() >= viewport.exactCenterY()
            if (!scrollAppearanceStep(forward)) forward = !forward
            SystemClock.sleep(100)
        }
        val node = checkNotNull(slider()) { "No visible native range control for '$label'; ${appearanceRangeDiagnostic(label)}" }
        val bounds = Rect().also(node::getBoundsInScreen)
        val viewport = appearanceScrollViewport()
        val diagnostic = "label=$label bounds=${bounds.toShortString()} heightDp=${bounds.height() / density} density=$density " +
            "viewport=${viewport.toShortString()} range=${node.rangeInfo?.current} state=${node.stateDescription} " +
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
        assertTrue("'$label' is not completely inside the safe own-app scroll viewport: $diagnostic", viewport.contains(bounds))
        assertNotNull("'$label' lost native slider semantics", node.rangeInfo)
        val old = read()
        val current = ((old - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val inset = 8f * density
        fun x(value: Float) = bounds.left + inset + (bounds.width() - inset * 2f) * value
        // Exercise the advertised touch region outside the 16dp circle on the first control.
        val touchY = bounds.exactCenterY() + if (label == "整页背景模糊") 14f * density else 0f
        val start = SystemClock.uptimeMillis()
        fun pointer(action: Int, target: Float) {
            if (action != MotionEvent.ACTION_CANCEL) {
                assertOwnForeground()
                assertTrue("Appearance gesture crossed a system edge: $diagnostic", viewport.contains(x(target).toInt(), touchY.toInt()))
            }
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
        assertOwnForeground()
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

    private fun assertOwnForeground() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertEquals("Appearance test lost the active app window; system overlays cannot receive these gestures",
            context.packageName, root?.packageName?.toString())
        val focused = instrumentation.uiAutomation.windows.filter { it.isFocused }
        assertTrue("Appearance test lost the focused app window: ${focused.map { it.root?.packageName }}",
            focused.isEmpty() || focused.any { it.root?.packageName?.toString() == context.packageName })
    }

    private fun safeViewport(): Rect {
        val result = Rect()
        instrumentation.runOnMainSync {
            val decor = checkNotNull(appearanceDecor) { "Appearance activity decor is unavailable" }
            // Insets are measured from physical display edges. Decor may already have
            // a nonzero screen origin; adding insets to that origin clips them twice.
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            checkNotNull(decor.display).getRealMetrics(metrics)
            result.set(0, 0, metrics.widthPixels, metrics.heightPixels)
            val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemGestures())
            if (insets != null) result.set(result.left + insets.left, result.top + insets.top,
                result.right - insets.right, result.bottom - insets.bottom)
            val visibleFrame = Rect().also(decor::getWindowVisibleDisplayFrame)
            check(result.intersect(visibleFrame)) { "Appearance visible window has no safe display intersection" }
        }
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        assertEquals(context.packageName, root.packageName?.toString())
        assertTrue("Active appearance window has no safe drawing area", result.intersect(Rect().also(root::getBoundsInScreen)))
        assertFalse("Appearance safe viewport is empty", result.isEmpty)
        return result
    }

    private fun appearanceScrollViewport(): Rect {
        assertOwnForeground()
        val result = safeViewport()
        ownNodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.let {
            assertTrue("Appearance scroll viewport does not intersect its safe app window", result.intersect(Rect().also(it::getBoundsInScreen)))
        }
        val margin = (4f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        result.inset(margin, margin)
        return result
    }

    private fun scrollAppearanceStep(forward: Boolean): Boolean {
        assertOwnForeground()
        val viewport = appearanceScrollViewport()
        val scroll = ownNodes().firstOrNull { it.isScrollable && it.rangeInfo == null && it.isVisibleToUser &&
            Rect.intersects(viewport, Rect().also(it::getBoundsInScreen)) }
        checkNotNull(scroll) { "No own-app appearance scroll container" }
        val before = appearanceControlDiagnostic()
        // A short physical vertical scroll avoids a whole-page accessibility jump
        // skipping newly added choices. The x coordinate is inside the outer padding.
        val density = context.resources.displayMetrics.density
        val x = viewport.left + 4f * density
        val from = viewport.top + viewport.height() * (if (forward) 0.72f else 0.32f)
        val to = viewport.top + viewport.height() * (if (forward) 0.46f else 0.58f)
        val start = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            if (action != MotionEvent.ACTION_CANCEL) {
                assertOwnForeground()
                assertTrue("Appearance reveal scroll left its safe own viewport", viewport.contains(x.toInt(), y.toInt()))
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
        return before != appearanceControlDiagnostic()
    }

    private fun appearanceControlDiagnostic(): String = ownNodes().filter {
        it.isVisibleToUser && (!it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank())
    }.joinToString("; ") { "text=${it.text} desc=${it.contentDescription} bounds=${Rect().also(it::getBoundsInScreen).toShortString()}" }

    private fun captureAppearanceFailure(failure: Throwable) {
        runCatching {
            val directory = File(context.cacheDir, "appearance_ui_failure_${UUID.randomUUID()}").apply { check(mkdir()) }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            File(directory, "failure.json").writeText(JSONObject().put("scope", "OWN_ISOLATED_APPEARANCE_FIXTURE_FAILURE")
                .put("version", InstalledTestTarget.versionName).put("instrumentationCompiledVersion", BuildConfig.VERSION_NAME).put("failureType", failure.javaClass.simpleName)
                .put("message", failure.message.orEmpty()).put("activePackage", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                .put("safeViewport", runCatching { safeViewport().toShortString() }.getOrNull())
                .put("nodes", appearanceControlDiagnostic()).toString(2))
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { File(directory, "failure.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { screenshot.recycle() }
            }
            failure.addSuppressed(AssertionError("captureRoot=${directory.absolutePath}"))
        }.onFailure { failure.addSuppressed(AssertionError("Appearance failure capture failed: ${it.javaClass.simpleName}")) }
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
