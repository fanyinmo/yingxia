package com.local.douyinsaver

import android.app.Application
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.compose.runtime.MutableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated source callbacks and real MediaProbe with controlled connections/MP4 bytes;
 * no page/network request, file publication or global engine. */
@RunWith(AndroidJUnit4::class)
class DesktopShareFallbackEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    @Test
    fun dnsFailureRemainsNetworkFailureAfterUnsuccessfulAlternatePage() = isolated { engine, records, old ->
        val dns = PageNetworkFailures.browserMessage(-2)
        main {
            seedBrowsing(engine, dynamicAlbum(1).id)
            engine.publicApiReadOverride = { null }
            engine.desktopShareResolveOverride = { _, complete -> complete(DesktopAlbumResult(DesktopAlbumStatus.FAILED)) }
            failMobile(engine, dns)
        }
        await { engine.stage == TaskStage.FAILED && !engine.busy }
        main {
            assertEquals(dns, engine.message)
            assertNull(engine.video)
            assertTrue(engine.queueResults.isEmpty())
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun dnsFailureDoesNotBlockVerifiedSameWorkAlternateSource() = isolated { engine, records, old ->
        val candidate = dynamicAlbum(1)
        main {
            seedBrowsing(engine, candidate.id)
            engine.publicApiReadOverride = { null }
            engine.desktopShareResolveOverride = { _, complete -> complete(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate)) }
            failMobile(engine, PageNetworkFailures.browserMessage(-2))
        }
        await { engine.stage == TaskStage.READY && !engine.busy }
        main {
            assertSame(candidate, engine.video)
            assertFalse(engine.message.contains("域名"))
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun emptyMobileAndApiRecoverACompleteMixedAlbumOnceWithoutSavingOrAnotherDesktopRead() = isolated { engine, records, old ->
        val candidate = dynamicAlbum(1)
        val neighbor = video(2)
        var apiReads = 0; var desktopReads = 0; var secondReads = 0; var saves = 0
        main {
            seedBrowsing(engine, candidate.id)
            state(engine, "queueResults", mapOf("neighbor" to neighbor))
            engine.publicApiReadOverride = { id -> assertEquals(candidate.id, id); apiReads++; null }
            engine.desktopShareResolveOverride = { id, complete ->
                assertEquals(candidate.id, id); desktopReads++
                complete(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            }
            engine.albumMotionResolveOverride = { _, _ -> secondReads++; error("The full desktop array was already checked") }
            engine.queueSaveOverride = { _, _ -> saves++; error("Parsing must not save") }
            failMobile(engine)
        }
        await { engine.stage == TaskStage.READY && !engine.busy }
        main {
            assertEquals(1, apiReads); assertEquals(1, desktopReads); assertEquals(0, secondReads); assertEquals(0, saves)
            assertSame(candidate, engine.video)
            assertSame(neighbor, engine.queueResults["neighbor"])
            assertEquals(listOf(AlbumAssetKind.DYNAMIC, AlbumAssetKind.STATIC), engine.video!!.images.map { it.kind })
            assertEquals(candidate.images.map { it.imageKey }, engine.video!!.images.map { it.imageKey })
            assertEquals(candidate.images.map { it.motion?.url }, engine.video!!.images.map { it.motion?.url })
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertEquals(AlbumMode.IMAGES, engine.albumMode)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun wrongWorkBgmOnlyAndIncompleteUntrustedAssetsCannotBecomeRecoveredContent() {
        val owned = dynamicAlbum(1)
        val badCover = owned.images.first().copy(mediaSources = emptyList())
        val badClip = owned.images.first().copy(motion = owned.images.first().motion!!.copy(
            mediaSources = listOf(MediaSource("https://unrelated.invalid/clip.mp4", WatermarkMode.CLEAN))))
        val untrustedVideo = video(1).copy(mediaSources = listOf(
            MediaSource("https://unrelated.invalid/video.mp4", WatermarkMode.CLEAN)))
        val candidates = listOf(dynamicAlbum(2), video(2), untrustedVideo,
            video(1).copy(mediaSources = listOf(MediaSource(video(1).mediaUrl, WatermarkMode.ORIGINAL))),
            video(1).copy(width = 0), owned.copy(images = emptyList()),
            owned.copy(images = listOf(badCover, owned.images.last())),
            owned.copy(images = listOf(badClip, owned.images.last())),
            owned.copy(images = owned.images.map { it.copy(kind = AlbumAssetKind.LIVE, motion = null) }),
            owned.copy(images = owned.images.map { it.copy(kind = AlbumAssetKind.DYNAMIC, motion = null) }))
        candidates.forEach { rejected -> isolated { engine, records, old ->
            main {
                seedBrowsing(engine, owned.id)
                engine.publicApiReadOverride = { null }
                engine.desktopShareResolveOverride = { _, complete ->
                    complete(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, rejected))
                }
                failMobile(engine)
            }
            await { engine.stage == TaskStage.FAILED && !engine.busy }
            main {
                assertNull(engine.video)
                assertTrue(engine.queueResults.isEmpty())
                assertEquals(AlbumMotionPhase.UNAVAILABLE, engine.albumMotionState().phase)
                assertEquals(listOf(old), records.history())
            }
        } }
    }

    @Test
    fun aCompleteStaticAlbumAndNativeAnimationRecoverWithoutPretendingToHaveMotionOrReadingAgain() {
        listOf(AlbumAssetKind.STATIC, AlbumAssetKind.ANIMATED).forEach { kind -> isolated { engine, records, old ->
            val candidate = stillAlbum(1, kind)
            var desktopReads = 0; var extraReads = 0; var saves = 0
            main {
                seedBrowsing(engine, candidate.id)
                engine.publicApiReadOverride = { null }
                engine.desktopShareResolveOverride = { id, complete ->
                    assertEquals(candidate.id, id); desktopReads++
                    complete(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
                }
                engine.albumMotionResolveOverride = { _, _ -> extraReads++; error("A complete desktop array must not be read again") }
                engine.queueSaveOverride = { _, _ -> saves++; error("Parsing must not publish an image") }
                failMobile(engine)
            }
            await { engine.stage == TaskStage.READY && !engine.busy }
            main {
                assertEquals(1, desktopReads); assertEquals(0, extraReads); assertEquals(0, saves)
                assertSame(candidate, engine.video)
                assertTrue(candidate.images.all { it.motion == null && it.kind == kind })
                assertEquals(if (kind == AlbumAssetKind.ANIMATED) AlbumMotionPhase.AVAILABLE else AlbumMotionPhase.UNAVAILABLE,
                    engine.albumMotionState().phase)
                if (kind == AlbumAssetKind.STATIC)
                    assertEquals("已取得图片，网页未提供逐图动态片段", engine.albumMotionState().detail)
                assertEquals(listOf(old), records.history())
            }
        } }
    }

    @Test
    fun aSameWorkDesktopVideoMustPassTheActualByteProbeBeforeItBecomesReady() = isolated { engine, records, old ->
        val candidate = video(1)
        val bytes = fixedMp4Bytes()
        val connections = Collections.synchronizedList(mutableListOf<ControlledMediaConnection>())
        var desktopReads = 0; var probeCreations = 0; var saves = 0
        main {
            seedBrowsing(engine, candidate.id)
            engine.publicApiReadOverride = { null }
            engine.desktopShareResolveOverride = { id, complete ->
                assertEquals(candidate.id, id); desktopReads++
                complete(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            }
            engine.mediaProbeFactoryOverride = {
                probeCreations++
                MediaProbe(connections = { uri ->
                    assertEquals(candidate.mediaUrl, uri.toString())
                    ControlledMediaConnection(uri, 200, bytes, "video/mp4").also { connections += it }
                }, cookies = { null })
            }
            engine.queueSaveOverride = { _, _ -> saves++; error("Source recovery must not download") }
            failMobile(engine)
            assertEquals(TaskStage.VERIFYING, engine.stage)
            assertNull("A candidate URL alone cannot become ready", engine.video)
        }
        await { engine.stage == TaskStage.READY && !engine.busy }
        main {
            assertEquals(1, desktopReads); assertEquals(1, probeCreations); assertEquals(0, saves)
            assertEquals(candidate.id, engine.video!!.id)
            assertFalse(engine.video!!.isAlbum)
            assertEquals(candidate.mediaUrl, engine.selectedContent!!.mediaUrl)
            assertEquals(1, connections.size)
            assertEquals("bytes=0-4095", connections.single().getRequestProperty("Range"))
            assertEquals(32, connections.single().bytesRead)
            assertTrue(connections.single().disconnected)
            assertTrue(engine.diagnostics.contains("media_verified"))
            assertTrue(engine.diagnostics.contains("media_probe_passed"))
            assertEquals(AlbumMotionPhase.IDLE, engine.albumMotionState().phase)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun aRejectedDesktopVideoCannotRetryTheSamePageOrBlockTheNextQueueWork() {
        listOf(403, 200).forEach { code -> isolated { engine, records, old ->
            val first = video(1); val second = video(2); val prior = video(3)
            val requests = Collections.synchronizedList(mutableListOf<ControlledMediaConnection>())
            var desktopReads = 0
            main {
                state(engine, "video", prior); state(engine, "stage", TaskStage.READY)
                engine.enqueueInput("https://www.douyin.com/video/${first.id} https://www.douyin.com/video/${second.id}")
                engine.publicApiReadOverride = { null }
                engine.desktopShareResolveOverride = { id, complete ->
                    assertEquals(first.id, id); desktopReads++
                    complete(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, first))
                }
                engine.mediaProbeFactoryOverride = {
                    MediaProbe(connections = { uri ->
                        assertEquals(first.mediaUrl, uri.toString())
                        ControlledMediaConnection(uri, code, "<html>Not a media file</html>".toByteArray(),
                            "text/html").also { requests += it }
                    }, cookies = { null })
                }
                engine.queueParseOverride = { task ->
                    if (ShareLinks.videoId(task.source) == first.id) { seedBrowsing(engine, first.id); failMobile(engine) }
                    else acceptVerified(engine, second)
                }
                engine.startQueue()
            }
            await { !engine.queueRunning && !engine.busy }
            main {
                assertEquals(1, desktopReads)
                assertEquals(1, requests.size); assertTrue(requests.single().disconnected)
                assertEquals(listOf(QueueStatus.FAILED, QueueStatus.READY), engine.queue.map { it.status })
                assertEquals(setOf(engine.queue.last().key), engine.queueResults.keys)
                assertSame(second, engine.queueResults[engine.queue.last().key])
                assertSame(prior, engine.video)
                assertEquals(0, requests.single().bytesRead)
                assertEquals(listOf(old), records.history())
            }
        } }
    }

    @Test
    fun cancellationReentryAndSupersedingInputRejectOldDesktopCallbacks() = isolated { engine, records, old ->
        val first = dynamicAlbum(1); val second = dynamicAlbum(2)
        val callbacks = mutableListOf<(DesktopAlbumResult) -> Unit>()
        var apiReads = 0
        main {
            seedBrowsing(engine, first.id)
            engine.publicApiReadOverride = { apiReads++; null }
            engine.desktopShareResolveOverride = { _, complete -> callbacks += complete }
            failMobile(engine)
            assertEquals(TaskStage.VERIFYING, engine.stage)
            assertTrue(engine.busy)
            failMobile(engine)
            assertEquals(1, callbacks.size); assertEquals(1, apiReads)
            engine.cancel()
            assertEquals(TaskStage.CANCELLED, engine.stage)
            assertFalse(engine.busy)
            callbacks.first()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, first))
            assertNull(engine.video)
            engine.clearInput()
            seedBrowsing(engine, second.id)
            failMobile(engine)
            assertEquals(2, callbacks.size)
            callbacks.first()(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION))
            assertEquals(TaskStage.VERIFYING, engine.stage)
            callbacks.last()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, second))
        }
        await { engine.stage == TaskStage.READY && !engine.busy }
        main {
            assertSame(second, engine.video)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun aDetachedHostRetiresItsCallbackAndAllowsTheInterruptedSameWorkToRetry() = isolated { engine, records, old ->
        val candidate = dynamicAlbum(1)
        val callbacks = mutableListOf<(DesktopAlbumResult) -> Unit>()
        lateinit var host: FrameLayout
        main {
            host = FrameLayout(application)
            engine.attachParserHost(host)
            seedBrowsing(engine, candidate.id)
            engine.publicApiReadOverride = { null }
            engine.desktopShareResolveOverride = { _, complete -> callbacks += complete }
            failMobile(engine)
            engine.detachParserHost(host)
            assertEquals(TaskStage.BROWSING, engine.stage)
            assertEquals(candidate.id, engine.browsingId)
            assertEquals(AlbumMotionPhase.UNAVAILABLE, engine.albumMotionState().phase)
            callbacks.first()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
            assertNull(engine.video)
            // The resumed mobile failure is injected, rather than navigating a real page.
            failMobile(engine)
            assertEquals(2, callbacks.size)
            callbacks.last()(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, candidate))
        }
        await { engine.stage == TaskStage.READY && !engine.busy }
        main {
            assertSame(candidate, engine.video)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun anActualVerificationGateKeepsTheShareIdAndExistingManualCheckEntryWithoutSaving() = isolated { engine, records, old ->
        val candidate = dynamicAlbum(1)
        main {
            seedBrowsing(engine, candidate.id)
            engine.publicApiReadOverride = { null }
            engine.desktopShareResolveOverride = { _, complete -> complete(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION)) }
            failMobile(engine)
            assertEquals(TaskStage.FAILED, engine.stage)
            assertEquals(candidate.id, engine.browsingId)
            assertFalse(engine.busy)
            assertEquals(AlbumMotionPhase.NEEDS_VERIFICATION, engine.albumMotionState().phase)
            assertTrue(engine.message.contains("手动验证"))
            engine.albumMotionResolveOverride = { id, complete ->
                assertEquals(candidate.id, id)
                complete(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION))
            }
            engine.saveFailedShareAlbumMotion(saveOnReady = false)
            assertEquals(AlbumMotionPhase.NEEDS_VERIFICATION, engine.albumMotionState().phase)
            engine.openAlbumMotionVerification()
            assertTrue(engine.albumMotionVerificationVisible)
            assertNull(engine.video)
            assertEquals(listOf(old), records.history())
            engine.cancelAlbumMotion()
            assertFalse(engine.busy)
            assertFalse(engine.albumMotionVerificationVisible)
            assertEquals(TaskStage.FAILED, engine.stage)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun failedUnavailableVerificationAndSynchronousExceptionsContinueTheQueueAndPreserveItsSingleResult() {
        listOf(DesktopAlbumStatus.FAILED, DesktopAlbumStatus.UNAVAILABLE, DesktopAlbumStatus.NEEDS_VERIFICATION,
            DesktopAlbumStatus.CANCELLED, null).forEach { outcome -> isolated { engine, records, old ->
            val first = dynamicAlbum(1); val second = video(2); val prior = video(3)
            var desktopReads = 0
            main {
                state(engine, "video", prior); state(engine, "stage", TaskStage.READY)
                engine.enqueueInput("https://www.douyin.com/note/${first.id} https://www.douyin.com/video/${second.id}")
                engine.publicApiReadOverride = { null }
                engine.desktopShareResolveOverride = { id, complete ->
                    assertEquals(first.id, id); desktopReads++
                    if (outcome == null) throw IllegalStateException("fixture resolver construction failure")
                    complete(DesktopAlbumResult(outcome))
                }
                engine.queueParseOverride = { task ->
                    if (ShareLinks.videoId(task.source) == first.id) {
                        seedBrowsing(engine, first.id)
                        failMobile(engine)
                    } else acceptVerified(engine, second)
                }
                engine.startQueue()
            }
            await { !engine.queueRunning && !engine.busy }
            main {
                assertEquals(1, desktopReads)
                assertEquals(listOf(QueueStatus.FAILED, QueueStatus.READY), engine.queue.map { it.status })
                assertEquals(setOf(engine.queue.last().key), engine.queueResults.keys)
                assertSame(second, engine.queueResults[engine.queue.last().key])
                assertSame(prior, engine.video)
                assertEquals(TaskStage.READY, engine.stage)
                assertEquals(listOf(old), records.history())
                if (outcome == DesktopAlbumStatus.NEEDS_VERIFICATION)
                    assertEquals(AlbumMotionPhase.NEEDS_VERIFICATION, engine.albumMotionState(engine.queue.first().key).phase)
            }
        } }
    }

    @Test
    fun aRecoveredQueueAlbumIsReadyAndAvailableBeforeTheNextWorkWithoutAnotherRead() = isolated { engine, records, old ->
        val first = dynamicAlbum(1); val second = video(2)
        var desktopReads = 0; var extraReads = 0
        main {
            engine.enqueueInput("https://www.douyin.com/note/${first.id} https://www.douyin.com/video/${second.id}")
            engine.publicApiReadOverride = { null }
            engine.desktopShareResolveOverride = { id, complete ->
                assertEquals(first.id, id); desktopReads++
                complete(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, first))
            }
            engine.albumMotionResolveOverride = { _, _ -> extraReads++; error("No duplicate page read") }
            engine.queueParseOverride = { task ->
                if (ShareLinks.videoId(task.source) == first.id) {
                    seedBrowsing(engine, first.id); failMobile(engine)
                } else acceptVerified(engine, second)
            }
            engine.startQueue()
        }
        await { !engine.queueRunning && !engine.busy }
        main {
            assertEquals(1, desktopReads); assertEquals(0, extraReads)
            assertEquals(listOf(QueueStatus.READY, QueueStatus.READY), engine.queue.map { it.status })
            assertSame(first, engine.queueResults[engine.queue.first().key])
            assertSame(second, engine.queueResults[engine.queue.last().key])
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState(engine.queue.first().key).phase)
            assertEquals(AlbumAssetKind.DYNAMIC, engine.queueResults[engine.queue.first().key]!!.images.first().kind)
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun pausingTheQueueRetiresThePendingFallbackAndLateResultsCannotResurrectIt() = isolated { engine, records, old ->
        val first = dynamicAlbum(1); val second = video(2)
        var late: ((DesktopAlbumResult) -> Unit)? = null
        main {
            engine.enqueueInput("https://www.douyin.com/note/${first.id} https://www.douyin.com/video/${second.id}")
            engine.publicApiReadOverride = { null }
            engine.desktopShareResolveOverride = { id, complete -> assertEquals(first.id, id); late = complete }
            engine.queueParseOverride = { task ->
                if (ShareLinks.videoId(task.source) == first.id) {
                    seedBrowsing(engine, first.id); failMobile(engine)
                } else acceptVerified(engine, second)
            }
            engine.startQueue()
            assertTrue(engine.busy)
            engine.stopQueue()
            assertFalse(engine.busy); assertFalse(engine.queueRunning)
            assertEquals(listOf(QueueStatus.QUEUED, QueueStatus.QUEUED), engine.queue.map { it.status })
            checkNotNull(late)(DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, first))
            assertTrue(engine.queueResults.isEmpty()); assertNull(engine.video)
            engine.desktopShareResolveOverride = { _, complete -> complete(DesktopAlbumResult(DesktopAlbumStatus.UNAVAILABLE)) }
            engine.startQueue()
        }
        await { !engine.queueRunning && !engine.busy }
        main {
            assertEquals(listOf(QueueStatus.FAILED, QueueStatus.READY), engine.queue.map { it.status })
            assertSame(second, engine.queueResults[engine.queue.last().key])
            assertFalse(engine.queueResults.containsKey(engine.queue.first().key))
            assertEquals(listOf(old), records.history())
        }
    }

    @Test
    fun anAvailableApiNativeAnimationWinsWithoutCheckingTheFailedShareDesktopRoute() = isolated { engine, records, old ->
        val base = dynamicAlbum(1)
        val url = "https://p3.douyinpic.com/native_animation.gif"
        val candidate = base.copy(images = listOf(ParsedImage(url, 640, 480,
            listOf(MediaSource(url, WatermarkMode.CLEAN)), AlbumAssetKind.ANIMATED, "image/gif", imageKey = "own-native")))
        var desktopReads = 0
        main {
            seedBrowsing(engine, candidate.id)
            engine.publicApiReadOverride = { candidate }
            engine.desktopShareResolveOverride = { _, _ -> desktopReads++; error("API already supplied this work") }
            failMobile(engine)
        }
        await { engine.stage == TaskStage.READY && !engine.busy }
        main {
            assertEquals(0, desktopReads)
            assertSame(candidate, engine.video)
            assertEquals(AlbumMotionPhase.AVAILABLE, engine.albumMotionState().phase)
            assertEquals(listOf(old), records.history())
        }
    }

    private fun dynamicAlbum(index: Int): ParsedVideo {
        val id = "999999999999999999$index"
        val clip = "https://v11.douyinvod.com/web/share_fallback_$index.mp4"
        val images = (0..1).map { item ->
            val cover = "https://p3.douyinpic.com/share_fallback_${index}_$item.webp"
            ParsedImage(cover, 640, 480, listOf(MediaSource(cover, WatermarkMode.CLEAN)),
                kind = if (item == 0) AlbumAssetKind.DYNAMIC else AlbumAssetKind.STATIC,
                motion = if (item == 0) ParsedMotion(clip, 640, 480, 2.7,
                    listOf(MediaSource(clip, WatermarkMode.CLEAN))) else null,
                imageKey = "$id-photo-$item")
        }
        return ParsedVideo(id, "fixture mixed album $index", "", 0.0, 640, 480, images = images,
            bgmUrl = "https://sf11.douyinstatic.com/share_fallback_$index.mp3")
    }

    private fun video(index: Int): ParsedVideo {
        val url = "https://v3.douyinvod.com/share_fallback_video_$index.mp4"
        return ParsedVideo("999999999999999999$index", "fixture video $index", url, 3.0, 640, 480,
            mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)))
    }

    private fun stillAlbum(index: Int, kind: AlbumAssetKind): ParsedVideo = dynamicAlbum(index).let { album ->
        album.copy(images = album.images.mapIndexed { item, image ->
            val url = "https://p3.douyinpic.com/fallback_${index}_$item.${if (kind == AlbumAssetKind.ANIMATED) "gif" else "jpg"}"
            image.copy(url = url, mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)), kind = kind,
                mimeType = if (kind == AlbumAssetKind.ANIMATED) "image/gif" else "image/jpeg", motion = null)
        })
    }

    private fun fixedMp4Bytes(): ByteArray = instrumentation.context.assets.open("motion_test/fixed_red_blue_4s.mp4").use { it.readBytes() }.also {
        assertEquals("Independent MP4 input fixture changed", "b70fa2a2fbaee5cf936aef489b35e47071d837fa34bb5c858c6a9c0920ab1899",
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) })
    }

    private class ControlledMediaConnection(uri: URI, private val code: Int, private val bytes: ByteArray,
        private val type: String) : HttpURLConnection(uri.toURL()) {
        var bytesRead = 0
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentType() = type
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getInputStream() = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
    }

    private fun seedBrowsing(engine: SaverEngine, id: String) {
        state(engine, "stage", TaskStage.BROWSING)
        state(engine, "browsingId", id)
    }

    private fun failMobile(engine: SaverEngine, reason: String = "fixture mobile page returned no work data") = SaverEngine::class.java
        .getDeclaredMethod("parseFailed", Int::class.javaPrimitiveType, String::class.java)
        .apply { isAccessible = true }.invoke(engine, engine.generation, reason)

    private fun acceptVerified(engine: SaverEngine, content: ParsedVideo) = SaverEngine::class.java
        .getDeclaredMethod("acceptVerifiedResult", Int::class.javaPrimitiveType, ParsedVideo::class.java, List::class.java)
        .apply { isAccessible = true }.invoke(engine, engine.generation, content, emptyList<SavedVideo>())

    private fun isolated(body: (SaverEngine, DownloadRecords, SavedVideo) -> Unit) {
        val prefix = "desktop_share_fallback_${UUID.randomUUID()}_"
        val records = DownloadRecords(application, prefix)
        val old = SavedVideo("previous", "既有隔离记录", "content://com.local.douyinsaver.fixture/previous", 40)
        records.save(old)
        lateinit var engine: SaverEngine
        main {
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(application, prefix)
        }
        try { body(engine, records, old) }
        finally {
            main {
                engine.cancel()
                (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
            }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics", "album_timing", "download_folder").forEach {
                application.deleteSharedPreferences(prefix + it)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(engine: SaverEngine, name: String, value: T) {
        val delegate = SaverEngine::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(engine)
        (delegate as MutableState<T>).value = value
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            var complete = false
            main { complete = condition() }
            if (complete) return
            SystemClock.sleep(20)
        }
        main { assertTrue("Isolated fallback did not reach its expected state", condition()) }
    }
}
