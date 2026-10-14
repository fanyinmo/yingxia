package com.local.douyinsaver

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GifGalleryPublishTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publishedGifHasImmediateDimensionsMimeAndAndroidAnimation() {
        val pending = DownloadStorage(context).createPending("gif_gallery_${UUID.randomUUID()}.gif", null, "image/gif")
        try {
            context.contentResolver.openOutputStream(pending.uri)!!.use { output ->
                val encoder = GifEncoder(output, 16, 12, maximumColors = 128, compatibleColorTables = true)
                encoder.addFrame(IntArray(192) { 0xffff0000.toInt() }, 300)
                encoder.addFrame(IntArray(192) { 0xff0000ff.toInt() }, 300)
                encoder.finish()
            }
            val uri = pending.publish()
            val columns = arrayOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING)
            context.contentResolver.query(uri, columns, null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(16, cursor.getInt(0))
                assertEquals(12, cursor.getInt(1))
                assertEquals("image/gif", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
            val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri))
            assertTrue(decoded is AnimatedImageDrawable)
            assertEquals(16, decoded.intrinsicWidth)
            assertEquals(12, decoded.intrinsicHeight)
        } finally { pending.rollback() }
    }

    @Test fun incompleteGifCannotBePublishedAndOwnedFileCanBeCleaned() {
        val pending = DownloadStorage(context).createPending("gif_invalid_${UUID.randomUUID()}.gif", null, "image/gif")
        try {
            context.contentResolver.openOutputStream(pending.uri)!!.use { it.write("GIF89a".toByteArray()) }
            try { pending.publish(); fail("Truncated GIF must remain unpublished") }
            catch (_: IllegalArgumentException) { }
            context.contentResolver.query(pending.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally { pending.cleanup() }
        context.contentResolver.query(pending.uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use {
            assertEquals(0, it.count)
        }
    }
}
