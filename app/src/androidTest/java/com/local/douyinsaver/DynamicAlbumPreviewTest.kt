package com.local.douyinsaver

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** An original two-frame GIF, with no network, user images, records or settings modified. */
@RunWith(AndroidJUnit4::class)
class DynamicAlbumPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun originalGifActuallyAnimatesPausesAndReleases() = isolatedEngine {
        previewOriginal(DynamicPreviewFixtureProvider.GIF_URI)
    }

    @Test fun originalWebpActuallyAnimatesPausesAndReleases() = isolatedEngine {
        previewOriginal(DynamicPreviewFixtureProvider.WEBP_URI)
    }

    @Test fun mediaStoreGifCanBeDeletedWhileItsPrivatePreviewKeepsAnimating() = isolatedEngine {
        val original = checkNotNull(context.contentResolver.openInputStream(DynamicPreviewFixtureProvider.GIF_URI)).use { it.readBytes() }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "dynamic_preview_owned_${UUID.randomUUID()}.gif")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DynamicPreviewTests")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var deleted = false
        var deleteResult: Future<Int>? = null
        val deletion = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "owned-preview-provider-delete").apply { isDaemon = true }
        }
        val before = previewCopies().toSet()
        try {
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(original) }
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { Column { AlbumImagePreview(uri.toString(), Modifier.size(240.dp), "自制动图预览") } }
                } }
                lateinit var animation: AnimatedImageDrawable
                await {
                    var current: AnimatedImageDrawable? = null
                    instrumentation.runOnMainSync { current = previewView()?.drawable as? AnimatedImageDrawable }
                    current?.let { animation = it; it.isRunning } == true
                }
                val copy = previewCopies().filterNot { it in before }.single()
                assertArrayEquals("The preview changed the encoded animation bytes", original, copy.readBytes())
                // Keep the Drawable strongly referenced and running throughout the delete.
                // Success cannot depend on GC closing an AnimatedImageDrawable provider FD.
                deleteResult = deletion.submit<Int> { context.contentResolver.delete(uri, null, null) }
                assertEquals("Deleting the owned original was blocked by its running preview", 1,
                    checkNotNull(deleteResult).get(10, TimeUnit.SECONDS).toInt())
                deleted = true
                assertTrue(animation.isRunning)
                val colors = mutableSetOf<String>()
                await {
                    val pixel = previewPixel()
                    if (android.graphics.Color.red(pixel) > 200 && android.graphics.Color.blue(pixel) < 40) colors += "red"
                    if (android.graphics.Color.blue(pixel) > 200 && android.graphics.Color.red(pixel) < 40) colors += "blue"
                    colors.size == 2
                }
                scenario.onActivity { it.setContent { MaterialTheme { Text("预览已关闭") } } }
                await { !animation.isRunning && !copy.exists() }
                assertTrue("A closed preview retained its private files", previewCopies().all { it in before })
                // A provider failure on a later attempt must not leave a private copy either.
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { Column { AlbumImagePreview(uri.toString(), Modifier.size(240.dp), "自制动图预览") } }
                } }
                await { textVisible("图片预览暂不可用") }
                assertTrue("A failed preview retained its private files", previewCopies().all { it in before })
            }
            assertTrue("Destroying the Activity retained its private files", previewCopies().all { it in before })
        } finally {
            if (!deleted && (deleteResult == null || deleteResult?.isDone == true)) {
                runCatching { deletion.submit<Int> { context.contentResolver.delete(uri, null, null) }.get(10, TimeUnit.SECONDS) }
            }
            deleteResult?.cancel(true)
            deletion.shutdownNow()
        }
    }

    private fun previewOriginal(uri: Uri) {
        val before = previewCopies().toSet()
        stage("launch_${uri.lastPathSegment}")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { Column {
                    AlbumImagePreview(uri.toString(), Modifier.size(240.dp), "自制动图预览")
                } }
            } }
            stage("waiting_for_animated_drawable")
            lateinit var animation: AnimatedImageDrawable
            await {
                var drawable: AnimatedImageDrawable? = null
                instrumentation.runOnMainSync { drawable = previewView()?.drawable as? AnimatedImageDrawable }
                drawable?.let { animation = it; it.isRunning } == true
            }
            val copy = previewCopies().filterNot { it in before }.single()
            assertArrayEquals("The preview changed the encoded animation bytes",
                checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }, copy.readBytes())
            stage("waiting_for_both_original_frames")
            val colors = mutableSetOf<String>()
            await {
                val color = previewPixel()
                if (android.graphics.Color.red(color) > 200 && android.graphics.Color.blue(color) < 40) colors += "red"
                if (android.graphics.Color.blue(color) > 200 && android.graphics.Color.red(color) < 40) colors += "blue"
                colors.size == 2
            }
            stage("both_frames_drawn_pause")
            clickText("暂停动图")
            await { !animation.isRunning }
            val paused = previewPixel()
            SystemClock.sleep(400)
            assertEquals("A paused original animation changed frame", paused, previewPixel())
            stage("paused_frame_preserved")
            scenario.moveToState(Lifecycle.State.STARTED)
            assertFalse(animation.isRunning)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFalse("Returning to the App changed the user's paused state", animation.isRunning)
            stage("paused_state_preserved_after_background")
            clickText("播放动图")
            await { animation.isRunning }
            stage("play_requested")
            scenario.moveToState(Lifecycle.State.STARTED)
            await { !animation.isRunning }
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { animation.isRunning }
            stage("play_resumed_after_background")
            scenario.onActivity { it.setContent { MaterialTheme { Text("预览已关闭") } } }
            await { !animation.isRunning && !copy.exists() }
            stage("animation_released")
        }
        assertTrue("Closing the preview left private files", previewCopies().all { it in before })
        stage("scenario_closed")
    }

    private fun previewCopies(): List<File> = context.cacheDir.listFiles()?.filter {
        it.isFile && it.name.startsWith("album_image_preview_") && it.name.endsWith(".cache")
    }.orEmpty()

    private fun textVisible(text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun find(node: AccessibilityNodeInfo): Boolean {
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser && node.text?.toString() == text) return true
            for (index in 0 until node.childCount) if (node.getChild(index)?.let(::find) == true) return true
            return false
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find) == true
    }

    private fun stage(value: String) { Log.i("DynamicAlbumPreview", value); println("dynamic_preview_stage=$value") }

    private fun previewView(): ImageView? {
        fun find(view: View): ImageView? {
            if (view is ImageView && view.isAttachedToWindow && view.contentDescription == "自制动图预览") return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
    }

    private fun previewPixel(): Int {
        val bounds = Rect()
        instrumentation.runOnMainSync { check(checkNotNull(previewView()).getGlobalVisibleRect(bounds)) }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try { screenshot.getPixel(bounds.centerX(), bounds.centerY()) } finally { screenshot.recycle() }
    }

    private fun clickText(text: String) {
        var target: AccessibilityNodeInfo? = null
        await {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            fun search(node: AccessibilityNodeInfo) {
                if (node.packageName?.toString() == context.packageName && node.text?.toString() == text) {
                    var candidate: AccessibilityNodeInfo? = node
                    while (candidate != null) {
                        if (candidate.isEnabled && candidate.isClickable) { target = candidate; return }
                        candidate = candidate.parent
                    }
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(::search)
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
            target != null
        }
        assertTrue("The '$text' action was not accepted", checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun await(timeout: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "Timed out waiting for original animation preview" }
            SystemClock.sleep(70)
        }
    }

    private fun isolatedEngine(block: () -> Unit) {
        val prefix = "dynamic_preview_${UUID.randomUUID()}_"
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        lateinit var engine: SaverEngine
        instrumentation.runOnMainSync {
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(context.applicationContext as Application, prefix)
            singleton.set(null, engine)
        }
        try { block() } finally {
            instrumentation.runOnMainSync {
                (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
                singleton.set(null, previous)
            }
            for (name in listOf("downloads", "download_tasks", "download_options", "parse_diagnostics")) {
                context.deleteSharedPreferences(prefix + name)
            }
        }
    }
}
