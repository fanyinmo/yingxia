package com.local.douyinsaver

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID

/** No user preferences, records, files or network sources are changed by these isolated settings tests. */
@RunWith(AndroidJUnit4::class)
class StaticImageTimingSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    @Test fun newInstallStartsAtThreeSecondsAndLegacyChoiceSurvivesMigration() = isolated { prefix ->
        val preferences = application.getSharedPreferences(prefix + "download_options", Context.MODE_PRIVATE)
        assertEquals(3.0, AlbumTimingPreferences(application, prefix).readStaticSeconds(), 0.0)
        assertTrue(preferences.all.isEmpty())
        assertTrue(preferences.edit().putInt("image_seconds", 7).putString("naming", "TITLE")
            .putString("watermark_mode", "ORIGINAL").commit())
        val legacy = preferences.all.toMap()
        assertEquals(7.0, AlbumTimingPreferences(application, prefix).readStaticSeconds(), 0.0)
        assertEquals(legacy, preferences.all)
    }

    @Test fun tenthsPersistWithoutChangingLegacyPreferencesOrOldHistoryAndRejectInvalidUpdates() = isolated { prefix ->
        val preferences = application.getSharedPreferences(prefix + "download_options", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putInt("image_seconds", 7).putString("naming", "TITLE").commit())
        val records = DownloadRecords(application, prefix)
        val old = SavedVideo("old", "旧导出记录", "content://com.local.douyinsaver.fixture/old", 42,
            isAlbum = true, albumTimingSignature = "auto;static=7")
        records.save(old)
        val store = AlbumTimingPreferences(application, prefix)
        for ((seconds, tenths) in listOf(0.1 to 1, 1.2 to 12, 2.3999999999 to 24, 120.0 to 1200)) {
            assertEquals(tenths / 10.0, store.updateStaticSeconds(seconds), 0.0)
            assertEquals(tenths, preferences.getInt("static_image_duration_tenths", -1))
            assertEquals(tenths / 10.0, AlbumTimingPreferences(application, prefix).readStaticSeconds(), 0.0)
            assertEquals(7, preferences.getInt("image_seconds", -1))
            assertEquals("TITLE", preferences.getString("naming", null))
            assertEquals(listOf(old), records.history())
        }
        val valid = preferences.all.toMap()
        listOf(0.0, -0.1, 0.09, 120.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { store.updateStaticSeconds(value) }
            assertEquals(valid, preferences.all)
        }
    }

    @Test fun productionEnginePassesDecimalDefaultToEverySaveModeWhileKeepingTheOverrideIndependent() = isolated { prefix ->
        val preferences = application.getSharedPreferences(prefix + "download_options", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putInt("image_seconds", 7).commit())
        val engines = mutableListOf<SaverEngine>()
        val received = Collections.synchronizedList(mutableListOf<DownloadOptions>())
        val image = "https://p3.douyinpic.com/settings_timing_fixture.jpg"
        val fixture = ParsedVideo("9999999999999999917", "受控默认时长", "", 0.0, 48, 48,
            images = listOf(ParsedImage(image, mediaSources = listOf(MediaSource(image, WatermarkMode.CLEAN)))),
            bgmUrl = "https://lf-music.douyinstatic.com/settings_timing_fixture.m4a")
        try {
            lateinit var engine: SaverEngine
            main {
                engine = createEngine(prefix).also(engines::add)
                assertEquals(7.0, engine.staticImageSeconds, 0.0)
                engine.updateStaticImageSeconds(1.2)
                engine.gifStartSeconds = 1.0f
                engine.gifDurationSeconds = 6.0f
                seed(engine, "video", fixture)
                engine.queueSaveOverride = { content, options ->
                    received.add(options)
                    SavedVideo(content.id, content.title, "content://com.local.douyinsaver.fixture/${UUID.randomUUID()}", 42,
                        isAlbum = true, watermarkMode = WatermarkMode.CLEAN)
                }
            }
            for (mode in listOf(AlbumMode.GIF, AlbumMode.VIDEO, AlbumMode.IMAGES)) {
                val before = received.size
                main { engine.albumMode = mode; engine.download(force = true) }
                await { received.size == before + 1 && engine.stage == TaskStage.DONE }
                assertEquals(mode, received.last().albumMode)
                assertEquals(1.2, received.last().staticImageSeconds!!, 0.0)
                assertNull(received.last().itemDurationSeconds)
                assertEquals(1f, received.last().gifStartSeconds, 0f)
                assertEquals(6f, received.last().gifDurationSeconds, 0f)
            }
            main { engine.setSelectedItemDuration(0.7); engine.albumMode = AlbumMode.GIF; engine.download(force = true) }
            await { received.size == 4 && engine.stage == TaskStage.DONE }
            assertEquals(0.7, received.last().itemDurationSeconds!!, 0.0)
            assertEquals(1.2, received.last().staticImageSeconds!!, 0.0)
            main {
                engine.setSelectedItemDuration(null)
                val reloaded = createEngine(prefix).also(engines::add)
                assertEquals(1.2, reloaded.staticImageSeconds, 0.0)
                assertNull(reloaded.itemDurationSeconds)
            }
            assertEquals(7, preferences.getInt("image_seconds", -1))
        } finally {
            main { engines.forEach { (field(it, "scope") as CoroutineScope).cancel() } }
        }
    }

    private fun createEngine(prefix: String): SaverEngine = SaverEngine::class.java
        .getDeclaredConstructor(Application::class.java, String::class.java).apply { isAccessible = true }
        .newInstance(application, prefix)
    private fun field(engine: SaverEngine, name: String): Any? = SaverEngine::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(engine)
    @Suppress("UNCHECKED_CAST")
    private fun <T> seed(engine: SaverEngine, name: String, value: T) {
        (field(engine, "${name}\$delegate") as MutableState<T>).value = value
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        main { assertTrue("The isolated timing save did not finish", condition()) }
    }
    private fun isolated(body: (String) -> Unit) {
        val prefix = "static_timing_${UUID.randomUUID()}_"
        val userSettings = application.getSharedPreferences("download_options", Context.MODE_PRIVATE).all.toMap()
        val userHistory = application.getSharedPreferences("downloads", Context.MODE_PRIVATE).all.toMap()
        try {
            body(prefix)
            assertEquals(userSettings, application.getSharedPreferences("download_options", Context.MODE_PRIVATE).all)
            assertEquals(userHistory, application.getSharedPreferences("downloads", Context.MODE_PRIVATE).all)
        } finally {
            listOf("download_options", "downloads", "download_tasks", "parse_diagnostics").forEach {
                application.deleteSharedPreferences(prefix + it)
            }
        }
    }
}
