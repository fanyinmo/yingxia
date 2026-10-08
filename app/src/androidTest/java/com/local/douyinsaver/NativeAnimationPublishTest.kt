package com.local.douyinsaver

import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** APNG original publication only. System gallery animation playback needs its own acceptance. */
@RunWith(AndroidJUnit4::class)
class NativeAnimationPublishTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun publishesOriginalApngBytesAndChangingFramesWithPngMimeAndReloadableAnimatedRecord() = runBlocking(Dispatchers.IO) {
        val token = UUID.randomUUID().toString().replace("-", "")
        val namespace = "native_animation_publish_${token}_"
        val directory = File(context.cacheDir, namespace).apply {
            check(canonicalFile.parentFile == context.cacheDir.canonicalFile && mkdir())
        }
        val ownedUris = linkedSetOf<Uri>()
        val ownedFiles = mutableListOf<File>()
        val cleanupErrors = mutableListOf<String>()
        try {
            val source = File(directory, "original-apng.png").also(ownedFiles::add)
            instrumentation.context.assets.open("dynamic_album_test/two_frames.png").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            val sourceHash = hash(source)
            val sentinel = File(directory, "unrelated-sentinel.bin").also(ownedFiles::add)
                .apply { writeText("Keep the caller's unrelated file") }
            val sentinelHash = hash(sentinel)
            val image = AlbumMediaValidation.image(source)
            assertEquals("image/png", image.mimeType)
            assertTrue(image.animated)
            assertChangingApngFrames(image)

            // The neighbour is self-made in a UUID namespace; real user history is never opened.
            val neighbour = SavedVideo("9999999999999999930", "Controlled neighbour", "content://fixture/$token",
                17L, savedAt = 1L, mimeType = "image/png", isAlbum = true,
                albumAssets = listOf(SavedAlbumAsset("content://fixture/$token", "image/png", AlbumAssetKind.STATIC)))
            val records = DownloadRecords(context, namespace)
            assertTrue(records.history().isEmpty())
            records.save(neighbour)
            val url = "https://p3.douyinpic.com/native_animation_publish_$token.png"
            val parsed = ParsedVideo("9999999999999999931", "Controlled APNG original", "", 0.0,
                image.width, image.height, images = listOf(ParsedImage(url, image.width, image.height,
                    mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)), kind = AlbumAssetKind.ANIMATED,
                    mimeType = "image/png", imageKey = token)))
            val result = AlbumDownloader(context).saveLocalEntries(parsed, listOf(LocalAlbumEntry(image)),
                DownloadStorage(context), null, DownloadOptions(albumMode = AlbumMode.IMAGES, fileName = namespace),
                { _, _ -> }, { saved ->
                    ownedUris.addAll(saved.uris.map(Uri::parse))
                    records.save(saved)
                })
            ownedUris.addAll(result.uris.map(Uri::parse))
            assertEquals(1, result.uris.size)
            assertEquals(source.length(), result.bytes)
            assertEquals("image/png", result.mimeType)
            assertEquals(AlbumMode.IMAGES.name, result.exportMode)
            val asset = result.albumAssets.single()
            assertEquals(result.uri, asset.uri)
            assertEquals("image/png", asset.mimeType)
            assertEquals(AlbumAssetKind.ANIMATED, asset.kind)
            assertEquals(0, asset.sourceIndex)
            assertFalse(asset.embeddedMotion)
            assertEquals("", asset.motionUri)
            val uri = Uri.parse(asset.uri)
            assertTrue(uri.path.orEmpty().contains("images"))
            assertEquals("image/png", context.contentResolver.getType(uri))
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).endsWith("_001.png"))
                assertEquals(source.length(), cursor.getLong(1))
                assertEquals(0, cursor.getInt(2))
            }

            val published = File(directory, "published-apng.png").also(ownedFiles::add)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                published.outputStream().use { output -> input.copyTo(output) }
            }
            assertEquals("Published APNG bytes changed", sourceHash, hash(published))
            val publishedImage = AlbumMediaValidation.image(published)
            assertEquals("image/png", publishedImage.mimeType)
            assertTrue(publishedImage.animated)
            assertEquals(image.width, publishedImage.width)
            assertEquals(image.height, publishedImage.height)
            assertChangingApngFrames(publishedImage)
            val reloaded = DownloadRecords(context, namespace).history()
            assertEquals(listOf(result, neighbour), reloaded)
            assertEquals(AlbumAssetKind.ANIMATED, reloaded.first().albumAssets.single().kind)
            assertEquals("image/png", reloaded.first().mimeTypeFor(asset.uri))
            assertEquals("Caller APNG source was modified", sourceHash, hash(source))
            assertEquals("Caller neighbour file was modified", sentinelHash, hash(sentinel))
        } finally {
            // Exact returned URIs and this test's UUID files/preferences only; no collection scan.
            ownedUris.forEach { uri -> runCatching {
                check(context.contentResolver.delete(uri, null, null) == 1)
            }.onFailure { cleanupErrors += "URI ${it.javaClass.simpleName}" } }
            listOf("downloads", "download_tasks").forEach { context.deleteSharedPreferences(namespace + it) }
            ownedFiles.forEach { file -> runCatching {
                check(file.canonicalFile.parentFile == directory.canonicalFile)
                if (file.exists()) check(file.delete())
            }.onFailure { cleanupErrors += "private file ${it.javaClass.simpleName}" } }
            runCatching {
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.delete())
            }.onFailure { cleanupErrors += "private directory ${it.javaClass.simpleName}" }
            assertTrue("Owned APNG fixture cleanup failed: $cleanupErrors", cleanupErrors.isEmpty())
        }
        Unit
    }

    private fun assertChangingApngFrames(image: LocalAlbumImage) {
        // Decode the saved APNG's actual animation chunks, independently of Android's still PNG frame.
        AnimatedImageFrames(image).use { frames ->
            assertEquals(2, frames.frameCount)
            assertEquals(500L, frames.durationMs)
            assertEquals(48, frames.width)
            assertEquals(48, frames.height)
            assertEquals(Color.RED, frames.frameAt(0L).getPixel(24, 24))
            assertEquals(Color.RED, frames.frameAt(249L).getPixel(24, 24))
            assertEquals(Color.BLUE, frames.frameAt(250L).getPixel(24, 24))
            assertEquals(Color.BLUE, frames.frameAt(499L).getPixel(24, 24))
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
