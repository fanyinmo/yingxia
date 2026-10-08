package com.local.douyinsaver

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.security.MessageDigest

/** Independently decoded original input frames, never precomputed Android export results. */
internal object MatchedLiveCoverFixture {
    enum class Cover(val asset: String, val source: String, val frame: Int, val ptsUs: Long, val color: String) {
        RED_JPEG("fixed_red_blue_2s_cover_red_000.jpg", "fixed_red_blue_2s.mp4", 0, 0, "red"),
        RED_PNG("fixed_red_blue_2s_cover_red_000.png", "fixed_red_blue_2s.mp4", 0, 0, "red"),
        BLUE_MIDDLE_PNG("fixed_red_blue_2s_cover_blue_045.png", "fixed_red_blue_2s.mp4", 45, 1_500_000, "blue"),
        BLUE_FIRST_LONG_PNG("fixed_blue_red_4s_cover_blue_000.png", "fixed_blue_red_4s.mp4", 0, 0, "blue"),
    }

    fun copy(cover: Cover, directory: File): File {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val manifestBytes = assets.open("motion_test/fixed_live_covers.json").use { it.readBytes() }
        assertEquals("Independent actual-frame input manifest changed",
            "86dba1ca63696965725f611f19abd9a114eb9943660922487ab94d32616065bd", hash(manifestBytes))
        val entries = JSONObject(String(manifestBytes, Charsets.UTF_8)).getJSONArray("covers")
        val proof = (0 until entries.length()).map { entries.getJSONObject(it) }
            .single { it.getString("asset") == "motion_test/${cover.asset}" }
        assertEquals("motion_test/${cover.source}", proof.getString("sourceAsset"))
        assertEquals(if (cover.source == "fixed_red_blue_2s.mp4")
            "2bae70e8fc2718d3bab7f8271ea7e39f732e90ff92998481f703485a50a98b57"
        else "6bffa756909f402a6445a8c3f65e2921b2f645acdaa739e3f54ee0f32c88b7a4", proof.getString("sourceSha256"))
        assertEquals(cover.frame, proof.getInt("sourceFrameIndex"))
        assertEquals(cover.ptsUs, proof.getLong("presentationTimestampUs"))
        val bytes = assets.open("motion_test/${cover.asset}").use { it.readBytes() }
        assertEquals(proof.getString("sha256"), hash(bytes))
        assertEquals(proof.getLong("sizeBytes"), bytes.size.toLong())
        assertTrue("Original cover no longer matches the independently decoded actual source frame",
            proof.getDouble("rgbMeanSquaredError") <= 4.0)
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals(1280, bitmap.width); assertEquals(720, bitmap.height)
            for (x in listOf(0, 150, 1129, 1279)) {
                val pixel = bitmap.getPixel(x, bitmap.height / 2)
                assertTrue("Original frame's black side bars were lost",
                    maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 4)
            }
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertEquals(cover.color, if (Color.red(pixel) > Color.blue(pixel) + 80) "red"
                else if (Color.blue(pixel) > Color.red(pixel) + 80) "blue" else "other")
        } finally { bitmap.recycle() }
        return File(directory, cover.asset).apply { writeBytes(bytes) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
