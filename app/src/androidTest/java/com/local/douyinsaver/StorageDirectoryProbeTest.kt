package com.local.douyinsaver

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Opt-in directory comparison of the same pending-file protocol, using only an owned UUID. */
class StorageDirectoryProbeTest {
    @org.junit.Rule @JvmField val diagnostic = OwnTestThreadDiagnosticRule()

    @Test fun publishAndReadOneOwnedFileAtAnExplicitlyChosenSupportedDirectory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("run_storage_probe") == "true")
        val mime = args.getString("probe_mime") ?: "image/jpeg"
        require(mime in listOf("image/jpeg", "image/gif", "image/webp", "video/mp4"))
        val token = UUID.randomUUID().toString()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "storage_directory_probe_$token").apply { check(mkdir()) }
        val location = when (args.getString("probe_location") ?: "DCIM") {
            "DCIM" -> "DCIM/YingxiaValidation_$token"
            "DEFAULT" -> if (mime == "video/mp4") DownloadStorage.DEFAULT_LOCATION else DownloadStorage.DEFAULT_IMAGE_LOCATION
            else -> error("probe_location must be DCIM or DEFAULT")
        }
        val extension = when (mime) { "image/gif" -> "gif"; "image/webp" -> "webp"; "video/mp4" -> "mp4"; else -> "jpg" }
        val source = File(directory, "source.$extension")
        val reportFile = File(directory, "probe.json")
        val report = JSONObject().put("success", false).put("mime", mime).put("location", location)
            .put("scope", "OWN_UUID_MEDIASTORE_DIRECTORY_COMPARISON_NOT_PRODUCT_DOWNLOAD_ACCEPTANCE")
        fun phase(value: String) { report.put("phase", value); reportFile.writeText(report.toString(2)) }
        phase("preparing")
        if (mime == "image/jpeg") {
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try { source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
            finally { bitmap.recycle() }
        } else {
            val asset = when (mime) {
                "image/gif" -> "dynamic_album_test/two_frames.gif"
                "image/webp" -> "dynamic_album_test/two_frames.webp"
                else -> "motion_test/fixed_red_blue_2s.mp4"
            }
            instrumentation.context.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
        }
        val sourceHash = hash(source.readBytes())
        report.put("sourceSha256", sourceHash).put("sourceBytes", source.length())
        val collection = if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var owned: Uri? = null
        try {
            phase("before_insert")
            owned = checkNotNull(context.contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "yingxia_probe_${token}.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, location)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            report.put("ownedUri", owned.toString()); phase("after_insert")
            context.contentResolver.openOutputStream(owned, "w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            phase("after_write_close")
            assertEquals(1, context.contentResolver.update(owned, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            phase("after_publish")
            val saved = context.contentResolver.openInputStream(owned)!!.use { it.readBytes() }
            assertEquals(sourceHash, hash(saved))
            assertEquals(mime, context.contentResolver.getType(owned))
            phase("after_read_and_mime")
            report.put("success", true)
        } finally {
            owned?.let { phase("before_owned_delete"); assertEquals(1, context.contentResolver.delete(it, null, null)) }
            phase("finished")
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
