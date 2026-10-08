package com.local.douyinsaver

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Controlled HTTP responses, ordinary production download/save, and real Android decoding.
 * This verifies container preservation, not acceptance by a particular OEM gallery.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedLiveAlbumDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val imageUrl = "https://p3.douyinpic.com/embedded_live_fixture.jpg"
    private val secondImageUrl = "https://p3.douyinpic.com/embedded_live_second_fixture.jpg"

    @Test fun livePhotosDownloadPreservesAnEmbeddedJpegTaggedLiveWithoutAnExternalMotionUrl() =
        preservesEmbedded(AlbumMode.LIVE_PHOTOS)

    @Test fun imagesDownloadPreservesAnEmbeddedJpegTaggedLiveWithoutAnExternalMotionUrl() =
        preservesEmbedded(AlbumMode.IMAGES)

    @Test fun staticJpegTaggedLiveRejectsTheWholeAlbumBeforePublishingOrRecording() = isolated("static-rejection") { scope ->
        val fixtures = fixtures(scope.directory)
        for (mode in listOf(AlbumMode.LIVE_PHOTOS, AlbumMode.IMAGES)) {
            val network = network(scope, mapOf(imageUrl to fixtures.embedded.readBytes(), secondImageUrl to fixtures.cover.readBytes()))
            val options = options(scope, mode)
            var saving = 0
            var saved = 0
            val failure = runCatching {
                network.downloader.download(content(listOf(image(imageUrl), image(secondImageUrl))), null,
                    options, { _, _ -> }, { saving++ }, { saved++; scope.track(it) })
            }.exceptionOrNull()
            assertTrue("A static cover tagged LIVE must be rejected", failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().contains("第 2 项缺少实况动态内容"))
            assertTrue(failure.message.orEmpty().contains("不会仅保存封面"))
            assertEquals(listOf(imageUrl, secondImageUrl), network.requests)
            assertEquals(0, saving)
            assertEquals("No successful save/record callback is allowed", 0, saved)
            assertTrue("No published or pending owned URI may survive", ownedUris(options.fileName).isEmpty())
            network.assertTemporaryFilesRemoved()
        }
        scope.report.put("rejectsStaticLiveCover", true).put("checksBothOriginalImageModes", true)
    }

    @Test fun declaredEmbeddedMp4WithInvalidVideoTracksIsRejectedBeforePublishing() = isolated("invalid-embedded-video") { scope ->
        val fixtures = fixtures(scope.directory)
        val corrupt = File(scope.directory, "invalid-motion.jpg")
        val info = checkNotNull(MotionPhotoContainer.inspect(fixtures.embedded))
        corrupt.writeBytes(fixtures.embedded.readBytes().apply {
            // Keep the complete JPEG, declared offset/length and ftyp; erase the actual
            // movie after its first header. Header recognition alone must not pass.
            fill(0, info.videoOffset.toInt() + 12, size)
        })
        assertNotNull(MotionPhotoContainer.inspect(corrupt))
        assertEquals("image/jpeg", AlbumMediaValidation.image(corrupt).mimeType)
        for (mode in listOf(AlbumMode.LIVE_PHOTOS, AlbumMode.IMAGES)) {
            val network = network(scope, mapOf(imageUrl to corrupt.readBytes()))
            val options = options(scope, mode)
            var saving = 0
            var saved = 0
            val failure = runCatching {
                network.downloader.download(content(listOf(image(imageUrl))), null, options,
                    { _, _ -> }, { saving++ }, { saved++; scope.track(it) })
            }.exceptionOrNull()
            assertNotNull("An embedded declaration without a valid movie must fail", failure)
            assertEquals(listOf(imageUrl), network.requests)
            assertEquals(0, saving)
            assertEquals(0, saved)
            assertTrue(ownedUris(options.fileName).isEmpty())
            network.assertTemporaryFilesRemoved()
        }
        scope.report.put("rejectsInvalidEmbeddedVideoBeyondItsHeader", true)
    }

    @Test fun explicitCoversModeStillAllowsAStaticCoverAndDiscardsEmbeddedMotionOnlyOnRequest() = isolated("explicit-covers") { scope ->
        val fixtures = fixtures(scope.directory)
        for ((index, source) in listOf(fixtures.cover, fixtures.embedded).withIndex()) {
            val originalHash = hash(source)
            val network = network(scope, mapOf(imageUrl to source.readBytes()))
            val result = network.downloader.download(content(listOf(image(imageUrl))), null,
                options(scope, AlbumMode.COVERS), { _, _ -> }, {}, scope::track)
            val asset = result.albumAssets.single()
            assertEquals(AlbumAssetKind.STATIC, asset.kind)
            assertFalse(asset.embeddedMotion)
            assertEquals("", asset.motionUri)
            assertEquals("image/jpeg", asset.mimeType)
            assertFalse(result.fileName.endsWith("_MP.jpg"))
            val saved = copySaved(result.uri, scope.directory, "explicit-cover-$index.jpg")
            val decoded = AlbumMediaValidation.image(saved)
            assertEquals(1280, decoded.width)
            assertEquals(720, decoded.height)
            assertNull(MotionPhotoContainer.inspect(saved))
            val bytes = saved.readBytes()
            assertEquals(0xff, bytes[bytes.size - 2].toInt() and 255)
            assertEquals(0xd9, bytes.last().toInt() and 255)
            assertEquals(originalHash, hash(source))
            assertEquals(listOf(imageUrl), network.requests)
            assertPublishedImage(result)
            network.assertTemporaryFilesRemoved()
        }
        scope.report.put("explicitCoversBehaviorPreserved", true)
    }

    @Test fun cancellingTheEmbeddedLivePhotoPublicationRollsBackItsPendingUriAndDoesNotRecordIt() = isolated("cancel-publication") { scope ->
        val fixtures = fixtures(scope.directory)
        val network = network(scope, mapOf(imageUrl to fixtures.embedded.readBytes()))
        val options = options(scope, AlbumMode.LIVE_PHOTOS)
        var saving = false
        var recorded = 0
        val failure = runCatching {
            network.downloader.download(content(listOf(image(imageUrl))), null, options,
                { bytes, _ -> if (saving && bytes > 0) throw CancellationException("Controlled publication cancellation") },
                { saving = true }, { recorded++; scope.track(it) })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue("Cancellation must occur after the validated resource reaches publication", saving)
        assertEquals(0, recorded)
        assertTrue(ownedUris(options.fileName).isEmpty())
        assertEquals(listOf(imageUrl), network.requests)
        network.assertTemporaryFilesRemoved()
        scope.report.put("cancelledPendingPublicationRolledBack", true)
    }

    private fun preservesEmbedded(mode: AlbumMode) = isolated(mode.name.lowercase()) { scope ->
        val fixtures = fixtures(scope.directory)
        val packedBytes = fixtures.embedded.readBytes()
        val originalHash = hash(fixtures.embedded)
        val network = network(scope, mapOf(imageUrl to packedBytes))
        val result = network.downloader.download(content(listOf(image(imageUrl))), null,
            options(scope, mode), { _, _ -> }, {}, scope::track)
        assertEquals(listOf(imageUrl), network.requests)
        assertEquals(1, result.uris.size)
        assertEquals(1, result.albumAssets.size)
        assertEquals(AlbumAssetKind.LIVE, result.albumAssets.single().kind)
        assertTrue(result.albumAssets.single().embeddedMotion)
        assertEquals(0, result.albumAssets.single().sourceIndex)
        assertEquals("", result.albumAssets.single().motionUri)
        assertEquals("image/jpeg", result.mimeType)
        assertEquals(WatermarkMode.CLEAN, result.watermarkMode)
        assertEquals(mode.name, result.exportMode)
        assertEquals(1, result.albumSourceCount)
        assertTrue(result.fileName.endsWith("_001_MP.jpg"))
        assertEquals(packedBytes.size.toLong(), result.bytes)
        assertPublishedImage(result)
        val saved = copySaved(result.uri, scope.directory, "saved-MP.jpg")
        assertArrayEquals("The complete native JPEG must be copied byte-for-byte", packedBytes, saved.readBytes())
        val verified = EmbeddedMotionReader.validate(saved)
        assertEquals(1280, verified.width)
        assertEquals(720, verified.height)
        assertTrue(verified.hasAudio)
        assertEquals(0L, verified.container.presentationTimestampUs)
        assertEquals(saved.length(), verified.container.totalBytes)
        val bytes = saved.readBytes()
        val offset = verified.container.videoOffset.toInt()
        assertEquals("JPEG EOI must remain immediately before the original MP4", 0xff, bytes[offset - 2].toInt() and 255)
        assertEquals(0xd9, bytes[offset - 1].toInt() and 255)
        assertEquals("ftyp", bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII))
        assertArrayEquals("The MP4 must extend completely to the file's final byte",
            fixtures.video.readBytes(), bytes.copyOfRange(offset, bytes.size))
        val clip = EmbeddedMotionReader.extractForPreview(saved, File(scope.directory, "saved-original.mp4"))
        assertArrayEquals(fixtures.video.readBytes(), clip.readBytes())
        assertEquals(sampleDigest(fixtures.video), sampleDigest(clip))
        assertAllOriginalFrames(clip, scope.report)
        assertEquals(originalHash, hash(fixtures.embedded))
        network.assertTemporaryFilesRemoved()
        scope.report.put("wholeMotionJpegBytesPreserved", true).put("originalMp4BytesPreserved", true)
            .put("jpegEoiBeforeMp4", true).put("mp4EndsAtEof", true)
            .put("sourceImageKind", "LIVE").put("externalMotionUrlPresent", false)
    }

    private data class Fixtures(val cover: File, val video: File, val embedded: File)
    private suspend fun fixtures(directory: File): Fixtures {
        val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
        val video = File(directory, "original.mp4").also { output ->
            instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        }
        assertEquals("Independent original-video fixture changed",
            "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(video))
        val embedded = MotionPhotoFixtureWriter.createForGallery(cover, video, File(directory, "original-MP.jpg"),
            MotionPhotoContainer.Format.XIAOMI, presentationTimestampUs = 0)
        return Fixtures(cover, video, embedded)
    }

    private fun image(url: String) = ParsedImage(url, 1280, 720,
        mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)), kind = AlbumAssetKind.LIVE, motion = null)

    private fun content(images: List<ParsedImage>) = ParsedVideo("9999999999999999851", "Controlled embedded live photograph", "",
        0.0, 1280, 720, images = images)

    private fun options(scope: Scope, mode: AlbumMode) = DownloadOptions(albumMode = mode, watermarkMode = WatermarkMode.CLEAN,
        fileName = scope.namespace + UUID.randomUUID().toString().replace("-", "")).also { scope.prefixes.add(it.fileName) }

    private class Network(val downloader: AlbumDownloader, val requests: MutableList<String>, val directories: Set<File>) {
        fun assertTemporaryFilesRemoved() {
            assertTrue("The actual download must create its private temporary directory", directories.isNotEmpty())
            assertTrue("The actual download must remove all of its own temporary files", directories.all { !it.exists() })
        }
    }

    private fun network(scope: Scope, bodies: Map<String, ByteArray>): Network {
        val baseline = albumDirectories()
        val directories = mutableSetOf<File>()
        val requests = mutableListOf<String>()
        val transfer = MediaTransfer(connections = { uri ->
            // This is the same injected-response seam as GalleryExportPipelineTest:
            // only known logical CDN URLs are served; normal source validation remains.
            val bytes = checkNotNull(bodies[uri.toString()]) { "Unexpected request outside this controlled source" }
            val created = albumDirectories() - baseline
            check(created.size == 1) { "Cannot establish ownership of the download temporary directory" }
            directories.add(created.single())
            scope.downloadDirectories.add(created.single())
            requests.add(uri.toString())
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentType() = "application/octet-stream"
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        }, cookies = { null })
        return Network(AlbumDownloader(context, transfer), requests, directories)
    }

    private fun albumDirectories(): Set<File> = context.cacheDir.listFiles().orEmpty().filter {
        it.isDirectory && Regex("album_[0-9a-fA-F-]{36}").matches(it.name)
    }.map { it.canonicalFile }.toSet()

    private fun assertPublishedImage(saved: SavedVideo) {
        val uri = Uri.parse(saved.uri)
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
        assertTrue("A live photo must use the image collection", uri.path.orEmpty().contains("images"))
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(saved.fileName, cursor.getString(1))
        }
    }

    private fun copySaved(raw: String, directory: File, name: String) = File(directory, name).also { output ->
        context.contentResolver.openInputStream(Uri.parse(raw))!!.use { input -> output.outputStream().use { input.copyTo(it) } }
    }

    private fun ownedUris(prefix: String): Set<String> {
        require(prefix.startsWith("embedded_live_"))
        val result = mutableSetOf<String>()
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            val arguments = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$prefix*"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), arguments, null)!!.use { cursor ->
                while (cursor.moveToNext()) result.add(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString())
            }
        }
        return result
    }

    /** The independent input manifest fixes all 60 sample times and actual decoded colors.
     * Every saved frame is requested by its presentation index, not only one sync frame.
     */
    private fun assertAllOriginalFrames(file: File, report: JSONObject) {
        val manifest = instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.json").use { it.readBytes() }
        assertEquals("Independent source-decode manifest changed",
            "ac6d70c4a9d9727a3219ccb26c120a96bb307c16ba510ca8b235185375b0c453", hash(manifest))
        val validation = JSONObject(String(manifest, Charsets.UTF_8)).getJSONObject("validation")
        val frames = validation.getJSONObject("independentSequentialDecode").getJSONArray("frames")
        assertEquals(60, frames.length())
        val extractor = MediaExtractor()
        var sampleCount = 0
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            val format = extractor.getTrackFormat(track)
            assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(track)
            while (extractor.sampleSize > 0) {
                assertTrue("Unexpected extra encoded frame", sampleCount < frames.length())
                val expected = frames.getJSONObject(sampleCount)
                assertTrue("Encoded presentation time changed at frame $sampleCount",
                    abs(expected.getLong("ptsUs") - extractor.sampleTime) <= 2L)
                sampleCount++
                if (!extractor.advance()) break
            }
        } finally { extractor.release() }
        assertEquals(frames.length(), sampleCount)
        val retriever = MediaMetadataRetriever()
        var decoded = 0
        var red = 0
        var blue = 0
        try {
            retriever.setDataSource(file.absolutePath)
            assertEquals(2_000L, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong())
            for (start in 0 until frames.length() step 4) {
                val count = minOf(4, frames.length() - start)
                val batch = retriever.getFramesAtIndex(start, count)
                try {
                    assertEquals("All requested saved-video frames must decode", count, batch.size)
                    batch.forEachIndexed { offset, bitmap ->
                        val expected = frames.getJSONObject(start + offset)
                        assertEquals(1280, bitmap.width)
                        assertEquals(720, bitmap.height)
                        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        val color = when {
                            Color.red(pixel) > Color.blue(pixel) + 80 -> "red"
                            Color.blue(pixel) > Color.red(pixel) + 80 -> "blue"
                            else -> "other"
                        }
                        assertEquals("Saved decoded frame ${start + offset} changed", expected.getString("color"), color)
                        for (x in listOf(0, 150, 1129, 1279)) {
                            val edge = bitmap.getPixel(x, bitmap.height / 2)
                            assertTrue("Black side bars lost at frame ${start + offset}",
                                maxOf(Color.red(edge), Color.green(edge), Color.blue(edge)) <= 4)
                        }
                        if (color == "red") red++ else if (color == "blue") blue++
                        decoded++
                    }
                } finally { batch.forEach { it.recycle() } }
            }
        } finally { retriever.release() }
        assertEquals(60, decoded)
        assertEquals(30, red)
        assertEquals(30, blue)
        report.put("encodedVideoFrameCount", sampleCount).put("decodedVideoFrameCount", decoded)
            .put("decodedRedFrameCount", red).put("decodedBlueFrameCount", blue)
            .put("originalGeometryAndPresentationTimes", true)
    }

    private fun sampleDigest(file: File): String {
        val extractor = MediaExtractor()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            extractor.selectTrack(video)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val bytes = ByteArray(size)
                buffer.position(0); buffer.get(bytes)
                digest.update(bytes)
                digest.update(ByteBuffer.allocate(12).putLong(extractor.sampleTime).putInt(extractor.sampleFlags).array())
                if (!extractor.advance()) break
            }
        } finally { extractor.release() }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(file: File) = hash(file.readBytes())
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Scope(val directory: File, val namespace: String, val report: JSONObject) {
        val prefixes = mutableListOf<String>()
        val createdUris = mutableSetOf<String>()
        val downloadDirectories = mutableSetOf<File>()
        fun track(saved: SavedVideo) { createdUris.addAll(saved.uris) }
    }

    private fun isolated(label: String, action: suspend (Scope) -> Unit) = runBlocking(Dispatchers.IO) {
        val namespace = "embedded_live_${UUID.randomUUID().toString().replace("-", "")}_"
        val directory = File(context.cacheDir, namespace).also { check(it.mkdir()) }
        val report = JSONObject().put("test", label).put("scope", "CONTROLLED_ALBUM_DOWNLOAD_EMBEDDED_LIVE")
            .put("success", false).put("realPublicSource", false).put("oemGalleryPlaybackVerified", false)
            .put("installedVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val scope = Scope(directory, namespace, report)
        try {
            action(scope)
            report.put("success", true)
        } catch (error: Throwable) {
            report.put("errorType", error.javaClass.simpleName).put("error", DiagnosticText.clean(error.message.orEmpty(), 800))
            throw error
        } finally {
            val cleanupErrors = mutableListOf<String>()
            val exactOwnedUris = scope.createdUris.toMutableSet()
            scope.prefixes.forEach { prefix -> runCatching { exactOwnedUris.addAll(ownedUris(prefix)) }
                .onFailure { cleanupErrors.add(it.javaClass.simpleName) } }
            // external and external_primary are aliases for these owned primary-volume
            // rows. Prefer the returned URI and delete each image/video row only once.
            val distinctOwnedUris = exactOwnedUris.distinctBy { Uri.parse(it).pathSegments.takeLast(3) }
            distinctOwnedUris.forEach { raw -> runCatching {
                check(context.contentResolver.delete(Uri.parse(raw), null, null) == 1)
            }.onFailure { cleanupErrors.add(it.javaClass.simpleName) } }
            val sourceCleanupVerified = scope.downloadDirectories.all { !it.exists() }
            if (!sourceCleanupVerified) report.put("success", false)
            scope.downloadDirectories.filter { it.exists() }.forEach { owned -> runCatching {
                check(owned.canonicalFile.parentFile == context.cacheDir.canonicalFile &&
                    Regex("album_[0-9a-fA-F-]{36}").matches(owned.name))
                check(owned.deleteRecursively())
            }.onFailure { cleanupErrors.add(it.javaClass.simpleName) } }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            if (!directory.deleteRecursively()) cleanupErrors.add("FixtureDirectoryCleanupFailed")
            scope.prefixes.forEach { prefix -> runCatching { check(ownedUris(prefix).isEmpty()) }
                .onFailure { cleanupErrors.add(it.javaClass.simpleName) } }
            report.put("createdUriCount", distinctOwnedUris.size).put("ownedUriCleanupVerified", cleanupErrors.isEmpty())
                .put("productionTemporaryCleanupVerified", sourceCleanupVerified).put("cleanupErrorCount", cleanupErrors.size)
            if (cleanupErrors.isNotEmpty()) report.put("success", false)
            val reportFile = File(context.cacheDir, "embedded_live_album_result_${namespace.removeSuffix("_")}.json")
            reportFile.writeText(report.toString(2))
            println("embedded_live_album_report=${reportFile.absolutePath}")
            assertTrue("Exact owned URI/file cleanup failed: $cleanupErrors", cleanupErrors.isEmpty())
        }
        Unit
    }
}
