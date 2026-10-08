package com.local.douyinsaver

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** No real downloads, user preferences or existing history are changed. */
@RunWith(AndroidJUnit4::class)
class WatermarkRecordsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun existingClassificationsAndCleanRecordsRoundTripWithoutRewritingHistory() = isolated { namespace ->
        context.getSharedPreferences(namespace + "downloads", 0).edit().putString("completed",
            """[{"id":"7689301063664454962","title":"old","uri":"content://fixture/old","bytes":42}]""").commit()
        val records = DownloadRecords(context, namespace)
        val old = records.history().single()
        assertNull(old.watermarkMode)
        val marked = old.copy(uri = "content://fixture/marked", uris = listOf("content://fixture/marked"), watermarkMode = WatermarkMode.WATERMARKED)
        val clean = old.copy(uri = "content://fixture/clean", uris = listOf("content://fixture/clean"), watermarkMode = WatermarkMode.CLEAN)
        records.save(marked); records.save(clean)
        val restored = DownloadRecords(context, namespace).history()
        assertEquals(3, restored.size)
        assertEquals(listOf(WatermarkMode.CLEAN, WatermarkMode.WATERMARKED, null), restored.map { it.watermarkMode })
        assertEquals(old.uri, restored.last().uri)
    }

    @Test fun duplicateDetectionAlwaysUsesCleanAndNeverReusesMarkedOrUnknownRecords() = isolated { namespace ->
        val engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(context.applicationContext as Application, namespace)
        val old = SavedVideo("7689301063664454962", "old", "content://fixture/old", 42)
        val marked = old.copy(uri = "content://fixture/marked", watermarkMode = WatermarkMode.WATERMARKED)
        val clean = old.copy(uri = "content://fixture/clean", watermarkMode = WatermarkMode.CLEAN)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            setState(engine, "video", ParsedVideo(old.id, old.title, "", 0.0, 0, 0))
            setState(engine, "duplicateCandidates", listOf(old, marked, clean))
            engine.updateWatermarkMode(WatermarkMode.CLEAN)
            assertEquals(clean, engine.duplicate)
            engine.updateWatermarkMode(WatermarkMode.WATERMARKED)
            assertEquals(clean, engine.duplicate)
            setState(engine, "duplicateCandidates", listOf(old, marked))
            engine.updateWatermarkMode(WatermarkMode.CLEAN)
            assertNull(engine.duplicate)
            engine.updateWatermarkMode(WatermarkMode.WATERMARKED)
            assertNull(engine.duplicate)
            engine.updateWatermarkMode(WatermarkMode.ORIGINAL)
            assertNull(engine.duplicate)
        }
    }

    private fun isolated(body: (String) -> Unit) {
        val namespace = "watermark_records_${UUID.randomUUID()}_"
        try { body(namespace) } finally {
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics").forEach { context.deleteSharedPreferences(namespace + it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(engine: SaverEngine, name: String, value: T) {
        val field = SaverEngine::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(engine) as MutableState<T>).value = value
    }
}
