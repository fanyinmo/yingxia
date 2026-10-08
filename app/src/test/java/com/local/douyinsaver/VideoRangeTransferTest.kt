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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URI

class VideoRangeTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://24898382.ydycdn.com:58001/a%2Fb.mp4?signature=private_a%2Bb%3D&x=&x=1"
    private val chunk = VideoRangeTransfer.CHUNK_BYTES.toInt()
    private val stable = "\"stable-video\""
    private fun destination() = File(temporary.root, "range-output.mp4")
    private fun mp4(size: Int) = ByteArray(size) { (it * 37).toByte() }.apply {
        "ftypisom".toByteArray(Charsets.US_ASCII).copyInto(this, 4)
    }

    @Test fun onlyTheConfirmedRelayIsEligibleForOptInRangeDownloads() {
        assertTrue(VideoRangeTransfer.supports(url))
        assertTrue(VideoRangeTransfer.supports(url.replace("ydycdn.com", "YDYCDN.COM")))
        listOf(url.replace(":58001", ":443"), url.replace("24898382", "node"),
            url.replace("24898382", "248983820"), url.replace("https:", "http:"),
            "https://v11-weba.douyinvod.com/video.mp4", "https://user@24898382.ydycdn.com:58001/video.mp4")
            .forEach { assertFalse(it, VideoRangeTransfer.supports(it)) }
    }

    @Test fun multipleSegmentsKeepEveryOriginalByteAndRawSignatureAndUseStrongIfRange() = runBlocking {
        val bytes = mp4(chunk * 2 + 91)
        val connections = mutableListOf<FakeConnection>()
        val diagnostics = mutableListOf<String>()
        val validations = mutableListOf<String>()
        val transfer = transfer(connections) { partial(it, bytes) }
        val progress = mutableListOf<Long>()
        val size = transfer.fetch(url, destination(), bytes.size.toLong(), validations::add, diagnostics::add) { done, total ->
            assertEquals(bytes.size.toLong(), total)
            progress += done
        }
        assertEquals(bytes.size.toLong(), size)
        assertArrayEquals(bytes, destination().readBytes())
        assertEquals(listOf(url, url, url), validations)
        assertEquals(listOf(url, url, url), connections.map { it.url.toString() })
        assertEquals(listOf("bytes=0-${chunk - 1}", "bytes=$chunk-${chunk * 2 - 1}",
            "bytes=${chunk * 2}-${bytes.lastIndex}"), connections.map { it.getRequestProperty("Range") })
        assertEquals(listOf(null, stable, stable), connections.map { it.getRequestProperty("If-Range") })
        assertEquals(listOf(chunk.toLong(), (chunk * 2).toLong(), bytes.size.toLong()), progress)
        assertTrue(connections.all { it.disconnected })
        assertTrue(connections.all { it.getRequestProperty("Accept-Encoding") == "identity" })
        assertFalse(diagnostics.joinToString().contains("private_"))
        assertFalse(diagnostics.joinToString().contains(stable))
    }

    @Test fun aTruncatedSegmentIsRetriedWithoutAppendingAnyOfItsPartialBytes() = runBlocking {
        val bytes = mp4(chunk + 97)
        val connections = mutableListOf<FakeConnection>()
        var first = true
        val transfer = transfer(connections) { connection ->
            partial(connection, bytes).let { reply ->
                if (first) { first = false; reply.copy(failAfter = 123) } else reply
            }
        }
        transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> }
        assertEquals(3, connections.size)
        assertEquals(listOf(null, stable, stable), connections.map { it.getRequestProperty("If-Range") })
        assertEquals(connections[0].getRequestProperty("Range"), connections[1].getRequestProperty("Range"))
        assertArrayEquals(bytes, destination().readBytes())
        assertTrue(connections.all { it.disconnected })
    }

    @Test fun repeatedTruncationIsLimitedToTwoRetriesAndDeletesTheOwnedPartialFile() {
        val bytes = mp4(chunk + 10)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { partial(it, bytes).copy(failAfter = 123) }
        assertThrows(ProtocolException::class.java) {
            runBlocking { transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> } }
        }
        assertEquals(3, connections.size)
        assertTrue(connections.all { it.disconnected })
        assertFalse(destination().exists())
    }

    @Test fun weakMissingAndMalformedEtagsNeverPermitMultipleResponseAssembly() {
        val bytes = mp4(chunk + 10)
        for (etag in listOf(null, "W/$stable", "unquoted-tag", "\"bad\nvalue\"")) {
            val connections = mutableListOf<FakeConnection>()
            val transfer = transfer(connections) { partial(it, bytes).copy(etag = etag) }
            assertThrows(RangeIdentityUnavailableException::class.java) {
                runBlocking { transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> } }
            }
            assertEquals(1, connections.size)
            assertEquals(0, connections.single().bytesRead)
            assertFalse(destination().exists())
        }
    }

    @Test fun aChangedOrMissingStrongValidatorOnALaterSegmentRejectsTheWholeFile() {
        val bytes = mp4(chunk + 10)
        for (etag in listOf("\"different-video\"", null)) {
            val connections = mutableListOf<FakeConnection>()
            val transfer = transfer(connections) { connection ->
                partial(connection, bytes).let { if (connections.size > 1) it.copy(etag = etag) else it }
            }
            assertThrows(IllegalArgumentException::class.java) {
                try {
                    runBlocking { transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> } }
                } catch (error: RangeIdentityUnavailableException) {
                    throw IllegalArgumentException(error)
                }
            }
            assertEquals(2, connections.size)
            assertEquals(0, connections.last().bytesRead)
            assertFalse(destination().exists())
        }
    }

    @Test fun invalidRangeStartEndTotalAndContentLengthAreRejectedBeforeReadingOrAppending() {
        val bytes = mp4(chunk + 10)
        val malformed = listOf(
            "bytes 1-${chunk - 1}/${bytes.size}", "bytes 0-${chunk - 2}/${bytes.size}",
            "bytes 0-${chunk}/${bytes.size}", "bytes 0-${chunk - 1}/${chunk - 1}",
            "bytes 0-${chunk - 1}/*", "bytes 0-${chunk - 1}/9223372036854775808", null,
        )
        for (range in malformed) {
            val connections = mutableListOf<FakeConnection>()
            val transfer = transfer(connections) { partial(it, bytes).copy(range = range) }
            assertThrows(IllegalStateException::class.java) {
                try { runBlocking { transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> } } }
                catch (error: IllegalArgumentException) { throw IllegalStateException(error) }
            }
            assertEquals(0, connections.single().bytesRead)
            assertFalse(destination().exists())
        }
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { partial(it, bytes).copy(declaredLength = chunk - 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> } }
        }
        assertEquals(0, connections.single().bytesRead)
        assertFalse(destination().exists())
    }

    @Test fun aChangedTotalOnALaterSegmentRejectsPreviouslyCommittedData() {
        val bytes = mp4(chunk + 10)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { connection ->
            partial(connection, bytes).let { reply ->
                if (connections.size == 1) reply else reply.copy(range = "bytes $chunk-${bytes.lastIndex}/${bytes.size + 1}")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(url, destination(), bytes.size.toLong() + 10) { _, _ -> } }
        }
        assertEquals(2, connections.size)
        assertFalse(destination().exists())
    }

    @Test fun totalsAboveTheConfiguredLimitAreRejectedWithoutReading() {
        val bytes = mp4(chunk)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { partial(it, bytes).copy(range = "bytes 0-${chunk - 1}/${VideoRangeTransfer.MAX_BYTES + 1}") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(url, destination(), VideoRangeTransfer.MAX_BYTES) { _, _ -> } }
        }
        assertEquals(0, connections.single().bytesRead)
        assertFalse(destination().exists())
    }

    @Test fun aRangeIgnoredFromTheBeginningCanSaveOnlyAnEntire200Response() = runBlocking {
        val bytes = mp4(chunk + 13)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { Reply(status = 200, body = bytes, etag = null) }
        transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> }
        assertEquals(1, connections.size)
        assertArrayEquals(bytes, destination().readBytes())
    }

    @Test fun a200AfterPartialSegmentsRestartsAtZeroAndNeverAppendsTheWholeFile() = runBlocking {
        val oldBytes = mp4(chunk + 17)
        val replacement = mp4(chunk + 61).also { it[123] = 11 }
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) {
            if (connections.size == 1) partial(it, oldBytes) else Reply(status = 200, body = replacement, etag = "\"new-video\"")
        }
        val size = transfer.fetch(url, destination(), replacement.size.toLong()) { _, _ -> }
        assertEquals(replacement.size.toLong(), size)
        assertEquals(2, connections.size)
        assertArrayEquals(replacement, destination().readBytes())
    }

    @Test fun anIncomplete200ReplacementCannotLeaveOldOrPartiallyReplacedBytes() {
        val bytes = mp4(chunk + 17)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) {
            if (connections.size == 1) partial(it, bytes) else Reply(status = 200, body = bytes.copyOf(123), declaredLength = bytes.size.toLong())
        }
        assertThrows(java.io.EOFException::class.java) {
            runBlocking { transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> } }
        }
        assertEquals(2, connections.size)
        assertFalse(destination().exists())
    }

    @Test fun encodedHtmlNonMp4AndBodiesLongerThanTheRangeAreRejected() {
        val bytes = mp4(100)
        val replies = listOf(
            Reply(206, bytes, "bytes 0-99/100", type = "text/html"),
            Reply(206, bytes, "bytes 0-99/100", encoding = "gzip"),
            Reply(206, ByteArray(100), "bytes 0-99/100"),
            Reply(206, bytes + 1.toByte(), "bytes 0-99/100", declaredLength = 100),
        )
        for (reply in replies) {
            val connections = mutableListOf<FakeConnection>()
            val transfer = transfer(connections) { reply }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { transfer.fetch(url, destination(), 100) { _, _ -> } }
            }
            assertEquals(1, connections.size)
            assertFalse(destination().exists())
        }
    }

    @Test fun redirectsUseTheSharedTrustRulesAndTheSelectedRenditionGuardBeforeAnyNextRequest() {
        val targets = listOf("https://unknown.example/private_path?token=private_secret",
            "https://24898382.ydycdn.com:58002/video.mp4",
            "https://24898382.ydycdn.com:58001/video.mp4?watermark=1")
        for (target in targets) {
            val connections = mutableListOf<FakeConnection>()
            val transfer = transfer(connections) { Reply(302, ByteArray(0), location = target) }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { transfer.fetch(url, destination(), 100, validateUrl = {
                    WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, listOf(MediaSource(url, WatermarkMode.CLEAN)))
                }) { _, _ -> } }
            }
            assertEquals(listOf(url), connections.map { it.url.toString() })
            assertTrue(connections.single().disconnected)
            assertFalse(destination().exists())
        }
    }

    @Test fun anAllowedRedirectPreservesTheRangeAndGuardWithoutChangingSignedBytes() = runBlocking {
        val bytes = mp4(100)
        val target = "https://24898383.ydycdn.com:58001/a%2fb.mp4?signature=private_b%2bA%3d"
        val connections = mutableListOf<FakeConnection>()
        val guards = mutableListOf<String>()
        val transfer = transfer(connections) {
            if (it.url.toString() == url) Reply(302, ByteArray(0), location = target) else partial(it, bytes)
        }
        transfer.fetch(url, destination(), 100, validateUrl = guards::add) { _, _ -> }
        assertEquals(listOf(url, target), guards)
        assertEquals(listOf(url, target), connections.map { it.url.toString() })
        assertTrue(connections.all { it.getRequestProperty("Range") == "bytes=0-99" })
        assertArrayEquals(bytes, destination().readBytes())
    }

    @Test fun explicitCancellationDisconnectsAndDoesNotCommitOrKeepThePartialFile() {
        val bytes = mp4(100)
        val connections = mutableListOf<FakeConnection>()
        lateinit var transfer: VideoRangeTransfer
        transfer = transfer(connections) { partial(it, bytes).copy(onRead = { transfer.cancel() }) }
        assertThrows(CancellationException::class.java) {
            runBlocking { transfer.fetch(url, destination(), 100) { _, _ -> } }
        }
        assertEquals(1, connections.size)
        assertTrue(connections.single().disconnected)
        assertFalse(destination().exists())
    }

    @Test fun parentCancellationAfterACommittedSegmentStopsBeforeAnotherRequestAndDeletesTheTempFile() {
        val bytes = mp4(chunk + 10)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { partial(it, bytes) }
        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(Job()) {
                transfer.fetch(url, destination(), bytes.size.toLong()) { _, _ -> currentCoroutineContext().cancel() }
            } }
        }
        assertEquals(1, connections.size)
        assertFalse(destination().exists())
    }

    @Test fun anExistingNonemptyFileIsNeverTruncatedOrDeleted() {
        val original = byteArrayOf(1, 2, 3)
        destination().writeBytes(original)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { error("Must not connect") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfer.fetch(url, destination(), 100) { _, _ -> } }
        }
        assertTrue(connections.isEmpty())
        assertArrayEquals(original, destination().readBytes())
    }

    @Test fun callbacksAndLocalWritesDoNotBecomeRemoteRetryAttempts() {
        val bytes = mp4(100)
        val connections = mutableListOf<FakeConnection>()
        val transfer = transfer(connections) { partial(it, bytes) }
        assertThrows(java.io.IOException::class.java) {
            runBlocking { transfer.fetch(url, destination(), 100) { _, _ -> throw java.io.IOException("local progress failure") } }
        }
        assertEquals(1, connections.size)
        assertFalse(destination().exists())
    }

    private fun transfer(connections: MutableList<FakeConnection>, reply: (FakeConnection) -> Reply) =
        VideoRangeTransfer(connections = { uri -> FakeConnection(uri, reply).also { connections += it } }, cookies = { null })

    private fun partial(connection: FakeConnection, bytes: ByteArray): Reply {
        val requested = connection.getRequestProperty("Range").removePrefix("bytes=").split('-').map(String::toInt)
        val end = minOf(requested[1], bytes.lastIndex)
        return Reply(206, bytes.copyOfRange(requested[0], end + 1), "bytes ${requested[0]}-$end/${bytes.size}")
    }

    private data class Reply(val status: Int, val body: ByteArray, val range: String? = null,
        val etag: String? = "\"stable-video\"", val declaredLength: Long = body.size.toLong(),
        val type: String = "video/mp4", val encoding: String? = null, val location: String? = null,
        val failAfter: Int? = null, val onRead: (() -> Unit)? = null)

    private class FakeConnection(uri: URI, factory: (FakeConnection) -> Reply) : HttpURLConnection(uri.toURL()) {
        private val reply by lazy { factory(this) }
        var disconnected = false
        var bytesRead = 0
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = reply.status
        override fun getContentType() = reply.type
        override fun getContentLengthLong() = reply.declaredLength
        override fun getHeaderField(name: String?): String? = when {
            name.equals("Content-Range", true) -> reply.range
            name.equals("ETag", true) -> reply.etag
            name.equals("Content-Encoding", true) -> reply.encoding
            name.equals("Location", true) -> reply.location
            else -> null
        }
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(reply.body) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reply.failAfter?.let { if (bytesRead >= it) throw ProtocolException("unexpected end of stream") }
                val limit = reply.failAfter?.let { minOf(length, it - bytesRead) } ?: length
                val count = super.read(buffer, offset, limit)
                if (count > 0) bytesRead += count
                reply.onRead?.invoke()
                return count
            }
            override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
        }
    }
}
