package com.local.douyinsaver

import android.app.Application
import android.os.SystemClock
import android.widget.FrameLayout
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

/** Isolated Engine state tests: every resolution and save is a fixture, with no WebView or network. */
@RunWith(AndroidJUnit4::class)
class AlbumMotionEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    @Test fun unavailableAndFailedReadsKeepTheReadyAlbumAndItsIndependentOptions() = isolated { engine, records, old ->
        val base = album(9)
        val neighbour = album(2)
        val callbacks = mutableListOf<(DesktopAlbumResult) -> Unit>()
        main {
            seedReady(engine, base)
            state(engine, "queueResults", mapOf("neighbour" to neighbour))
            engine.fileName = "保留原来的名称"
            state(engine, "downloaded", 123L)
            state(engine, "total", 456L)
            engine.gifStartSeconds = 1f
            engine.gifDurationSeconds = 4f
            engine.albumMotionResolveOverride = { id, callback ->
                assertEquals(base.id, id)
                callbacks += callback
            }
            assertEquals("IDLE", engine.albumMotionState().phase.name)
            engine.saveAlbumMotion()
            assertEquals("READING", engine.albumMotionState().phase.name)
            assertSame(base, engine.video)
            assertSame(neighbour, engine.queueResults["neighbour"])
            callbacks.last()(DesktopAlbumResult(DesktopAlbumStatus.UNAVAILABLE, reason = "fixture unavailable"))
            assertEquals("UNAVAILABLE", engine.albumMotionState().phase.name)
            assertEquals(TaskStage.READY, engine.stage)
            engine.saveAlbumMotion()
            callbacks.last()(DesktopAlbumResult(DesktopAlbumStatus.FAILED, reason = "fixture failed"))
            assertEquals("UNAVAILABLE", engine.albumMotionState().phase.name)
            assertSame(base, engine.video)
            assertSame(neighbour, engine.queueResults["neighbour"])
            assertEquals("保留原来的名称", engine.fileName)
            assertEquals(123L, engine.downloaded)
            assertEquals(456L, engine.total)
            assertEquals(1f, engine.gifStartSeconds, 0f)
            assertEquals(4f, engine.gifDurationSeconds, 0f)
            assertEquals(AlbumMode.IMAGES, engine.albumMode)
            assertEquals(listOf(old), records.history())
            assertFalse(engine.busy)
        }
    }

    @Test fun aCompleteCandidateEnrichesOnlyItsRequestedQueueWorkAndRestoresTheSingleResult() = isolated { engine, records, old ->
        val single = album(9)
        val first = album(1)
        val second = album(2)
        val target = "target"
        val neighbour = "neighbour"
        val largerDesktopArray = live(first)
        val extraUrl = "https://p3.douyinpic.com/motion_engine_${first.id}_extra.webp"
        val candidate = largerDesktopArray.copy(images = largerDesktopArray.images +
            ParsedImage(extraUrl, 640, 480, listOf(MediaSource(extraUrl, WatermarkMode.CLEAN)), imageKey = "${first.id}-extra"))
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        var neighbourDetail = ""
        main {
            seedReady(engine, single)
            seedQueue(engine, listOf(target to first, neighbour to second))
            engine.albumMotionResolveOverride = { id, callback ->
                if (id == second.id) callback(DesktopAlbumResult(DesktopAlbumStatus.UNAVAILABLE, reason = "neighbour unavailable"))
                else { assertEquals(first.id, id); complete = callback }
            }
            engine.queueSaveOverride = { content, options ->
                received += content to options
                saved(content, options)
            }
            engine.saveAlbumMotion(neighbour)
            assertEquals("UNAVAILABLE", engine.albumMotionState(neighbour).phase.name)
            neighbourDetail = engine.albumMotionState(neighbour).detail
            engine.saveAlbumMotion(target)
            assertSame(single, engine.video)
            assertSame(first, engine.queueResults[target])
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertEquals(AlbumMode.IMAGES, received.single().second.albumMode)
            assertEquals(WatermarkMode.CLEAN, received.single().second.watermarkMode)
            assertEquals(candidate.id, received.single().first.id)
            assertEquals(candidate.images.size, received.single().first.images.size)
            assertNotNull(received.single().first.images.first().motion)
            assertSame(single, engine.video)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.queueResults[target]))
            assertTrue(engine.queueResults[target]!!.images.none { image -> second.images.any { it.imageKey == image.imageKey || it.url == image.url } })
            assertSame(second, engine.queueResults[neighbour])
            assertEquals("UNAVAILABLE", engine.albumMotionState(neighbour).phase.name)
            assertEquals(neighbourDetail, engine.albumMotionState(neighbour).detail)
            assertEquals(listOf(old), records.history().filter { it.id == old.id })
            assertEquals(2, records.history().size)
        }
    }

    @Test fun readyAnimatedGifAndWebpHonorExplicitGifChoiceWithoutADesktopRead() = isolated { engine, records, old ->
        var resolveCalls = 0
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            engine.albumMotionResolveOverride = { _, _ -> resolveCalls++; error("A ready animation must not read the desktop page") }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
        }
        listOf("image/gif" to "gif", "image/webp" to "webp").forEachIndexed { index, (mime, extension) ->
            val static = album(index + 1)
            val url = "https://p3.douyinpic.com/original_engine_animation_$index.$extension?signature=fixture"
            val animated = static.copy(images = static.images.mapIndexed { imageIndex, image ->
                if (imageIndex == 0) ParsedImage(url, image.width, image.height,
                    listOf(MediaSource(url, WatermarkMode.CLEAN)), AlbumAssetKind.ANIMATED, mime) else image
            })
            main {
                seedReady(engine, animated)
                engine.albumMode = AlbumMode.GIF
                engine.saveAlbumMotion(mode = AlbumMode.GIF)
                assertEquals(0, resolveCalls)
            }
            await { received.size == index + 1 && !engine.busy }
            main {
                val (content, options) = received[index]
                assertSame(animated, content)
                assertSame(animated, engine.video)
                assertEquals(AlbumMode.GIF, options.albumMode)
                assertEquals(WatermarkMode.CLEAN, options.watermarkMode)
                assertEquals(url, content.images.first().url)
                assertEquals(mime, content.images.first().mimeType)
                assertEquals(AlbumAssetKind.ANIMATED, content.images.first().kind)
                assertTrue(content.images.none { it.kind == AlbumAssetKind.LIVE || it.motion != null })
                assertEquals(static.images.last(), content.images.last())
                assertEquals("AVAILABLE", engine.albumMotionState().phase.name)
                assertEquals(0, resolveCalls)
            }
        }
        assertEquals(3, records.history().size)
        assertEquals(old, records.history().last())
    }

    @Test fun knownMotionSourcesHonorTheFormatOnBothSingleAndBatchCardsWithoutARead() = isolated { engine, records, old ->
        val single = album(9)
        val target = live(album(1))
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        var reads = 0
        main {
            engine.albumMotionResolveOverride = { _, _ -> reads++; error("A known motion needs no desktop read") }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
        }
        listOf(false to AlbumMode.IMAGES, false to AlbumMode.GIF, true to AlbumMode.IMAGES, true to AlbumMode.GIF)
            .forEachIndexed { index, (batch, mode) ->
                main {
                    seedReady(engine, if (batch) single else target)
                    if (batch) seedQueue(engine, listOf("target" to target))
                    engine.saveAlbumMotion(if (batch) "target" else null, force = true, mode = mode)
                }
                await { received.size == index + 1 && !engine.busy }
                main {
                    val (content, options) = received[index]
                    assertSame(target, content)
                    assertEquals(mode, options.albumMode)
                    assertEquals(WatermarkMode.CLEAN, options.watermarkMode)
                    assertSame(target.images.first().motion, content.images.first().motion)
                    assertEquals(640, content.images.first().motion?.width)
                    assertEquals(480, content.images.first().motion?.height)
                    assertEquals(2.0, content.images.first().motion?.durationSeconds ?: 0.0, 0.0)
                    assertSame(if (batch) single else target, engine.video)
                    if (batch) assertSame(target, engine.queueResults["target"])
                    assertEquals(0, reads)
                    assertFalse(engine.albumMotionState(if (batch) "target" else null).detail.contains("GIF") && mode != AlbumMode.GIF)
                }
            }
        assertEquals(old, records.history().last())
    }

    @Test fun newlyReadOriginalAndGifExportsHaveIndependentDuplicateChecks() = isolated { engine, records, old ->
        val base = album(1)
        val candidate = live(base)
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedReady(engine, base)
            engine.albumMotionResolveOverride = { _, callback -> callback(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate)) }
            engine.queueSaveOverride = { content, options ->
                received += content to options
                savedAlbum(content, options, received.size)
            }
            engine.saveAlbumMotion(mode = AlbumMode.GIF)
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertEquals(AlbumMode.GIF, received.first().second.albumMode)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.video))
            engine.saveAlbumMotion()
        }
        await { received.size == 2 && !engine.busy }
        main {
            assertEquals(AlbumMode.IMAGES, received.last().second.albumMode)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.video))
            engine.saveAlbumMotion(mode = AlbumMode.GIF)
            assertFalse(engine.busy)
            assertTrue(engine.message.contains("跳过重复"))
            engine.saveAlbumMotion()
            assertFalse(engine.busy)
            assertTrue(engine.message.contains("跳过重复"))
            assertEquals(2, received.size)
            engine.saveAlbumMotion(force = true)
        }
        await { received.size == 3 && !engine.busy }
        main {
            assertEquals(AlbumMode.IMAGES, received.last().second.albumMode)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.video))
            assertEquals(4, records.history().size)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun aPendingReadKeepsItsRequestedGifFormatAcrossVerification() = isolated { engine, records, old ->
        val base = album(1)
        val candidate = live(base)
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedReady(engine, base)
            engine.albumMotionResolveOverride = { _, callback -> complete = callback }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
            engine.saveAlbumMotion(mode = AlbumMode.GIF)
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION))
            engine.openAlbumMotionVerification()
            engine.albumMode = AlbumMode.IMAGES
            engine.continueAlbumMotionVerification()
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertEquals(AlbumMode.GIF, received.single().second.albumMode)
            assertSame(candidate, received.single().first)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.video))
            assertEquals(old, records.history().last())
        }
    }

    @Test fun explicitGifRemainsAvailableInFailedShareRecovery() = isolated { engine, records, old ->
        val candidate = live(album(1))
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedFailedShare(engine, candidate.id)
            engine.albumMotionResolveOverride = { _, callback -> callback(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate)) }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
            engine.saveFailedShareAlbumMotion(mode = AlbumMode.GIF)
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertEquals(AlbumMode.GIF, received.single().second.albumMode)
            assertSame(candidate, engine.video)
            assertEquals(2, records.history().size)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun coversModeUsesTheSeparateDownloadActionAndCannotStartAMotionRead() = isolated { engine, records, old ->
        var reads = 0
        var saves = 0
        main {
            engine.albumMotionResolveOverride = { _, _ -> reads++ }
            engine.queueSaveOverride = { content, options -> saves++; saved(content, options) }
            seedReady(engine, album(1))
            engine.saveAlbumMotion(mode = AlbumMode.COVERS)
            assertEquals(TaskStage.READY, engine.stage)
            assertFalse(engine.busy)
            seedFailedShare(engine, album(1).id)
            engine.saveFailedShareAlbumMotion(mode = AlbumMode.COVERS)
            assertEquals(TaskStage.FAILED, engine.stage)
            assertFalse(engine.busy)
            assertEquals(0, reads)
            assertEquals(0, saves)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun incompleteWrongWorkAndUnclassifiedCandidatesNeverReachSaving() = isolated { engine, records, old ->
        val base = album(1)
        val saveCalls = Collections.synchronizedList(mutableListOf<ParsedVideo>())
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        main {
            seedReady(engine, base)
            engine.albumMotionResolveOverride = { _, callback -> complete = callback }
            engine.queueSaveOverride = { content, options -> saveCalls += content; saved(content, options) }
            val candidate = live(base)
            val unknown = candidate.copy(images = candidate.images.map { image ->
                image.copy(motion = image.motion?.let { motion ->
                    motion.copy(mediaSources = listOf(MediaSource(motion.url, WatermarkMode.ORIGINAL)))
                })
            })
            listOf(candidate.copy(id = album(2).id), candidate.copy(images = candidate.images.take(1)), base, unknown)
                .forEach { rejected ->
                    engine.saveAlbumMotion()
                    checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, rejected))
                    assertEquals("UNAVAILABLE", engine.albumMotionState().phase.name)
                    assertSame(base, engine.video)
                    assertEquals(TaskStage.READY, engine.stage)
                    assertFalse(engine.busy)
                }
            assertTrue(saveCalls.isEmpty())
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun aCancelledNonceCannotCompleteOrCancelTheNextReadOfTheSameWork() = isolated { engine, records, old ->
        val base = album(1)
        val callbacks = mutableListOf<(DesktopAlbumResult) -> Unit>()
        main {
            seedReady(engine, base)
            engine.albumMotionResolveOverride = { _, callback -> callbacks += callback }
            engine.saveAlbumMotion()
            val generation = engine.generation
            engine.cancelAlbumMotion()
            assertSame(base, engine.video)
            assertEquals(TaskStage.READY, engine.stage)
            engine.saveAlbumMotion()
            assertEquals(generation, engine.generation)
            assertEquals(2, callbacks.size)
            callbacks.first()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(base)))
            callbacks.first()(DesktopAlbumResult(DesktopAlbumStatus.FAILED, reason = "late failure"))
            callbacks.first()(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION, reason = "late gate"))
            assertEquals("READING", engine.albumMotionState().phase.name)
            assertSame(base, engine.video)
            assertEquals(listOf(old), records.history())
            callbacks.last()(DesktopAlbumResult(DesktopAlbumStatus.UNAVAILABLE, reason = "current unavailable"))
            assertEquals("UNAVAILABLE", engine.albumMotionState().phase.name)
            assertFalse(engine.busy)
        }
    }

    @Test fun clearingTheQueueAndChangingTheInputRetireLateMotionCallbacks() = isolated { engine, records, old ->
        val single = album(9)
        val queued = album(1)
        val target = "target"
        val callbacks = mutableListOf<(DesktopAlbumResult) -> Unit>()
        main {
            seedReady(engine, single)
            seedQueue(engine, listOf(target to queued))
            engine.albumMotionResolveOverride = { _, callback -> callbacks += callback }
            engine.saveAlbumMotion(target)
            engine.clearQueue()
            callbacks.first()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(queued)))
            assertTrue(engine.queue.isEmpty())
            assertTrue(engine.queueResults.isEmpty())
            assertEquals("IDLE", engine.albumMotionState(target).phase.name)
            assertSame(single, engine.video)
            engine.saveAlbumMotion()
            engine.updateInput("https://www.douyin.com/note/${album(2).id}")
            seedReady(engine, album(2))
            callbacks.last()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(single)))
            assertEquals(album(2), engine.video)
            engine.saveAlbumMotion()
            engine.clearInput()
            callbacks.last()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(album(2))))
            assertNull(engine.video)
            assertTrue(engine.input.isBlank())
            assertEquals(listOf(old), records.history())
            assertFalse(engine.busy)
        }
    }

    @Test fun removingTheRequestedTaskCannotResurrectItWithALateCandidate() = isolated { engine, records, old ->
        val single = album(9)
        val queued = album(1)
        val neighbour = album(2)
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        main {
            seedReady(engine, single)
            seedQueue(engine, listOf("target" to queued, "neighbour" to neighbour))
            engine.albumMotionResolveOverride = { _, callback -> complete = callback }
            engine.saveAlbumMotion("target")
            engine.removeTask("target")
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(queued)))
            assertEquals(listOf("neighbour"), engine.queue.map { it.key })
            assertEquals(setOf("neighbour"), engine.queueResults.keys)
            assertSame(neighbour, engine.queueResults["neighbour"])
            assertSame(single, engine.video)
            assertEquals("IDLE", engine.albumMotionState("target").phase.name)
            assertFalse(engine.busy)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun generationQueueEpochAndBaseReferenceEachGuardAnOtherwiseCurrentNonce() = isolated { engine, records, old ->
        val base = album(1)
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        val saves = Collections.synchronizedList(mutableListOf<ParsedVideo>())
        main {
            engine.albumMotionResolveOverride = { _, callback -> complete = callback }
            engine.queueSaveOverride = { content, options -> saves += content; saved(content, options) }
            listOf("generation", "queueEpoch", "baseReference").forEach { guard ->
                seedReady(engine, base)
                engine.saveAlbumMotion()
                val retained = when (guard) {
                    "generation" -> { state(engine, "generation", engine.generation + 1); base }
                    "queueEpoch" -> {
                        val field = SaverEngine::class.java.getDeclaredField("queueEpoch").apply { isAccessible = true }
                        field.setInt(engine, field.getInt(engine) + 1)
                        base
                    }
                    else -> base.copy(title = base.title + " updated").also { replacement ->
                        assertNotEquals(base, replacement)
                        assertNotSame(base, replacement)
                        state(engine, "video", replacement)
                    }
                }
                checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(base)))
                assertSame(retained, engine.video)
                assertEquals(TaskStage.READY, engine.stage)
                assertTrue(saves.isEmpty())
                engine.cancelAlbumMotion()
            }
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun detachingOnlyTheActiveHostRetiresItsReadAndPreservesTheOriginalAlbum() = isolated { engine, records, old ->
        val base = album(1)
        val callbacks = mutableListOf<(DesktopAlbumResult) -> Unit>()
        main {
            seedReady(engine, base)
            val activeHost = FrameLayout(application)
            val otherHost = FrameLayout(application)
            engine.attachParserHost(activeHost)
            engine.albumMotionResolveOverride = { _, callback -> callbacks += callback }
            engine.saveAlbumMotion()
            engine.detachParserHost(otherHost)
            assertEquals("READING", engine.albumMotionState().phase.name)
            engine.detachParserHost(activeHost)
            assertFalse(engine.busy)
            assertSame(base, engine.video)
            callbacks.single()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(base)))
            callbacks.single()(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION, reason = "detached gate"))
            assertNotEquals("READING", engine.albumMotionState().phase.name)
            assertNotEquals("NEEDS_VERIFICATION", engine.albumMotionState().phase.name)
            assertSame(base, engine.video)
            assertEquals(TaskStage.READY, engine.stage)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun aVerificationGateCanBeCancelledWithoutDiscardingTheReadyImages() = isolated { engine, records, old ->
        val base = album(1)
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        main {
            seedReady(engine, base)
            engine.albumMotionResolveOverride = { _, callback -> complete = callback }
            engine.saveAlbumMotion()
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION, reason = "fixture gate"))
            assertEquals("NEEDS_VERIFICATION", engine.albumMotionState().phase.name)
            assertSame(base, engine.video)
            engine.openAlbumMotionVerification()
            assertTrue(engine.albumMotionVerificationVisible)
            assertSame(base, engine.video)
            engine.continueAlbumMotionVerification()
            assertFalse(engine.albumMotionVerificationVisible)
            assertEquals("READING", engine.albumMotionState().phase.name)
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION, reason = "fixture second gate"))
            engine.openAlbumMotionVerification()
            assertTrue(engine.albumMotionVerificationVisible)
            engine.cancel()
            assertFalse(engine.albumMotionVerificationVisible)
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(base)))
            assertSame(base, engine.video)
            assertEquals(TaskStage.READY, engine.stage)
            assertFalse(engine.busy)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun aSynchronousReadFailureStillAllowsTheSingleAlbumToSaveAsOriginalImages() = isolated { engine, records, old ->
        val base = album(1)
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedReady(engine, base)
            engine.albumMotionResolveOverride = { _, callback ->
                callback(DesktopAlbumResult(DesktopAlbumStatus.FAILED, reason = "synchronous fixture failure"))
            }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
            engine.saveAlbumMotion()
            assertEquals("UNAVAILABLE", engine.albumMotionState().phase.name)
            assertEquals(TaskStage.READY, engine.stage)
            engine.download()
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertSame(base, received.single().first)
            assertSame(base, engine.video)
            assertEquals(AlbumMode.IMAGES, received.single().second.albumMode)
            assertEquals(WatermarkMode.CLEAN, received.single().second.watermarkMode)
            assertEquals(2, records.history().size)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun aFailedMotionReadStillAllowsAnOriginalImageBatchToContinuePastSaveFailure() = isolated { engine, records, old ->
        val single = album(9)
        val first = album(1)
        val second = album(2)
        val target = "target"
        val neighbour = "neighbour"
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        main {
            seedReady(engine, single)
            seedQueue(engine, listOf(target to first, neighbour to second))
            engine.albumMotionResolveOverride = { _, callback -> complete = callback }
            engine.saveAlbumMotion(target)
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.UNAVAILABLE, reason = "no motion"))
            engine.queueSaveOverride = { content, options ->
                calls += content.id
                assertEquals(AlbumMode.IMAGES, options.albumMode)
                assertFalse(content.hasDynamicAlbumAssets)
                if (content.id == first.id) error("fixture first save failure")
                saved(content, options)
            }
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving && calls.size == 2 }
        main {
            assertEquals(listOf(first.id, second.id), calls.toList())
            assertEquals(QueueStatus.FAILED, engine.queue.first().status)
            assertEquals(QueueStatus.DONE, engine.queue.last().status)
            assertSame(first, engine.queueResults[target])
            assertSame(second, engine.queueResults[neighbour])
            assertSame(single, engine.video)
            assertEquals(2, records.history().size)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun aKnownFailedShareCanRecoverACompleteCandidateAndSaveOriginalMotionByDefault() = isolated { engine, records, old ->
        val candidate = live(album(1))
        val neighbour = album(2)
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedFailedShare(engine, candidate.id)
            state(engine, "queueResults", mapOf("neighbour" to neighbour))
            engine.albumMotionResolveOverride = { id, callback ->
                assertEquals(candidate.id, id)
                callback(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
            engine.saveFailedShareAlbumMotion()
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertSame(candidate, received.single().first)
            assertSame(candidate, engine.video)
            assertEquals(AlbumMode.IMAGES, received.single().second.albumMode)
            assertEquals(WatermarkMode.CLEAN, received.single().second.watermarkMode)
            assertEquals("AVAILABLE", engine.albumMotionState().phase.name)
            assertEquals(TaskStage.DONE, engine.stage)
            assertSame(neighbour, engine.queueResults["neighbour"])
            assertEquals(2, records.history().size)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun failedShareUnavailableFailureAndCancellationKeepTheFailureWithoutHistory() = isolated { engine, records, old ->
        val candidate = live(album(1))
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        main {
            seedFailedShare(engine, candidate.id)
            val failedMessage = engine.message
            engine.albumMotionResolveOverride = { _, callback -> complete = callback }
            listOf(DesktopAlbumStatus.UNAVAILABLE, DesktopAlbumStatus.FAILED).forEach { status ->
                engine.saveFailedShareAlbumMotion()
                assertEquals("READING", engine.albumMotionState().phase.name)
                assertEquals(TaskStage.FAILED, engine.stage)
                assertNull(engine.video)
                checkNotNull(complete)(DesktopAlbumResult(status, reason = "fixture failed share unavailable"))
                assertEquals("UNAVAILABLE", engine.albumMotionState().phase.name)
                assertEquals(TaskStage.FAILED, engine.stage)
                assertEquals(failedMessage, engine.message)
                assertEquals(candidate.id, engine.browsingId)
                assertNull(engine.video)
                assertFalse(engine.busy)
            }
            engine.saveFailedShareAlbumMotion()
            engine.cancel()
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            assertEquals(TaskStage.FAILED, engine.stage)
            assertEquals(failedMessage, engine.message)
            assertNull(engine.video)
            assertFalse(engine.busy)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun unknownFailedShareIdsAndLateResultsAfterClearInputCannotStartRecovery() = isolated { engine, records, old ->
        val candidate = live(album(1))
        var complete: ((DesktopAlbumResult) -> Unit)? = null
        var resolveCalls = 0
        main {
            engine.updateInput("https://www.douyin.com/note/${candidate.id}")
            seedFailedShare(engine, null)
            engine.albumMotionResolveOverride = { _, callback -> resolveCalls++; complete = callback }
            engine.saveFailedShareAlbumMotion()
            assertEquals(0, resolveCalls)
            assertEquals(TaskStage.FAILED, engine.stage)
            assertNull(engine.video)
            assertFalse(engine.busy)
            state(engine, "browsingId", candidate.id)
            engine.saveFailedShareAlbumMotion()
            assertEquals(1, resolveCalls)
            assertTrue(engine.busy)
            engine.clearInput()
            checkNotNull(complete)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            assertTrue(engine.input.isBlank())
            assertNull(engine.browsingId)
            assertNull(engine.video)
            assertEquals(TaskStage.IDLE, engine.stage)
            assertFalse(engine.busy)
            engine.saveFailedShareAlbumMotion()
            assertEquals(1, resolveCalls)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun checkingDynamicSourcesOnlyEnrichesTheMatchingSingleWorkAndDoesNotSave() = isolated { engine, records, old ->
        val base = album(1)
        val candidate = live(base).copy(images = live(base).images.map { image ->
            if (image.motion != null) image.copy(kind = AlbumAssetKind.DYNAMIC) else image
        })
        val neighbour = album(2)
        var reads = 0; var saves = 0
        main {
            seedReady(engine, base)
            state(engine, "queueResults", mapOf("neighbour" to neighbour))
            engine.itemDurationSeconds = 1.7
            engine.albumMotionResolveOverride = { owner, callback ->
                reads++; assertEquals(base.id, owner)
                callback(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            }
            engine.queueSaveOverride = { content, options -> saves++; savedAlbum(content, options, saves) }
            engine.checkAlbumMotion()
            assertEquals(1, reads); assertEquals(0, saves)
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertEquals(TaskStage.READY, engine.stage); assertFalse(engine.busy)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.video))
            assertSame(neighbour, engine.queueResults["neighbour"])
            assertEquals(1.7, checkNotNull(engine.itemDurationSeconds), 0.0)
            assertEquals(listOf(old), records.history())
            engine.saveAlbumMotion()
            assertFalse(engine.busy); assertEquals(0, saves)
            assertTrue(engine.message.contains("请选择保存为实况照片或无声动图"))
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun checkingQueueDynamicSourcesLeavesItsNeighborAndSingleWorkUntouchedWithoutSaving() = isolated { engine, records, old ->
        val single = album(9); val target = album(1); val neighbour = album(2)
        val candidate = live(target).copy(images = live(target).images.reversed())
        var saves = 0
        main {
            seedReady(engine, single)
            seedQueue(engine, listOf("target" to target, "neighbour" to neighbour))
            engine.albumMotionResolveOverride = { owner, callback ->
                assertEquals(target.id, owner); callback(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            }
            engine.queueSaveOverride = { content, options -> saves++; savedAlbum(content, options, saves) }
            engine.checkAlbumMotion("target")
            assertFalse(engine.busy); assertEquals(0, saves)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.queueResults["target"]))
            assertSame(neighbour, engine.queueResults["neighbour"]); assertSame(single, engine.video)
            assertEquals(QueueStatus.READY, engine.queue.first().status)
            assertEquals(QueueStatus.READY, engine.queue.last().status)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun unknownDynamicRequiresAChoiceOnSingleAndQueueCardsAndRetainsKnownTypes() = isolated { engine, records, old ->
        val single = album(9)
        val base = live(album(1))
        val unknown = base.copy(images = base.images + base.images.last().copy(
            url = "https://p3.douyinpic.com/unknown_owned_extra.webp",
            mediaSources = listOf(MediaSource("https://p3.douyinpic.com/unknown_owned_extra.webp", WatermarkMode.CLEAN)),
            imageKey = "${base.id}-unknown-extra",
            kind = AlbumAssetKind.DYNAMIC, motion = base.images.first().motion))
        val neighbor = album(2)
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            engine.albumMotionResolveOverride = { _, _ -> error("Ready owned motion must not read another website") }
            engine.queueSaveOverride = { content, options -> received += content to options; savedAlbum(content, options, received.size) }
        }
        listOf(false to AlbumMode.LIVE_PHOTOS, true to AlbumMode.MOTION_VIDEOS).forEachIndexed { index, (queued, chosen) ->
            main {
                seedReady(engine, if (queued) single else unknown)
                if (queued) seedQueue(engine, listOf("target" to unknown, "neighbor" to neighbor))
                engine.saveAlbumMotion(if (queued) "target" else null)
                assertFalse(engine.busy); assertEquals(index, received.size)
                assertTrue(engine.message.contains("请选择保存为实况照片或无声动图"))
                engine.saveAlbumMotion(if (queued) "target" else null, force = true, mode = chosen)
            }
            await { received.size == index + 1 && !engine.busy }
            main {
                val (content, options) = received[index]
                assertEquals(chosen, options.albumMode)
                assertAlbumCorrespondence(unknown, content)
                assertEquals(listOf(AlbumAssetKind.LIVE, AlbumAssetKind.STATIC, AlbumAssetKind.DYNAMIC), content.images.map { it.kind })
                if (queued) {
                    assertSame(single, engine.video)
                    assertAlbumCorrespondence(unknown, checkNotNull(engine.queueResults["target"]))
                    assertSame(neighbor, engine.queueResults["neighbor"])
                }
            }
        }
        main { assertEquals(3, records.history().size); assertEquals(old, records.history().last()) }
    }

    @Test fun selectedPhotoFollowsItsExactIdentityAfterDesktopArrayReordering() = isolated { engine, records, old ->
        val base = album(1)
        val candidate = live(base).copy(images = live(base).images.reversed())
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedReady(engine, base)
            engine.albumMotionResolveOverride = { owner, callback ->
                assertEquals(base.id, owner); callback(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            }
            engine.queueSaveOverride = { content, options -> received += content to options; savedAlbum(content, options, received.size) }
            engine.saveAlbumMotion(selectedImageIndices = listOf(0))
        }
        await { received.size == 1 && !engine.busy }
        main {
            val (selected, options) = received.single()
            assertEquals(listOf(1), options.selectedImageIndices)
            assertEquals(base.images.first().imageKey, selected.images[options.selectedImageIndices.single()].imageKey)
            assertEquals(base.images.first().url, selected.images[options.selectedImageIndices.single()].url)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.video))
            assertEquals(2, records.history().size); assertEquals(old, records.history().last())
        }
    }

    @Test fun failedShareCheckRecoversKnownLiveAsReadyWithoutSavingUntilUserRequestsIt() = isolated { engine, records, old ->
        val candidate = live(album(1)); val neighbor = album(2)
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedFailedShare(engine, candidate.id)
            state(engine, "queueResults", mapOf("neighbor" to neighbor))
            engine.albumMotionResolveOverride = { id, callback ->
                assertEquals(candidate.id, id); callback(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            }
            engine.queueSaveOverride = { content, options -> received += content to options; savedAlbum(content, options, received.size) }
            engine.saveFailedShareAlbumMotion(saveOnReady = false)
            assertFalse(engine.busy); assertEquals(TaskStage.READY, engine.stage)
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertAlbumCorrespondence(candidate, checkNotNull(engine.video))
            assertSame(neighbor, engine.queueResults["neighbor"])
            assertTrue(received.isEmpty()); assertEquals(listOf(old), records.history())
            engine.saveAlbumMotion()
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertAlbumCorrespondence(candidate, received.single().first)
            val record = records.history().first { it.id == candidate.id }
            assertEquals(1, record.albumAssets.count { it.embeddedMotion })
            assertTrue(record.albumAssets.all { it.motionUri.isBlank() })
            assertEquals(candidate.images.size, record.uris.size)
            assertSame(neighbor, engine.queueResults["neighbor"])
            assertEquals(old, records.history().last())
        }
    }

    @Test fun parsingAutomaticallyReadsTheSameAlbumAndPromotesMotionWithoutSaving() = isolated { engine, records, old ->
        val base = album(1)
        val enriched = live(base)
        var reads = 0
        var callback: ((DesktopAlbumResult) -> Unit)? = null
        main {
            engine.albumMotionResolveOverride = { id, complete ->
                assertEquals(base.id, id); reads++; callback = complete
            }
            acceptParsed(engine, base)
            assertEquals(1, reads)
            assertEquals(AlbumMotionPhase.READING, engine.albumMotionState().phase)
            assertTrue(engine.busy)
            checkNotNull(callback)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, enriched))
            assertAlbumCorrespondence(enriched, checkNotNull(engine.video))
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertFalse(engine.busy)
            assertEquals(TaskStage.READY, engine.stage)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun automaticVerificationOrTimeoutKeepsCoversAndReleasesTheRead() = isolated { engine, records, old ->
        listOf(DesktopAlbumStatus.NEEDS_VERIFICATION, DesktopAlbumStatus.UNAVAILABLE).forEachIndexed { index, status ->
            val base = album(index + 1)
            main {
                engine.albumMotionResolveOverride = { _, complete -> complete(DesktopAlbumResult(status, reason = "fixture timeout")) }
                acceptParsed(engine, base)
                assertSame(base, engine.video)
                assertEquals(TaskStage.READY, engine.stage)
                assertEquals(AlbumMotionPhase.UNAVAILABLE, engine.albumMotionState().phase)
                assertFalse(engine.busy)
                assertFalse(engine.albumMotionVerificationVisible)
                assertEquals(listOf(old), records.history())
            }
        }
    }

    @Test fun declaredLiveWithoutAMobileClipAutomaticallyRecoversWithoutSavingItsCoverAsLive() = isolated { engine, records, old ->
        val base = album(1).let { source -> source.copy(images = source.images.mapIndexed { index, image ->
            if (index == 0) image.copy(kind = AlbumAssetKind.LIVE) else image
        }) }
        val enriched = live(base)
        var reads = 0
        var callback: ((DesktopAlbumResult) -> Unit)? = null
        main {
            engine.albumMotionResolveOverride = { id, complete ->
                assertEquals(base.id, id); reads++; callback = complete
            }
            engine.queueSaveOverride = { _, _ -> error("Parsing a missing live clip must never save a still cover") }
            assertFalse(WatermarkSources.available(base, WatermarkMode.CLEAN))
            acceptParsed(engine, base)
            assertEquals(1, reads)
            assertSame(base, engine.video)
            assertEquals(AlbumAssetKind.LIVE, engine.video!!.images.first().kind)
            assertNull(engine.video!!.images.first().motion)
            assertEquals(AlbumMotionPhase.READING, engine.albumMotionState().phase)
            assertTrue(engine.busy)
            engine.download()
            assertEquals(listOf(old), records.history())
            checkNotNull(callback)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, enriched))
            assertAlbumCorrespondence(enriched, checkNotNull(engine.video))
            assertEquals(base.images.map { it.imageKey }, engine.video!!.images.map { it.imageKey })
            assertTrue(WatermarkSources.available(engine.video!!, WatermarkMode.CLEAN))
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertEquals(TaskStage.READY, engine.stage)
            assertFalse(engine.busy)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun failedDeclaredLiveRecoveryRetainsItsTypeButCannotDownloadAStillAsLive() = isolated { engine, records, old ->
        val base = album(1).let { source -> source.copy(images = source.images.mapIndexed { index, image ->
            if (index == 0) image.copy(kind = AlbumAssetKind.LIVE) else image
        }) }
        val attempted = Collections.synchronizedList(mutableListOf<ParsedVideo>())
        main {
            // Metadata eligibility permits reading the JPEG, not publishing its
            // still cover. Model the production file-validation rejection here;
            // EmbeddedLiveAlbumDownloadTest exercises that actual byte check.
            engine.queueSaveOverride = { candidate, _ ->
                attempted += candidate
                throw IllegalArgumentException("第 1 项缺少实况动态内容，不会仅保存封面")
            }
        }
        listOf(DesktopAlbumStatus.NEEDS_VERIFICATION, DesktopAlbumStatus.UNAVAILABLE, DesktopAlbumStatus.FAILED).forEachIndexed { index, status ->
            main {
                engine.albumMotionResolveOverride = { id, complete ->
                    assertEquals(base.id, id); complete(DesktopAlbumResult(status, reason = "fixture missing live clip"))
                }
                acceptParsed(engine, base)
                assertSame(base, engine.video)
                assertEquals(AlbumAssetKind.LIVE, engine.video!!.images.first().kind)
                assertEquals(TaskStage.READY, engine.stage)
                assertEquals(AlbumMotionPhase.UNAVAILABLE, engine.albumMotionState().phase)
                assertFalse(engine.busy)
                assertFalse(engine.albumMotionVerificationVisible)
                assertTrue(AlbumActionUiPolicy.coversAvailable(base))
                assertFalse(WatermarkSources.available(base, WatermarkMode.CLEAN))
                assertTrue(WatermarkSources.availableForDownload(base, WatermarkMode.CLEAN))
                engine.download()
                // The controlled transfer can reject a cover-only JPEG before
                // this assertion. Both states are legitimate; terminal failure
                // and unchanged records are checked after the await below.
                assertTrue(engine.stage in listOf(TaskStage.DOWNLOADING, TaskStage.FAILED))
                assertEquals(listOf(old), records.history())
            }
            await { !engine.busy && attempted.size == index + 1 }
            main {
                assertEquals(TaskStage.FAILED, engine.stage)
                assertSame(base, attempted.last())
                assertSame(base, engine.video)
                assertEquals(AlbumAssetKind.LIVE, engine.video!!.images.first().kind)
                assertEquals(AlbumMotionPhase.UNAVAILABLE, engine.albumMotionState().phase)
                assertTrue(engine.message.contains("实况动态内容"))
                assertEquals(listOf(old), records.history())
            }
        }
    }

    @Test fun automaticSingleAndQueueRecoveryRetainKnownKindsByUniquePhotoIdentityAfterDesktopReordering() {
        listOf(false, true).forEach { queued -> isolated { engine, records, old ->
            val base = album(1).let { source -> source.copy(images = source.images.mapIndexed { index, image ->
                image.copy(kind = if (index == 0) AlbumAssetKind.LIVE else AlbumAssetKind.ANIMATED)
            }) }
            val neighbor = album(2)
            val desktop = live(base).let { source ->
                val clip = checkNotNull(source.images.first().motion)
                source.copy(images = source.images.map { it.copy(kind = AlbumAssetKind.DYNAMIC, motion = clip) }.reversed())
            }
            val expected = desktop.copy(images = desktop.images.map { image ->
                image.copy(kind = base.images.single { it.imageKey == image.imageKey }.kind)
            })
            var reads = 0
            var callback: ((DesktopAlbumResult) -> Unit)? = null
            main {
                if (queued) {
                    seedQueue(engine, listOf("target" to base, "neighbor" to neighbor))
                    state(engine, "queue", engine.queue.map { if (it.key == "target") it.copy(status = QueueStatus.PARSING) else it })
                    SaverEngine::class.java.getDeclaredField("activeTaskKey").apply { isAccessible = true }.set(engine, "target")
                    state(engine, "selectedTaskKey", "target")
                    state(engine, "queueRunning", true)
                }
                engine.albumMotionResolveOverride = { id, complete -> assertEquals(base.id, id); reads++; callback = complete }
                engine.queueSaveOverride = { _, _ -> error("Automatic type recovery must not save any file") }
                acceptParsed(engine, base)
                assertEquals(1, reads)
                assertTrue(engine.busy)
                checkNotNull(callback)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, desktop))
                val result = if (queued) checkNotNull(engine.queueResults["target"]) else checkNotNull(engine.video)
                assertAlbumCorrespondence(expected, result)
                assertEquals(listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.LIVE), result.images.map { it.kind })
                assertEquals("保存动图与实况", AlbumActionUiPolicy.primaryLabel(result))
                assertFalse(AlbumActionUiPolicy.requiresFormatChoice(result))
                assertEquals(desktop.images.map { it.motion }, result.images.map { it.motion })
                assertEquals(desktop.images.map { it.mediaSources }, result.images.map { it.mediaSources })
                assertEquals(listOf(AlbumAssetKind.DYNAMIC, AlbumAssetKind.DYNAMIC), desktop.images.map { it.kind })
                assertEquals(listOf(AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED), base.images.map { it.kind })
                assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState(if (queued) "target" else null).phase)
                assertEquals(listOf(old), records.history())
            }
            await { !engine.busy && !engine.queueRunning }
            main {
                if (queued) {
                    assertAlbumCorrespondence(expected, checkNotNull(engine.queueResults["target"]))
                    assertSame(neighbor, engine.queueResults["neighbor"])
                    assertTrue(engine.queue.all { it.status == QueueStatus.READY })
                } else assertEquals(TaskStage.READY, engine.stage)
                assertEquals(listOf(old), records.history())
            }
        } }
    }

    @Test fun missingLiveRecoveryStillRejectsUntrustedCoversOtherMissingDynamicAssetsAndSourceLessVideos() = isolated { engine, records, old ->
        val base = album(1).let { source -> source.copy(images = source.images.mapIndexed { index, image ->
            if (index == 0) image.copy(kind = AlbumAssetKind.LIVE) else image
        }) }
        val badCover = base.copy(images = base.images.mapIndexed { index, image ->
            if (index == 0) image.copy(url = "https://untrusted.example/cover.jpg", mediaSources = emptyList()) else image
        })
        val missingOtherMotion = base.copy(images = base.images.mapIndexed { index, image ->
            if (index == 1) image.copy(kind = AlbumAssetKind.DYNAMIC) else image
        })
        val noVideoSource = ParsedVideo(base.id, "source-less video", "", 2.0, 640, 480)
        main {
            engine.albumMotionResolveOverride = { _, _ -> error("Unavailable covers or unrelated missing sources must fail before desktop reading") }
            engine.queueSaveOverride = { _, _ -> error("Rejected source must not save") }
            listOf(badCover, missingOtherMotion, noVideoSource).forEach { rejected ->
                acceptParsed(engine, rejected)
                assertEquals(TaskStage.FAILED, engine.stage)
                assertNull(engine.video)
                assertFalse(engine.busy)
                assertEquals(listOf(old), records.history())
            }
        }
    }

    @Test fun missingLiveRecoveryFailureDoesNotBlockQueueParsingOrItsValidNeighborsSaving() = isolated { engine, records, old ->
        val first = album(1).let { source -> source.copy(images = source.images.mapIndexed { index, image ->
            if (index == 0) image.copy(kind = AlbumAssetKind.LIVE) else image
        }) }
        val second = album(2)
        val reads = mutableListOf<String>()
        val savedIds = Collections.synchronizedList(mutableListOf<String>())
        val attemptedIds = Collections.synchronizedList(mutableListOf<String>())
        main {
            state(engine, "queue", listOf(QueueTask("first", "https://www.douyin.com/note/${first.id}", status = QueueStatus.PARSING),
                QueueTask("second", "https://www.douyin.com/note/${second.id}")))
            SaverEngine::class.java.getDeclaredField("activeTaskKey").apply { isAccessible = true }.set(engine, "first")
            state(engine, "selectedTaskKey", "first")
            state(engine, "queueRunning", true)
            engine.albumMotionResolveOverride = { id, complete ->
                reads += id; complete(DesktopAlbumResult(DesktopAlbumStatus.UNAVAILABLE, reason = "fixture source missing"))
            }
            engine.queueParseOverride = { task -> assertEquals("second", task.key); acceptParsed(engine, second) }
            engine.queueSaveOverride = { content, options ->
                attemptedIds += content.id
                if (content.id == first.id) throw IllegalArgumentException("第 1 项缺少实况动态内容，不会仅保存封面")
                assertEquals(second.id, content.id); savedIds += content.id; saved(content, options)
            }
            acceptParsed(engine, first)
        }
        await { !engine.queueRunning && !engine.busy }
        main {
            assertEquals(listOf(first.id, second.id), reads)
            assertTrue(engine.queue.all { it.status == QueueStatus.READY })
            assertSame(first, engine.queueResults["first"])
            assertEquals(AlbumAssetKind.LIVE, engine.queueResults["first"]!!.images.first().kind)
            assertFalse(WatermarkSources.available(first, WatermarkMode.CLEAN))
            engine.downloadParsedQueue()
        }
        await { !engine.batchSaving && savedIds.size == 1 }
        main {
            assertEquals(listOf(first.id, second.id), attemptedIds.toList())
            assertEquals(listOf(second.id), savedIds.toList())
            assertEquals(QueueStatus.FAILED, engine.queue.first().status)
            assertTrue(engine.queue.first().message.contains("实况动态内容"))
            assertEquals(QueueStatus.DONE, engine.queue.last().status)
            assertSame(first, engine.queueResults["first"])
            assertSame(second, engine.queueResults["second"])
            assertEquals(2, records.history().size)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun knownNativeAnimationNeedsNoExtraDesktopReadDuringParsing() = isolated { engine, records, old ->
        val base = album(1)
        val url = "https://p3.douyinpic.com/automatic_original.gif"
        val animation = base.copy(images = listOf(ParsedImage(url, 640, 480, listOf(MediaSource(url, WatermarkMode.CLEAN)),
            AlbumAssetKind.ANIMATED, "image/gif")))
        main {
            engine.albumMotionResolveOverride = { _, _ -> error("A complete native animation must not load another page") }
            acceptParsed(engine, animation)
            assertSame(animation, engine.video)
            assertFalse(engine.busy)
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun clearingInputRetiresAnAutomaticReadAndRejectsItsLateCandidate() = isolated { engine, records, old ->
        val base = album(1)
        var callback: ((DesktopAlbumResult) -> Unit)? = null
        main {
            engine.albumMotionResolveOverride = { _, complete -> callback = complete }
            acceptParsed(engine, base)
            engine.clearInput()
            assertNull(engine.video)
            assertEquals(TaskStage.IDLE, engine.stage)
            checkNotNull(callback)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(base)))
            assertNull(engine.video)
            assertFalse(engine.busy)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun pausingAnAutomaticQueueReadReleasesBusyAndIgnoresItsLateCandidate() = isolated { engine, records, old ->
        val first = album(1); val second = album(2)
        var late: ((DesktopAlbumResult) -> Unit)? = null
        main {
            state(engine, "queue", listOf(QueueTask("first", "https://www.douyin.com/note/${first.id}", status = QueueStatus.PARSING),
                QueueTask("second", "https://www.douyin.com/note/${second.id}")))
            SaverEngine::class.java.getDeclaredField("activeTaskKey").apply { isAccessible = true }.set(engine, "first")
            state(engine, "selectedTaskKey", "first")
            state(engine, "queueRunning", true)
            engine.albumMotionResolveOverride = { id, complete -> assertEquals(first.id, id); late = complete }
            acceptParsed(engine, first)
            assertTrue(engine.busy)
            assertSame(first, engine.queueResults["first"])
            engine.stopQueue()
            assertFalse(engine.busy)
            assertFalse(engine.queueRunning)
            assertSame(first, engine.queueResults["first"])
            checkNotNull(late)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, live(first)))
            assertSame(first, engine.queueResults["first"])
            engine.albumMotionResolveOverride = { id, complete ->
                assertEquals(second.id, id); complete(DesktopAlbumResult(DesktopAlbumStatus.UNAVAILABLE))
            }
            engine.queueParseOverride = { task -> assertEquals("second", task.key); acceptParsed(engine, second) }
            engine.startQueue()
        }
        await { !engine.queueRunning && !engine.busy }
        main {
            assertEquals(setOf("first", "second"), engine.queueResults.keys)
            assertSame(first, engine.queueResults["first"])
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun automaticVerificationInAQueueDoesNotBlockTheNextWork() = isolated { engine, records, old ->
        val first = album(1); val second = album(2)
        val reads = mutableListOf<String>()
        main {
            state(engine, "queue", listOf(QueueTask("first", "https://www.douyin.com/note/${first.id}", status = QueueStatus.PARSING),
                QueueTask("second", "https://www.douyin.com/note/${second.id}")))
            SaverEngine::class.java.getDeclaredField("activeTaskKey").apply { isAccessible = true }.set(engine, "first")
            state(engine, "selectedTaskKey", "first")
            state(engine, "queueRunning", true)
            engine.albumMotionResolveOverride = { id, complete ->
                reads += id
                complete(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION))
            }
            engine.queueParseOverride = { task -> assertEquals("second", task.key); acceptParsed(engine, second) }
            acceptParsed(engine, first)
        }
        await { !engine.queueRunning && !engine.busy }
        main {
            assertEquals(listOf(first.id, second.id), reads)
            assertEquals(setOf("first", "second"), engine.queueResults.keys)
            assertTrue(engine.queue.all { it.status == QueueStatus.READY })
            assertEquals(listOf(old), records.history())
        }
    }

    @Test fun knownLivePendingFileUsesDownloadQualificationWithoutClaimingMotionReadSuccess() = isolated { engine, records, old ->
        val pending = pendingEmbeddedLive(album(1))
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedReady(engine, pending)
            engine.albumMotionResolveOverride = { _, _ -> error("A known live file candidate must be verified by the downloader") }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
            assertFalse(WatermarkSources.available(pending, WatermarkMode.CLEAN))
            assertTrue(WatermarkSources.availableForDownload(pending, WatermarkMode.CLEAN))
            assertEquals(AlbumAssetKind.LIVE, checkNotNull(engine.selectedContent).images.first().kind)
            assertNull(checkNotNull(engine.selectedContent).images.first().motion)
            engine.saveAlbumMotion(mode = AlbumMode.LIVE_PHOTOS, selectedImageIndices = listOf(0))
            assertEquals(AlbumMotionPhase.IDLE, engine.albumMotionState().phase)
        }
        await { received.size == 1 && !engine.busy }
        main {
            assertSame(pending, received.single().first)
            assertEquals(listOf(0), received.single().second.selectedImageIndices)
            assertEquals(AlbumMode.LIVE_PHOTOS, received.single().second.albumMode)
            assertNull(pending.images.first().motion)
            assertEquals(AlbumMotionPhase.IDLE, engine.albumMotionState().phase)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun batchPendingLiveKeepsItsQueueIdentityAndDoesNotBlockTheNextDownloadFixture() = isolated { engine, records, old ->
        val single = album(9)
        val pending = pendingEmbeddedLive(album(1))
        val neighbour = album(2)
        val received = Collections.synchronizedList(mutableListOf<Pair<ParsedVideo, DownloadOptions>>())
        main {
            seedReady(engine, single)
            seedQueue(engine, listOf("pending" to pending, "neighbour" to neighbour))
            engine.albumMotionResolveOverride = { _, _ -> error("Batch download must not equate file verification to desktop motion") }
            engine.queueSaveOverride = { content, options -> received += content to options; saved(content, options) }
            engine.downloadParsedQueue()
        }
        await { received.size == 2 && !engine.busy && !engine.batchSaving }
        main {
            assertEquals(listOf(pending.id, neighbour.id), received.map { it.first.id })
            assertTrue(received.all { it.second.albumMode == AlbumMode.IMAGES && it.second.watermarkMode == WatermarkMode.CLEAN })
            assertSame(single, engine.video)
            assertSame(pending, engine.queueResults["pending"])
            assertSame(neighbour, engine.queueResults["neighbour"])
            assertTrue(engine.queue.all { it.status == QueueStatus.DONE })
            assertEquals(AlbumMotionPhase.IDLE, engine.albumMotionState("pending").phase)
            assertEquals(old, records.history().last())
        }
    }

    @Test fun unknownDynamicWithoutAMotionCannotUseTheEmbeddedLiveDownloadException() = isolated { engine, records, old ->
        val unknown = pendingEmbeddedLive(album(1)).let { it.copy(images = it.images.map { image ->
            if (image.kind == AlbumAssetKind.LIVE) image.copy(kind = AlbumAssetKind.DYNAMIC) else image
        }) }
        var downloads = 0
        main {
            seedReady(engine, unknown)
            engine.queueSaveOverride = { content, options -> downloads++; saved(content, options) }
            assertFalse(WatermarkSources.availableForDownload(unknown, WatermarkMode.CLEAN))
            assertFalse(WatermarkSources.requiresEmbeddedLiveVerification(unknown, WatermarkMode.CLEAN))
            engine.albumMode = AlbumMode.LIVE_PHOTOS
            engine.download()
            assertFalse(engine.busy)
            assertEquals(0, downloads)
            assertEquals(listOf(old), records.history())
        }
    }

    private fun pendingEmbeddedLive(base: ParsedVideo): ParsedVideo = base.copy(images = base.images.mapIndexed { index, image ->
        if (index != 0) image else {
            val url = "https://p3.douyinpic.com/embedded_live_engine_${base.id}.jpg"
            image.copy(url = url, kind = AlbumAssetKind.LIVE, mimeType = "image/jpeg", motion = null,
                mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)))
        }
    })

    private fun acceptParsed(engine: SaverEngine, content: ParsedVideo) {
        state(engine, "stage", TaskStage.VERIFYING)
        SaverEngine::class.java.getDeclaredMethod("acceptVerifiedResult", Int::class.javaPrimitiveType, ParsedVideo::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(engine, engine.generation, content, emptyList<SavedVideo>())
    }

    private fun album(index: Int): ParsedVideo {
        val id = "999999999999999999$index"
        val images = (0..1).map { image ->
            val url = "https://p3.douyinpic.com/motion_engine_${index}_$image.webp"
            ParsedImage(url, 640, 480, listOf(MediaSource(url, WatermarkMode.CLEAN)), imageKey = "$id-photo-$image")
        }
        return ParsedVideo(id, "fixture album $index", "", 0.0, 640, 480, coverUrl = images.first().url,
            images = images, bgmUrl = "https://sf11.douyinstatic.com/motion_engine_$index.mp3", bgmDurationSeconds = 5.0)
    }

    private fun live(base: ParsedVideo): ParsedVideo {
        val url = "https://v11.douyinvod.com/web/motion_engine_${base.id}.mp4?signature=fixture"
        val sources = WatermarkSources.videoSources(listOf(url), displayPlaybackUrls = listOf(url))
        return base.copy(images = base.images.mapIndexed { index, image ->
            if (index == 0) image.copy(kind = AlbumAssetKind.LIVE, motion = ParsedMotion(url, 640, 480, 2.0, sources)) else image
        })
    }

    private fun saved(content: ParsedVideo, options: DownloadOptions): SavedVideo = SavedVideo(content.id,
        content.title, "content://com.local.douyinsaver.fixture/motion-${content.id}", 42,
        mimeType = if (options.albumMode == AlbumMode.GIF) "image/gif" else "image/webp",
        isAlbum = true, watermarkMode = options.watermarkMode, exportMode = options.albumMode.name,
        albumTimingSignature = if (options.albumMode in listOf(AlbumMode.GIF, AlbumMode.VIDEO))
            AlbumTiming.signature(options) else "",
        gifExportQuality = if (options.albumMode == AlbumMode.GIF) options.gifExportQuality.name else "")

    private fun savedAlbum(content: ParsedVideo, options: DownloadOptions, sequence: Int): SavedVideo {
        val prefix = "content://com.local.douyinsaver.fixture/export-${content.id}-$sequence"
        val assets = AlbumSelection.indices(content, options.selectedImageIndices).map { index ->
            val image = content.images[index]
            val cover = "$prefix-cover-$index"
            val kind = if (image.kind == AlbumAssetKind.DYNAMIC) when (options.albumMode) {
                AlbumMode.LIVE_PHOTOS -> AlbumAssetKind.LIVE
                AlbumMode.MOTION_VIDEOS -> AlbumAssetKind.ANIMATED
                AlbumMode.GIF -> AlbumAssetKind.ANIMATED
                else -> error("Unknown dynamic fixture requires an explicit save format")
            } else image.kind
            when {
                image.motion != null && options.albumMode == AlbumMode.GIF ->
                    SavedAlbumAsset(cover, "image/gif", AlbumAssetKind.ANIMATED, sourceIndex = index)
                image.motion != null && kind == AlbumAssetKind.LIVE ->
                    SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true, sourceIndex = index)
                image.motion != null -> SavedAlbumAsset(cover, "video/mp4", AlbumAssetKind.ANIMATED, sourceIndex = index)
                else -> SavedAlbumAsset(cover, image.mimeType.ifBlank { "image/webp" }, kind, sourceIndex = index)
            }
        }
        val uris = assets.map { it.uri }
        return SavedVideo(content.id, content.title, uris.first(), 42, uris = uris,
            mimeType = if (options.albumMode == AlbumMode.GIF) "image/*" else "*/*",
            isAlbum = true, watermarkMode = options.watermarkMode, albumAssets = assets,
            exportMode = options.albumMode.name, albumSourceCount = content.images.size,
            albumTimingSignature = if (options.albumMode in listOf(AlbumMode.GIF, AlbumMode.VIDEO))
                AlbumTiming.signature(options) else "",
            gifExportQuality = if (options.albumMode == AlbumMode.GIF) options.gifExportQuality.name else "")
    }

    private fun assertAlbumCorrespondence(expected: ParsedVideo, actual: ParsedVideo) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.images.map { it.imageKey }, actual.images.map { it.imageKey })
        assertEquals(expected.images.map { it.url }, actual.images.map { it.url })
        assertEquals(expected.images.map { it.motion?.url }, actual.images.map { it.motion?.url })
        assertEquals(expected.images.map { it.kind }, actual.images.map { it.kind })
        assertEquals(expected.bgmUrl, actual.bgmUrl)
    }

    private fun seedReady(engine: SaverEngine, content: ParsedVideo) {
        state(engine, "video", content)
        state(engine, "stage", TaskStage.READY)
    }

    private fun seedFailedShare(engine: SaverEngine, id: String?) {
        state<ParsedVideo?>(engine, "video", null)
        state(engine, "stage", TaskStage.FAILED)
        state(engine, "browsingId", id)
        state(engine, "message", "fixture fast share parsing failed")
    }

    private fun seedQueue(engine: SaverEngine, entries: List<Pair<String, ParsedVideo>>) {
        state(engine, "queue", entries.map { (key, content) ->
            QueueTask(key, "https://www.douyin.com/note/${content.id}", content.title, QueueStatus.READY)
        })
        state(engine, "queueResults", entries.toMap())
    }

    private fun isolated(body: (SaverEngine, DownloadRecords, SavedVideo) -> Unit) {
        val prefix = "album_motion_validation_${UUID.randomUUID()}_"
        val records = DownloadRecords(application, prefix)
        val old = SavedVideo("previous", "既有隔离记录", "content://com.local.douyinsaver.fixture/previous", 40)
        records.save(old)
        lateinit var engine: SaverEngine
        main {
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(application, prefix)
        }
        try { body(engine, records, old) } finally {
            main {
                engine.cancelAlbumMotion()
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
        main { assertTrue("The isolated motion engine did not reach its expected state", condition()) }
    }
}
