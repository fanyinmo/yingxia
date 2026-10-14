package com.local.douyinsaver

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ParseHistoryEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val id = "7695376760892314330"
    private fun album(kind: AlbumAssetKind = AlbumAssetKind.STATIC): ParsedVideo {
        val url = "https://p3.douyinpic.com/history_fixture.webp"
        return ParsedVideo(id, "测试图集", "", 0.0, 0, 0,
            images = listOf(ParsedImage(url, 640, 480, listOf(MediaSource(url, WatermarkMode.CLEAN)), kind = kind)))
    }

    @Test fun staticAndKnownMotionBecomeReadyWithoutReadingButMissingLiveReadsOnce() = isolated { engine, _ ->
        var reads = 0
        main {
            assertEquals(GifExportQuality.HIGH_QUALITY, engine.gifExportQuality)
            engine.albumMotionResolveOverride = { _, _ -> reads++ }
            accept(engine, album())
            assertEquals(TaskStage.READY, engine.stage); assertFalse(engine.busy); assertEquals(0, reads)
            accept(engine, album(AlbumAssetKind.ANIMATED))
            assertFalse(engine.busy); assertEquals(0, reads)
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            accept(engine, album(AlbumAssetKind.LIVE))
            assertEquals(1, reads); assertTrue(engine.busy)
            engine.cancelAlbumMotion()
            accept(engine, album(AlbumAssetKind.DYNAMIC))
            assertEquals(2, reads); assertTrue(engine.busy)
        }
    }

    @Test fun independentRecordsSurviveReloadQueueClearAndKeepOnlyLatestWorkMetadata() = isolated { engine, prefix ->
        main {
            accept(engine, album())
            assertEquals(1, engine.parseHistory.size)
            engine.clearQueue()
            assertEquals(1, engine.parseHistory.size)
            accept(engine, album().copy(title = "新标题", bgmUrl = "https://lf-music.douyinstatic.com/signed_secret"))
            assertEquals("新标题", engine.parseHistory.single().title)
            val restored = ParseRecords(app, prefix).history().single()
            assertEquals("https://www.douyin.com/note/$id", restored.source)
            val stored = app.getSharedPreferences(prefix + "parse_records", 0).getString("items", "")!!
            assertFalse(stored.contains("signed_secret")); assertFalse(stored.contains("douyinpic"))
            engine.removeParseRecord(id)
            assertTrue(engine.parseHistory.isEmpty()); assertTrue(ParseRecords(app, prefix).history().isEmpty())
        }
    }

    @Test fun bothRecordEntrypointsAcquireFreshWorkWithoutDownloadingOrTouchingSavedFiles() = isolated { engine, prefix ->
        val saved = SavedVideo(id, "下载记录", "content://owned_fixture/untouched", 20)
        val downloads = DownloadRecords(app, prefix)
        downloads.save(saved)
        main {
            engine.selectedPage = AppPage.HISTORY
            engine.reparseSaved(saved)
            assertEquals(AppPage.HOME, engine.selectedPage)
            assertEquals("https://www.douyin.com/video/$id", engine.input)
        }
        await { engine.stage == TaskStage.BROWSING }
        main {
            accept(engine, album())
            engine.selectedPage = AppPage.PARSE_HISTORY
            engine.reparseRecord(engine.parseHistory.single())
            assertEquals(AppPage.HOME, engine.selectedPage)
            assertEquals("https://www.douyin.com/note/$id", engine.input)
        }
        await { engine.stage == TaskStage.BROWSING }
        main {
            accept(engine, album())
            assertEquals(TaskStage.READY, engine.stage)
            assertEquals(listOf(saved), downloads.history())
            state(engine, "stage", TaskStage.DOWNLOADING)
            engine.reparseRecord(ParseRecord("7695376760892314331", "忙时禁止", false))
            assertEquals("https://www.douyin.com/note/$id", engine.input)
        }
    }

    private fun accept(engine: SaverEngine, content: ParsedVideo) {
        state(engine, "stage", TaskStage.VERIFYING)
        SaverEngine::class.java.getDeclaredMethod("acceptVerifiedResult", Int::class.javaPrimitiveType, ParsedVideo::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(engine, engine.generation, content, emptyList<SavedVideo>())
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false; main { ready = condition() }; if (ready) return
            SystemClock.sleep(20)
        }
        main { assertTrue(condition()) }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> state(engine: SaverEngine, name: String, value: T) {
        (SaverEngine::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(engine) as MutableState<T>).value = value
    }
    private fun isolated(block: (SaverEngine, String) -> Unit) {
        val prefix = "parse_history_test_${UUID.randomUUID()}_"
        lateinit var engine: SaverEngine
        main { engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(app, prefix) }
        try { block(engine, prefix) } finally {
            main {
                engine.cancelAlbumMotion()
                (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
            }
            listOf("parse_records", "downloads", "download_tasks", "download_options", "parse_diagnostics").forEach { app.deleteSharedPreferences(prefix + it) }
        }
    }
}
