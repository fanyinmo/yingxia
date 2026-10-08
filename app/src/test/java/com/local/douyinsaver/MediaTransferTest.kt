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

    @Test fun existingDestinationsIncludingEmptyFilesAreRejectedWithoutRequestsOrDeletion() {
        val numeric = "https://24898382.ydycdn.com:58001/existing.mp4"
        for ((index, original) in listOf(ByteArray(0), bytes).withIndex()) {
            for ((urlIndex, url) in listOf(clean, numeric).withIndex()) {
                val file = File(temporary.root, "existing_${index}_$urlIndex").apply { writeBytes(original) }
                var requests = 0; var cookieReads = 0; var validations = 0; var progress = 0
                val transfer = MediaTransfer(connections = { uri -> requests++; response(uri) },
                    cookies = { cookieReads++; null })
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { transfer.fetch(url, file, 100, "素材", validateUrl = { validations++ }) { _, _ -> progress++ } }
                }
                assertEquals(0, requests); assertEquals(0, cookieReads)
                assertEquals(0, validations); assertEquals(0, progress)
                assertTrue(file.isFile)
                assertArrayEquals(original, file.readBytes())
            }
        }
    }

    @Test fun anExistingDirectoryAndItsContentsAreNeverRemoved() {
        val directory = temporary.newFolder("existing_directory")
        val original = File(directory, "caller_source").apply { writeBytes(bytes) }
        var requests = 0
        val transfer = MediaTransfer(connections = { uri -> requests++; response(uri) }, cookies = { null })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(clean, directory, 100, "图片") { _, _ -> } }
        }
        assertEquals(0, requests); assertTrue(directory.isDirectory)
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun truncatedResponsesRemoveOnlyTheNewlyOwnedPartialFileAndDisconnect() {
        lateinit var response: FakeConnection
        val transfer = MediaTransfer(connections = { uri ->
            FakeConnection(uri, 200, null, bytes, bytes.size + 1L).also { response = it }
        }, cookies = { null })
        val file = destination()
        var progressed = false
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(clean, file, 100, "图片") { done, _ ->
                progressed = true; assertEquals(bytes.size.toLong(), done); assertTrue(file.exists())
            } }
        }
        assertTrue(progressed); assertTrue(response.disconnected)
        assertFalse(file.exists())
    }

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

    @Test fun sharedTransferSavesTheNumericColdRelayWithTheExactTlsPortAndSignedBytes() = runBlocking {
        val original = ByteArray(64) { (it * 37).toByte() }.apply { "ftyp".toByteArray().copyInto(this, 4) }
        val entry = "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0200f0000fixture"
        val cold = "https://n98-v-ncdncold.douyinvod.com/first.mp4"
        val numeric = "https://24898382.ydycdn.com:58001/a%2Fb.mp4?signature=a%2Bb%3D&x=&x=1"
        val requested = mutableListOf<String>()
        val transfer = MediaTransfer(connections = { uri ->
            requested += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, cold)
                cold -> response(uri, 302, numeric)
                numeric -> FakeConnection(uri, 200, null, original)
                else -> error("Unexpected numeric CDN request: ${uri.host}")
            }
        }, cookies = { null })
        val file = destination()
        val count = transfer.fetch(entry, file, 100, "视频", validateUrl = {
            WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, listOf(MediaSource(entry, WatermarkMode.CLEAN)))
        }) { _, _ -> }
        assertEquals(listOf(entry, cold, numeric), requested)
        assertEquals(original.size.toLong(), count)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun missingRangeIdentityFallsBackToANewOwnedFullDownload() = runBlocking {
        val original = ByteArray(64) { (it * 29).toByte() }.apply { "ftyp".toByteArray().copyInto(this, 4) }
        val numeric = "https://24898382.ydycdn.com:58001/fallback.mp4?signature=private_clean"
        val requested = mutableListOf<FakeConnection>()
        val transfer = MediaTransfer(connections = { uri ->
            FakeConnection(uri, if (requested.isEmpty()) 206 else 200, null, original,
                headers = mapOf("Content-Range" to "bytes 0-63/64")).also { requested += it }
        }, cookies = { null })
        val file = destination()
        val count = transfer.fetch(numeric, file, 100, "视频") { _, _ -> }
        assertEquals(2, requested.size)
        assertEquals("bytes=0-99", requested.first().getRequestProperty("Range"))
        assertNull(requested.last().getRequestProperty("Range"))
        assertTrue(requested.all { it.disconnected })
        assertEquals(original.size.toLong(), count)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun numericRelayDownloadStillBlocksUntrustedPortsAndMarkedRedirectsBeforeRequest() {
        val numeric = "https://24898382.ydycdn.com:58001/first.mp4"
        for (target in listOf("https://24898382.ydycdn.com:58002/video.mp4",
            "https://node.ydycdn.com:58001/video.mp4", "$numeric?watermark=1")) {
            val requested = mutableListOf<String>()
            val transfer = MediaTransfer(connections = { uri ->
                requested += uri.toString()
                response(uri, 302, target)
            }, cookies = { null })
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { transfer.fetch(numeric, destination(), 100, "视频", validateUrl = {
                    WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, listOf(MediaSource(numeric, WatermarkMode.CLEAN)))
                }) { _, _ -> } }
            }
            assertEquals(listOf(numeric), requested)
            assertFalse(destination().exists())
        }
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
                    assertTrue("The cancelled file must have been created by this download", file.exists())
                    currentCoroutineContext().cancel()
                }
            } }
        }
        assertEquals(1, requests)
        assertFalse(file.exists())
    }

    private fun response(uri: URI, status: Int = 200, location: String? = null) = FakeConnection(uri, status, location, bytes)
    private class FakeConnection(uri: URI, private val status: Int, private val location: String?,
        private val bytes: ByteArray, private val expectedLength: Long = bytes.size.toLong(),
        private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = "application/octet-stream"
        override fun getContentLengthLong() = expectedLength
        override fun getHeaderField(name: String?): String? = if (name.equals("Location", true)) location
            else headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
}
