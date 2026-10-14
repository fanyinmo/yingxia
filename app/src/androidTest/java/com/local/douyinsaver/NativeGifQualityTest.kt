package com.local.douyinsaver

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

class NativeGifQualityTest {
    @Test fun uneditedNativeGifIsSavedOnceWithIdenticalBytesAtBothQualitySelections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = InstrumentationRegistry.getInstrumentation().context.assets.open("dynamic_album_test/two_frames.gif").use { it.readBytes() }
        val url = "https://p3.douyinpic.com/native_quality.gif"
        val transfer = MediaTransfer(connections = { uri -> object : HttpURLConnection(uri.toURL()) {
            override fun connect() { }
            override fun disconnect() { }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = original.size.toLong()
            override fun getContentType() = "image/gif"
            override fun getInputStream() = ByteArrayInputStream(original)
        } }, cookies = { null })
        val content = ParsedVideo("7695376760892314330", "原始 GIF 画质", "", 0.0, 0, 0,
            images = listOf(ParsedImage(url, mediaSources = listOf(MediaSource(url, WatermarkMode.CLEAN)), kind = AlbumAssetKind.ANIMATED)))
        for (quality in GifExportQuality.entries) {
            var saved: SavedVideo? = null
            var callbacks = 0
            try {
                val result = AlbumDownloader(context, transfer).download(content, null,
                    DownloadOptions(albumMode = AlbumMode.GIF, gifExportQuality = quality), { _, _ -> }, {}, { saved = it; callbacks++ })
                assertEquals(1, callbacks); assertEquals(1, result.uris.size); assertEquals(1, result.albumAssets.size)
                val bytes = context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { it.readBytes() }
                assertArrayEquals(original, bytes)
            } finally { saved?.uris?.forEach { context.contentResolver.delete(Uri.parse(it), null, null) } }
        }
    }
}
