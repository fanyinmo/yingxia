package com.local.douyinsaver

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses isolated preferences and fixture URIs; never touches existing history. */
@RunWith(AndroidJUnit4::class)
class DynamicAlbumRecordsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val cover = "content://fixture/cover"
    private val clip = "content://fixture/clip"
    private val animation = "content://fixture/animation"
    private fun pair() = SavedVideo("1", "mixed", cover, 300, mimeType = "*/*",
        uris = listOf(cover, clip, animation), isAlbum = true, watermarkMode = WatermarkMode.CLEAN,
        albumAssets = listOf(SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, clip),
            SavedAlbumAsset(animation, "image/gif", AlbumAssetKind.ANIMATED)))

    @Test fun mixedAlbumPairsAndIndividualMimeTypesSurviveNewRecordsInstance() = isolated { name ->
        val expected = pair()
        DownloadRecords(context, name).save(expected)
        val actual = DownloadRecords(context, name).history().single()
        assertEquals(expected, actual)
        assertEquals("image/jpeg", actual.mimeTypeFor(cover))
        assertEquals("video/mp4", actual.mimeTypeFor(clip))
        assertEquals("image/gif", actual.mimeTypeFor(animation))
        val motionOnly = AlbumRecordPolicy.retainFiles(actual, listOf(clip))
        DownloadRecords(context, name).writeHistory(listOf(motionOnly))
        assertEquals(motionOnly, DownloadRecords(context, name).history().single())
    }

    @Test fun malformedExternalAssetsCannotAddUrisOrClipsToExistingHistory() = isolated { name ->
        context.getSharedPreferences(name + "downloads", 0).edit().putString("completed", """
            [{"id":"1","title":"fixture","uri":"$cover","bytes":30,"isAlbum":true,
              "mimeType":"image/jpeg","uris":["$cover"],"albumAssets":[
              {"uri":"content://fixture/outside","mimeType":"image/jpeg","kind":"LIVE","motionUri":"$clip"},
              {"uri":"$cover","mimeType":"text/html","kind":"LIVE"},
              {"uri":"$cover","mimeType":"image/jpeg","kind":"FUTURE","motionUri":"$clip"}]}]
        """.trimIndent()).commit()
        val actual = DownloadRecords(context, name).history().single()
        assertEquals(listOf(cover), actual.uris)
        assertEquals(listOf(SavedAlbumAsset(cover, "image/jpeg")), actual.albumAssets)
    }

    @Test fun legacyRecordsRemainReadableWithoutInventingMotion() = isolated { name ->
        context.getSharedPreferences(name + "downloads", 0).edit().putString("completed", """
            [{"id":"1","title":"old","uri":"$cover","bytes":20,"isAlbum":true,"mimeType":"image/jpeg"}]
        """.trimIndent()).commit()
        val actual = DownloadRecords(context, name).history().single()
        assertTrue(actual.albumAssets.isEmpty())
        assertEquals(listOf(cover), actual.uris)
        assertEquals("image/jpeg", actual.mimeTypeFor(cover))
        assertEquals("", actual.exportMode)
        assertEquals(0L, actual.gifDurationMs)
    }

    @Test fun gifRangeAndConversionModePersistWithoutReplacingOriginalRecord() = isolated { name ->
        val original = pair()
        val gif = original.copy(uri = animation, uris = listOf(animation), mimeType = "image/gif", isAlbum = false,
            albumAssets = emptyList(), exportMode = AlbumMode.GIF.name, gifStartMs = 2_000, gifDurationMs = 6_000)
        val records = DownloadRecords(context, name)
        records.save(original)
        records.save(gif)
        assertEquals(listOf(gif, original), DownloadRecords(context, name).history())
    }

    @Test fun embeddedPhotoAndAlbumTimingSignatureSurviveReloadWithoutChangingLegacyRecords() = isolated { name ->
        val old = pair()
        val embedded = old.copy(uri = "content://fixture/embedded", uris = listOf("content://fixture/embedded"),
            albumAssets = listOf(SavedAlbumAsset("content://fixture/embedded", "image/jpeg", AlbumAssetKind.LIVE,
                embeddedMotion = true, sourceIndex = 2)), mimeType = "image/jpeg", albumSourceCount = 1)
        val converted = old.copy(uri = "content://fixture/composed", uris = listOf("content://fixture/composed"),
            albumAssets = emptyList(), mimeType = "video/mp4", exportMode = AlbumMode.VIDEO.name,
            albumTimingSignature = AlbumTiming.signature(DownloadOptions(itemDurationSeconds = 0.1)))
        val records = DownloadRecords(context, name)
        listOf(old, embedded, converted).forEach(records::save)
        assertEquals(listOf(converted, embedded, old), DownloadRecords(context, name).history())
    }

    @Test fun engineReusesEmbeddedCleanPhotosAndRejectsLegacyCoversAndSidecars() = isolated { name ->
        val engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(context.applicationContext as Application, name)
        val content = ParsedVideo("1", "mixed", "", 0.0, 0, 0, images = listOf(
            ParsedImage("https://p3.douyinpic.com/a.jpg", kind = AlbumAssetKind.LIVE,
                motion = ParsedMotion("https://v3.douyinvod.com/a.mp4")),
            ParsedImage("https://p3.douyinpic.com/b.gif", kind = AlbumAssetKind.ANIMATED)))
        val complete = pair()
        val incomplete = complete.copy(uris = listOf(cover, animation), mimeType = "image/*", albumAssets = emptyList())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            setState(engine, "video", content)
            setState(engine, "duplicateCandidates", listOf(incomplete, complete.copy(watermarkMode = WatermarkMode.WATERMARKED)))
            assertNull(engine.duplicate)
            setState(engine, "duplicateCandidates", listOf(incomplete, complete))
            assertNull(engine.duplicate)
            val embedded = complete.copy(uris = listOf(cover, animation), mimeType = "image/*",
                albumAssets = listOf(SavedAlbumAsset(cover, "image/jpeg", AlbumAssetKind.LIVE, embeddedMotion = true),
                    SavedAlbumAsset(animation, "image/gif", AlbumAssetKind.ANIMATED)))
            setState(engine, "duplicateCandidates", listOf(incomplete, complete, embedded))
            assertEquals(embedded, engine.duplicate)
        }
    }

    private fun isolated(body: (String) -> Unit) {
        val name = "dynamic_records_${UUID.randomUUID()}_"
        try { body(name) } finally {
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics")
                .forEach { context.deleteSharedPreferences(name + it) }
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(engine: SaverEngine, name: String, value: T) {
        val field = SaverEngine::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(engine) as MutableState<T>).value = value
    }
}
