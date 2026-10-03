package com.local.douyinsaver

import android.content.Intent
import android.app.ActivityManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeatureRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

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
