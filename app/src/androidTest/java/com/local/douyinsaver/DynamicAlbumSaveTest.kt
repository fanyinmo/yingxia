package com.local.douyinsaver

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Generated colors and codec fixtures only; no existing media, app data, or network is changed. */
@RunWith(AndroidJUnit4::class)
class DynamicAlbumSaveTest {
    @org.junit.Rule @JvmField val diagnostic = OwnTestThreadDiagnosticRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun detectsActualGifWebpAndApngAnimationWithoutTrustingSuffix() {
        directory().also { directory ->
            try {
                listOf("two_frames.gif" to "image/gif", "two_frames.webp" to "image/webp", "two_frames.png" to "image/png")
                    .forEach { (name, mime) ->
                        val file = fixture(name, directory)
                        val original = file.readBytes()
                        val validation = AlbumMediaValidation.image(file)
                        assertEquals(mime, validation.mimeType)
                        assertTrue("Animation was mistaken for a still image: $name", validation.animated)
                        assertTrue("Validation rewrote original animation", original.contentEquals(file.readBytes()))
                    }
                assertFalse(AlbumMediaValidation.image(fixture("static_cover.png", directory)).animated)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun savesGifAndWebpBytesAndMimeTypesToMediaStore() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        val entries = listOf("two_frames.gif", "two_frames.webp").map {
            LocalAlbumEntry(AlbumMediaValidation.image(fixture(it, directory)))
        }
        val content = content(entries, listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.ANIMATED))
        var saved: SavedVideo? = null
        try {
            val result = AlbumDownloader(context).saveLocalEntries(content, entries, DownloadStorage(context), null,
                DownloadOptions(), { _, _ -> }, { saved = it })
            assertEquals(2, result.uris.size)
            assertEquals(listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.ANIMATED), result.albumAssets.map { it.kind })
            assertEquals(entries.sumOf { it.image.file.length() }, result.bytes)
            entries.forEachIndexed { index, entry ->
                val uri = Uri.parse(result.uris[index])
                assertPreserved(entry.image.file, uri)
                assertEquals(entry.image.mimeType, context.contentResolver.getType(uri))
                assertEquals(uri.toString(), result.albumAssets[index].uri)
            }
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun preservesMixedImageOrderAndSingleEmbeddedLivePhoto() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        var saved: SavedVideo? = null
        try {
            val cover = AlbumMediaValidation.image(fixture("static_cover.png", directory))
            val animated = AlbumMediaValidation.image(fixture("two_frames.gif", directory))
            val motion = liveFixture(directory)
            val liveCover = AlbumMediaValidation.image(MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory))
            val entries = listOf(LocalAlbumEntry(cover), LocalAlbumEntry(liveCover, motion), LocalAlbumEntry(animated))
            val content = content(entries, listOf(AlbumAssetKind.STATIC, AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED))
            val result = AlbumDownloader(context).saveLocalEntries(content, entries, DownloadStorage(context), null,
                DownloadOptions(fileName = "dynamic_album_${UUID.randomUUID()}"), { _, _ -> }, { saved = it })
            assertEquals(3, result.uris.size)
            assertEquals("image/*", result.mimeType)
            assertEquals(3, result.albumAssets.size)
            assertEquals(result.uris, result.albumAssets.map { it.uri })
            assertEquals("", result.albumAssets[1].motionUri)
            assertTrue(result.albumAssets[1].embeddedMotion)
            assertEquals(AlbumAssetKind.LIVE, result.albumAssets[1].kind)
            assertEquals(result.uris.first(), result.coverUri)
            listOf(cover.file, null, animated.file).forEachIndexed { index, file ->
                val uri = Uri.parse(result.uris[index])
                if (file != null) assertPreserved(file, uri)
                else {
                    val packed = File(directory, "saved-live.jpg")
                    context.contentResolver.openInputStream(uri)!!.use { input -> packed.outputStream().use { input.copyTo(it) } }
                    EmbeddedMotionReader.validate(packed)
                    val extracted = EmbeddedMotionReader.extractForPreview(packed, File(directory, "extracted-motion.mp4"))
                    assertTrue(motion.readBytes().contentEquals(extracted.readBytes()))
                }
                val expectedMime = when (index) { 1 -> "image/jpeg"; 2 -> "image/gif"; else -> "image/png" }
                assertEquals(expectedMime, context.contentResolver.getType(uri))
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(1))
                    assertTrue(it.getString(0).contains("_${(index + 1).toString().padStart(3, '0')}"))
                    if (index == 1) assertTrue(it.getString(0).endsWith("_MP.jpg"))
                }
            }
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun cancellingLiveVideoCopyRollsBackCoverAndVideoTogether() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        val prefix = "livecancel${UUID.randomUUID().toString().replace("-", "")}" // Owned, isolated files only.
        try {
            val cover = AlbumMediaValidation.image(MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory))
            val entries = listOf(LocalAlbumEntry(cover, liveFixture(directory)))
            val content = content(entries, listOf(AlbumAssetKind.LIVE))
            try {
                AlbumDownloader(context).saveLocalEntries(content, entries, DownloadStorage(context), null,
                    DownloadOptions(fileName = prefix), { bytes, _ ->
                        if (bytes > cover.file.length()) throw CancellationException("Cancel while copying the paired video")
                    }, { fail("A partial live photo must not be reported saved") })
                fail("Save was not cancelled")
            } catch (_: CancellationException) {
                assertNoNewFiles(prefix)
            }
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun fullDownloadKeepsAnimationAndRetriesOnlyMatchingMotionSources() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        var saved: SavedVideo? = null
        try {
            val gif = fixture("two_frames.gif", directory).readBytes()
            val cover = MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_PNG, directory).readBytes()
            val motion = liveFixture(directory).readBytes()
            val gifUrl = "https://p3.douyinpic.com/test_animation.gif"
            val coverUrl = "https://p3.douyinpic.com/test_live_cover.png"
            val expiredUrl = "https://v3.douyinvod.com/test_expired.mp4"
            val healthyUrl = "https://v3.douyinvod.com/test_backup.mp4"
            val markedUrl = "https://v3.douyinvod.com/test_marked.mp4?watermark=1"
            val requests = mutableListOf<String>()
            val transfer = MediaTransfer(connections = { uri ->
                requests += uri.toString()
                when (uri.toString()) {
                    gifUrl -> response(uri, gif)
                    coverUrl -> response(uri, cover)
                    expiredUrl -> response(uri, ByteArray(0), 403)
                    healthyUrl -> response(uri, motion)
                    else -> error("No request is authorized for this isolated fixture")
                }
            }, cookies = { null })
            val parsed = ParsedVideo("9999999999999999972", "自制网络响应验证", "", 0.0, 48, 48, images = listOf(
                ParsedImage(gifUrl, 48, 48, listOf(MediaSource(gifUrl, WatermarkMode.CLEAN)), kind = AlbumAssetKind.ANIMATED),
                ParsedImage(coverUrl, 1280, 720, listOf(MediaSource(coverUrl, WatermarkMode.CLEAN)), kind = AlbumAssetKind.LIVE,
                    motion = ParsedMotion(expiredUrl, mediaSources = listOf(MediaSource(expiredUrl, WatermarkMode.CLEAN),
                        MediaSource(markedUrl, WatermarkMode.WATERMARKED), MediaSource(healthyUrl, WatermarkMode.CLEAN)))),
            ))
            val result = AlbumDownloader(context, transfer).download(parsed, null, DownloadOptions(), { _, _ -> }, {}, { saved = it })
            assertEquals(listOf(gifUrl, coverUrl, expiredUrl, healthyUrl), requests)
            assertEquals(2, result.uris.size)
            assertEquals("image/*", result.mimeType)
            assertEquals(AlbumAssetKind.ANIMATED, result.albumAssets[0].kind)
            assertEquals(AlbumAssetKind.LIVE, result.albumAssets[1].kind)
            val actual = context.contentResolver.openInputStream(Uri.parse(result.uris[0]))!!.use { it.readBytes() }
            assertTrue("The full pipeline changed original GIF bytes", gif.contentEquals(actual))
            val packed = File(directory, "saved-live.jpg")
            context.contentResolver.openInputStream(Uri.parse(result.uris[1]))!!.use { input -> packed.outputStream().use { input.copyTo(it) } }
            EmbeddedMotionReader.validate(packed)
            assertTrue(motion.contentEquals(EmbeddedMotionReader.extractForPreview(packed,
                File(directory, "saved-motion.mp4")).readBytes()))
            assertEquals("image/gif", result.albumAssets[0].mimeType)
            assertEquals("", result.albumAssets[1].motionUri)
            assertTrue(result.albumAssets[1].embeddedMotion)
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun missingLiveSidecarOrStaticSubstituteFailsBeforePublishing() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val cover = AlbumMediaValidation.image(fixture("static_cover.png", directory))
            listOf(AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED).forEach { kind ->
                val prefix = "missingmotion${UUID.randomUUID().toString().replace("-", "")}" // No existing files match.
                val entries = listOf(LocalAlbumEntry(cover))
                try {
                    AlbumDownloader(context).saveLocalEntries(content(entries, listOf(kind)), entries, DownloadStorage(context), null,
                        DownloadOptions(fileName = prefix), { _, _ -> }, { fail("Dynamic media was replaced with a cover") })
                    fail("Missing dynamic media was accepted")
                } catch (_: IllegalArgumentException) { assertNoNewFiles(prefix) }
            }
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun rejectsNonMp4DynamicResourceBeforePublishing() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        try {
            val fake = File(directory, "not_a_video.mp4").apply { writeText("<html>verification page, not video</html>") }
            assertThrows(IllegalArgumentException::class.java) { AlbumMediaValidation.motionVideo(fake) }
            assertFalse(File(directory, "composed.mp4").exists())
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun preservesGifAndLivePairInExplicitIsolatedSafFolder() = runBlocking(Dispatchers.IO) {
        // Same explicit, opt-in folder contract as the existing static album tests.
        assumeTrue(InstrumentationRegistry.getArguments().getString("album_saf_test") == "true")
        val storage = DownloadStorage(context)
        val testTree = InstrumentationRegistry.getArguments().getString("album_saf_tree_uri")
        val folder = if (testTree == null) storage.loadFolder() else {
            require(testTree == "content://com.android.externalstorage.documents/tree/primary%3AScreenshots%2FDouyinValidation030")
            DownloadFolder(testTree, "手机存储/Screenshots/DouyinValidation030")
        }
        assumeTrue(folder?.label?.endsWith("/Screenshots/DouyinValidation030") == true)
        storage.validateAccess(folder!!)
        val directory = directory()
        var saved: SavedVideo? = null
        try {
            val gif = AlbumMediaValidation.image(fixture("two_frames.gif", directory))
            val cover = AlbumMediaValidation.image(MatchedLiveCoverFixture.copy(MatchedLiveCoverFixture.Cover.RED_JPEG, directory))
            val motion = liveFixture(directory)
            val entries = listOf(LocalAlbumEntry(gif), LocalAlbumEntry(cover, motion))
            val result = AlbumDownloader(context).saveLocalEntries(content(entries, listOf(AlbumAssetKind.ANIMATED, AlbumAssetKind.LIVE)),
                entries, storage, folder, DownloadOptions(fileName = "dynamic_saf_${UUID.randomUUID()}"), { _, _ -> }, { saved = it })
            assertEquals(2, result.uris.size)
            assertEquals("image/*", result.mimeType)
            assertEquals(folder.label, result.locationLabel)
            listOf(gif.file, null).forEachIndexed { index, file ->
                val uri = Uri.parse(result.uris[index])
                if (file != null) assertPreserved(file, uri)
                else {
                    val packed = File(directory, "saf-live.jpg")
                    context.contentResolver.openInputStream(uri)!!.use { input -> packed.outputStream().use { input.copyTo(it) } }
                    EmbeddedMotionReader.validate(packed)
                    assertTrue(motion.readBytes().contentEquals(EmbeddedMotionReader.extractForPreview(packed,
                        File(directory, "saf-motion.mp4")).readBytes()))
                }
                context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertFalse(it.getString(0).startsWith("."))
                    assertFalse(it.getString(0).contains(".pending"))
                }
            }
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    @Test fun removedLivePublicationSavesOnlySilentClipAndKeepsMixedStaticAndGif() = runBlocking(Dispatchers.IO) {
        val directory = directory()
        var saved: SavedVideo? = null
        try {
            val still = AlbumMediaValidation.image(fixture("static_cover.png", directory))
            val gif = AlbumMediaValidation.image(fixture("two_frames.gif", directory))
            val motion = liveFixture(directory)
            val entries = listOf(LocalAlbumEntry(still), LocalAlbumEntry(still, motion), LocalAlbumEntry(gif))
            val content = content(entries, listOf(AlbumAssetKind.STATIC, AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED))
            val result = AlbumDownloader(context).saveLocalEntries(content, entries, DownloadStorage(context), null,
                DownloadOptions(fileName = "rc21_mixed_${UUID.randomUUID()}"), { _, _ -> }, { saved = it })
            assertEquals(3, result.uris.size)
            assertEquals(listOf("image/png", "video/mp4", "image/gif"), result.albumAssets.map { it.mimeType })
            assertTrue(result.albumAssets.none { it.embeddedMotion || it.kind == AlbumAssetKind.LIVE })
            assertPreserved(still.file, Uri.parse(result.uris[0]))
            assertPreserved(gif.file, Uri.parse(result.uris[2]))
            val output = File(directory, "published.mp4")
            context.contentResolver.openInputStream(Uri.parse(result.uris[1]))!!.use { input -> output.outputStream().use { input.copyTo(it) } }
            AlbumMediaValidation.motionVideo(output)
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertTrue(extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/"))
            } finally { extractor.release() }
        } finally { deleteSaved(saved); directory.deleteRecursively() }
        Unit
    }

    private fun content(entries: List<LocalAlbumEntry>, kinds: List<AlbumAssetKind>): ParsedVideo = ParsedVideo(
        "9999999999999999971", "自制动态图集保存验证", "", 0.0, 48, 48,
        images = entries.mapIndexed { index, entry ->
            val imageUrl = "https://p3.douyinpic.com/test_owned_$index"
            val motionUrl = "https://v3.douyinvod.com/test_owned_$index.mp4"
            ParsedImage(imageUrl, entry.image.width, entry.image.height,
                mediaSources = listOf(MediaSource(imageUrl, WatermarkMode.CLEAN)), kind = kinds[index],
                motion = entry.motionVideo?.let { ParsedMotion(motionUrl, mediaSources = listOf(MediaSource(motionUrl, WatermarkMode.CLEAN))) })
        },
    )

    private suspend fun liveFixture(directory: File): File {
        // Fixed original AVC/AAC input; actual save, extraction and rollback paths remain under test.
        val video = File(directory, "fixed_red_blue_2s.mp4").also { output ->
            instrumentation.context.assets.open("motion_test/fixed_red_blue_2s.mp4").use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
            val sourceHash = java.security.MessageDigest.getInstance("SHA-256").digest(output.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("Fixed original LIVE source differs from its independent manifest",
                "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57", sourceHash)
        }
        AlbumMediaValidation.motionVideo(video)
        return video
    }

    private fun fixture(name: String, directory: File): File = File(directory, name).also { file ->
        instrumentation.context.assets.open("dynamic_album_test/$name").use { source -> file.outputStream().use { source.copyTo(it) } }
    }
    private fun directory(): File = File(context.cacheDir, "dynamic_album_validation_${UUID.randomUUID()}").also { check(it.mkdir()) }
    private fun assertPreserved(source: File, destination: Uri) {
        val actual = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
        assertTrue("Dynamic original bytes were modified", source.readBytes().contentEquals(actual))
    }
    private fun assertNoNewFiles(prefix: String) {
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} GLOB ?", arrayOf("$prefix*"), null)!!.use { assertEquals(0, it.count) }
        }
    }
    private fun deleteSaved(saved: SavedVideo?) {
        saved?.uris?.forEach { value ->
            val uri = Uri.parse(value)
            if (DocumentsContract.isDocumentUri(context, uri)) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            else runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
    private fun response(uri: URI, body: ByteArray, status: Int = 200): HttpURLConnection = object : HttpURLConnection(uri.toURL()) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = "application/octet-stream"
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(body)
    }
}
