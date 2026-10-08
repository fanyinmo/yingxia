package com.local.douyinsaver

import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID

/** Real GIF encoding failures before publication, using isolated sources, records and owned URIs. */
@RunWith(AndroidJUnit4::class)
class AlbumGifRollbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun cancellationAfterSecondItemEncodesItsFirstFrameRollsBackBothGifs() = runBlocking(Dispatchers.IO) {
        verifyRollback(cancellation = true)
    }

    @Test fun ioFailureAfterSecondItemEncodesItsFirstFrameRollsBackBothGifs() = runBlocking(Dispatchers.IO) {
        verifyRollback(cancellation = false)
    }

    private suspend fun verifyRollback(cancellation: Boolean) {
        val token = UUID.randomUUID().toString().replace("-", "")
        val namespace = "album_gif_rollback_${token}_"
        val directory = File(context.cacheDir, namespace).apply {
            check(canonicalFile.parentFile == context.cacheDir.canonicalFile && mkdir())
        }
        val ownedPrivateFiles = mutableListOf<File>()
        val ownedUris = linkedSetOf<Uri>()
        val cleanupErrors = mutableListOf<String>()
        var pipelineDirectory: File? = null
        try {
            val animation = fixture("two_frames.png", directory, ownedPrivateFiles)
            val cover = fixture("static_cover.png", directory, ownedPrivateFiles)
            val video = File(directory, "source.mp4").also(ownedPrivateFiles::add)
            AnimatedImageVideoConverter(context).convert(AlbumMediaValidation.image(animation), video)
            val sentinel = File(directory, "unrelated-sentinel.bin").also(ownedPrivateFiles::add)
                .apply { writeText("Keep the caller's unrelated file: $token") }
            val originalHashes = listOf(animation, cover, video, sentinel).associateWith(::hash)
            val neighbour = SavedVideo("9999999999999999925", "Controlled neighbour", "content://fixture/$token",
                17L, savedAt = 1L, mimeType = "image/png", isAlbum = true,
                albumAssets = listOf(SavedAlbumAsset("content://fixture/$token", "image/png", AlbumAssetKind.STATIC)))
            val records = DownloadRecords(context, namespace)
            assertTrue(records.history().isEmpty())
            records.save(neighbour)
            val beforeHistory = records.history()
            val coverUrls = (0..1).map { "https://p3.douyinpic.com/rollback_${token}_$it.png" }
            val motionUrls = (0..1).map { "https://v3.douyinvod.com/rollback_${token}_$it.mp4" }
            val content = ParsedVideo("9999999999999999926", "Controlled two-motion GIF rollback", "", 0.0, 48, 48,
                images = (0..1).map { index -> ParsedImage(coverUrls[index], 48, 48,
                    mediaSources = listOf(MediaSource(coverUrls[index], WatermarkMode.CLEAN)), kind = AlbumAssetKind.LIVE,
                    motion = ParsedMotion(motionUrls[index], mediaSources = listOf(MediaSource(motionUrls[index], WatermarkMode.CLEAN)))) })
            val bodies = coverUrls.associateWith { cover.readBytes() } + motionUrls.associateWith { video.readBytes() }
            val requests = mutableListOf<String>()
            val transfer = MediaTransfer(connections = { url ->
                requests += url.toString()
                val bytes = checkNotNull(bodies[url.toString()]) { "Unexpected request outside the controlled rollback work" }
                object : HttpURLConnection(url.toURL()) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getContentType() = "application/octet-stream"
                    override fun getContentLengthLong() = bytes.size.toLong()
                    override fun getInputStream() = ByteArrayInputStream(bytes)
                }
            }, cookies = { null })
            val beforeDirectories = albumDirectories()
            var savingCalls = 0
            var savedCalls = 0
            var gifStarts = 0
            var firstDone = -1L
            var firstTotal = -1L
            var firstCompleteVerified = false
            var secondFirstFrameTriggered = false
            val failure = runCatching {
                AlbumDownloader(context, transfer).download(content, null,
                    DownloadOptions(albumMode = AlbumMode.GIF, fileName = namespace, itemDurationSeconds = null),
                    { done, total ->
                        // All network byte progress precedes onSaving. Each actual GIF then starts at zero.
                        if (savingCalls > 0) {
                            assertTrue(total >= 2L)
                            if (done == 0L) {
                                gifStarts++
                                assertTrue("Unexpected extra conversion", gifStarts <= 2)
                                if (gifStarts == 2) {
                                    assertEquals(firstTotal, firstDone)
                                    val owned = checkNotNull(pipelineDirectory)
                                    val firstGif = File(owned, "motion_0.gif")
                                    assertTrue("The first conversion did not finish", firstGif.isFile)
                                    assertEquals("First GIF is missing its completed trailer", 0x3b,
                                        firstGif.readBytes().last().toInt() and 0xff)
                                    assertChangingGif(firstGif)
                                    assertTrue("The second converter has not created its owned partial file",
                                        File(owned, "motion_1.gif").isFile)
                                    firstCompleteVerified = true
                                }
                            } else if (gifStarts == 1) {
                                firstDone = done
                                firstTotal = total
                            } else if (gifStarts == 2 && done >= 1L) {
                                // VideoGifConverter reports this only after encoder.addFrame, before finish().
                                assertEquals(1L, done)
                                assertTrue(firstCompleteVerified)
                                secondFirstFrameTriggered = true
                                if (cancellation) throw CancellationException("Controlled cancellation after second GIF frame")
                                else throw IOException("Controlled I/O failure after second GIF frame")
                            }
                        }
                    }, {
                        savingCalls++
                        assertEquals(1, savingCalls)
                        val newDirectories = albumDirectories() - beforeDirectories
                        assertEquals("Could not isolate the current pipeline's UUID directory", 1, newDirectories.size)
                        val candidate = newDirectories.single()
                        // Match both fully downloaded motion files before accepting ownership of this directory.
                        assertEquals(originalHashes.getValue(video), hash(File(candidate, "motion_0.mp4")))
                        assertEquals(originalHashes.getValue(video), hash(File(candidate, "motion_1.mp4")))
                        pipelineDirectory = candidate
                    }, { saved ->
                        savedCalls++
                        ownedUris.addAll(saved.uris.map(Uri::parse))
                        records.save(saved)
                    })
            }.exceptionOrNull()
            assertNotNull("The injected conversion failure did not propagate", failure)
            if (cancellation) assertTrue(failure is CancellationException) else assertTrue(failure is IOException)
            assertEquals(1, savingCalls)
            assertEquals(2, gifStarts)
            assertTrue("Failure happened before the second GIF encoded a real frame", secondFirstFrameTriggered)
            assertTrue(firstCompleteVerified)
            assertEquals(listOf(coverUrls[0], motionUrls[0], coverUrls[1], motionUrls[1]), requests)
            assertEquals(0, savedCalls)
            assertEquals(beforeHistory, DownloadRecords(context, namespace).history())
            assertEquals(listOf(AlbumAssetKind.LIVE, AlbumAssetKind.LIVE), content.images.map { it.kind })
            assertFalse("Rollback left this invocation's temporary inputs or either GIF", checkNotNull(pipelineDirectory).exists())
            assertNoOwnedRows(namespace)
            originalHashes.forEach { (file, expected) ->
                assertTrue("Rollback deleted a caller source or neighbour", file.isFile)
                assertEquals("Rollback changed a caller source or neighbour", expected, hash(file))
            }
        } finally {
            // Exact callback URIs and identified UUID files only, including cleanup of a failing fixture.
            ownedUris.forEach { uri -> runCatching {
                check(context.contentResolver.delete(uri, null, null) == 1)
            }.onFailure { cleanupErrors += "URI ${it.javaClass.simpleName}" } }
            pipelineDirectory?.takeIf { it.exists() }?.let { owned -> runCatching {
                check(owned.canonicalFile.parentFile == context.cacheDir.canonicalFile && albumDirectoryName.matches(owned.name))
                listOf("source_0", "source_1", "motion_0.mp4", "motion_1.mp4", "motion_0.gif", "motion_1.gif").forEach { name ->
                    val file = File(owned, name)
                    if (file.exists()) check(file.delete())
                }
                check(owned.delete())
            }.onFailure { cleanupErrors += "pipeline directory ${it.javaClass.simpleName}" } }
            listOf("downloads", "download_tasks").forEach { context.deleteSharedPreferences(namespace + it) }
            ownedPrivateFiles.forEach { file -> runCatching {
                check(file.canonicalFile.parentFile == directory.canonicalFile)
                if (file.exists()) check(file.delete())
            }.onFailure { cleanupErrors += "private file ${it.javaClass.simpleName}" } }
            runCatching {
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.delete())
            }.onFailure { cleanupErrors += "private directory ${it.javaClass.simpleName}" }
            assertTrue("Owned GIF rollback fixture cleanup failed: $cleanupErrors", cleanupErrors.isEmpty())
        }
    }

    private fun assertChangingGif(file: File) {
        val image = AlbumMediaValidation.image(file, GifConversionPolicy.MAX_GIF_BYTES)
        assertEquals("image/gif", image.mimeType)
        assertTrue(image.animated)
        AnimatedImageFrames(image).use { frames ->
            assertTrue(frames.frameCount >= 2)
            val red = frames.frameAt(100L).getPixel(24, 24)
            val blue = frames.frameAt(400L).getPixel(24, 24)
            assertTrue(Color.red(red) > Color.blue(red) + 80)
            assertTrue(Color.blue(blue) > Color.red(blue) + 80)
        }
    }

    private fun albumDirectories(): Set<File> = context.cacheDir.listFiles().orEmpty()
        .filter { it.isDirectory && albumDirectoryName.matches(it.name) }.map { it.canonicalFile }.toSet()

    private fun assertNoOwnedRows(prefix: String) {
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?", arrayOf("$prefix*"), null)!!.use { cursor ->
                assertEquals("Failed encoding published or left a pending owned media row", 0, cursor.count)
            }
        }
    }

    private fun fixture(name: String, directory: File, owned: MutableList<File>): File =
        File(directory, name).also { file ->
            owned.add(file)
            instrumentation.context.assets.open("dynamic_album_test/$name").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val albumDirectoryName = Regex("album_[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}
