package com.local.douyinsaver

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.MutableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.util.Collections
import java.util.UUID

/** Drives the isolated engine without network/service; readable-file tests use only their own pending rows. */
@RunWith(AndroidJUnit4::class)
class QueueEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val links = (1..3).map { "https://www.douyin.com/video/999999999999999999$it" }
    private fun fixture(index: Int, album: Boolean = false): ParsedVideo {
        val url = "https://v3-web.douyinvod.com/fixture_$index.mp4"
        val image = "https://p3.douyinpic.com/fixture_$index.jpg"
        return ParsedVideo("999999999999999999$index", "自制队列作品 $index", url, 4.0, 640, 480,
            images = if (album) listOf(ParsedImage(image, 640, 480, listOf(MediaSource(image, WatermarkMode.CLEAN)))) else emptyList(),
            mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)))
    }

    /** Official gallery display fields need no separate watermark download derivative. */
    private fun displayOnlyAlbum(index: Int, imageCount: Int = 1): ParsedVideo {
        val id = "999999999999999999$index"
        val images = (1..imageCount).map { ordinal ->
            val url = "https://p5-sign.douyinpic.com/queue_display_${index}_$ordinal.jpg?signature=fixture_$ordinal"
            AlbumCandidatePolicy.ImageCandidate(listOf(url), 1080, 1440, displayUrls = listOf(url))
        }
        return checkNotNull(AlbumCandidatePolicy.ready(id, "https://www.iesdouyin.com/share/note/$id/", id,
            "自制展示图集 $index", images,
            listOf("https://sf11-cdn-tos.douyinstatic.com/queue_album_music_$index.mp3"), 25.0))
    }

    @Test fun explicitBatchFormatAppliesOnlyToUnknownDynamicAndPreservesNeighborDefaults() = isolated { engine, _, original ->
        val motionUrl = "https://v3.douyinvod.com/queue_unknown_motion.mp4"
        fun dynamic(index: Int, kind: AlbumAssetKind) = fixture(index, album = true).let { content ->
            content.copy(images = content.images.map { it.copy(kind = kind,
                motion = ParsedMotion(motionUrl, mediaSources = listOf(MediaSource(motionUrl, WatermarkMode.CLEAN)))) })
        }
        val results = listOf(dynamic(1, AlbumAssetKind.DYNAMIC), fixture(2), dynamic(3, AlbumAssetKind.LIVE))
        val savedOptions = Collections.synchronizedList(mutableListOf<Pair<String, AlbumMode>>())
        main {
            engine.queueParseOverride = { task -> complete(engine, results[links.indexOf(task.source)]) }
            engine.queueSaveOverride = { content, options ->
                savedOptions.add(content.id to options.albumMode)
                SavedVideo(content.id, content.title, "content://com.local.douyinsaver.fixture/${UUID.randomUUID()}",
                    42, watermarkMode = WatermarkMode.CLEAN, isAlbum = content.isAlbum)
            }
            engine.enqueueInput(links.joinToString("\n")); engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 3 }
        main { engine.downloadParsedQueue(mode = AlbumMode.MOTION_VIDEOS) }
        await { !engine.batchSaving && engine.queue.all { it.status == QueueStatus.DONE } }
        main {
            assertEquals(listOf(results[0].id to AlbumMode.MOTION_VIDEOS, results[1].id to AlbumMode.IMAGES,
                results[2].id to AlbumMode.IMAGES), savedOptions.toList())
            assertEquals(AlbumAssetKind.DYNAMIC, engine.queueResults[engine.queue[0].key]!!.images.single().kind)
            assertEquals(AlbumAssetKind.LIVE, engine.queueResults[engine.queue[2].key]!!.images.single().kind)
            assertTrue(engine.history.contains(original))
        }
    }

    @Test fun singleDisplayOnlyAlbumBecomesReadyWithEveryImageAndItsMusic() = isolated { engine, records, original ->
        val album = displayOnlyAlbum(1, imageCount = 2)
        val preferences = application.getSharedPreferences("${field(engine, "namespace")}download_options", 0)
        val beforeOptions = preferences.all
        main {
            engine.acceptShare("自制图文分享 https://www.douyin.com/note/${album.id}")
            state(engine, "stage", TaskStage.VERIFYING)
            complete(engine, album)
            assertEquals(TaskStage.READY, engine.stage)
            val ready = checkNotNull(engine.video)
            assertEquals(album, ready)
            assertTrue(ready.isAlbum)
            assertTrue(WatermarkSources.available(ready, WatermarkMode.CLEAN))
            val selected = WatermarkSources.select(ready, WatermarkMode.CLEAN)
            assertEquals(album.images.map { it.url }, selected.images.map { it.url })
            assertEquals(album.images.first().url, selected.coverUrl)
            assertEquals(album.bgmUrl, selected.bgmUrl)
            assertEquals(25.0, selected.bgmDurationSeconds, 0.0)
            assertTrue(engine.message.contains("2 张图片"))
            assertTrue(engine.diagnostics.contains("media_probe_passed"))
            assertNull(engine.selectedTaskKey)
            assertTrue(engine.queueResults.isEmpty())
            assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
        }
        assertEquals(beforeOptions, preferences.all)
        assertEquals(listOf(original), records.history())
    }

    @Test fun automaticQueueAcceptsDisplayOnlyAlbumsAndContinuesPastAnIncompleteAlbum() = isolated { engine, records, original ->
        val sources = (1..4).map { "https://www.douyin.com/video/999999999999999999$it" }
        val first = displayOnlyAlbum(1)
        val last = displayOnlyAlbum(4, imageCount = 2)
        val incomplete = displayOnlyAlbum(2, imageCount = 2).let { album ->
            // A flattened URL without an official display field still carries no
            // clean-source evidence. The full album must fail, rather than drop it.
            val unknown = "https://p5-sign.douyinpic.com/queue_unclassified_2.jpg"
            val unknownImage = AlbumCandidatePolicy.ImageCandidate(listOf(unknown), 1080, 1440)
            val display = AlbumCandidatePolicy.ImageCandidate(listOf(album.images.first().url), 1080, 1440,
                displayUrls = listOf(album.images.first().url))
            checkNotNull(AlbumCandidatePolicy.ready(album.id, "https://www.iesdouyin.com/share/note/${album.id}/",
                album.id, "自制不完整图集", listOf(display, unknownImage), listOf(album.bgmUrl), 25.0))
        }
        val calls = mutableListOf<String>()
        var saves = 0
        main {
            engine.queueParseOverride = { task ->
                calls += task.key
                complete(engine, when (sources.indexOf(task.source)) {
                    0 -> first
                    1 -> incomplete
                    2 -> fixture(3)
                    3 -> last
                    else -> error("Unexpected isolated queue task")
                })
            }
            engine.queueSaveOverride = { _, _ -> saves++; error("Parsing must never start saving") }
            engine.enqueueInput(sources.joinToString("\n"))
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queue.last().status == QueueStatus.READY }
        main {
            assertEquals(4, calls.size)
            assertEquals(4, calls.distinct().size)
            assertEquals(listOf(QueueStatus.READY, QueueStatus.FAILED, QueueStatus.READY, QueueStatus.READY),
                engine.queue.map { it.status })
            assertTrue(engine.queue[1].message.contains("第 2 张图片"))
            assertEquals(setOf(engine.queue[0].key, engine.queue[2].key, engine.queue[3].key), engine.queueResults.keys)
            assertEquals(first, engine.queueResults[engine.queue[0].key])
            assertEquals(last, engine.queueResults[engine.queue[3].key])
            engine.queueResults.values.forEach { result ->
                assertTrue(WatermarkSources.available(result, WatermarkMode.CLEAN))
                if (result.isAlbum) {
                    assertTrue(result.bgmUrl.isNotBlank())
                    assertEquals(result.images.map { it.url }, WatermarkSources.select(result, WatermarkMode.CLEAN).images.map { it.url })
                }
            }
            assertEquals(0, saves)
            assertEquals(TaskStage.IDLE, engine.stage)
            assertNull(engine.video)
            assertNull(engine.selectedTaskKey)
            assertTrue(engine.message.contains("3 个作品可保存"))
        }
        assertEquals(listOf(original), records.history())
    }

    @Test fun oneStartParsesEveryVideoAndAlbumWithoutStartingAnySave() = isolated { engine, records, original ->
        val calls = mutableListOf<String>()
        var saves = 0
        main {
            engine.queueParseOverride = { task -> calls += task.key; complete(engine, fixture(links.indexOf(task.source) + 1, task.source == links[1])) }
            engine.queueSaveOverride = { _, _ -> saves++; error("Parsing must never start saving") }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 3 }
        main {
            assertEquals(3, calls.size)
            assertEquals(3, calls.distinct().size)
            assertEquals(0, saves)
            assertTrue(engine.queue.all { it.status == QueueStatus.READY })
            assertTrue(engine.queueResults.values.any { it.isAlbum && it.bgmUrl.isBlank() })
            assertNull(engine.selectedTaskKey)
            assertNull(engine.video)
            assertEquals(TaskStage.IDLE, engine.stage)
            val results = engine.queueResults
            engine.selectedPage = AppPage.SETTINGS
            engine.selectedPage = AppPage.HOME
            engine.startQueue()
            engine.clearInput()
            assertEquals(results, engine.queueResults)
            assertEquals(3, calls.size)
            assertNull(engine.video)
        }
        assertEquals(listOf(original), records.history())
        assertTrue(records.queue().all { it.status == QueueStatus.FAILED && it.message.contains("重新解析") })
    }

    /** Metadata/state regression only: no network, MediaStore row, or readable-file fixture. */
    @Test fun automaticMixedDynamicQueueUsesCleanImagesAndKeepsLiveManifestForRepeatSaves() = isolated { engine, records, original ->
        val still = fixture(1, album = true)
        val animated = fixture(2, album = true).let { content ->
            val url = "https://p3.douyinpic.com/queue_dynamic.gif"
            content.copy(images = listOf(ParsedImage(url, 640, 480,
                listOf(MediaSource(url, WatermarkMode.CLEAN)), AlbumAssetKind.ANIMATED, "image/gif")))
        }
        val live = fixture(3, album = true).let { content ->
            val motionUrl = "https://v3-web.douyinvod.com/queue_live.mp4?wm=0"
            content.copy(images = content.images.map { image -> image.copy(kind = AlbumAssetKind.LIVE,
                motion = ParsedMotion(motionUrl, 640, 480, 2.4,
                    listOf(MediaSource(motionUrl, WatermarkMode.CLEAN)))) })
        }
        val results = listOf(still, animated, live)
        val coverUri = "content://com.local.douyinsaver.fixture/dynamic-old-cover"
        val coverOnly = SavedVideo(live.id, live.title, coverUri, 42, mimeType = "image/jpeg",
            isAlbum = true, watermarkMode = WatermarkMode.CLEAN,
            albumAssets = listOf(SavedAlbumAsset(coverUri, "image/jpeg", AlbumAssetKind.STATIC)))
        records.save(coverOnly)
        val parseCalls = mutableListOf<String>()
        val saves = Collections.synchronizedList(mutableListOf<Pair<String, DownloadOptions>>())
        main {
            engine.queueParseOverride = { task ->
                parseCalls += task.key
                val index = links.indexOf(task.source)
                complete(engine, results[index], duplicates = if (index == 2) listOf(coverOnly) else emptyList())
            }
            engine.queueSaveOverride = { content, options ->
                saves += content.id to options
                val selected = WatermarkSources.select(content, options.watermarkMode)
                val uri = "content://com.local.douyinsaver.fixture/dynamic-${content.id}"
                val kind = selected.images.single().kind
                val mime = if (kind == AlbumAssetKind.ANIMATED) "image/gif" else "image/jpeg"
                SavedVideo(content.id, content.title, uri, 84, mimeType = mime,
                    uris = listOf(uri),
                    isAlbum = true, watermarkMode = options.watermarkMode,
                    albumAssets = listOf(SavedAlbumAsset(uri, mime, kind,
                        embeddedMotion = kind == AlbumAssetKind.LIVE, sourceIndex = 0)), albumSourceCount = 1)
            }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 3 }
        main {
            assertEquals(3, parseCalls.size)
            assertEquals(3, parseCalls.distinct().size)
            assertTrue(saves.isEmpty())
            assertTrue(engine.queue.all { it.status == QueueStatus.READY })
            assertEquals(results, engine.queue.map { engine.queueResults[it.key] })
            engine.viewTaskResult(engine.queue.last().key)
            assertNull("An existing still cover must not suppress its missing live clip", engine.duplicate)
            engine.albumMode = AlbumMode.VIDEO
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving && engine.queue.all { it.status == QueueStatus.DONE } }
        main {
            assertEquals(results.map { it.id }, saves.map { it.first })
            assertTrue(saves.all { it.second.albumMode == AlbumMode.IMAGES && it.second.watermarkMode == WatermarkMode.CLEAN })
            assertTrue(engine.message.contains("已保存 3 项") && engine.message.contains("已跳过 0 项"))
            val restored = records.history().single { it.id == live.id && it.uri != coverUri }
            val asset = restored.albumAssets.single()
            assertEquals(AlbumAssetKind.LIVE, asset.kind)
            assertEquals(restored.uri, asset.uri)
            assertTrue(asset.embeddedMotion)
            assertTrue(asset.motionUri.isBlank())
            assertEquals("image/jpeg", restored.mimeTypeFor(asset.uri))
            assertEquals(1, restored.uris.size)
            assertTrue(records.history().contains(coverOnly))
            assertTrue(records.history().contains(original))
            // Newly committed manifests enter every same-work duplicate cache without
            // requesting provider access; this test deliberately owns metadata only.
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving }
        main {
            assertEquals(3, saves.size)
            assertEquals(3, parseCalls.size)
            assertTrue(engine.message.contains("已保存 0 项") && engine.message.contains("已跳过 3 项"))
            assertTrue(engine.queue.all { it.status == QueueStatus.DONE })
            assertEquals(5, records.history().size)
        }
    }

    @Test fun aFailedItemDoesNotStopLaterAlbumsAndCanBeRetriedIndividually() = isolated { engine, _, _ ->
        var failFirst = true
        main {
            engine.queueParseOverride = { task ->
                val index = links.indexOf(task.source) + 1
                if (index == 1 && failFirst) { failFirst = false; error("自制解析失败") }
                complete(engine, fixture(index, index == 2))
            }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 2 }
        main {
            val first = engine.queue.first()
            val later = engine.queueResults
            assertEquals(QueueStatus.FAILED, first.status)
            assertTrue(engine.queue.drop(1).all { it.status == QueueStatus.READY })
            engine.retryTask(first.key)
            assertEquals(QueueStatus.READY, engine.queue.first().status)
            assertEquals(3, engine.queueResults.size)
            later.forEach { (key, result) -> assertEquals(result, engine.queueResults[key]) }
            assertFalse(engine.queueRunning)
        }
    }

    @Test fun cancellingAndRestartingIgnoresStaleCallbacksAndKeepsEarlierResults() = isolated { engine, _, _ ->
        val calls = mutableListOf<String>()
        var holdSecond = true
        var cancelledGeneration = 0
        main {
            engine.queueParseOverride = { task ->
                calls += task.key
                val index = links.indexOf(task.source) + 1
                if (index == 2 && holdSecond) cancelledGeneration = engine.generation
                else complete(engine, fixture(index, index == 2))
            }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
        }
        await { engine.queue[1].status == QueueStatus.PARSING }
        main {
            val firstResult = engine.queueResults[engine.queue.first().key]
            engine.cancel()
            assertFalse(engine.queueRunning)
            assertEquals(QueueStatus.QUEUED, engine.queue[1].status)
            complete(engine, fixture(2, true), cancelledGeneration)
            assertEquals(1, engine.queueResults.size)
            holdSecond = false
            engine.startQueue()
            assertEquals(firstResult, engine.queueResults[engine.queue.first().key])
        }
        await { !engine.queueRunning && engine.queueResults.size == 3 }
        main {
            assertEquals(1, calls.count { it == engine.queue.first().key })
            assertEquals(2, calls.count { it == engine.queue[1].key })
            assertEquals(1, calls.count { it == engine.queue.last().key })
            assertTrue(engine.queue.all { it.status == QueueStatus.READY })
        }
    }

    @Test fun runningQueueLocksSortingAndActiveDeletionButCanRemoveAWaitingItem() = isolated { engine, _, _ ->
        main {
            engine.enqueueInput(links.joinToString("\n"))
            val first = engine.queue.first().key
            engine.moveTaskTo(first, 2)
            assertEquals(links.drop(1) + links.first(), engine.queue.map { it.source })
            engine.queueParseOverride = { }
            engine.startQueue()
            val order = engine.queue.map { it.key }
            engine.moveTaskTo(order.last(), 0)
            engine.moveTask(order.last(), -1)
            engine.removeTask(order.first())
            assertEquals(order, engine.queue.map { it.key })
            engine.removeTask(order[1])
            assertEquals(listOf(order.first(), order.last()), engine.queue.map { it.key })
            engine.stopQueue()
            engine.moveTaskTo(order.first(), 1)
            assertEquals(listOf(order.last(), order.first()), engine.queue.map { it.key })
        }
    }

    @Test fun explicitBatchSaveContinuesAfterFailureAndStoresAnAlbumWithoutBgmAsImages() = isolated { engine, records, original ->
        val saves = Collections.synchronizedList(mutableListOf<Pair<String, AlbumMode>>())
        main {
            engine.queueParseOverride = { task -> complete(engine, fixture(links.indexOf(task.source) + 1, task.source == links[1])) }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 3 }
        main {
            engine.queueSaveOverride = { result, options ->
                saves += result.id to options.albumMode
                if (result.id.endsWith("1")) error("自制保存失败")
                SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/${result.id}", 42,
                    mimeType = if (result.isAlbum) "image/png" else "video/mp4", isAlbum = result.isAlbum,
                    watermarkMode = options.watermarkMode)
            }
            engine.albumMode = AlbumMode.VIDEO
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving && engine.queue.count { it.status == QueueStatus.DONE } == 2 }
        main {
            assertEquals(3, saves.size)
            assertTrue(saves.all { it.second == AlbumMode.IMAGES })
            assertEquals(QueueStatus.FAILED, engine.queue.first().status)
            assertEquals(3, engine.queueResults.size)
            assertTrue(engine.message.contains("已保存 2 项") && engine.message.contains("失败 1 项"))
            assertNull(engine.selectedTaskKey)
            assertNull(engine.video)
            assertEquals(TaskStage.IDLE, engine.stage)
            engine.downloadParsedQueue(setOf(engine.queue.last().key))
            assertFalse(engine.batchSaving)
            assertEquals(3, saves.size)
        }
        assertEquals(3, records.history().size)
        assertEquals(original, records.history().last())
        assertTrue(records.history().any { it.isAlbum && it.mimeType == "image/png" })
    }

    @Test fun batchParsingPreservesAndRestoresTheIndependentSingleLinkResult() = isolated { engine, _, original ->
        val single = fixture(9)
        main {
            engine.updateInput("原来的分享文案 ${links.first()}")
            state(engine, "video", single)
            state(engine, "stage", TaskStage.READY)
            state(engine, "duplicateCandidates", listOf(original))
            state(engine, "downloaded", 123L)
            state(engine, "total", 456L)
            engine.fileName = "原作品名称"
            engine.queueParseOverride = { task -> complete(engine, fixture(links.indexOf(task.source) + 1)) }
            engine.enqueueInput(links.take(2).joinToString("\n"))
            engine.startQueue()
            assertEquals("原来的分享文案 ${links.first()}", engine.input)
        }
        await { !engine.queueRunning && engine.queueResults.size == 2 }
        main {
            assertEquals(single, engine.video)
            assertNull(engine.selectedTaskKey)
            assertEquals("原来的分享文案 ${links.first()}", engine.input)
            assertEquals("原作品名称", engine.fileName)
            assertEquals(123L, engine.downloaded)
            assertEquals(456L, engine.total)
            val retained = engine.queueResults
            engine.clearInput()
            assertNull(engine.video)
            assertEquals(retained, engine.queueResults)
        }
    }

    @Test fun individualRetryAndSaveSuccessOrFailureRestoreTheSingleResult() = isolated { engine, records, _ ->
        val single = fixture(9)
        main {
            engine.updateInput("单链接原文 ${links.first()}")
            state(engine, "video", single)
            state(engine, "stage", TaskStage.READY)
            engine.fileName = "单链接名称"
            engine.queueParseOverride = { complete(engine, fixture(1)) }
            engine.enqueueInput(links.first())
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 1 }
        main {
            engine.retryTask(engine.queue.single().key)
            assertEquals(single, engine.video)
            assertNull(engine.selectedTaskKey)
            assertEquals("单链接名称", engine.fileName)
            engine.queueSaveOverride = { result, options ->
                SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/single-save", 42,
                    watermarkMode = options.watermarkMode)
            }
            engine.downloadTask(engine.queue.single().key)
        }
        await { engine.queue.single().status == QueueStatus.DONE && engine.video == single }
        main {
            assertNull(engine.selectedTaskKey)
            assertEquals("单链接原文 ${links.first()}", engine.input)
            engine.queueSaveOverride = { _, _ -> error("自制单项保存失败") }
            engine.downloadTask(engine.queue.single().key, force = true)
        }
        await { engine.queue.single().status == QueueStatus.FAILED && engine.video == single }
        main {
            assertNull(engine.selectedTaskKey)
            assertEquals("单链接名称", engine.fileName)
            assertEquals(1, engine.queueResults.size)
        }
        assertEquals(2, records.history().size)
    }

    @Test fun queueAndBatchGapsLockNamingFolderAndHistoryOperations() = isolated { engine, records, original ->
        main {
            state(engine, "stage", TaskStage.READY)
            val naming = engine.namingRule
            val folder = engine.downloadFolder
            val beforeMessage = engine.message
            val defaultVersion = engine.defaultWatermarkMode
            val batchVersion = engine.batchWatermarkMode
            val singleVersion = engine.watermarkMode
            listOf("queueRunning", "batchSaving").forEach { running ->
                state(engine, running, true)
                assertFalse("The regression requires an idle gap", engine.busy)
                engine.updateNamingRule(if (naming == NamingRule.ID) NamingRule.TITLE else NamingRule.ID)
                engine.selectDownloadFolder(Uri.parse("content://com.local.douyinsaver.fixture/invalid-folder"))
                engine.resetDownloadFolder()
                engine.manageHistory(setOf(original.uri), deleteFiles = false)
                engine.setDefaultVersion(WatermarkMode.ORIGINAL)
                engine.updateBatchWatermarkMode(WatermarkMode.WATERMARKED)
                engine.updateWatermarkMode(WatermarkMode.WATERMARKED)
                assertEquals(naming, engine.namingRule)
                assertEquals(folder, engine.downloadFolder)
                assertEquals(beforeMessage, engine.message)
                assertEquals(defaultVersion, engine.defaultWatermarkMode)
                assertEquals(batchVersion, engine.batchWatermarkMode)
                assertEquals(singleVersion, engine.watermarkMode)
                assertFalse(engine.busy)
                assertEquals(listOf(original), engine.history)
                state(engine, running, false)
            }
        }
        assertEquals(listOf(original), records.history())
    }

    @Test fun cancellingBatchInAnIdleGapKeepsSavedFilesAndRestoresTheSingleResult() = isolated { engine, records, original ->
        val single = fixture(9)
        val saves = Collections.synchronizedList(mutableListOf<String>())
        main {
            engine.updateInput("原文 ${links.first()}")
            state(engine, "video", single)
            state(engine, "stage", TaskStage.READY)
            engine.queueParseOverride = { task -> complete(engine, fixture(links.indexOf(task.source) + 1)) }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 3 }
        main {
            engine.queueSaveOverride = { result, options ->
                saves += result.id
                SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/gap-save", 42,
                    watermarkMode = options.watermarkMode)
            }
            engine.downloadParsedQueue()
        }
        await { engine.batchSaving && !engine.busy && engine.queue.first().status == QueueStatus.DONE }
        main {
            engine.cancel()
            assertFalse(engine.batchSaving)
            assertEquals(single, engine.video)
            assertNull(engine.selectedTaskKey)
            assertEquals("原文 ${links.first()}", engine.input)
            assertEquals(QueueStatus.DONE, engine.queue.first().status)
            assertTrue(engine.queue.drop(1).all { it.status == QueueStatus.READY })
        }
        SystemClock.sleep(350)
        main {
            assertEquals(1, saves.size)
            assertEquals(3, engine.queueResults.size)
        }
        assertEquals(2, records.history().size)
        assertEquals(original, records.history().last())
    }

    @Test fun differentShortLinksForTheSameWorkSaveOnlyOneFileInTheBatch() = isolated { engine, records, original ->
        val shortLinks = listOf("https://v.douyin.com/QueueFixtureOne/", "https://v.douyin.com/QueueFixtureTwo/")
        val saves = Collections.synchronizedList(mutableListOf<String>())
        main {
            engine.queueParseOverride = { complete(engine, fixture(1)) }
            engine.enqueueInput(shortLinks.joinToString("\n"))
            assertEquals(2, engine.queue.size)
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 2 }
        main {
            assertEquals(1, engine.queueResults.values.map { it.id }.distinct().size)
            engine.queueSaveOverride = { result, options ->
                saves += result.id
                SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/same-work", 42,
                    watermarkMode = options.watermarkMode)
            }
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving && engine.queue.all { it.status == QueueStatus.DONE } }
        main {
            assertEquals(listOf(fixture(1).id), saves)
            assertEquals(2, engine.queueResults.size)
            assertTrue(engine.message.contains("已保存 1 项") && engine.message.contains("已跳过 1 项"))
            assertTrue(engine.queue.last().message.contains("跳过重复保存"))
            assertNull(engine.selectedTaskKey)
            assertNull(engine.video)
        }
        assertEquals(2, records.history().size)
        assertEquals(original, records.history().last())
    }

    @Test fun cancellingAnIndividualRetryRestoresTheSingleResultOrClearsAnEmptySelection() = isolated { engine, _, _ ->
        val single = fixture(9)
        main {
            engine.updateInput("独立原文 ${links.first()}")
            state(engine, "video", single)
            state(engine, "stage", TaskStage.READY)
            engine.fileName = "独立名称"
            engine.enqueueInput(links.first())
            engine.queueParseOverride = { }
            val key = engine.queue.single().key
            engine.retryTask(key)
            val cancelledGeneration = engine.generation
            assertFalse(engine.queueRunning)
            assertTrue(engine.busy)
            engine.cancel()
            assertEquals(QueueStatus.CANCELLED, engine.queue.single().status)
            assertEquals(single, engine.video)
            assertEquals(TaskStage.READY, engine.stage)
            assertNull(engine.selectedTaskKey)
            assertEquals("独立名称", engine.fileName)
            complete(engine, fixture(1), cancelledGeneration)
            assertTrue(engine.queueResults.isEmpty())
            assertEquals(single, engine.video)

            engine.clearInput()
            engine.retryTask(key)
            val emptyGeneration = engine.generation
            engine.cancel()
            assertEquals(QueueStatus.CANCELLED, engine.queue.single().status)
            assertNull(engine.selectedTaskKey)
            assertNull(engine.video)
            assertEquals(TaskStage.IDLE, engine.stage)
            assertEquals("", engine.input)
            complete(engine, fixture(1), emptyGeneration)
            assertTrue(engine.queueResults.isEmpty())
            assertNull(engine.video)
        }
    }

    @Test fun clearingAllStatusesPersistsAnEmptyQueueAndPreservesTheIndependentResultAndHistory() = isolated { engine, records, original ->
        val single = fixture(9)
        main {
            engine.updateWatermarkMode(WatermarkMode.CLEAN)
            engine.updateInput("原来的单链接 ${links.first()}")
            state(engine, "video", single)
            state(engine, "stage", TaskStage.READY)
            state(engine, "downloaded", 123L)
            state(engine, "total", 456L)
            engine.fileName = "独立作品名称"
            engine.queueParseOverride = { task -> complete(engine, fixture(links.indexOf(task.source) + 1)) }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 3 }
        main {
            engine.queueSaveOverride = { result, options ->
                SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/clear-keeps-file", 42,
                    watermarkMode = options.watermarkMode)
            }
            engine.downloadParsedQueue(setOf(engine.queue.first().key))
        }
        await { !engine.batchSaving && engine.queue.first().status == QueueStatus.DONE }
        main {
            engine.enqueueInput((4..6).joinToString("\n") { "https://v.douyin.com/ClearFixture$it/" })
            state(engine, "queue", engine.queue.mapIndexed { index, task -> when (index) {
                3 -> task.copy(status = QueueStatus.FAILED)
                4 -> task.copy(status = QueueStatus.CANCELLED)
                else -> task
            } })
            records.writeQueue(engine.queue)
            assertEquals(setOf(QueueStatus.DONE, QueueStatus.READY, QueueStatus.FAILED,
                QueueStatus.CANCELLED, QueueStatus.QUEUED), engine.queue.map { it.status }.toSet())
            val beforeHistory = engine.history
            engine.viewTaskResult(engine.queue[1].key)
            assertNotEquals(single, engine.video)
            engine.clearQueue()
            assertTrue(engine.queue.isEmpty())
            assertTrue(engine.queueResults.isEmpty())
            assertTrue((field(engine, "queueDuplicates") as Map<*, *>).isEmpty())
            assertTrue((field(engine, "pendingBatchKeys") as List<*>).isEmpty())
            assertNull(engine.selectedTaskKey)
            assertEquals(single, engine.video)
            assertEquals(TaskStage.READY, engine.stage)
            assertEquals("原来的单链接 ${links.first()}", engine.input)
            assertEquals("独立作品名称", engine.fileName)
            assertEquals(123L, engine.downloaded)
            assertEquals(456L, engine.total)
            assertEquals(beforeHistory, engine.history)
            assertEquals(2, beforeHistory.size)
            assertEquals(original, beforeHistory.last())
        }
        assertTrue(records.queue().isEmpty())
        assertEquals(2, records.history().size)
        val namespace = field(engine, "namespace") as String
        main {
            val reloaded = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(application, namespace)
            try {
                assertTrue(reloaded.queue.isEmpty())
                assertEquals(records.history(), reloaded.history)
                assertEquals("原来的单链接 ${links.first()}", reloaded.input)
            } finally { (field(reloaded, "scope") as CoroutineScope).cancel() }
        }
    }

    @Test fun clearingIsBlockedDuringBusyWorkAndBothIdleProcessingGaps() = isolated { engine, records, original ->
        main {
            engine.updateInput("保留单链接 ${links.first()}")
            state(engine, "video", fixture(9))
            state(engine, "stage", TaskStage.READY)
            engine.enqueueInput(links.take(2).joinToString("\n"))
            state(engine, "queueResults", mapOf(engine.queue.first().key to fixture(1)))
            val beforeQueue = engine.queue
            val beforeResults = engine.queueResults
            val beforeMessage = engine.message
            listOf("queueRunning", "batchSaving").forEach { flag ->
                state(engine, flag, true)
                assertFalse("This must exercise the idle gap guard", engine.busy)
                engine.clearQueue()
                assertEquals(beforeQueue, engine.queue)
                assertEquals(beforeResults, engine.queueResults)
                assertEquals(beforeMessage, engine.message)
                state(engine, flag, false)
            }
            listOf(TaskStage.VERIFYING, TaskStage.DOWNLOADING, TaskStage.SAVING).forEach { active ->
                state(engine, "stage", active)
                assertTrue(engine.busy)
                engine.clearQueue()
                assertEquals(beforeQueue, engine.queue)
                assertEquals(beforeResults, engine.queueResults)
                assertEquals(beforeMessage, engine.message)
            }
            assertEquals(beforeQueue, records.queue())
            assertEquals(listOf(original), engine.history)
            state(engine, "stage", TaskStage.READY)
            engine.clearQueue()
            assertTrue(engine.queue.isEmpty())
            assertEquals(fixture(9), engine.video)
            assertEquals("保留单链接 ${links.first()}", engine.input)
        }
        assertTrue(records.queue().isEmpty())
        assertEquals(listOf(original), records.history())
    }

    @Test fun onlyConfirmedCleanHistoryCountsAsAnExistingDownload() = isolated { engine, _, unknown ->
        main {
            state(engine, "video", fixture(1).copy(id = unknown.id))
            state(engine, "stage", TaskStage.READY)
            val marked = unknown.copy(uri = unknown.uri + "/marked", watermarkMode = WatermarkMode.WATERMARKED)
            val original = unknown.copy(uri = unknown.uri + "/original", watermarkMode = WatermarkMode.ORIGINAL)
            state(engine, "duplicateCandidates", listOf(marked, original, unknown))
            WatermarkMode.entries.forEach { previousMode ->
                engine.updateWatermarkMode(previousMode)
                assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
                assertNull(engine.duplicate)
            }
            val clean = unknown.copy(uri = unknown.uri + "/clean", watermarkMode = WatermarkMode.CLEAN)
            state(engine, "duplicateCandidates", listOf(marked, original, unknown, clean))
            assertEquals(clean, engine.duplicate)
            assertEquals(fixture(1).mediaUrl, engine.selectedContent?.mediaUrl)
        }
    }

    @Test fun obsoleteVersionChangesCannotAlterSingleOrBatchSavingAndRepeatedBatchIsSkipped() = isolated { engine, records, original ->
        val calls = Collections.synchronizedList(mutableListOf<WatermarkMode>())
        var parses = 0
        main {
            engine.setDefaultVersion(WatermarkMode.ORIGINAL)
            engine.updateWatermarkMode(WatermarkMode.WATERMARKED)
            engine.updateBatchWatermarkMode(WatermarkMode.WATERMARKED)
            engine.queueParseOverride = { task ->
                parses++
                val index = links.indexOf(task.source) + 1
                val clean = fixture(index)
                complete(engine, clean.copy(mediaSources = clean.mediaSources +
                    MediaSource("https://v3-web.douyinvod.com/marked_fixture_$index.mp4", WatermarkMode.WATERMARKED)))
            }
            engine.enqueueInput(links.take(2).joinToString("\n"))
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queueResults.size == 2 }
        main {
            assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
            assertEquals(WatermarkMode.CLEAN, engine.defaultWatermarkMode)
            assertEquals(WatermarkMode.CLEAN, engine.batchWatermarkMode)
            engine.queueSaveOverride = { result, options ->
                assertEquals(WatermarkMode.CLEAN, options.watermarkMode)
                assertEquals(result.mediaSources.first { it.mode == WatermarkMode.CLEAN }.url,
                    WatermarkSources.select(result, options.watermarkMode).mediaUrl)
                calls += options.watermarkMode
                SavedVideo(result.id, result.title,
                    "content://com.local.douyinsaver.fixture/${result.id}/CLEAN", 42,
                    watermarkMode = options.watermarkMode)
            }
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving && engine.queue.all { it.status == QueueStatus.DONE } }
        main {
            assertEquals(listOf(WatermarkMode.CLEAN, WatermarkMode.CLEAN), calls)
            assertTrue(engine.diagnostics.contains("batch_started requested=CLEAN default=CLEAN items=2"))
            assertTrue(engine.diagnostics.contains("requested=CLEAN actual=CLEAN"))
            engine.updateBatchWatermarkMode(WatermarkMode.ORIGINAL)
            engine.updateWatermarkMode(WatermarkMode.WATERMARKED)
            engine.downloadTask(engine.queue.first().key)
            assertFalse(engine.busy)
            assertEquals(2, calls.size)
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving && engine.queue.all { it.status == QueueStatus.DONE } }
        main {
            assertEquals(listOf(WatermarkMode.CLEAN, WatermarkMode.CLEAN), calls)
            assertEquals(2, parses)
            assertTrue(engine.message.contains("已保存 0 项") && engine.message.contains("已跳过 2 项"))
            assertFalse(engine.diagnostics.contains("requested=WATERMARKED"))
            assertFalse(engine.diagnostics.contains("requested=ORIGINAL"))
            val reloaded = reload(engine)
            try {
                assertEquals(WatermarkMode.CLEAN, reloaded.defaultWatermarkMode)
                assertEquals(WatermarkMode.CLEAN, reloaded.watermarkMode)
                assertEquals(WatermarkMode.CLEAN, reloaded.batchWatermarkMode)
            } finally { (field(reloaded, "scope") as CoroutineScope).cancel() }
        }
        assertEquals(3, records.history().size)
        assertEquals(original, records.history().last())
        assertTrue(records.history().filter { it.id != original.id }.all { it.watermarkMode == WatermarkMode.CLEAN })
    }

    @Test fun singleSavingUsesTheCleanSourceAndDoesNotSkipAnOlderMarkedRecord() = isolated { engine, records, original ->
        val clean = fixture(1)
        val marked = SavedVideo(clean.id, clean.title, "content://com.local.douyinsaver.fixture/marked", 42,
            watermarkMode = WatermarkMode.WATERMARKED)
        var savedMode: WatermarkMode? = null
        records.save(marked)
        main {
            engine.acceptShare(links.first())
            state(engine, "video", clean.copy(mediaSources = clean.mediaSources +
                MediaSource("https://v3-web.douyinvod.com/marked_fixture.mp4", WatermarkMode.WATERMARKED)))
            state(engine, "stage", TaskStage.READY)
            state(engine, "duplicateCandidates", listOf(marked))
            engine.updateWatermarkMode(WatermarkMode.WATERMARKED)
            assertNull(engine.duplicate)
            engine.queueSaveOverride = { result, options ->
                savedMode = options.watermarkMode
                assertEquals(clean.mediaUrl, WatermarkSources.select(result, options.watermarkMode).mediaUrl)
                SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/clean", 42,
                    watermarkMode = options.watermarkMode)
            }
            engine.download()
        }
        await { !engine.busy && engine.stage == TaskStage.DONE }
        main {
            assertEquals(WatermarkMode.CLEAN, savedMode)
            assertEquals(WatermarkMode.CLEAN, engine.duplicate?.watermarkMode)
        }
        assertEquals(3, records.history().size)
        assertTrue(records.history().contains(marked))
        assertTrue(records.history().contains(original))
    }

    @Test fun obsoleteVersionSettersDoNotWritePreferencesAndNewInputAlwaysUsesClean() = isolated { engine, _, original ->
        main {
            val preferences = application.getSharedPreferences("${field(engine, "namespace")}download_options", 0)
            val previousPreferences = preferences.all
            WatermarkMode.entries.forEach { previousMode ->
                engine.updateWatermarkMode(previousMode)
                engine.updateBatchWatermarkMode(previousMode)
                engine.setDefaultVersion(previousMode)
                assertEquals(WatermarkMode.CLEAN, engine.defaultWatermarkMode)
                assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
                assertEquals(WatermarkMode.CLEAN, engine.batchWatermarkMode)
            }
            engine.updateInput(links.first())
            engine.clearInput()
            assertEquals(WatermarkMode.CLEAN, engine.watermarkMode)
            assertEquals(previousPreferences, preferences.all)
            val reloaded = reload(engine)
            try {
                assertEquals(WatermarkMode.CLEAN, reloaded.watermarkMode)
                assertEquals(WatermarkMode.CLEAN, reloaded.batchWatermarkMode)
                assertEquals(listOf(original), reloaded.history)
            } finally { (field(reloaded, "scope") as CoroutineScope).cancel() }
        }
    }

    @Test fun legacyMarkedAndOriginalDefaultsAreIgnoredWithoutDeletingPreferencesOrRecords() = isolated { engine, records, original ->
        main {
            val preferences = application.getSharedPreferences("${field(engine, "namespace")}download_options", 0)
            for (previousMode in listOf(WatermarkMode.WATERMARKED, WatermarkMode.ORIGINAL)) {
                assertTrue(preferences.edit().putString("watermark_mode", previousMode.name)
                    .putString("default_watermark_mode", previousMode.name).commit())
                val previousPreferences = preferences.all
                val migrated = reload(engine)
                try {
                    assertEquals(WatermarkMode.CLEAN, migrated.defaultWatermarkMode)
                    assertEquals(WatermarkMode.CLEAN, migrated.watermarkMode)
                    assertEquals(WatermarkMode.CLEAN, migrated.batchWatermarkMode)
                    migrated.setDefaultVersion(previousMode)
                    assertEquals(previousPreferences, preferences.all)
                    assertEquals(listOf(original), migrated.history)
                } finally { (field(migrated, "scope") as CoroutineScope).cancel() }
            }
        }
        assertEquals(listOf(original), records.history())
    }

    @Test fun missingCleanAlbumImagesAndUnclassifiedVideoFailWithoutBlockingLaterWorks() = isolated { engine, records, original ->
        var saves = 0
        main {
            engine.queueParseOverride = { task ->
                val index = links.indexOf(task.source) + 1
                val result = when (index) {
                    1 -> fixture(1, album = true).let { album ->
                        val markedImage = "https://p3.douyinpic.com/marked_fixture.jpg"
                        album.copy(images = album.images + ParsedImage(markedImage, 640, 480,
                            listOf(MediaSource(markedImage, WatermarkMode.WATERMARKED),
                                MediaSource(markedImage, WatermarkMode.ORIGINAL))))
                    }
                    2 -> fixture(2).copy(mediaSources = emptyList())
                    else -> fixture(3)
                }
                complete(engine, result)
            }
            engine.queueSaveOverride = { _, _ -> saves++; error("Parsing must never save") }
            engine.enqueueInput(links.joinToString("\n"))
            engine.startQueue()
        }
        await { !engine.queueRunning && engine.queue.last().status == QueueStatus.READY }
        main {
            assertEquals(listOf(QueueStatus.FAILED, QueueStatus.FAILED, QueueStatus.READY), engine.queue.map { it.status })
            assertEquals(setOf(engine.queue.last().key), engine.queueResults.keys)
            assertTrue(engine.queue.first().message.contains("第 2 张图片"))
            assertFalse(engine.queue.any { it.message.contains("切换版本") })
            assertEquals(0, saves)
            assertNull(engine.video)
            assertNull(engine.selectedTaskKey)
        }
        assertEquals(listOf(original), records.history())
    }

    @Test fun removingHistoryRefreshesQueueAndSingleSnapshotAndNeverSkipsADeletedFile() = isolated { engine, records, original ->
        val saves = Collections.synchronizedList(mutableListOf<String>())
        listOf(false, true).forEach { deleteFiles -> withOwnReadableFile { uri ->
            val parsed = fixture(1)
            val saved = SavedVideo(parsed.id, parsed.title, uri.toString(), 4, watermarkMode = WatermarkMode.CLEAN)
            records.save(saved)
            main {
                engine.clearQueue()
                engine.updateInput(links.first())
                state(engine, "video", parsed)
                state(engine, "stage", TaskStage.READY)
                state(engine, "duplicateCandidates", listOf(saved))
                state(engine, "history", records.history())
                engine.queueParseOverride = { complete(engine, parsed, duplicates = listOf(saved)) }
                engine.enqueueInput(links.first())
                engine.startQueue()
            }
            await { !engine.queueRunning && engine.queueResults.size == 1 }
            main {
                engine.viewTaskResult(engine.queue.single().key)
                assertEquals(saved, engine.duplicate)
                engine.manageHistory(setOf(saved.uri), deleteFiles)
            }
            await { !engine.busy && engine.history.none { it.uri == saved.uri } }
            assertEquals(!deleteFiles, readable(uri))
            val beforeSaves = saves.size
            main {
                assertNull(engine.duplicate)
                assertTrue((field(engine, "queueDuplicates") as Map<*, *>).values.all { (it as List<*>).isEmpty() })
                engine.cancel() // Idle return restores the independent single-link snapshot.
                assertEquals(parsed, engine.video)
                assertNull(engine.selectedTaskKey)
                assertNull(engine.duplicate)
                engine.queueSaveOverride = { result, options ->
                    saves += result.id
                    SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/replaced_${UUID.randomUUID()}", 42,
                        watermarkMode = options.watermarkMode)
                }
                engine.downloadParsedQueue()
            }
            await { !engine.batchSaving && engine.queue.single().status == QueueStatus.DONE }
            main {
                assertEquals(beforeSaves + 1, saves.size)
                assertTrue(engine.message.contains("已保存 1 项") && engine.message.contains("已跳过 0 项"))
                assertTrue(engine.history.contains(original))
            }
        } }
    }

    @Test fun refreshingHistoryDropsUnreadableQueueFilesThatWereDeletedOutsideTheApp() = isolated { engine, records, _ ->
        withOwnReadableFile { uri ->
            val saved = SavedVideo(fixture(1).id, "自制文件", uri.toString(), 4, watermarkMode = WatermarkMode.CLEAN)
            records.save(saved)
            main {
                engine.queueParseOverride = { complete(engine, fixture(1), duplicates = listOf(saved)) }
                engine.enqueueInput(links.first())
                engine.startQueue()
            }
            await { !engine.queueRunning && engine.queueResults.size == 1 }
            assertEquals(1, application.contentResolver.delete(uri, null, null))
            main { engine.refreshHistory() }
            await { (field(engine, "queueDuplicates") as Map<*, *>).values.all { (it as List<*>).isEmpty() } }
            var saves = 0
            main {
                assertTrue(engine.history.any { it.uri == saved.uri })
                engine.queueSaveOverride = { result, options ->
                    saves++
                    SavedVideo(result.id, result.title, "content://com.local.douyinsaver.fixture/refreshed-replacement", 42,
                        watermarkMode = options.watermarkMode)
                }
                engine.downloadParsedQueue()
            }
            await { !engine.batchSaving && engine.queue.single().status == QueueStatus.DONE }
            main { assertEquals(1, saves); assertTrue(engine.message.contains("已跳过 0 项")) }
        }
    }

    @Test fun rejectedProbeHostSurvivesEngineDiagnosticsWhileSecretsNeverReachTheSavedSummary() = isolated { engine, records, original ->
        val unknownHost = "v1234567890-long-redirect-diagnostic-host.example"
        val sourceSignature = "source_signature_fixture_0123456789abcdef"
        val targetSignature = "redirect_signature_fixture_0123456789abcdef"
        val fakeCookie = "sessionid=fixture_cookie_secret"
        val source = "https://v3-web.douyinvod.com/diagnostic_fixture.mp4?signature=$sourceSignature"
        val target = "http://$unknownHost:8080/private_fixture_path.mp4?signature=$targetSignature#private_fragment"
        val requested = Collections.synchronizedList(mutableListOf<String>())
        lateinit var response: HttpURLConnection
        val trace = SaverEngine::class.java.getDeclaredMethod("trace", String::class.java)
            .apply { isAccessible = true }
        // Exercise the production recording boundary on its required main thread.
        // The parser's hard-coded probe factory is not replaced by this test.
        val recordEvent: (String) -> Unit = { event -> main { trace.invoke(engine, event) } }
        val probe = MediaProbe(connections = { uri ->
            requested += uri.toString()
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 302
                override fun getHeaderField(name: String?): String? =
                    target.takeIf { name.equals("Location", ignoreCase = true) }
            }.also { response = it }
        }, cookies = { fakeCookie })
        val content = fixture(1).copy(mediaUrl = source,
            mediaSources = listOf(MediaSource(source, WatermarkMode.CLEAN)))
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { probe.verify(content, recordEvent) }
        }
        assertEquals(listOf(source), requested)
        assertEquals(fakeCookie, response.getRequestProperty("Cookie"))
        assertFalse(failure.message.orEmpty().contains(targetSignature))
        main {
            val redirect = engine.diagnostics.lineSequence().single { it.contains("media_redirect") }
            assertTrue(redirect, redirect.contains("host=$unknownHost"))
            assertTrue(redirect, redirect.contains("scheme=http") && redirect.contains("port=8080"))
            assertTrue(redirect, redirect.contains("allowed=false"))
        }
        // Also exercise defensive recording of a raw failure excerpt, beyond the
        // probe's deliberately minimal redirect event. All secrets are synthetic.
        recordEvent("source_failure url=$target signature=$targetSignature\nCookie: $fakeCookie\n\tfixture_folded_cookie")
        main {
            val summary = application.getSharedPreferences("${field(engine, "namespace")}parse_diagnostics", 0)
                .getString("summary", null)
            assertEquals(engine.diagnostics, summary)
            val savedDiagnostic = checkNotNull(summary)
            assertTrue(savedDiagnostic.contains("host=$unknownHost"))
            for (secret in listOf(sourceSignature, targetSignature, fakeCookie, "fixture_folded_cookie",
                "private_fixture_path", "private_fragment", source, target)) {
                assertFalse("A secret escaped into persisted diagnostics: $secret", savedDiagnostic.contains(secret))
            }
            val reloaded = reload(engine)
            try { assertEquals(savedDiagnostic, reloaded.diagnostics) }
            finally { (field(reloaded, "scope") as CoroutineScope).cancel() }
            assertEquals(TaskStage.IDLE, engine.stage)
            assertTrue(engine.queue.isEmpty())
        }
        assertEquals(listOf(original), records.history())
    }

    private fun withOwnReadableFile(body: (Uri) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "yingxia-queue-test-${UUID.randomUUID()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/YingxiaValidation")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(application.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(application.contentResolver.openOutputStream(uri)).use { it.write(byteArrayOf(1, 2, 3, 4)) }
            assertTrue(readable(uri))
            body(uri)
        } finally {
            // The test may deliberately remove this row itself. Android no longer
            // grants ownership-based delete access after that row is gone.
            if (readable(uri)) application.contentResolver.delete(uri, null, null)
        }
    }
    private fun readable(uri: Uri): Boolean = runCatching {
        application.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun reload(engine: SaverEngine): SaverEngine = SaverEngine::class.java
        .getDeclaredConstructor(Application::class.java, String::class.java).apply { isAccessible = true }
        .newInstance(application, field(engine, "namespace") as String)

    private fun field(engine: SaverEngine, name: String): Any? = SaverEngine::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(engine)

    private fun complete(engine: SaverEngine, result: ParsedVideo, generation: Int = engine.generation,
        duplicates: List<SavedVideo> = emptyList()) = SaverEngine::class.java
        .getDeclaredMethod("acceptVerifiedResult", Int::class.javaPrimitiveType, ParsedVideo::class.java, List::class.java)
        .apply { isAccessible = true }.invoke(engine, generation, result, duplicates)

    private fun isolated(body: (SaverEngine, DownloadRecords, SavedVideo) -> Unit) {
        val prefix = "queue_validation_${UUID.randomUUID()}_"
        val records = DownloadRecords(application, prefix)
        val original = SavedVideo("previous", "既有记录", "content://com.local.douyinsaver.fixture/previous", 40)
        records.save(original)
        lateinit var engine: SaverEngine
        main { engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(application, prefix) }
        try { body(engine, records, original) } finally {
            main {
                val scope = SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope
                scope.cancel()
            }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics").forEach {
                application.deleteSharedPreferences(prefix + it)
            }
        }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> state(engine: SaverEngine, name: String, value: T) {
        val delegate = SaverEngine::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(engine)
        (delegate as MutableState<T>).value = value
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(20)
        }
        main { assertTrue("The isolated queue did not reach its expected state", condition()) }
    }
}
