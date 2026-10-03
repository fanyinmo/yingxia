package com.local.douyinsaver

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in public-link pipeline; ordinary test runs never request or download a real work. */
@RunWith(AndroidJUnit4::class)
class PhonePipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: SaverViewModel
    private var headlessHost: FrameLayout? = null
    private var lifecycleApplication: Application? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var testActivity: MainActivity? = null

    @Test
    fun currentFailedSample() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Public-link testing requires run_public_pipeline=true",
            arguments.getString("run_public_pipeline") == "true")
        val id = requireNotNull(arguments.getString("videoId")) {
            "videoId is required for public-link testing"
        }
        require(id.matches(Regex("[0-9]{10,25}"))) { "videoId must contain 10 to 25 digits" }
        val download = when (arguments.getString("download") ?: "false") {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException("download must be true or false")
        }
        val downloadTimeout = arguments.getString("downloadTimeoutMs")?.toLongOrNull() ?: 600_000L
        require(downloadTimeout in 10_000L..1_800_000L)
        val shareUrl = requireNotNull(arguments.getString("shareUrl")) {
            "shareUrl is required for public-link testing"
        }.let { supplied ->
            try {
                ShareLinks.extract(supplied)
            } catch (_: Exception) {
                throw IllegalArgumentException("shareUrl must be one HTTPS Douyin share URL")
            }
        }
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareUrl)
        }
        var previousUris: Set<String> = emptySet()
        Log.i(TAG, "started id=$id download=$download")
        if (arguments.getString("headless") == "true") {
            Log.i(TAG, "launch_mode=headless")
            instrumentation.runOnMainSync {
                model = SaverViewModel(instrumentation.targetContext.applicationContext as Application)
                headlessHost = FrameLayout(instrumentation.targetContext)
                model.attachParserHost(headlessHost!!)
                model.acceptShare(shareUrl)
                previousUris = model.history.map { it.uri }.toSet()
                model.resolve()
            }
        } else {
            prepareTestActivityVisibility()
            val launched = ActivityScenario.launch<MainActivity>(intent)
            scenario = launched
            launched.onActivity { activity ->
                model = ViewModelProvider(activity)[SaverViewModel::class.java]
                assertFalse("The app already has an active task", model.busy)
                assertEquals("The app did not receive the shared URL", shareUrl, ShareLinks.extract(model.input))
                previousUris = model.history.map { it.uri }.toSet()
                model.selectedPage = AppPage.HOME
                model.resolve()
            }
        }

        val ready = awaitStage(TaskStage.READY, 90_000L)
        assertEquals("Parsed video must match the requested work", id, ready.videoId)
        Log.i(TAG, "parsed id=$id")
        if (!download) return

        instrumentation.runOnMainSync { model.download() }
        val done = awaitStage(TaskStage.DONE, downloadTimeout)
        val saved = done.history.firstOrNull { it.id == id && it.uri !in previousUris }
        assertNotNull("No newly saved record for the requested work", saved)
        checkNotNull(saved)
        assertTrue("The saved record must contain more than 32 bytes", saved.bytes > 32L)
        verifySavedMedia(saved)
    }

    private fun prepareTestActivityVisibility() {
        instrumentation.runOnMainSync {
            val application = instrumentation.targetContext.applicationContext as Application
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity !is MainActivity) return
                    testActivity = activity
                    // Shows only this test's app above the keyguard; it never dismisses or unlocks it.
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    Log.i(TAG, "test_activity_created visibility_requested=true")
                }

                override fun onActivityResumed(activity: Activity) {
                    if (activity === testActivity) Log.i(TAG, "test_activity_resumed")
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === testActivity) testActivity = null
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            }
            lifecycleApplication = application
            lifecycleCallbacks = callbacks
            application.registerActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun awaitStage(expected: TaskStage, timeoutMs: Long): Snapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previousStage: TaskStage? = null
        var lastProgress = 0L
        while (true) {
            val snapshot = snapshot()
            if (snapshot.stage != previousStage) {
                Log.i(TAG, "stage=${snapshot.stage}")
                previousStage = snapshot.stage
            }
            if (snapshot.stage == TaskStage.DOWNLOADING && SystemClock.elapsedRealtime() - lastProgress >= 10_000L) {
                Log.i(TAG, "download_progress id=${snapshot.videoId} bytes=${snapshot.downloaded} total=${snapshot.total}")
                lastProgress = SystemClock.elapsedRealtime()
            }
            if (snapshot.stage == expected) return snapshot
            if (snapshot.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED)) {
                throw AssertionError(failureDescription(snapshot, "Pipeline stopped"))
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError(failureDescription(snapshot, "Timed out waiting for $expected"))
            }
            // Only this instrumentation worker sleeps; the app's main thread remains free.
            SystemClock.sleep(250L)
        }
    }

    private fun snapshot(): Snapshot {
        lateinit var result: Snapshot
        instrumentation.runOnMainSync {
            result = Snapshot(model.stage, model.message, model.diagnostics, model.video?.id, model.history.toList(), model.downloaded, model.total)
        }
        return result
    }

    private fun failureDescription(state: Snapshot, reason: String): String =
        "$reason; stage=${state.stage}; message=${DiagnosticText.clean(state.message, 600)}; " +
            "diagnostics=${DiagnosticText.clean(state.diagnostics, 12_000).takeLast(6_000)}"

    private fun verifySavedMedia(saved: SavedVideo) {
        val uri = Uri.parse(saved.uri)
        assertEquals("Saved video should use a content URI", "content", uri.scheme)
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val retriever = MediaMetadataRetriever()
        try {
            val input = resolver.openInputStream(uri)
            assertNotNull("Saved content URI must be readable", input)
            checkNotNull(input).use {
                val header = ByteArray(32)
                var count = 0
                while (count < header.size) {
                    val read = it.read(header, count, header.size - count)
                    if (read < 0) break
                    if (read > 0) count += read
                }
                assertEquals("Saved content must contain a readable media header", 32, count)
            }
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue("Saved video width must be positive", width > 0)
            assertTrue("Saved video height must be positive", height > 0)
            assertTrue("Saved video duration must be positive", duration > 0L)
            val frame = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
            assertNotNull("The phone must decode a saved video frame", frame)
            checkNotNull(frame)
            try {
                assertTrue("Decoded frame must have valid dimensions", frame.width > 0 && frame.height > 0)
                Log.i(TAG, "media_verified id=${saved.id} bytes=${saved.bytes} width=$width height=$height duration_ms=$duration frame=${frame.width}x${frame.height}")
            } finally {
                frame.recycle()
            }
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
            // This is the new local content URI, never a signed HTTP playback URL.
            val localUri = uri.buildUpon().clearQuery().fragment(null).build()
            Log.i(TAG, "saved id=${saved.id} bytes=${saved.bytes} uri=$localUri filename=${DiagnosticText.clean(name, 160)}")
        } catch (error: Exception) {
            throw AssertionError("Saved media verification failed: ${error.javaClass.simpleName}; " +
                failureDescription(snapshot(), "Local media check"))
        } finally {
            retriever.release()
        }
    }

    @After
    fun closeActivity() {
        headlessHost?.let { host ->
            instrumentation.runOnMainSync {
                if (model.busy) model.cancel()
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (snapshot().stage in listOf(TaskStage.CANCELLING, TaskStage.SAVING) && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100L)
            instrumentation.runOnMainSync { model.detachParserHost(host) }
        }
        headlessHost = null
        // The new completed file and all existing history stay available to the user.
        try {
            scenario?.close()
        } finally {
            instrumentation.runOnMainSync {
                lifecycleCallbacks?.let { lifecycleApplication?.unregisterActivityLifecycleCallbacks(it) }
                // Also clean up if launch timed out before ActivityScenario returned its handle.
                testActivity?.let { activity ->
                    activity.setShowWhenLocked(false)
                    activity.setTurnScreenOn(false)
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (!activity.isFinishing) activity.finish()
                }
                testActivity = null
                lifecycleCallbacks = null
                lifecycleApplication = null
            }
            scenario = null
        }
    }

    private data class Snapshot(
        val stage: TaskStage,
        val message: String,
        val diagnostics: String,
        val videoId: String?,
        val history: List<SavedVideo>,
        val downloaded: Long,
        val total: Long,
    )

    private companion object {
        const val TAG = "DyPhoneTest"
    }
}
