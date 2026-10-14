package com.local.douyinsaver

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class GifShareAdaptiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun noisySequenceExceedingOneMiBKeepsSelectedQualityWithoutRetry() = isolated { directory ->
        val random = java.util.Random(42)
        val images = (0 until 6).map { index ->
            val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(IntArray(480 * 480) { Color.rgb(random.nextInt(128) * 2, 0, 0) }, 0, 480, 0, 0, 480, 480)
            val source = File(directory, "source_$index.png")
            try { source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            AlbumMediaValidation.image(source)
        }
        val output = File(directory, "only-output.gif")
        var starts = 0
        AlbumGifComposer(GifExportQuality.SHARE).compose(images, 100L, output) { done, _ -> if (done == 0L) starts++ }
        assertEquals(1, starts)
        assertTrue("Quality must not be reduced to meet the old size target", output.length() > 1024L * 1024)
        val verified = AlbumMediaValidation.image(output)
        AnimatedImageFrames(verified).use {
            assertEquals(600L, it.durationMs)
            assertEquals(6, it.frameCount)
            assertEquals(480, it.width)
        }
        assertEquals(listOf("only-output.gif"), directory.listFiles()!!.filter { it.extension == "gif" }.map { it.name })
        assertTrue(images.all { it.file.exists() })
    }

    @Test fun cancellingAnAdaptiveExportLeavesNoPartialGifOrDeletedSource() = isolated { directory ->
        val images = (0..1).map { index ->
            val source = File(directory, "source_$index.png")
            val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(if(index==0) Color.RED else Color.BLUE) }
            try { source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            AlbumMediaValidation.image(source)
        }
        val output = File(directory, "cancel.gif")
        try {
            AlbumGifComposer(GifExportQuality.SHARE).compose(images, 100L, output) { done, _ ->
                if (done == 1L) throw CancellationException("test cancellation")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(output.exists())
        assertTrue(images.all { it.file.exists() })
    }

    private fun isolated(block: suspend (File) -> Unit) = runBlocking {
        val directory = File(context.cacheDir, "gif_share_test_${UUID.randomUUID()}")
        check(directory.mkdir())
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
