package com.local.douyinsaver

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Controlled CDN bytes, actual ContentDownloader/decoders/MediaStore; no network or gallery claim. */
@RunWith(AndroidJUnit4::class)
class ContentDownloaderEmbeddedLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val photoUrl = "https://p3.douyinpic.com/content_native_photo.jpeg?token=owned%2Fsame-image"
    private val neighbourUrl = "https://p3.douyinpic.com/content_native_neighbour.jpeg"
    private val videoUrl = "https://v3.douyinvod.com/content_native_video.mp4"
    private val bgmUrl = "https://lf-music.douyinstatic.com/content_native_bgm.m4a"

    @Test fun knownLiveEmbeddedJpegPassesContentEntryAndKeepsEveryOriginalByteInAllNormalModes() = runBlocking(Dispatchers.IO) {
        val directory = directory(); val saved = mutableListOf<SavedVideo>()
        try {
            val source = motionFixture(directory)
            val sourceHash = hash(source)
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            val native = MotionPhotoFixtureWriter.create(cover, source, File(directory, "owned_original_MP.jpg"), 0)
            val nativeHash = hash(native)
            val input = content(listOf(image(photoUrl, AlbumAssetKind.LIVE, "owned-native-image")))
            assertFalse(WatermarkSources.available(input, WatermarkMode.CLEAN))
            assertNull(WatermarkSources.actualMode(input, WatermarkMode.CLEAN))
            assertTrue(WatermarkSources.requiresEmbeddedLiveVerification(input))
            for (mode in listOf(AlbumMode.IMAGES, AlbumMode.LIVE_PHOTOS, AlbumMode.MOTION_VIDEOS, AlbumMode.CONVERT_TO_LIVE)) {
                val network = network(mapOf(photoUrl to native.readBytes()))
                var saving = 0; var completed = 0
                val result = network.downloader.download(input, null, options(mode), { _, _ -> }, { saving++ },
                    { completed++; saved.add(it) })
                assertEquals(listOf(photoUrl), network.requests)
                assertTrue(network.probeRequests.isEmpty())
                assertEquals(1, saving); assertEquals(1, completed)
                assertEquals(1, result.uris.size); assertEquals(1, result.albumAssets.size)
                assertEquals(WatermarkMode.CLEAN, result.watermarkMode)
                val asset = result.albumAssets.single()
                assertEquals(AlbumAssetKind.LIVE, asset.kind); assertTrue(asset.embeddedMotion)
                assertEquals("", asset.motionUri); assertEquals(0, asset.sourceIndex)
                assertEquals("image/jpeg", context.contentResolver.getType(Uri.parse(asset.uri)))
                assertTrue(result.fileName.endsWith("_001_MP.jpg"))
                assertPublishedImage(result)
                val output = copySaved(result, directory, "preserved-$mode.jpg")
                assertEquals("A real embedded photo was rewritten", nativeHash, hash(output))
                assertTrue(EmbeddedMotionReader.validate(output).hasAudio)
                val clip = EmbeddedMotionReader.extractForPreview(output, File(directory, "preserved-$mode.mp4"))
                assertEquals(sourceHash, hash(clip))
                assertEquals(60, videoSamples(clip))
                assertChangingFrames(clip)
                assertEquals(AlbumAssetKind.LIVE, input.images.single().kind)
                assertNull(input.images.single().motion)
                assertEquals("owned-native-image", input.images.single().imageKey)
                assertFalse(WatermarkSources.available(input, WatermarkMode.CLEAN))
            }
            assertEquals(sourceHash, hash(source)); assertEquals(nativeHash, hash(native))
        } finally { saved.forEach(::deleteSaved); directory.deleteRecursively() }
        Unit
    }

    @Test fun individualNativeLiveSaveDoesNotFetchNeighboursAndRetainsOriginalIndex() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            val native = MotionPhotoFixtureWriter.create(cover, source, File(directory, "individual_MP.jpg"), 0)
            val input = content(listOf(image(neighbourUrl, AlbumAssetKind.STATIC, "neighbour-0"),
                image(photoUrl, AlbumAssetKind.LIVE, "native-1"), image(neighbourUrl, AlbumAssetKind.STATIC, "neighbour-2")))
            val network = network(mapOf(photoUrl to native.readBytes()))
            val result = network.downloader.download(input, null, options().copy(selectedImageIndices = listOf(1)),
                { _, _ -> }, {}, { saved = it })
            assertEquals(listOf(photoUrl), network.requests)
            assertEquals(1, result.uris.size)
            assertEquals(1, result.albumAssets.single().sourceIndex)
            assertEquals(AlbumAssetKind.LIVE, result.albumAssets.single().kind)
            assertEquals(hash(native), hash(copySaved(result, directory, "individual-saved.jpg")))
            assertEquals(listOf("neighbour-0", "native-1", "neighbour-2"), input.images.map { it.imageKey })
            assertNull(input.images[1].motion)
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun liveStaticCoverFailsThroughContentEntryBeforeAllNormalOrDerivedPublication() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            val sourceHash = hash(cover)
            for (mode in listOf(AlbumMode.IMAGES, AlbumMode.LIVE_PHOTOS, AlbumMode.MOTION_VIDEOS,
                AlbumMode.CONVERT_TO_LIVE, AlbumMode.GIF, AlbumMode.VIDEO)) {
                val network = network(mapOf(photoUrl to cover.readBytes()))
                val input = content(listOf(image(photoUrl, AlbumAssetKind.LIVE, "missing-clip")), withBgm = true)
                val option = options(mode); var saving = 0; var completed = 0
                val temporaryBefore = albumScratchNames()
                try {
                    network.downloader.download(input, null, option, { _, _ -> }, { saving++ }, { completed++ })
                    fail("$mode saved a known LIVE cover without motion")
                } catch (error: IllegalArgumentException) {
                    assertTrue(error.message.orEmpty().contains("实况"))
                }
                assertEquals(listOf(photoUrl), network.requests)
                assertTrue(network.probeRequests.isEmpty())
                assertEquals(0, saving); assertEquals(0, completed)
                assertNoPublishedFiles(option.fileName)
                assertEquals(temporaryBefore, albumScratchNames())
            }
            assertEquals(sourceHash, hash(cover))
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun animatedVideoCoverStillFailsInsteadOfUsingPendingLiveEligibility() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            val input = content(listOf(image(photoUrl, AlbumAssetKind.ANIMATED, "animated-only-cover")), withBgm = true)
            assertFalse(WatermarkSources.requiresEmbeddedLiveVerification(input))
            for (mode in listOf(AlbumMode.IMAGES, AlbumMode.MOTION_VIDEOS, AlbumMode.CONVERT_TO_LIVE, AlbumMode.GIF, AlbumMode.VIDEO)) {
                val network = network(mapOf(photoUrl to cover.readBytes())); val option = options(mode)
                var completed = 0
                try {
                    network.downloader.download(input, null, option, { _, _ -> }, {}, { completed++ })
                    fail("$mode accepted an animated item's static substitute")
                } catch (error: IllegalArgumentException) {
                    assertTrue(error.message.orEmpty().contains("静态封面"))
                }
                assertEquals(listOf(photoUrl), network.requests)
                assertEquals(0, completed); assertNoPublishedFiles(option.fileName)
            }
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun explicitCoverDownloadRemainsStaticAndVideoGifKeepsStrictVerifiedVideoPath() = runBlocking(Dispatchers.IO) {
        val directory = directory(); val saved = mutableListOf<SavedVideo>()
        try {
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            val coverNetwork = network(mapOf(photoUrl to cover.readBytes()))
            val covered = coverNetwork.downloader.download(content(listOf(image(photoUrl, AlbumAssetKind.LIVE, "cover-choice"))),
                null, options(AlbumMode.COVERS), { _, _ -> }, {}, { saved.add(it) })
            assertEquals(listOf(photoUrl), coverNetwork.requests)
            assertEquals(AlbumAssetKind.STATIC, covered.albumAssets.single().kind)
            assertFalse(covered.albumAssets.single().embeddedMotion)
            assertNull(MotionPhotoContainer.inspect(copySaved(covered, directory, "explicit-cover.jpg")))

            val source = motionFixture(directory); val sourceHash = hash(source)
            val video = ParsedVideo("9999999999999999932", "owned source video", videoUrl, 2.0, 1280, 720,
                mediaSources = listOf(MediaSource(videoUrl, WatermarkMode.CLEAN)))
            val videoNetwork = network(mapOf(videoUrl to source.readBytes()))
            assertFalse(WatermarkSources.requiresEmbeddedLiveVerification(video))
            val gif = videoNetwork.downloader.download(video, null, options(AlbumMode.GIF).copy(
                gifStartSeconds = 0f, gifDurationSeconds = 2f, gifExportQuality = GifExportQuality.SHARE),
                { _, _ -> }, {}, { saved.add(it) })
            assertEquals(listOf(videoUrl), videoNetwork.probeRequests)
            assertEquals(listOf(videoUrl), videoNetwork.requests)
            assertEquals("image/gif", context.contentResolver.getType(Uri.parse(gif.uri)))
            val gifFile = copySaved(gif, directory, "verified-video.gif")
            val actual = AlbumMediaValidation.image(gifFile)
            assertTrue(actual.animated); assertEquals("image/gif", actual.mimeType)
            assertEquals(480, actual.width); assertEquals(270, actual.height)
            val frames = GifAnimationInspector.frames(gifFile.readBytes())
            assertEquals(20, frames.size); assertEquals(2_000L, frames.sumOf { it.durationMs })
            assertTrue("Full GIF frame decode lost the source motion", frames.map { it.pixel(240, 135) }.distinct().size >= 2)
            assertEquals(sourceHash, hash(source))
        } finally { saved.forEach(::deleteSaved); directory.deleteRecursively() }
        Unit
    }

    private data class Network(val downloader: ContentDownloader, val requests: MutableList<String>, val probeRequests: MutableList<String>)
    private fun network(bodies: Map<String, ByteArray>): Network {
        val requests = mutableListOf<String>(); val probeRequests = mutableListOf<String>()
        fun response(uri: URI, log: MutableList<String>): HttpURLConnection {
            log.add(uri.toString())
            val bytes = bodies[uri.toString()] ?: error("Unexpected controlled-fixture request: ${uri.host}")
            return object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getContentType() = if (uri.toString() == videoUrl) "video/mp4" else "application/octet-stream"
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        }
        return Network(ContentDownloader(context,
            MediaTransfer(connections = { response(it, requests) }, cookies = { null }),
            MediaProbe(connections = { response(it, probeRequests) }, cookies = { null })), requests, probeRequests)
    }
    private fun image(url: String, kind: AlbumAssetKind, key: String) = ParsedImage(url, 1280, 720,
        listOf(MediaSource(url, WatermarkMode.CLEAN)), kind, mimeType = "image/jpeg", imageKey = key)
    private fun content(images: List<ParsedImage>, withBgm: Boolean = false) = ParsedVideo("9999999999999999931",
        "owned embedded live content", "", 0.0, 1280, 720, images = images, bgmUrl = if (withBgm) bgmUrl else "")
    private fun options(mode: AlbumMode = AlbumMode.IMAGES) = DownloadOptions(albumMode = mode,
        fileName = "content_embedded_${UUID.randomUUID().toString().replace("-", "")}")
    private fun directory() = File(context.cacheDir, "content_embedded_test_${UUID.randomUUID()}").also { check(it.mkdir()) }
    private fun motionFixture(directory: File) = File(directory, "original-motion.mp4").also { output ->
        instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
            output.outputStream().use { input.copyTo(it) }
        }
        assertEquals("2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(output))
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun copySaved(saved: SavedVideo, directory: File, name: String) = File(directory, name).also { output ->
        context.contentResolver.openInputStream(Uri.parse(saved.uri))!!.use { input -> output.outputStream().use { input.copyTo(it) } }
    }
    private fun deleteSaved(saved: SavedVideo?) { saved?.uris?.forEach { context.contentResolver.delete(Uri.parse(it), null, null) } }
    private fun albumScratchNames() = context.cacheDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("album_") }.map { it.name }.toSet()
    private fun assertNoPublishedFiles(prefix: String) {
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { uri ->
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?", arrayOf("$prefix*"), null)!!.use { assertEquals(0, it.count) }
        }
    }
    private fun assertPublishedImage(saved: SavedVideo) {
        assertTrue(Uri.parse(saved.uri).path.orEmpty().contains("images"))
        context.contentResolver.query(Uri.parse(saved.uri), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
    private fun videoSamples(file: File): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            extractor.selectTrack(track)
            var count = 0
            while (extractor.sampleTrackIndex == track && extractor.sampleSize > 0) { count++; if (!extractor.advance()) break }
            count
        } finally { extractor.release() }
    }
    private fun assertChangingFrames(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val first = retriever.getScaledFrameAtTime(200_000, MediaMetadataRetriever.OPTION_CLOSEST, 32, 32)!!
            val later = retriever.getScaledFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST, 32, 32)!!
            try { assertNotEquals(first.getPixel(first.width/2, first.height/2), later.getPixel(later.width/2, later.height/2)) }
            finally { first.recycle(); later.recycle() }
        } finally { retriever.release() }
    }
}
