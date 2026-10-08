package com.local.douyinsaver

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Real probe, shared transfer policy and Android decoding with self-generated MP4 bytes.
 * Connections are controlled: this is not a real-network VideoDownloader or player test.
 * No user preferences, history, queue, folder selection or existing files are changed.
 */
@RunWith(AndroidJUnit4::class)
class ColdCdnPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val entry = "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0200f0000cold_fixture"
    private val cold = "https://v5-coldx.douyinvod.com/fixture.mp4?token=cold_fixture_signature"
    private val firstHost = "1AAAPDVQ9V7RO58H1WXTK5JDB4O5MTOGKB6YWG7NOEY.bdcgslb.com"
    private val secondHost = "1AAAUMQG5W1GGJGYJ6U2RGVZZXEGYJT3KXA4FLTUS4A.bdcgslb.com"
    private val first = "https://$firstHost/fixture%2Fvideo.mp4?token=first_fixture_signature&part=a%2Bb%3D"
    private val second = "https://$secondHost/fixture%2Fvideo.mp4?token=second_fixture_signature&part=a%2Bb%3D"

    @Test fun numericColdRelayCanProbeTransferAndDecodeAnOriginalMp4OnItsConfirmedTlsPort() = withDirectory { directory ->
        val original = createMp4(directory)
        val bytes = original.readBytes()
        val coldNode = "https://n98-v-ncdncold.douyinvod.com/fixture.mp4"
        val relay = "https://24898382.ydycdn.com:58001/fixture%2Fvideo.mp4?token=fixture_signature&part=a%2Bb%3D"
        val probeRequests = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            probeRequests += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, coldNode)
                coldNode -> response(uri, 302, relay)
                relay -> response(uri, 206, bytes = bytes)
                else -> error("Unexpected controlled numeric CDN request: ${uri.host}")
            }
        }, cookies = { null })
        val verified = probe.verify(content(), diagnostics::add)
        assertEquals(listOf(entry, coldNode, relay), probeRequests)
        assertEquals(relay, verified.mediaUrl)
        assertTrue(diagnostics.any { it.contains("host=24898382.ydycdn.com port=58001 allowed=true") })
        assertFalse(diagnostics.any { it.contains("fixture_signature") || it.contains("token=") })

        val transferRequests = mutableListOf<String>()
        val transfer = MediaTransfer(connections = { uri ->
            transferRequests += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, coldNode)
                coldNode -> response(uri, 302, relay)
                relay -> response(uri, 200, bytes = bytes)
                else -> error("Unexpected controlled numeric CDN request: ${uri.host}")
            }
        }, cookies = { null })
        val destination = File(directory, "numeric-relay-received.mp4")
        val size = transfer.fetch(entry, destination, bytes.size.toLong() + 1, "自制视频",
            validateUrl = { WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, verified.mediaSources) }) { _, _ -> }
        assertEquals(listOf(entry, coldNode, relay), transferRequests)
        assertEquals(bytes.size.toLong(), size)
        assertArrayEquals(bytes, destination.readBytes())
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(destination.absolutePath)
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 3_800L..4_200L)
            val frame = checkNotNull(retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST))
            try { assertTrue(frame.width > 0 && frame.height > 0) } finally { frame.recycle() }
        } finally { retriever.release() }
    }

    @Test fun confirmedColdCdnSourceCanRefreshThroughTheSharedTransferAndDecodeLocally() = withDirectory { directory ->
        val mp4 = createMp4(directory)
        val bytes = mp4.readBytes()
        val probeRequests = mutableListOf<String>()
        val probeConnections = mutableListOf<FakeConnection>()
        val diagnostics = mutableListOf<String>()
        val probe = MediaProbe(connections = { uri ->
            probeRequests += uri.toString()
            when (uri.toString()) {
                entry -> response(uri, 302, cold)
                cold -> response(uri, 302, first)
                first -> response(uri, 206, bytes = bytes)
                else -> error("Unexpected probe request: ${uri.host}")
            }.also { probeConnections += it }
        }, cookies = { null })
        val verified = probe.verify(content(), diagnostics::add)
        val selected = WatermarkSources.select(verified, WatermarkMode.CLEAN)
        assertEquals(listOf(entry, cold, first), probeRequests)
        assertEquals(first, selected.mediaUrl)
        assertTrue(verified.mediaSources.contains(MediaSource(entry, WatermarkMode.CLEAN)))
        assertTrue(verified.mediaSources.contains(MediaSource(first, WatermarkMode.CLEAN)))
        assertTrue(probeConnections.all { it.disconnected })
        assertEquals(32, probeConnections.last().bytesRead)
        assertTrue(diagnostics.map { DiagnosticText.clean(it) }.any {
            it.contains("host=$firstHost") && it.contains("allowed=true")
        })
        assertFalse(diagnostics.any { it.contains("fixture_signature") || it.contains("token=") })

        // A later request may get another legitimate dynamic host, after the probe is finished.
        val transferRequests = mutableListOf<String>()
        val transferConnections = mutableListOf<FakeConnection>()
        val transfer = MediaTransfer(connections = { uri ->
            transferRequests += uri.toString()
            when (uri.toString()) {
                first -> response(uri, 302, second)
                second -> response(uri, 200, bytes = bytes)
                else -> error("Unexpected transfer request: ${uri.host}")
            }.also { transferConnections += it }
        }, cookies = { null })
        val destination = File(directory, "received.mp4")
        var reported = 0L
        val size = transfer.fetch(selected.mediaUrl, destination, bytes.size.toLong() + 1, "自制视频",
            validateUrl = { WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, selected.mediaSources) }) { done, _ ->
            reported = done
        }
        assertEquals(listOf(first, second), transferRequests)
        assertEquals(bytes.size.toLong(), size)
        assertEquals(size, reported)
        assertArrayEquals(bytes, destination.readBytes())
        assertTrue(transferConnections.all { it.disconnected })
        assertEquals(WatermarkMode.CLEAN, WatermarkSources.actualMode(verified, WatermarkMode.CLEAN))

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(destination.absolutePath)
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() > 0)
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt() > 0)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("The transferred local MP4 has an invalid duration: $duration", duration in 3_800L..4_200L)
            val frame = checkNotNull(retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)) {
                "The transferred MP4 cannot decode a real frame"
            }
            try { assertTrue(frame.width > 0 && frame.height > 0) } finally { frame.recycle() }
        } finally { retriever.release() }
    }

    @Test fun coldCdnProbeDoesNotRequestAnUnknownOrWatermarkedThirdRedirect() = runBlocking(Dispatchers.IO) {
        val targets = listOf(
            "https://unknown.example/fixture.mp4",
            "https://$secondHost/fixture.mp4?watermark=1",
        )
        for (target in targets) {
            val requests = mutableListOf<String>()
            val connections = mutableListOf<FakeConnection>()
            val probe = MediaProbe(connections = { uri ->
                requests += uri.toString()
                when (uri.toString()) {
                    entry -> response(uri, 302, cold)
                    cold -> response(uri, 302, first)
                    first -> response(uri, 302, target)
                    else -> error("Rejected redirect was requested: ${uri.host}")
                }.also { connections += it }
            }, cookies = { null })
            var failure: Exception? = null
            try { probe.verify(content()) } catch (error: Exception) { failure = error }
            assertNotNull("A forbidden third redirect was accepted", failure)
            assertEquals(listOf(entry, cold, first), requests)
            assertFalse(requests.contains(target))
            assertTrue(connections.all { it.disconnected })
        }
    }

    @Test fun sharedTransferRejectsAChangedSourceAndLeavesNoPartialFile() = withDirectory { directory ->
        val targets = listOf(
            "https://unknown.example/fixture.mp4",
            "https://$secondHost/fixture.mp4?watermark=1",
            "https://$secondHost/already_classified_marked.mp4",
        )
        for ((index, target) in targets.withIndex()) {
            val requests = mutableListOf<String>()
            val connections = mutableListOf<FakeConnection>()
            val transfer = MediaTransfer(connections = { uri ->
                requests += uri.toString()
                check(uri.toString() == first) { "Rejected redirect was requested: ${uri.host}" }
                response(uri, 302, target).also { connections += it }
            }, cookies = { null })
            val sources = listOf(MediaSource(first, WatermarkMode.CLEAN),
                MediaSource(target, WatermarkMode.WATERMARKED))
            val destination = File(directory, "rejected_$index.mp4")
            var failure: Exception? = null
            try {
                transfer.fetch(first, destination, 1_024, "自制视频", validateUrl = {
                    WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, sources)
                }) { _, _ -> }
            } catch (error: Exception) { failure = error }
            assertNotNull("A forbidden refreshed source was accepted", failure)
            assertEquals(listOf(first), requests)
            assertFalse(destination.exists())
            assertTrue(connections.all { it.disconnected })
        }
    }

    private fun content() = ParsedVideo("cold-cdn-fixture", "自制冷 CDN 验证", entry, 4.0, 720, 720,
        mediaSources = listOf(MediaSource(entry, WatermarkMode.CLEAN)))

    private suspend fun createMp4(directory: File): File {
        return File(directory, "fixed_red_blue_4s.mp4").also { output ->
            instrumentation.context.assets.open("motion_test/fixed_red_blue_4s.mp4").use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
            val sourceHash = java.security.MessageDigest.getInstance("SHA-256").digest(output.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("Fixed four-second CDN source differs from its independent manifest",
                "b70fa2a2fbaee5cf936aef489b35e47071d837fa34bb5c858c6a9c0920ab1899", sourceHash)
        }
    }

    private fun withDirectory(body: suspend (File) -> Unit) = runBlocking(Dispatchers.IO) {
        withTimeout(180_000L) {
            val directory = File(context.cacheDir, "cold_cdn_fixture_${UUID.randomUUID()}")
            check(directory.mkdir())
            try { body(directory) } finally {
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.deleteRecursively()) { "Could not clean this test's private fixture" }
                assertFalse(directory.exists())
            }
        }
    }

    private fun response(uri: URI, code: Int, location: String? = null, bytes: ByteArray = ByteArray(32)) =
        FakeConnection(uri, code, location, bytes)

    private class FakeConnection(uri: URI, private val status: Int, private val location: String?,
                                 private val bytes: ByteArray) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        var bytesRead = 0
        private val servedBytes = if (status == 206) bytes.copyOfRange(0, minOf(4096, bytes.size)) else bytes
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = "video/mp4"
        override fun getContentLengthLong() = servedBytes.size.toLong()
        override fun getHeaderField(name: String?): String? = when {
            name.equals("Location", true) -> location
            name.equals("Content-Range", true) && status == 206 -> "bytes 0-${servedBytes.size - 1}/${bytes.size}"
            else -> null
        }
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(servedBytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
    }
}
