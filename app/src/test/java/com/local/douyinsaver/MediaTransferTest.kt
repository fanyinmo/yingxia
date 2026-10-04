package com.local.douyinsaver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class MediaTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clean = "https://p3.douyinpic.com/photo.jpg?signature=private_clean"
    private val marked = "https://p3.douyinpic.com/download.jpg?signature=private_marked"
    private val fresh = "https://p6.douyinpic.com/fresh.jpg?signature=private_fresh"
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val sources = listOf(MediaSource(clean, WatermarkMode.CLEAN), MediaSource(marked, WatermarkMode.WATERMARKED))
    private fun destination() = File(temporary.root, "download")
    private fun guard(url: String) { WatermarkSources.requireSelectedUrl(url, WatermarkMode.CLEAN, sources) }

    @Test fun aMarkedInitialOrBackupAddressIsBlockedBeforeAnyConnection() {
        var requests = 0
        val transfer = MediaTransfer(connections = { uri -> requests++; response(uri) }, cookies = { null })
        val file = destination()
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(marked, file, 100, "图片", validateUrl = ::guard) { _, _ -> } }
        }
        assertEquals(0, requests)
        assertFalse(file.exists())
        assertFalse(error.message.orEmpty().contains("private_"))
    }

    @Test fun aRealGetRedirectToTheKnownMarkedImageIsBlockedBeforeFollowingIt() {
        val requested = mutableListOf<String>()
        lateinit var initial: FakeConnection
        val transfer = MediaTransfer(connections = { uri ->
            requested += uri.toString()
            response(uri, 302, marked).also { initial = it }
        }, cookies = { null })
        val file = destination()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(clean, file, 100, "图片", validateUrl = ::guard) { _, _ -> } }
        }
        assertEquals(listOf(clean), requested)
        assertTrue(initial.disconnected)
        assertFalse(file.exists())
    }

    @Test fun anUnclassifiedFreshTrustedRedirectCanSaveWithoutConflictingWithOriginalMetadata() = runBlocking {
        val requested = mutableListOf<String>()
        val transfer = MediaTransfer(connections = { uri ->
            requested += uri.toString()
            if (uri.toString() == clean) response(uri, 302, fresh) else response(uri)
        }, cookies = { null })
        val file = destination()
        val imageSources = sources + MediaSource(fresh, WatermarkMode.ORIGINAL)
        val count = transfer.fetch(clean, file, 100, "图片", validateUrl = {
            WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, imageSources)
        }) { _, _ -> }
        assertEquals(listOf(clean, fresh), requested)
        assertEquals(bytes.size.toLong(), count)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun explicitMarkedEndpointAndUnsafeRedirectsAreBothBlockedBeforeTheNextGet() {
        val targets = listOf("https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200f0000same_work",
            "https://p6.douyinpic.com/photo~watermark-v2:logo.jpg",
            "https://unsafe.example/image.jpg")
        targets.forEach { target ->
            val requested = mutableListOf<String>()
            val transfer = MediaTransfer(connections = { uri ->
                requested += uri.toString(); response(uri, 302, target)
            }, cookies = { null })
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { transfer.fetch(clean, destination(), 100, "图片", validateUrl = ::guard) { _, _ -> } }
            }
            assertEquals(listOf(clean), requested)
            assertFalse(destination().exists())
        }
    }

    @Test fun bgmUsesTheDefaultCallbackAndStillAllowsTheExistingTrailingProgressLambda() = runBlocking {
        val transfer = MediaTransfer(connections = { uri -> response(uri) }, cookies = { null })
        val file = destination()
        var progressed = 0L
        val count = transfer.fetch("https://sf3.bytecdn.com/bgm.mp3?watermark=1", file, 100, "BGM") { done, _ -> progressed = done }
        assertEquals(bytes.size.toLong(), count)
        assertEquals(count, progressed)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun transferFollowsTheSameColdSchedulingChainAndPreservesTheSignedTarget() = runBlocking {
        val entry = "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0200f0000fixture"
        val cold = "https://v5-coldx.douyinvod.com/first.mp4"
        val dynamic = "https://1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com/a%2Fb.mp4?signature=a%2Bb%3D&x=&x=1"
        val requested = mutableListOf<String>()
        val transfer = MediaTransfer(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, cold)
                cold -> response(uri, 302, dynamic)
                else -> response(uri)
            }
        }, cookies = { null })
        val file = destination()
        val count = transfer.fetch(entry, file, 100, "视频", validateUrl = {
            WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, listOf(MediaSource(entry, WatermarkMode.CLEAN)))
        }) { _, _ -> }
        assertEquals(listOf(entry, cold, dynamic), requested)
        assertEquals(bytes.size.toLong(), count)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun aSchedulingTransferCannotFollowAnUnknownOrMarkedNextHopAndLeavesNoFile() {
        val dynamic = "https://1AAAUMQG5W1GGJGYJ6U2RGVZZXEGYJT3KXA4FLTUS4A.bdcgslb.com/first.mp4"
        listOf("https://unknown.example/video.mp4", "$dynamic?watermark=1").forEach { target ->
            val requested = mutableListOf<String>()
            val transfer = MediaTransfer(connections = { uri ->
                requested += uri.toString(); response(uri, 302, target)
            }, cookies = { null })
            val file = destination()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { transfer.fetch(dynamic, file, 100, "视频", validateUrl = {
                    WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, listOf(MediaSource(dynamic, WatermarkMode.CLEAN)))
                }) { _, _ -> } }
            }
            assertEquals(listOf(dynamic), requested)
            assertFalse(file.exists())
        }
    }

    @Test fun cancellationDoesNotFollowAnotherGetOrLeaveAPartialFile() {
        var requests = 0
        val transfer = MediaTransfer(connections = { uri -> requests++; response(uri) }, cookies = { null })
        val file = destination()
        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(Job()) {
                transfer.fetch(clean, file, 100, "图片", validateUrl = ::guard) { _, _ ->
                    currentCoroutineContext().cancel()
                }
            } }
        }
        assertEquals(1, requests)
        assertFalse(file.exists())
    }

    private fun response(uri: URI, status: Int = 200, location: String? = null) = FakeConnection(uri, status, location, bytes)
    private class FakeConnection(uri: URI, private val status: Int, private val location: String?,
        private val bytes: ByteArray) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = "application/octet-stream"
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getHeaderField(name: String?): String? = if (name.equals("Location", true)) location else null
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
}
