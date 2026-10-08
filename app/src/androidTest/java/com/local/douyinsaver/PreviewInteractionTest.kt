package com.local.douyinsaver

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Real playback and gestures on a self-generated content URI; no public video or user history. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PreviewInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun decoderInventoryForPreview() {
        val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { codec ->
            !codec.isEncoder && codec.supportedTypes.any { it.equals("video/avc", true) || it.equals("video/hevc", true) }
        }.sortedBy { it.name }
        println("preview_decoder_inventory count=${decoders.size}")
        decoders.forEach { codec ->
            val types = codec.supportedTypes.filter { it.equals("video/avc", true) || it.equals("video/hevc", true) }
            println("preview_decoder name=${codec.name} types=${types.joinToString(",")} " +
                "hardwareAccelerated=${codec.isHardwareAccelerated} softwareOnly=${codec.isSoftwareOnly} vendor=${codec.isVendor}")
        }
    }

    @Test fun localPreviewPlaysPausesSeeksMutesReplaysAndReleasesOnDismiss() = withIsolatedEngine {
        createFixture(portrait = true).use { fixture ->
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                withCaptureOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) {
                    mountPreview(scenario, fixture)
                    val player = waitForPlayer()
                    ensurePlaying(player)
                    logPlayerVideoFormat("playing_before_seek", player)
                    assertEquals("The portrait fixture was not actually displayed as 9:16. ${fixture.dimensionsDescription()}", 9.0 / 16.0,
                        fixture.width.toDouble() / fixture.height, 0.005)
                    assertDecodedAspect(player, fixture)
                    clickDescription("暂停视频")
                    waitFor { !observe(player).playing && !observe(player).playWhenReady }
                    val paused = observe(player).position
                    SystemClock.sleep(350)
                    assertTrue("Paused playback position advanced", abs(observe(player).position - paused) < 180)

                    if (observe(player).volume == 0f) clickDescription("取消静音")
                    waitFor { observe(player).volume > 0f }
                    val beforeMute = playbackDiagnostics(player)
                    val muteAction = clickDescription("静音")
                    val afterMute = playbackDiagnostics(player)
                    capture("preview_mute_validation.png")
                    waitFor(diagnostics = {
                        runCatching { capture("preview_mute_failed_validation.png") }
                        "Mute did not expose both zero volume and the unmute label. before=$beforeMute; " +
                            "action=$muteAction; immediatelyAfter=$afterMute; current=${playbackDiagnostics(player)}"
                    }) { observe(player).volume == 0f && described("取消静音") != null }
                    val unmuteAction = clickDescription("取消静音")
                    waitFor(diagnostics = {
                        "Unmute did not expose both nonzero volume and the mute label. action=$unmuteAction; " +
                            "current=${playbackDiagnostics(player)}"
                    }) { observe(player).volume > 0f && described("静音") != null }

                    val normalFrame = playerBounds()
                    clickDescription("放大画面")
                    waitFor(diagnostics = { "Expand did not enlarge the actual player view. ${playbackDiagnostics(player)}" }) {
                        val frame = playerBounds()
                        described("收起画面") != null &&
                            (frame.width() > normalFrame.width() || frame.height() > normalFrame.height())
                    }
                    assertFalse("Expanding a paused preview unexpectedly resumed it", observe(player).playWhenReady)
                    capture("preview_expanded_validation.png")
                    clickDescription("收起画面")
                    waitFor(diagnostics = { "Collapse did not restore the actual player view. ${playbackDiagnostics(player)}" }) {
                        val frame = playerBounds()
                        described("放大画面") != null && abs(frame.width() - normalFrame.width()) <= 4 &&
                            abs(frame.height() - normalFrame.height()) <= 4
                    }

                    val duration = observe(player).duration
                    assertTrue("The fixture did not have a usable real-player duration", duration in 3_000L..5_000L)
                    clickDescription("前进10秒")
                    waitFor(diagnostics = { "Forward seek did not clamp to the actual duration. ${playbackDiagnostics(player)}" }) {
                        val state = observe(player)
                        !state.playWhenReady && abs(state.position - duration) <= 180 && elapsedSeconds(currentTimeText()) >= 3
                    }
                    clickDescription("后退10秒")
                    waitFor(diagnostics = { "Backward seek did not clamp to the start. ${playbackDiagnostics(player)}" }) {
                        val state = observe(player)
                        !state.playWhenReady && state.state == Player.STATE_READY && state.position in 0L..150L &&
                            elapsedSeconds(currentTimeText()) == 0
                    }

                    val beforeTime = currentTimeText()
                    val progress = described("视频播放进度") ?: error("The progress control was not rendered")
                    val rectangle = Rect().also(progress::getBoundsInScreen)
                    assertTrue("Progress control has no touch bounds", rectangle.width() > 80 && rectangle.height() > 0)
                    logPlayerVideoFormat("before_drag_seek", player)
                    drag(rectangle, 0.15f, 0.70f)
                    waitFor(diagnostics = { "The seek did not produce a ready second frame. ${playbackDiagnostics(player)}" }) {
                        val state = observe(player)
                        state.state == Player.STATE_READY && state.position in 2_200L..3_300L &&
                            currentTimeText() != beforeTime && elapsedSeconds(currentTimeText()) >= 2
                    }
                    logPlayerVideoFormat("ready_after_drag_seek_before_screenshot", player)
                    assertFalse("Dragging a paused preview unexpectedly resumed it", observe(player).playWhenReady)
                    assertTrue("The rendered total time did not describe the local fixture",
                        elapsedSeconds(described("视频总时长")?.text?.toString().orEmpty()) in 3..5)
                    capture("preview_portrait_validation.png") { screenshot -> assertPortraitRenderedShapes(screenshot, fixture) }

                    clickDescription("播放视频")
                    waitFor(10_000) { observe(player).state == Player.STATE_ENDED && described("重新播放") != null }
                    clickDescription("重新播放")
                    waitFor { observe(player).playing && observe(player).position < 1_500 }
                    clickClose()
                    waitFor { observe(player).released && described("视频播放进度") == null }
                    assertNull("The dismissed preview retained a window/player view", findPlayerView())
                    assertTrue(nodes().any { it.text?.toString() == "本地预览已关闭" })
                }
            }
        }
    }

    @Test fun localPreviewPausesForBackgroundAndReleasesWithActivity() = withIsolatedEngine {
        createFixture().use { fixture ->
            val observedPlayers = mutableListOf<ExoPlayer>()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                withCaptureOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE) {
                    mountPreview(scenario, fixture)
                    val active = waitForPlayer()
                    observedPlayers += active
                    ensurePlaying(active)
                    assertDecodedAspect(active, fixture)
                    clickDescription("暂停视频")
                    waitFor { !observe(active).playWhenReady && !observe(active).playing }
                    val timeline = checkNotNull(described("视频播放进度"))
                    drag(Rect().also(timeline::getBoundsInScreen), 0.10f, 0.25f)
                    waitFor {
                        val state = observe(active)
                        state.state == Player.STATE_READY && state.position in 900L..1_500L && !state.playWhenReady
                    }
                    awaitVisiblePausedFrame(active, fixture, observe(active).position, "before_background")
                    // Resume before moving to CREATED so lifecycle pause is still tested.
                    clickDescription("播放视频")
                    ensurePlaying(active)
                    // Test a frame inside the first still segment, not its 2 s
                    // cut. Closest-frame reference decoding can pick the red
                    // frame before that cut while the player's seek renders the
                    // first blue frame after it; both are valid at that boundary.
                    instrumentation.runOnMainSync { active.seekTo(500L) }
                    waitFor {
                        val state = observe(active)
                        state.playing && state.playWhenReady && state.position in 500L..1_100L
                    }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    waitFor { !observe(active).playing && !observe(active).playWhenReady }
                    val paused = observe(active).position
                    val pausedVolume = observe(active).volume
                    SystemClock.sleep(350)
                    assertTrue("Background playback continued", abs(observe(active).position - paused) < 180)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    val resumed = waitForPlayer()
                    if (resumed !in observedPlayers) observedPlayers += resumed
                    assertFalse("Returning to the foreground unexpectedly resumed audio", observe(resumed).playWhenReady)
                    assertEquals("Returning to the foreground changed volume", pausedVolume, observe(resumed).volume, 0.001f)
                    waitFor { described("播放视频") != null || described("重新播放") != null }
                    awaitVisiblePausedFrame(resumed, fixture, paused, "after_foreground")
                    val restored = observe(resumed)
                    SystemClock.sleep(350)
                    val settled = observe(resumed)
                    assertFalse("Frame restoration restarted playback", settled.playing || settled.playWhenReady)
                    assertTrue("The restored paused position advanced", abs(settled.position - restored.position) < 180)
                    assertEquals("Restoring the paused frame changed volume", restored.volume, settled.volume, 0.001f)
                    capture("preview_landscape_validation.png")
                }
            }
            observedPlayers.forEach { player -> waitFor { observe(player).released } }
        }
    }

    @Test fun previewSettingsPersistIndependentStepsAndSeekActualPlayer() = withIsolatedEngine {
        val namespace = "preview_controls_${UUID.randomUUID()}_"
        val preferences = PreviewPreferences(context, namespace)
        try {
            assertEquals(PreviewSeekOptions(), preferences.read())
            createFixture().use { fixture ->
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                    withCaptureOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) {
                        scenario.onActivity { activity -> activity.setContent {
                            MaterialTheme {
                                Column(Modifier.padding(20.dp)) { SectionCard { PreviewSettingsControls(preferences) } }
                            }
                        } }
                        clickDescription("前后各5秒")
                        waitFor { preferences.read() == PreviewSeekOptions(backwardSeconds = 5, forwardSeconds = 5) }
                        setSeekSeconds("预览后退秒数", "1")
                        waitFor { preferences.read().backwardSeconds == 1 }
                        clickDescription("前后各30秒")
                        waitFor { preferences.read() == PreviewSeekOptions(backwardSeconds = 30, forwardSeconds = 30) }
                        setSeekSeconds("预览后退秒数", "1")
                        waitFor { preferences.read().backwardSeconds == 1 }
                        setSeekSeconds("预览前进秒数", "2")
                        waitFor { preferences.read().forwardSeconds == 2 }
                        setSeekSeconds("预览前进秒数", "121")
                        waitFor { nodes().any { it.text?.toString() == "请输入 1–120 之间的秒数" } }
                        assertEquals("Invalid input overwrote the last valid setting", 2, preferences.read().forwardSeconds)
                        setSeekSeconds("预览前进秒数", "2")
                        waitFor(diagnostics = { "The valid setting did not replace the error field. ${semanticsDiagnostics()}" }) {
                            nodes().any { it.text?.toString() == "可设置 1–120 秒，自动保存" } &&
                                nodes().none { it.text?.toString() == "请输入 1–120 之间的秒数" } &&
                                seekSettingField("预览前进秒数")?.text?.toString() == "2" &&
                                seekSettingField("预览后退秒数")?.text?.toString() == "1"
                        }
                        val quickOptions = listOf(5, 10, 15, 30, 60).mapNotNull { value ->
                            described("前后各${value}秒")?.takeIf { it.isVisibleToUser }?.let { Rect().also(it::getBoundsInScreen) }
                        }.filterNot { it.isEmpty }
                        assertTrue("Too few shortcut controls were visible to validate the shared row", quickOptions.size >= 2)
                        assertTrue("Preview shortcuts wrapped into multiple rows",
                            quickOptions.maxOf { it.centerY() } - quickOptions.minOf { it.centerY() } <= 2)
                        val restored = PreviewPreferences(context, namespace).read()
                        assertEquals(PreviewSeekOptions(backwardSeconds = 1, forwardSeconds = 2), restored)
                        capture("preview_controls_settings_validation.png")

                        mountPreview(scenario, fixture, restored)
                        val player = waitForPlayer()
                        ensurePlaying(player)
                        clickDescription("暂停视频")
                        waitFor { !observe(player).playWhenReady }
                        // Start from a deterministic paused position without bypassing the seek controls under test.
                        instrumentation.runOnMainSync { player.seekTo(0) }
                        waitFor { observe(player).state == Player.STATE_READY && observe(player).position < 150L }
                        assertNotNull(described("后退1秒"))
                        assertNotNull(described("前进2秒"))
                        assertNull(described("后退10秒"))
                        capture("preview_custom_steps_validation.png")
                        clickDescription("前进2秒")
                        waitFor(diagnostics = { "Configured forward step was not applied. ${playbackDiagnostics(player)}" }) {
                            val state = observe(player)
                            !state.playWhenReady && abs(state.position - 2_000L) < 150L
                        }
                        clickDescription("后退1秒")
                        waitFor(diagnostics = { "Configured backward step was not applied. ${playbackDiagnostics(player)}" }) {
                            val state = observe(player)
                            !state.playWhenReady && abs(state.position - 1_000L) < 150L
                        }
                        clickDescription("后退1秒")
                        waitFor { observe(player).position < 150L }
                        clickDescription("后退1秒")
                        waitFor { observe(player).position < 150L && !observe(player).playWhenReady }
                        repeat(3) { clickDescription("前进2秒") }
                        waitFor(diagnostics = { "Configured forward step exceeded the duration. ${playbackDiagnostics(player)}" }) {
                            val state = observe(player)
                            !state.playWhenReady && abs(state.position - state.duration) < 150L
                        }
                        clickClose()
                        waitFor { observe(player).released }
                    }
                }
            }
        } finally { context.deleteSharedPreferences(namespace + "preview_options") }
    }

    @Test fun previewShortcutsFitOneRowAndRevealCompleteLastButtonWithLargeText() = withIsolatedEngine {
        data class LayoutCase(val widthDp: Int, val fontScale: Float, val needsScroll: Boolean, val captureName: String)
        val layouts = listOf(
            LayoutCase(300, 1f, false, "preview_shortcuts_300_validation.png"),
            // Redmi's 1220 px / ~3.3 density, after screen and card padding, is about 294 dp.
            LayoutCase(294, 1f, false, "preview_shortcuts_phone_width_validation.png"),
            LayoutCase(284, 1f, true, "preview_shortcuts_narrow_emulator_validation.png"),
            LayoutCase(300, 1.8f, true, "preview_shortcuts_large_text_validation.png"),
        )
        layouts.forEach { layout ->
            val namespace = "preview_row_${UUID.randomUUID()}_"
            val preferences = PreviewPreferences(context, namespace)
            try {
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                    withCaptureOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) {
                        var pixelDensity = 1f
                        scenario.onActivity { activity ->
                            activity.setContent {
                                val nativeDensity = LocalDensity.current
                                pixelDensity = nativeDensity.density
                                CompositionLocalProvider(LocalDensity provides Density(nativeDensity.density, layout.fontScale)) {
                                    MaterialTheme(shapes = MaterialTheme.shapes.copy(small = RoundedCornerShape(12.dp))) {
                                        // Use the actual settings component/card, with an exact inner viewport.
                                        // Keep the requested inner width reachable on a 360 dp
                                        // emulator: 300 + 36 card padding + 2 * 8 outer = 352.
                                        // The previous 20 dp outer inset constrained it to 284.
                                        Column(Modifier.safeDrawingPadding().padding(horizontal = 8.dp, vertical = 20.dp)
                                            .width((layout.widthDp + 36).dp)) {
                                            SectionCard { PreviewSettingsControls(preferences) }
                                        }
                                    }
                                }
                            }
                        }
                        waitFor(diagnostics = { "No shortcut row for $layout. ${semanticsDiagnostics()}" }) {
                            shortcutViewport() != null && clickTarget("前后各5秒") != null
                        }
                        val viewport = checkNotNull(shortcutViewport())
                        assertEquals("The fixture did not reproduce the requested viewport width", layout.widthDp.toFloat(),
                            viewport.width() / pixelDensity, 1f)
                        val first = Rect().also(checkNotNull(clickTarget("前后各5秒"))::getBoundsInScreen)
                        assertTrue("The first shortcut lost its 48 dp touch area: $first", first.height() / pixelDensity >= 47.5f)
                        assertTrue("The first shortcut was already clipped: $first in $viewport", viewport.contains(first))
                        assertTrue("The first shortcut border had no 2 dp inset: $first in $viewport",
                            first.left >= viewport.left + pixelDensity * 2)
                        val shortcutLabels = listOf(5, 10, 15, 30, 60).map { "前后各${it}秒" }
                        fun lastFullyVisible(): Boolean {
                            val last = clickTarget(shortcutLabels.last()) ?: return false
                            val bounds = Rect().also(last::getBoundsInScreen)
                            return viewport.contains(bounds) && abs(bounds.width() - first.width()) <= 2 &&
                                bounds.right <= viewport.right - pixelDensity * 2 && bounds.height() / pixelDensity >= 47.5f
                        }
                        if (layout.needsScroll) {
                            assertFalse("Large text did not get its necessary scrollable width", lastFullyVisible())
                            // Swipe on the actual own-app row; stop only when the entire final control fits.
                            for (attempt in 0 until 5) {
                                if (lastFullyVisible()) break
                                drag(viewport, 0.88f, 0.12f)
                            }
                            waitFor(diagnostics = { "The last shortcut/border remained clipped for $layout. " +
                                "viewport=$viewport first=$first; ${semanticsDiagnostics()}" }) { lastFullyVisible() }
                        } else {
                            shortcutLabels.forEach { label ->
                                val button = checkNotNull(clickTarget(label)) { "Missing ordinary-width shortcut $label. ${semanticsDiagnostics()}" }
                                val bounds = Rect().also(button::getBoundsInScreen)
                                assertTrue("$label was clipped at ordinary phone width: $bounds in $viewport", viewport.contains(bounds))
                                assertTrue("$label lost part of its equal-width control: $bounds vs $first", abs(bounds.width() - first.width()) <= 2)
                                assertTrue("$label lost its 48 dp touch area", bounds.height() / pixelDensity >= 47.5f)
                            }
                            if (!lastFullyVisible()) capture("preview_shortcuts_failed_validation.png")
                            assertTrue("The last ordinary-width border had no 2 dp inset space for $layout. " +
                                "viewport=$viewport first=$first density=$pixelDensity; ${semanticsDiagnostics()}", lastFullyVisible())
                        }
                        val visibleBounds = shortcutLabels.mapNotNull { label ->
                            clickTarget(label)?.let { Rect().also(it::getBoundsInScreen) }
                        }.filterNot { it.isEmpty }
                        assertTrue("Shortcut controls wrapped into more than one row for $layout",
                            visibleBounds.all { abs(it.centerY() - first.centerY()) <= 2 })
                        capture(layout.captureName)
                        clickDescription("前后各60秒")
                        waitFor(diagnostics = { "The fully visible last shortcut did not update both settings. ${semanticsDiagnostics()}" }) {
                            preferences.read() == PreviewSeekOptions(60, 60) &&
                                seekSettingField("预览后退秒数")?.text?.toString() == "60" &&
                                seekSettingField("预览前进秒数")?.text?.toString() == "60"
                        }
                        assertEquals(PreviewSeekOptions(60, 60), PreviewPreferences(context, namespace).read())
                    }
                }
            } finally { context.deleteSharedPreferences(namespace + "preview_options") }
        }
    }

    private fun shortcutViewport(): Rect? = nodes().firstNotNullOfOrNull { node ->
        val collection = node.collectionInfo
        if (node.isVisibleToUser && collection != null && collection.rowCount == 1 && collection.columnCount == 5)
            Rect().also(node::getBoundsInScreen).takeUnless { it.isEmpty }
        else null
    }

    private fun setSeekSeconds(description: String, value: String) {
        waitFor { seekSettingField(description) != null }
        val editable = checkNotNull(seekSettingField(description))
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("The numeric setting rejected an accessibility edit", editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        instrumentation.waitForIdleSync()
    }

    private fun seekSettingField(description: String): AccessibilityNodeInfo? {
        val field = described(description) ?: return null
        val area = Rect().also(field::getBoundsInScreen)
        return nodes().firstOrNull { node ->
            node.isEditable && area.contains(Rect().also(node::getBoundsInScreen)) &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        }
    }

    private fun mountPreview(scenario: ActivityScenario<MainActivity>, fixture: Fixture,
                             options: PreviewSeekOptions = PreviewSeekOptions()) {
        val visible = mutableStateOf(true)
        scenario.onActivity { activity ->
            // Use the production component in an actual Activity window. The source is local;
            // mounting it directly avoids the remote-work download-source policy on Home.
            activity.setContent {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(0xFF2AA1E8), onPrimary = Color.White,
                    surface = Color(0xFFF8FBFD), surfaceContainer = Color(0xFFEDF5FA), onSurface = Color(0xFF1F2A30)
                )) {
                    if (visible.value) VideoPreviewDialog(fixture.uri.toString(), "本地播放器验证", fixture.width, fixture.height,
                        backwardSeconds = options.backwardSeconds, forwardSeconds = options.forwardSeconds) {
                        visible.value = false
                    } else Text("本地预览已关闭")
                }
            }
        }
    }

    private fun withCaptureOrientation(scenario: ActivityScenario<MainActivity>, requested: Int,
                                       expectedConfiguration: Int, body: () -> Unit) {
        val enabled = InstrumentationRegistry.getArguments().getString("capture_preview") == "true"
        var previous = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var changed = false
        try {
            if (enabled) {
                scenario.onActivity { activity ->
                    previous = activity.requestedOrientation
                    changed = true
                    activity.requestedOrientation = requested
                }
                waitFor {
                    var orientation = Configuration.ORIENTATION_UNDEFINED
                    scenario.onActivity { orientation = it.resources.configuration.orientation }
                    orientation == expectedConfiguration
                }
            }
            body()
        } finally {
            // This changes only the test Activity's requested orientation, never a device setting.
            if (changed) scenario.onActivity { it.requestedOrientation = previous }
        }
    }

    private fun capture(name: String, inspect: ((Bitmap) -> Unit)? = null) {
        if (InstrumentationRegistry.getArguments().getString("capture_preview") != "true") return
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("The local preview screenshot was unavailable")
        try {
            File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            inspect?.invoke(bitmap)
        }
        finally { bitmap.recycle() }
    }

    private data class WhiteComponent(val bounds: Rect, val pixels: Int) {
        val ratio get() = bounds.width().toDouble() / bounds.height()
        override fun toString() = "bounds=${bounds.toShortString()} pixels=$pixels ratio=$ratio"
    }

    private fun awaitVisiblePausedFrame(player: ExoPlayer, fixture: Fixture, expectedPosition: Long, phase: String) {
        val metadata = MediaMetadataRetriever()
        val expectedColor = try {
            metadata.setDataSource(context, fixture.uri)
            val frame = checkNotNull(metadata.getFrameAtTime(expectedPosition * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST))
            try { frame.getPixel(frame.width / 2, frame.height / 2) } finally { frame.recycle() }
        } finally { metadata.release() }
        val red = android.graphics.Color.red(expectedColor)
        val green = android.graphics.Color.green(expectedColor)
        val blue = android.graphics.Color.blue(expectedColor)
        check(maxOf(red, green, blue) - minOf(red, green, blue) >= 80) { "The source fixture did not have a colored paused frame" }
        val initialVolume = observe(player).volume
        var imageDetails = "no screenshot"
        waitFor(diagnostics = {
            runCatching { capture("preview_landscape_failed_validation.png") }
            "No visible paused source frame ($phase), expectedPosition=$expectedPosition expectedRGB=$red,$green,$blue; " +
                "$imageDetails; ${playbackDiagnostics(player)}"
        }) {
            val state = observe(player)
            assertFalse("Waiting for a visible frame restarted playback ($phase)", state.playing || state.playWhenReady)
            assertEquals("Frame restoration changed volume ($phase)", initialVolume, state.volume, 0.001f)
            if (state.state != Player.STATE_READY) false else {
                assertTrue("Frame restoration changed the paused position ($phase): expected=$expectedPosition actual=${state.position}",
                    abs(state.position - expectedPosition) < 180)
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val bounds = playerBounds()
                    check(bounds.intersect(0, 0, screenshot.width, screenshot.height))
                    val pixels = IntArray(bounds.width() * bounds.height())
                    val readable = if (screenshot.config == Bitmap.Config.HARDWARE)
                        checkNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) else screenshot
                    try { readable.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height()) }
                    finally { if (readable !== screenshot) readable.recycle() }
                    val matches = pixels.count { color ->
                        abs(android.graphics.Color.red(color) - red) <= 55 &&
                            abs(android.graphics.Color.green(color) - green) <= 55 &&
                            abs(android.graphics.Color.blue(color) - blue) <= 55
                    }
                    val whiteShapes = whiteComponents(screenshot, bounds)
                        .filter { it.bounds.centerY() < bounds.top + bounds.height() * 0.4 }
                    val fraction = matches.toDouble() / pixels.size
                    imageDetails = "bounds=${bounds.toShortString()} sourceColorFraction=$fraction upperWhiteShapes=$whiteShapes"
                    // A centered white play glyph over black cannot satisfy either the
                    // colored-source area or the fixture's white pattern near its top.
                    fraction >= 0.10 && whiteShapes.isNotEmpty()
                } finally { screenshot.recycle() }
            }
        }
    }

    private fun assertPortraitRenderedShapes(screenshot: Bitmap, fixture: Fixture) {
        val decoded = checkNotNull(BitmapFactory.decodeFile(File(context.cacheDir, "preview_decoded_frame.png").absolutePath)) {
            "The portrait source frame was unavailable for visual comparison"
        }
        val expected = try { whiteComponents(decoded, Rect(0, 0, decoded.width, decoded.height)) }
            finally { decoded.recycle() }
        val videoBounds = playerBounds()
        val actual = whiteComponents(screenshot, videoBounds)
        val details = "${fixture.dimensionsDescription()}; videoBounds=${videoBounds.toShortString()}; " +
            "sourceComponents=$expected; screenshotComponents=$actual"
        assertTrue("The source fixture did not contain its square and two white bars. $details", expected.size >= 3)
        val expectedSquare = expected.first()
        assertTrue("The decoded source's largest shape was not square. $details", expectedSquare.ratio in 0.85..1.15)
        assertTrue("The preview did not render three recognizable white shapes. $details", actual.size >= 3)
        // Select by pixel area, never by a preferred ratio: a stretched main square
        // must fail rather than be discarded in favor of the small play-button glyph.
        assertTrue("The displayed main square was rotated or stretched. $details", actual.first().ratio in 0.85..1.15)
        val expectedBars = expected.drop(1).take(2)
        assertTrue("The source fixture's white bars were not horizontal. $details", expectedBars.all { it.ratio >= 4.0 })
        actual.drop(1).take(2).forEachIndexed { index, bar ->
            val sourceRatio = expectedBars[index].ratio
            assertTrue("A displayed white bar changed direction or aspect ratio. $details",
                bar.ratio >= 4.0 && bar.ratio in (sourceRatio * 0.75)..(sourceRatio * 1.25))
        }
    }

    private fun whiteComponents(bitmap: Bitmap, requestedBounds: Rect): List<WhiteComponent> {
        val crop = Rect(requestedBounds)
        check(crop.intersect(0, 0, bitmap.width, bitmap.height)) { "The video bounds did not intersect the screenshot" }
        val width = crop.width()
        val height = crop.height()
        val pixels = IntArray(width * height)
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE)
            checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else bitmap
        try { readable.getPixels(pixels, 0, width, crop.left, crop.top, width, height) }
        finally { if (readable !== bitmap) readable.recycle() }
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val components = mutableListOf<WhiteComponent>()
        fun white(index: Int): Boolean {
            val color = pixels[index]
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            return minOf(red, green, blue) >= 235 && maxOf(red, green, blue) - minOf(red, green, blue) <= 18
        }
        for (seed in pixels.indices) {
            if (visited[seed]) continue
            visited[seed] = true
            if (!white(seed)) continue
            var head = 0
            var tail = 1
            queue[0] = seed
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            fun offer(index: Int) {
                if (visited[index]) return
                visited[index] = true
                if (white(index)) queue[tail++] = index
            }
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
                if (x > 0) offer(index - 1)
                if (x + 1 < width) offer(index + 1)
                if (y > 0) offer(index - width)
                if (y + 1 < height) offer(index + width)
            }
            if (tail >= maxOf(20, pixels.size / 5_000)) components += WhiteComponent(
                Rect(crop.left + left, crop.top + top, crop.left + right + 1, crop.top + bottom + 1), tail)
        }
        return components.sortedByDescending { it.pixels }
    }

    private fun ensurePlaying(player: ExoPlayer) {
        waitFor {
            val state = observe(player)
            state.playing || described("播放视频") != null || described("重新播放") != null
        }
        if (!observe(player).playing) {
            if (described("重新播放") != null) clickDescription("重新播放") else clickDescription("播放视频")
        }
        waitFor { observe(player).playing }
    }

    private fun assertDecodedAspect(player: ExoPlayer, fixture: Fixture) {
        var actualRatio = 0.0
        var decodedDescription = "unknown"
        waitFor(diagnostics = { "The player never reported usable video dimensions. ${fixture.dimensionsDescription()}; " +
            "$decodedDescription; ${playbackDiagnostics(player)}" }) {
            instrumentation.runOnMainSync {
                val size = player.videoSize
                val format = player.videoFormat
                if (size.width > 0 && size.height > 0) {
                    decodedDescription = "source=videoSize playerWidth=${size.width}, playerHeight=${size.height}, pixelRatio=${size.pixelWidthHeightRatio}"
                    actualRatio = size.width.toDouble() * size.pixelWidthHeightRatio / size.height
                } else if (format != null && format.width > 0 && format.height > 0) {
                    // Media3 1.8's GL VideoSink may leave videoSize UNKNOWN. The active
                    // decoder format still describes the stream; screenshot shapes below
                    // remain the independent check that these dimensions are rendered.
                    val rotation = ((format.rotationDegrees % 360) + 360) % 360
                    val encodedRatio = format.width.toDouble() * format.pixelWidthHeightRatio / format.height
                    actualRatio = if (rotation == 90 || rotation == 270) 1.0 / encodedRatio else encodedRatio
                    decodedDescription = "source=videoFormat encodedWidth=${format.width}, encodedHeight=${format.height}, " +
                        "rotation=$rotation, pixelRatio=${format.pixelWidthHeightRatio}, playerSize=UNKNOWN"
                } else {
                    decodedDescription = "playerSize=UNKNOWN videoFormat=$format"
                    actualRatio = 0.0
                }
            }
            actualRatio > 0.0
        }
        assertEquals("The actual displayed video aspect ratio differed from its normalized metadata. " +
            "${fixture.dimensionsDescription()}; $decodedDescription",
            fixture.width.toDouble() / fixture.height, actualRatio, 0.005)
    }

    private fun logPlayerVideoFormat(stage: String, player: ExoPlayer) {
        var details = ""
        instrumentation.runOnMainSync {
            val format = player.videoFormat
            val size = player.videoSize
            details = "preview_video stage=$stage released=${player.isReleased} " +
                "width=${format?.width} height=${format?.height} rotation=${format?.rotationDegrees} " +
                "pixelRatio=${format?.pixelWidthHeightRatio} mime=${format?.sampleMimeType} " +
                "displayWidth=${size.width} displayHeight=${size.height} displayPixelRatio=${size.pixelWidthHeightRatio} " +
                "state=${player.playbackState} position=${player.currentPosition} duration=${player.duration} " +
                "playing=${player.isPlaying} playWhenReady=${player.playWhenReady}"
        }
        println(details)
    }

    private data class Playback(val position: Long, val state: Int, val playing: Boolean,
                                val playWhenReady: Boolean, val volume: Float, val released: Boolean, val duration: Long)

    private fun observe(player: ExoPlayer): Playback {
        lateinit var result: Playback
        instrumentation.runOnMainSync {
            result = if (player.isReleased) Playback(0, Player.STATE_IDLE, false, false, 0f, true, 0)
                else Playback(player.currentPosition, player.playbackState, player.isPlaying, player.playWhenReady,
                    player.volume, false, player.duration)
        }
        return result
    }

    private fun waitForPlayer(): ExoPlayer {
        var player: ExoPlayer? = null
        waitFor(20_000) {
            instrumentation.runOnMainSync { player = findPlayerViewOnMain()?.player as? ExoPlayer }
            player != null && described("视频播放进度") != null && observe(player!!).state != Player.STATE_IDLE
        }
        return checkNotNull(player)
    }

    private fun findPlayerView(): PlayerView? {
        var result: PlayerView? = null
        instrumentation.runOnMainSync { result = findPlayerViewOnMain() }
        return result
    }

    private fun playerBounds(): Rect {
        val bounds = Rect()
        instrumentation.runOnMainSync {
            val view = findPlayerViewOnMain() ?: error("No attached player view to measure")
            check(view.getGlobalVisibleRect(bounds)) { "The actual player view was not visible" }
        }
        return bounds
    }

    private fun findPlayerViewOnMain(): PlayerView? {
        fun find(view: View): PlayerView? {
            if (view is PlayerView && view.isAttachedToWindow && view.context.packageName == context.packageName) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
    }

    private fun currentTimeText() = described("当前播放时间")?.text?.toString().orEmpty()

    private fun elapsedSeconds(label: String): Int {
        val parts = Regex("(\\d+):(\\d{2})").find(label) ?: return -1
        return parts.groupValues[1].toInt() * 60 + parts.groupValues[2].toInt()
    }

    private fun clickDescription(label: String): String {
        waitFor(diagnostics = { "No clickable '$label' node. ${semanticsDiagnostics()}" }) {
            clickTarget(label) != null
        }
        val target = checkNotNull(clickTarget(label))
        val before = describeNode(target)
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertTrue("The '$label' action was not accepted. target=$before; ${semanticsDiagnostics()}", accepted)
        instrumentation.waitForIdleSync()
        return "ACTION_CLICK accepted=$accepted target=$before; after=${semanticsDiagnostics()}"
    }

    private fun clickTarget(label: String): AccessibilityNodeInfo? {
        val described = described(label) ?: return null
        val descriptionBounds = Rect().also(described::getBoundsInScreen)
        if (descriptionBounds.isEmpty) return null
        var current: AccessibilityNodeInfo? = described
        while (current != null) {
            if (!current.refresh() || current.packageName?.toString() != context.packageName) return null
            val bounds = Rect().also(current::getBoundsInScreen)
            // Some controls keep their label on an inner glyph node. Resolve only the
            // nearest own-app clickable ancestor enclosing that label's touch bounds.
            if (!bounds.contains(descriptionBounds)) return null
            if (current.isClickable && current.isEnabled && current.isVisibleToUser) return current
            current = current.parent
        }
        return null
    }

    private fun clickClose() {
        val described = described("关闭预览")
        val close = described ?: nodes().firstOrNull { it.text?.toString() == "关闭预览" && it.isClickable }
        assertTrue("The close action was not accepted", checkNotNull(close).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun drag(rectangle: Rect, from: Float, to: Float) {
        val down = SystemClock.uptimeMillis()
        fun pointer(action: Int, elapsed: Long, fraction: Float) {
            val event = MotionEvent.obtain(down, down + elapsed, action,
                rectangle.left + rectangle.width() * fraction, rectangle.exactCenterY(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue("The seek pointer event was not delivered", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        pointer(MotionEvent.ACTION_DOWN, 0, from)
        repeat(8) { step -> pointer(MotionEvent.ACTION_MOVE, (step + 1) * 25L, from + (to - from) * (step + 1) / 8f) }
        pointer(MotionEvent.ACTION_UP, 225, to)
        instrumentation.waitForIdleSync()
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            // Compose may update pixels/player state before an accessibility change
            // invalidates UiAutomation's cached tree. Fetch each node from its provider.
            if (!node.refresh()) return
            if (node.packageName?.toString() == context.packageName) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun described(label: String) = nodes().firstOrNull { it.contentDescription?.toString() == label }

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "description='${node.contentDescription?.toString().orEmpty()}' text='${node.text?.toString().orEmpty()}' " +
            "class=${node.className} bounds=${bounds.toShortString()} clickable=${node.isClickable} " +
            "enabled=${node.isEnabled} visible=${node.isVisibleToUser} window=${node.windowId}"
    }

    private fun semanticsDiagnostics(): String = nodes()
        .filter { !it.contentDescription.isNullOrEmpty() || it.isClickable }
        .joinToString(prefix = "nodes=[", postfix = "]", separator = "; ") { describeNode(it) }

    private fun playbackDiagnostics(player: ExoPlayer): String {
        val state = observe(player)
        var attachedPlayer: Player? = null
        instrumentation.runOnMainSync { attachedPlayer = findPlayerViewOnMain()?.player }
        return "player=${System.identityHashCode(player)} volume=${state.volume} released=${state.released} " +
            "position=${state.position} duration=${state.duration} state=${state.state} playing=${state.playing} playWhenReady=${state.playWhenReady} " +
            "attachedPlayer=${attachedPlayer?.let(System::identityHashCode)} samePlayer=${attachedPlayer === player}; ${semanticsDiagnostics()}"
    }

    private fun waitFor(timeoutMs: Long = 10_000, diagnostics: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(75)
        }
        fail("Expected local-preview state was not reached" + diagnostics?.let { "; ${it()}" }.orEmpty())
    }

    private fun withIsolatedEngine(body: () -> Unit) {
        val namespace = "preview_ui_${UUID.randomUUID()}_"
        val constructor = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java).apply { isAccessible = true }
        val isolated = constructor.newInstance(context.applicationContext as Application, namespace)
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        instrumentation.runOnMainSync { singleton.set(null, isolated) }
        try { body() } finally {
            instrumentation.runOnMainSync {
                val scope = SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(isolated) as CoroutineScope
                scope.cancel()
                singleton.set(null, previous)
            }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics").forEach {
                context.deleteSharedPreferences(namespace + it)
            }
        }
    }

    private class Fixture(val uri: Uri, val width: Int, val height: Int,
                          val encodedWidth: Int, val encodedHeight: Int, val rotation: Int,
                          private val context: Context, private val directory: File) : Closeable {
        fun dimensionsDescription() = "encodedWidth=$encodedWidth, encodedHeight=$encodedHeight, rotation=$rotation, " +
            "displayWidth=$width, displayHeight=$height"

        override fun close() {
            try { context.contentResolver.delete(uri, null, null) } finally {
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }

    private fun createFixture(portrait: Boolean = false): Fixture = runBlocking(Dispatchers.IO) {
        withTimeout(180_000L) {
            val directory = File(context.cacheDir, "preview_fixture_${UUID.randomUUID()}")
            check(directory.mkdir())
            var uri: Uri? = null
            try {
                fun asset(name: String) = File(directory, name).also { file ->
                    instrumentation.context.assets.open("album_test/$name").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                }
                fun portraitImage(name: String, second: Boolean): File {
                    val file = File(directory, name)
                    val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(if (second) android.graphics.Color.rgb(55, 127, 201)
                            else android.graphics.Color.rgb(207, 85, 85))
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
                        if (second) canvas.drawRect(110f, 130f, 250f, 270f, paint)
                        else canvas.drawCircle(180f, 200f, 70f, paint)
                        canvas.drawRect(65f, 350f, 295f, 380f, paint)
                        if (second) canvas.drawRect(65f, 420f, 295f, 450f, paint)
                        file.outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not create a local portrait fixture" }
                        }
                    } finally { bitmap.recycle() }
                    return file
                }
                val imageFiles = if (portrait) listOf(portraitImage("first_red.png", false), portraitImage("second_blue.png", true))
                    else listOf(asset("first_red.png"), asset("second_blue.png"))
                val images = imageFiles.map(AlbumMediaValidation::image)
                val audio = asset("short_bgm.m4a")
                val video = AlbumVideoComposer(context).compose(images, audio, AlbumMediaValidation.audioDurationMs(audio),
                    2, directory, {})
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "yingxia_preview_fixture_${UUID.randomUUID()}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/YingxiaPreviewTests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = checkNotNull(context.contentResolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
                val created = checkNotNull(uri)
                context.contentResolver.openOutputStream(created, "w")!!.use { output ->
                    video.inputStream().use { it.copyTo(output) }
                }
                check(context.contentResolver.update(created, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) == 1)
                AlbumMediaValidation.composedVideo(context, created, 4_000)
                val metadata = MediaMetadataRetriever()
                val dimensions = try {
                    metadata.setDataSource(context, created)
                    val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
                        .let { ((it % 360) + 360) % 360 }
                    check(width > 0 && height > 0) { "The local fixture did not expose its encoded dimensions" }
                    if (portrait && InstrumentationRegistry.getArguments().getString("capture_preview") == "true") {
                        // Explicit debug artifacts stay in this test's own app cache for
                        // inspection. The normal temporary URI and source cleanup still runs.
                        video.copyTo(File(context.cacheDir, "preview_fixture_validation.mp4"), overwrite = true)
                        val frame = checkNotNull(metadata.getFrameAtTime(2_800_000L, MediaMetadataRetriever.OPTION_CLOSEST)) {
                            "Could not decode the portrait fixture's source frame"
                        }
                        try {
                            File(context.cacheDir, "preview_decoded_frame.png").outputStream().use {
                                check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not save the decoded source frame" }
                            }
                        } finally { frame.recycle() }
                    }
                    Triple(width, height, rotation)
                } finally { metadata.release() }
                // Transformer can encode portrait output as landscape samples plus a
                // track rotation. Dialog dimensions describe the displayed orientation.
                val rotated = dimensions.third == 90 || dimensions.third == 270
                val displayWidth = if (rotated) dimensions.second else dimensions.first
                val displayHeight = if (rotated) dimensions.first else dimensions.second
                Fixture(created, displayWidth, displayHeight, dimensions.first, dimensions.second,
                    dimensions.third, context, directory)
            } catch (error: Throwable) {
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                directory.deleteRecursively()
                throw error
            }
        }
    }
}
