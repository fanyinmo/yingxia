package com.local.douyinsaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Complete save paths with deterministic CDN responses, real codecs and isolated owned URIs. */
@RunWith(AndroidJUnit4::class)
class GalleryExportPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val redUrl = "https://p3.douyinpic.com/gallery_test_red"
    private val blueUrl = "https://p3.douyinpic.com/gallery_test_blue"
    private val motionUrl = "https://v3.douyinvod.com/gallery_test_motion.mp4"
    private val bgmUrl = "https://lf-music.douyinstatic.com/gallery_test_bgm.m4a"

    @Test fun liveDownloadPublishesOneRealMotionJpegAndKeepsOriginalClipIncludingAudio() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            val network = network(mapOf(redUrl to cover.readBytes(), motionUrl to source.readBytes()))
            val result = network.downloader.download(content(listOf(image(redUrl, AlbumAssetKind.LIVE, motion = true))), null,
                options(), { _, _ -> }, {}, { saved = it })
            assertEquals(1, result.uris.size); assertEquals(1, result.albumAssets.size)
            val asset = result.albumAssets.single()
            assertEquals(AlbumAssetKind.LIVE, asset.kind); assertTrue(asset.embeddedMotion); assertEquals("", asset.motionUri)
            assertEquals("image/jpeg", asset.mimeType); assertTrue(result.fileName.endsWith("_001_MP.jpg"))
            assertTrue(Uri.parse(asset.uri).path.orEmpty().contains("images"))
            val container = copySaved(result, directory, "saved-live.jpg")
            val verified = EmbeddedMotionReader.validate(container)
            assertTrue(verified.hasAudio)
            val preview = EmbeddedMotionReader.extractForPreview(context, Uri.parse(asset.uri), File(directory, "live-preview.mp4"))
            assertEquals(hash(source), hash(preview))
            assertEquals(listOf(redUrl, motionUrl), network.requests)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(container.absolutePath, bounds)
            assertEquals(1280, bounds.outWidth); assertEquals(720, bounds.outHeight)
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun explicitAnimationConversionPublishesOneSilentMotionPhotoWithRealChangingFrames() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val originalHash = hash(source)
            val network = network(mapOf(redUrl to asset("album_test/first_red.png", directory).readBytes(), motionUrl to source.readBytes()))
            val result = network.downloader.download(content(listOf(image(redUrl, AlbumAssetKind.ANIMATED, true))), null,
                options(AlbumMode.CONVERT_TO_LIVE), { _, _ -> }, {}, { saved = it })
            assertEquals(1, result.uris.size)
            assertEquals("image/jpeg", result.mimeType)
            assertEquals(AlbumAssetKind.LIVE, result.albumAssets.single().kind)
            assertTrue(result.albumAssets.single().embeddedMotion)
            assertTrue(result.fileName.endsWith("_MP.jpg"))
            val photo = copySaved(result, directory, "converted.jpg")
            val actual = EmbeddedMotionReader.validate(photo)
            assertFalse(actual.hasAudio)
            assertTrue(actual.container.presentationTimestampUs >= 0)
            val clip = EmbeddedMotionReader.extractForPreview(photo, File(directory, "converted.mp4"))
            assertEquals(sampleDigest(source), sampleDigest(clip))
            assertColor(clip, 250_000, "red"); assertColor(clip, 1_500_000, "blue")
            assertEquals(originalHash, hash(source))
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun alreadyEmbeddedLivePhotoIsDetectedAndPublishedWithEveryOriginalByte() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory)
            val native = MotionPhotoFixtureWriter.create(cover, source, File(directory, "native_MP.jpg"), 0)
            val originalHash = hash(native)
            val network = network(mapOf(redUrl to native.readBytes()))
            // Metadata may describe this as a JPEG: classification must inspect the actual file.
            val result = network.downloader.download(content(listOf(image(redUrl))), null,
                options(), { _, _ -> }, {}, { saved = it })
            assertEquals(listOf(redUrl), network.requests)
            assertEquals(1, result.uris.size)
            assertEquals(AlbumAssetKind.LIVE, result.albumAssets.single().kind)
            assertTrue(result.albumAssets.single().embeddedMotion)
            val photo = copySaved(result, directory, "preserved.jpg")
            assertEquals(originalHash, hash(photo))
            assertTrue(EmbeddedMotionReader.validate(photo).hasAudio)
            val clip = EmbeddedMotionReader.extractForPreview(photo, File(directory, "preserved.mp4"))
            assertEquals(hash(source), hash(clip))
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun animatedMp4SaveIsSilentAndPreservesEncodedVideoFramesSizeAndTiming() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val network = network(mapOf(redUrl to asset("album_test/first_red.png", directory).readBytes(), motionUrl to source.readBytes()))
            val originalHash = hash(source)
            val result = network.downloader.download(content(listOf(image(redUrl, AlbumAssetKind.ANIMATED, true))), null,
                options(), { _, _ -> }, {}, { saved = it })
            assertEquals(1, result.uris.size); assertEquals("video/mp4", result.mimeType)
            assertEquals(AlbumAssetKind.ANIMATED, result.albumAssets.single().kind)
            assertFalse(result.albumAssets.single().embeddedMotion)
            val output = copySaved(result, directory, "silent.mp4")
            assertEquals(listOf("video/avc"), trackTypes(output))
            assertEquals(sampleDigest(source), sampleDigest(output))
            assertEquals(originalHash, hash(source))
            assertNear(duration(source), duration(output), 100L)
            assertColor(output, 250_000, "red"); assertColor(output, 1_500_000, "blue")
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun unknownDynamicRequiresAnExplicitOriginalExportFormatWithoutPublishingAStillFallback() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        val prefix = "unknown_format_${UUID.randomUUID()}"
        try {
            val source = motionFixture(directory)
            val original = content(listOf(image(redUrl, AlbumAssetKind.DYNAMIC, true)))
            val network = network(mapOf(redUrl to jpeg(directory, 960, 720).readBytes(), motionUrl to source.readBytes()))
            val failure = runCatching {
                network.downloader.download(original, null, options().copy(fileName = prefix), { _, _ -> }, {}, { saved = it })
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("请选择保存为实况照片或无声动图"))
            assertNull(saved); assertNoOwnedFiles(prefix)
            assertEquals(AlbumAssetKind.DYNAMIC, original.images.single().kind)
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun explicitUnknownFormatChoicesNeverReclassifyKnownLiveOrAnimatedNeighbors() = runBlocking(Dispatchers.IO) {
        val directory = directory(); val saved = mutableListOf<SavedVideo>()
        try {
            val source = motionFixture(directory)
            val originalHash = hash(source)
            val bodies = mapOf(redUrl to MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory).readBytes(),
                blueUrl to MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.BLUE_MIDDLE_PNG, directory).readBytes(),
                motionUrl to source.readBytes())
            val original = content(listOf(image(redUrl, AlbumAssetKind.DYNAMIC, true),
                image(blueUrl, AlbumAssetKind.LIVE, true), image(redUrl, AlbumAssetKind.ANIMATED, true)))
            for (mode in listOf(AlbumMode.LIVE_PHOTOS, AlbumMode.MOTION_VIDEOS)) {
                val network = network(bodies)
                val result = network.downloader.download(original, null, options(mode), { _, _ -> }, {}, { saved.add(it) })
                val expectedKinds = listOf(if (mode == AlbumMode.LIVE_PHOTOS) AlbumAssetKind.LIVE else AlbumAssetKind.ANIMATED,
                    AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED)
                assertEquals(expectedKinds, result.albumAssets.map { it.kind })
                assertEquals(listOf(0, 1, 2), result.albumAssets.map { it.sourceIndex })
                assertEquals(3, result.uris.size)
                result.albumAssets.forEachIndexed { index, entry ->
                    val file = copySaved(result.copy(uri = entry.uri), directory, "$mode-$index.${if (entry.embeddedMotion) "jpg" else "mp4"}")
                    if (expectedKinds[index] == AlbumAssetKind.LIVE) {
                        assertEquals("image/jpeg", entry.mimeType); assertTrue(entry.embeddedMotion)
                        assertTrue(EmbeddedMotionReader.validate(file).hasAudio)
                        val clip = EmbeddedMotionReader.extractForPreview(file, File(directory, "$mode-$index-preview.mp4"))
                        assertEquals(originalHash, hash(clip))
                    } else {
                        assertEquals("video/mp4", entry.mimeType); assertFalse(entry.embeddedMotion)
                        assertEquals(listOf("video/avc"), trackTypes(file))
                        assertEquals(sampleDigest(source), sampleDigest(file))
                        assertNear(duration(source), duration(file), 100L)
                    }
                    assertEquals("", entry.motionUri)
                }
                assertEquals(listOf(redUrl, motionUrl, blueUrl, motionUrl, redUrl, motionUrl), network.requests)
            }
            assertEquals(listOf(AlbumAssetKind.DYNAMIC, AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED), original.images.map { it.kind })
            assertEquals(originalHash, hash(source))
        } finally { saved.forEach(::deleteSaved); directory.deleteRecursively() }
        Unit
    }

    @Test fun unknownDynamicCanExplicitlyChooseGifWithoutPretendingItsMp4IsALivePhoto() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val original = content(listOf(image(redUrl, AlbumAssetKind.DYNAMIC, true)))
            val network = network(mapOf(redUrl to jpeg(directory, 960, 720).readBytes(), motionUrl to source.readBytes()))
            val result = network.downloader.download(original, null, options(AlbumMode.GIF), { _, _ -> }, {}, { saved = it })
            val entry = result.albumAssets.single()
            assertEquals(AlbumAssetKind.ANIMATED, entry.kind); assertEquals("image/gif", entry.mimeType)
            assertFalse(entry.embeddedMotion); assertEquals(0, entry.sourceIndex)
            val frames = gifFrames(copySaved(result, directory, "unknown-choice.gif").readBytes())
            assertTrue(frames.map { classify(it.second) }.toSet().containsAll(listOf("red", "blue")))
            assertNear(duration(source), frames.sumOf { it.first }.toLong() * 10L, 30L)
            assertEquals(AlbumAssetKind.DYNAMIC, original.images.single().kind)
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun staticGalleryCreatesOneLoopingGifWithTwoDifferentFramesAtPointOneSecondEach() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val red = asset("album_test/first_red.png", directory); val blue = asset("album_test/second_blue.png", directory)
            val hashes = listOf(hash(red), hash(blue))
            val network = network(mapOf(redUrl to red.readBytes(), blueUrl to blue.readBytes()))
            val result = network.downloader.download(content(listOf(image(redUrl), image(blueUrl))), null,
                options(AlbumMode.GIF).copy(itemDurationSeconds = 0.1), { _, _ -> }, {}, { saved = it })
            assertEquals(1, result.uris.size); assertEquals("image/gif", result.mimeType)
            assertEquals("image/gif", context.contentResolver.getType(Uri.parse(result.uri)))
            val output = copySaved(result, directory, "sequence.gif")
            val frames = gifFrames(output.readBytes())
            assertEquals(listOf(10, 10), frames.map { it.first })
            assertEquals(listOf("red", "blue"), frames.map { classify(it.second) })
            assertTrue(output.readBytes().toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
            assertEquals(hashes, listOf(hash(red), hash(blue)))
            assertEquals(listOf(redUrl, blueUrl), network.requests)
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun dynamicGifCompatibilityActuallySavesAnimatedImageWithChangingDecodedFrames() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val network = network(mapOf(redUrl to asset("album_test/first_red.png", directory).readBytes(), motionUrl to source.readBytes()))
            val result = network.downloader.download(content(listOf(image(redUrl, AlbumAssetKind.ANIMATED, true))), null,
                options(AlbumMode.GIF), { _, _ -> }, {}, { saved = it })
            assertEquals(1, result.uris.size); assertEquals("image/gif", result.mimeType)
            val output = copySaved(result, directory, "compatibility.gif")
            val frames = gifFrames(output.readBytes())
            assertTrue(frames.size >= 16)
            assertTrue(frames.map { classify(it.second) }.toSet().containsAll(listOf("red", "blue")))
            assertNear(duration(source), frames.sumOf { it.first }.toLong() * 10L, 30L)
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun dynamicBgmCompositionUsesRealMotionAndItsDefaultDurationInsteadOfBlueStaticCover() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val network = network(mapOf(blueUrl to asset("album_test/second_blue.png", directory).readBytes(),
                motionUrl to source.readBytes(), bgmUrl to asset("album_test/short_bgm.m4a", directory).readBytes()))
            var warnings = 0
            val result = network.downloader.download(content(listOf(image(blueUrl, AlbumAssetKind.ANIMATED, true)), withBgm = true), null,
                options(AlbumMode.VIDEO), { _, _ -> }, {}, { saved = it }, { warnings++; true })
            assertEquals(0, warnings); assertEquals("video/mp4", result.mimeType)
            val output = copySaved(result, directory, "dynamic-bgm.mp4")
            assertNear(duration(source), duration(output), 150L)
            assertTrue(trackTypes(output).containsAll(listOf("video/avc", "audio/mp4a-latm")))
            assertColor(output, 250_000, "red"); assertColor(output, 1_500_000, "blue")
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun customDurationWarnsThenTrimsOrLoopsRealMotionWithoutChangingSpeed() = runBlocking(Dispatchers.IO) {
        val directory = directory(); val saved = mutableListOf<SavedVideo>()
        try {
            val source = motionFixture(directory)
            val bodies = mapOf(redUrl to asset("album_test/first_red.png", directory).readBytes(), motionUrl to source.readBytes(),
                bgmUrl to asset("album_test/short_bgm.m4a", directory).readBytes())
            for (seconds in listOf(0.5, 3.5)) {
                val network = network(bodies)
                var warning: List<DurationAdjustment>? = null
                val result = network.downloader.download(content(listOf(image(redUrl, AlbumAssetKind.ANIMATED, true)), withBgm = true), null,
                    options(AlbumMode.VIDEO).copy(itemDurationSeconds = seconds), { _, _ -> }, {}, { saved.add(it) },
                    { changes -> warning = changes; true })
                assertNotNull(warning); assertEquals(0, warning!!.single().index)
                assertEquals(seconds, warning!!.single().requestedSeconds, 0.001)
                assertTrue(warning!!.single().originalSeconds >= 2.0)
                val output = copySaved(result, directory, "custom-$seconds.mp4")
                assertNear((seconds * 1_000).toLong(), duration(output), 150L)
                assertColor(output, 250_000, "red")
                if (seconds > 2) {
                    assertColor(output, 1_500_000, "blue")
                    assertColor(output, 2_250_000, "red")
                    assertColor(output, 3_250_000, "blue")
                }
            }
        } finally { saved.forEach(::deleteSaved); directory.deleteRecursively() }
        Unit
    }

    @Test fun decliningDurationWarningCreatesNoPublishedFilesRecordsOrBgmRequest() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        val prefix = "gallery_decline_${UUID.randomUUID().toString().replace("-", "")}" 
        try {
            val source = motionFixture(directory)
            val network = network(mapOf(redUrl to asset("album_test/first_red.png", directory).readBytes(), motionUrl to source.readBytes()))
            var records = 0; var warning = false
            try {
                network.downloader.download(content(listOf(image(redUrl, AlbumAssetKind.ANIMATED, true)), withBgm = true), null,
                    options(AlbumMode.VIDEO).copy(fileName = prefix, itemDurationSeconds = 0.5), { _, _ -> }, {}, { records++ },
                    { warning = true; false })
                fail("Declining duration confirmation continued saving")
            } catch (_: CancellationException) { }
            assertTrue(warning); assertEquals(0, records)
            assertFalse(network.requests.contains(bgmUrl)); assertNoOwnedFiles(prefix)
            assertTrue(source.exists())
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun mixedSaveKeepsStaticLiveAnimatedOrderAndSingleItemDoesNotFetchNeighbors() = runBlocking(Dispatchers.IO) {
        val directory = directory(); val saved = mutableListOf<SavedVideo>()
        try {
            val source = motionFixture(directory)
            val red = asset("album_test/first_red.png", directory).readBytes()
            val blue = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.BLUE_MIDDLE_PNG, directory).readBytes()
            val gifUrl = "https://p3.douyinpic.com/gallery_test_native.gif"
            val gif = asset("dynamic_album_test/two_frames.gif", directory).readBytes()
            val content = content(listOf(image(redUrl), image(blueUrl, AlbumAssetKind.LIVE, true), image(gifUrl, AlbumAssetKind.ANIMATED)))
            val network = network(mapOf(redUrl to red, blueUrl to blue, motionUrl to source.readBytes(), gifUrl to gif))
            val whole = network.downloader.download(content, null, options(), { _, _ -> }, {}, { saved.add(it) })
            assertEquals(3, whole.uris.size)
            assertEquals(listOf(AlbumAssetKind.STATIC, AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED), whole.albumAssets.map { it.kind })
            assertEquals(listOf(false, true, false), whole.albumAssets.map { it.embeddedMotion })
            assertEquals(listOf(0, 1, 2), whole.albumAssets.map { it.sourceIndex })
            val selectedNetwork = network(mapOf(blueUrl to blue, motionUrl to source.readBytes()))
            val selected = AlbumSelection.select(content, listOf(1))
            val single = selectedNetwork.downloader.download(selected, null, options().copy(selectedImageIndices = listOf(1)),
                { _, _ -> }, {}, { saved.add(it) })
            assertEquals(listOf(blueUrl, motionUrl), selectedNetwork.requests)
            assertEquals(1, single.uris.size); assertEquals(1, single.albumAssets.single().sourceIndex)
            assertTrue(single.fileName.endsWith("_002_MP.jpg"))
        } finally { saved.forEach(::deleteSaved); directory.deleteRecursively() }
        Unit
    }

    @Test fun mixedBgmCompositionKeepsStaticThenActualMotionThenStaticWithDefaultTimes() = runBlocking(Dispatchers.IO) {
        val directory = directory(); var saved: SavedVideo? = null
        try {
            val source = motionFixture(directory)
            val network = network(mapOf(redUrl to asset("album_test/first_red.png", directory).readBytes(),
                blueUrl to asset("album_test/second_blue.png", directory).readBytes(), motionUrl to source.readBytes(),
                bgmUrl to asset("album_test/short_bgm.m4a", directory).readBytes()))
            val result = network.downloader.download(content(listOf(image(blueUrl), image(blueUrl, AlbumAssetKind.ANIMATED, true), image(redUrl)), true),
                null, options(AlbumMode.VIDEO).copy(imageSeconds = 1), { _, _ -> }, {}, { saved = it })
            val output = copySaved(result, directory, "mixed-bgm.mp4")
            assertNear(duration(source) + 2_000L, duration(output), 150L)
            assertColor(output, 250_000, "blue")
            assertColor(output, 1_250_000, "red")
            assertColor(output, 2_500_000, "blue")
            assertColor(output, 3_500_000, "red")
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun persistedDecimalStaticDefaultReachesRealGifAndBgmFilesWhileMotionKeepsItsDuration() = runBlocking(Dispatchers.IO) {
        val directory = directory(); val saved = mutableListOf<SavedVideo>()
        val prefix = "gallery_timing_${UUID.randomUUID()}_"
        try {
            val timing = AlbumTimingPreferences(context, prefix)
            val red = asset("album_test/first_red.png", directory)
            val blue = asset("album_test/second_blue.png", directory)
            val sourceHashes = listOf(hash(red), hash(blue))
            val bodies = mapOf(redUrl to red.readBytes(), blueUrl to blue.readBytes())
            for (seconds in listOf(0.1, 1.2)) {
                timing.updateStaticSeconds(seconds)
                val persisted = AlbumTimingPreferences(context, prefix).readStaticSeconds()
                val result = network(bodies).downloader.download(content(listOf(image(redUrl), image(blueUrl))), null,
                    options(AlbumMode.GIF).copy(staticImageSeconds = persisted), { _, _ -> }, {}, { saved.add(it) })
                val frames = gifFrames(copySaved(result, directory, "default-$seconds.gif").readBytes())
                assertEquals(listOf((seconds * 100).toInt(), (seconds * 100).toInt()), frames.map { it.first })
                assertEquals(listOf("red", "blue"), frames.map { classify(it.second) })
                assertEquals("auto;static=${AlbumDurationUiPolicy.seconds(seconds)}", result.albumTimingSignature)
            }
            val persisted = AlbumTimingPreferences(context, prefix).readStaticSeconds()
            val bgm = asset("album_test/short_bgm.m4a", directory).readBytes()
            val staticResult = network(bodies + (bgmUrl to bgm)).downloader.download(
                content(listOf(image(redUrl), image(blueUrl)), true), null,
                options(AlbumMode.VIDEO).copy(staticImageSeconds = persisted), { _, _ -> }, {}, { saved.add(it) })
            val staticVideo = copySaved(staticResult, directory, "default-static-bgm.mp4")
            assertNear(2_400L, duration(staticVideo), 150L)
            assertColor(staticVideo, 300_000, "red"); assertColor(staticVideo, 1_500_000, "blue")
            val source = motionFixture(directory)
            var warnings = 0
            val mixedResult = network(bodies + mapOf(bgmUrl to bgm, motionUrl to source.readBytes())).downloader.download(
                content(listOf(image(blueUrl), image(blueUrl, AlbumAssetKind.ANIMATED, true), image(redUrl)), true), null,
                options(AlbumMode.VIDEO).copy(staticImageSeconds = persisted), { _, _ -> }, {}, { saved.add(it) }, { warnings++; true })
            val mixedVideo = copySaved(mixedResult, directory, "default-mixed-bgm.mp4")
            assertEquals(0, warnings)
            assertNear(duration(source) + 2_400L, duration(mixedVideo), 150L)
            assertColor(mixedVideo, 300_000, "blue"); assertColor(mixedVideo, 1_500_000, "red")
            assertColor(mixedVideo, 2_700_000, "blue"); assertColor(mixedVideo, 3_600_000, "red")
            val exportedHashes = saved.map { result -> hash(copySaved(result, directory, "before-${saved.indexOf(result)}")) }
            timing.updateStaticSeconds(0.1)
            assertEquals(exportedHashes, saved.map { result -> hash(copySaved(result, directory, "after-${saved.indexOf(result)}")) })
            assertEquals(sourceHashes, listOf(hash(red), hash(blue)))
        } finally {
            saved.forEach(::deleteSaved)
            context.deleteSharedPreferences(prefix + "download_options")
            directory.deleteRecursively()
        }
        Unit
    }

    private data class Network(val downloader: AlbumDownloader, val requests: MutableList<String>)
    private fun network(bodies: Map<String, ByteArray>): Network {
        val requests = mutableListOf<String>()
        return Network(AlbumDownloader(context, MediaTransfer(connections = { uri ->
            requests += uri.toString()
            val body = bodies[uri.toString()] ?: error("No request is authorized for this isolated fixture: ${uri.host}")
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentType() = "application/octet-stream"
                override fun getContentLengthLong() = body.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(body)
            }
        }, cookies = { null })), requests)
    }
    private fun options(mode: AlbumMode = AlbumMode.IMAGES) = DownloadOptions(albumMode = mode,
        fileName = "gallery_pipeline_${UUID.randomUUID().toString().replace("-", "")}")
    private fun content(images: List<ParsedImage>, withBgm: Boolean = false) = ParsedVideo("9999999999999999911", "受控素材保存", "", 0.0, 48, 48,
        images = images, bgmUrl = if (withBgm) bgmUrl else "")
    private fun image(url: String, kind: AlbumAssetKind = AlbumAssetKind.STATIC, motion: Boolean = false) = ParsedImage(url, 48, 48,
        mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)), kind = kind,
        motion = if (motion) ParsedMotion(motionUrl, mediaSources = listOf(MediaSource(motionUrl, WatermarkMode.CLEAN))) else null)
    private fun directory() = File(context.cacheDir, "gallery_pipeline_test_${UUID.randomUUID()}").also { check(it.mkdir()) }
    private fun asset(path: String, directory: File): File = File(directory, path.substringAfterLast('/')).also { output ->
        instrumentation.context.assets.open(path).use { input -> output.outputStream().use { input.copyTo(it) } }
    }
    private suspend fun motionFixture(directory: File): File {
        // Input preparation is independent; every actual BGM download/export remains under test.
        return asset("motion_test/fixed_red_blue_2s.mp4", directory).also { output ->
            assertEquals("Controlled motion fixture differs from its independent manifest",
                "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", hash(output))
        }
    }
    private fun jpeg(directory: File, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return File(directory, "cover.jpg").also { file ->
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }
    private fun copySaved(saved: SavedVideo, directory: File, name: String): File = File(directory, name).also { output ->
        context.contentResolver.openInputStream(Uri.parse(saved.uri))!!.use { input -> output.outputStream().use { input.copyTo(it) } }
    }
    private fun deleteSaved(saved: SavedVideo?) { saved?.uris?.forEach { context.contentResolver.delete(Uri.parse(it), null, null) } }
    private fun assertNoOwnedFiles(prefix: String) {
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?", arrayOf("$prefix*"), null)!!.use { assertEquals(0, it.count) }
        }
    }
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun duration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try { retriever.setDataSource(file.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
        finally { retriever.release() }
    }
    private fun trackTypes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try { extractor.setDataSource(file.absolutePath); (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() } }
        finally { extractor.release() }
    }
    private fun sampleDigest(file: File): String {
        val extractor = MediaExtractor(); val digest = MessageDigest.getInstance("SHA-256")
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            while (true) {
                buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                val data = ByteArray(size); buffer.position(0); buffer.get(data); digest.update(data)
                digest.update(ByteBuffer.allocate(12).putLong(extractor.sampleTime).putInt(extractor.sampleFlags).array())
                extractor.advance()
            }
        } finally { extractor.release() }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun assertNear(expected: Long, actual: Long, tolerance: Long) { assertTrue("Expected $expected ms, actual $actual ms", abs(expected - actual) <= tolerance) }
    private fun assertColor(file: File, timestampUs: Long, expected: String) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("No frame at $timestampUs")
            try { assertEquals("Frame at $timestampUs us was flattened or reordered", expected, classify(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))) }
            finally { bitmap.recycle() }
        } finally { retriever.release() }
    }
    private fun classify(color: Int): String = when {
        Color.red(color) > Color.blue(color) + 80 -> "red"
        Color.blue(color) > Color.red(color) + 80 -> "blue"
        else -> "other"
    }
    /** Independently rebuild each GIF image block to decode its real pixels with Android. */
    private fun gifFrames(data: ByteArray): List<Pair<Int, Int>> {
        assertEquals("GIF89a", data.copyOfRange(0, 6).toString(Charsets.US_ASCII))
        val packed = data[10].toInt() and 255
        val headerEnd = 13 + if (packed and 128 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0
        val header = data.copyOfRange(0, headerEnd)
        var position = headerEnd; var delay = 0; var control = ByteArray(0)
        val result = mutableListOf<Pair<Int, Int>>()
        fun blocks() { while (true) { val size = data[position++].toInt() and 255; if (size == 0) break; position += size; require(position <= data.size) } }
        while (position < data.size) when (data[position].toInt() and 255) {
            0x21 -> {
                val start = position; val type = data[position + 1].toInt() and 255; position += 2
                if (type == 0xf9) delay = (data[position + 2].toInt() and 255) or ((data[position + 3].toInt() and 255) shl 8)
                blocks(); if (type == 0xf9) control = data.copyOfRange(start, position)
            }
            0x2c -> {
                val start = position; val local = data[position + 9].toInt() and 255; position += 10
                if (local and 128 != 0) position += 3 * (1 shl ((local and 7) + 1))
                position++; blocks()
                val single = header + control + data.copyOfRange(start, position) + byteArrayOf(0x3b)
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(single))) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                try { result += delay to bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) } finally { bitmap.recycle() }
            }
            0x3b -> return result
            else -> error("Invalid GIF block")
        }
        error("GIF trailer missing")
    }
}
