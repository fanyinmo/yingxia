package com.local.douyinsaver

import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit local copy of a previously confirmed public source, never personal gallery media. */
@RunWith(AndroidJUnit4::class)
class GifQualityRealSourceTest {
    @Test fun compareActualConfirmedMotionSourceWithoutClippingItsRange() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("compare_real_gif_source") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "emulator_rc11_motion_source.mp4")
        val expectedHash = requireNotNull(args.getString("source_sha256"))
        assertTrue(source.isFile && source.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        assertEquals(expectedHash, hash(source))
        val directory = File(context.cacheDir, "emulator_real_gif_${UUID.randomUUID()}")
        check(directory.mkdir())
        println("real_gif_comparison_directory=${directory.absolutePath}")
        val converter = VideoGifConverter()
        val duration = converter.readDurationMs(source)
        assertTrue(duration in 100L..60_000L)
        val observations = JSONArray()
        val outputs = mutableMapOf<GifExportQuality, File>()
        for (quality in listOf(GifExportQuality.HIGH_QUALITY, GifExportQuality.SHARE)) {
            val result = converter.convert(source, File(directory, "${quality.name.lowercase()}.gif"), 0L, duration, quality) { _, _ -> }
            assertEquals(0x3b, result.readBytes().last().toInt() and 255)
            var animated = false
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(result)) { decoder, info, _ ->
                animated = info.isAnimated; decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                assertTrue(animated)
                assertTrue(maxOf(bitmap.width, bitmap.height) <= quality.maximumEdge)
                observations.put(JSONObject().put("quality", quality.name).put("width", bitmap.width).put("height", bitmap.height)
                    .put("bytes", result.length()).put("sha256", hash(result)))
            } finally { bitmap.recycle() }
            outputs[quality] = result
        }
        assertTrue(outputs.getValue(GifExportQuality.SHARE).length() < outputs.getValue(GifExportQuality.HIGH_QUALITY).length())
        assertEquals(expectedHash, hash(source))
        File(directory, "comparison.json").writeText(JSONObject().put("scope", "REAL_SOURCE_FILE_NOT_CHAT_OR_NATIVE_GALLERY_ACCEPTANCE")
            .put("sourceBytes", source.length()).put("sourceSha256", expectedHash).put("durationMs", duration).put("outputs", observations).toString(2))
        Unit
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
