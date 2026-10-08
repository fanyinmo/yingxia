package com.local.douyinsaver

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Exercises production HistoryScreen with isolated records and only uniquely owned test files. */
@RunWith(AndroidJUnit4::class)
class HistoryControlsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun searchFindsTitleFileNameAndWorkIdWithoutChangingHistory() = isolated { fixture ->
        mount(fixture) {
            val original = fixture.records.history()
            setQuery("  aLpHa  ")
            assertDisplayedOrder(listOf(fixture.alpha.title), fixture)
            setQuery("nEeDlE")
            assertDisplayedOrder(listOf(fixture.bravo.title), fixture)
            setQuery(fixture.charlie.id)
            assertDisplayedOrder(listOf(fixture.charlie.title), fixture)
            setQuery("not-in-any-fixture")
            await { textOrNull("没有符合条件的作品") != null }
            assertTrue(visibleFixtureTitles(fixture).isEmpty())
            assertNotNull(textOrNull("试试其他标题或作品编号。"))
            setQuery("x".repeat(205))
            await { searchField().text?.length == 200 }
            assertNotNull(textOrNull("没有符合条件的作品"))
            setQuery("   ")
            assertDisplayedOrder(listOf(fixture.bravo.title, fixture.delta.title, fixture.alpha.title, fixture.charlie.title), fixture)
            assertEquals(original, fixture.records.history())
            assertEquals(original, fixture.engine.history)
        }
    }

    @Test fun everySortControlChangesActualCardOrderWithoutRewritingRecords() = isolated { fixture ->
        mount(fixture) {
            val original = fixture.records.history()
            assertDisplayedOrder(listOf(fixture.bravo.title, fixture.delta.title, fixture.alpha.title, fixture.charlie.title), fixture)
            clickText("最早")
            assertDisplayedOrder(listOf(fixture.charlie.title, fixture.alpha.title, fixture.delta.title, fixture.bravo.title), fixture)
            clickText("标题")
            assertDisplayedOrder(listOf(fixture.alpha.title, fixture.bravo.title, fixture.charlie.title, fixture.delta.title), fixture)
            clickText("大小")
            assertDisplayedOrder(listOf(fixture.delta.title, fixture.alpha.title, fixture.charlie.title, fixture.bravo.title), fixture)
            clickText("最新")
            assertDisplayedOrder(listOf(fixture.bravo.title, fixture.delta.title, fixture.alpha.title, fixture.charlie.title), fixture)
            assertEquals(original, fixture.records.history())
            assertEquals(original, fixture.engine.history)
        }
    }

    @Test fun cancellingRecordRemovalAndFileDeletionPreservesSelectionFilesAndRecords() = isolated { fixture ->
        mount(fixture) {
            val original = fixture.records.history()
            setQuery("猫")
            await { visibleFixtureTitles(fixture).contains(fixture.alpha.title) }
            clickText("批量管理")
            await { textOrNull("已选 0 项") != null }
            clickCheckbox(rowCheckbox(fixture.alpha.title))
            await { textOrNull("已选 1 项") != null }
            assertTrue(rowCheckbox(fixture.alpha.title).isChecked)
            assertFalse(rowCheckbox(fixture.bravo.title).isChecked)
            clickText("移除记录")
            await { textOrNull("移除 1 条记录？") != null }
            assertNotNull(textOrNull("只从 App 中移除记录，本地文件会继续保留在原来的文件夹中。"))
            clickText("取消")
            await { textOrNull("移除 1 条记录？") == null && textOrNull("已选 1 项") != null }
            assertTrue(rowCheckbox(fixture.alpha.title).isChecked)
            assertEquals(original, fixture.records.history())
            clickText("删除文件")
            await { textOrNull("删除 1 项的文件？") != null }
            assertNotNull(textOrNull("确认删除文件"))
            clickText("取消")
            await { textOrNull("删除 1 项的文件？") == null && textOrNull("已选 1 项") != null }
            assertEquals(original, fixture.records.history())
            assertEquals(original, fixture.engine.history)
            assertFixtureFilesUnchanged(fixture)
            clickText("完成选择")
            await { textOrNull("批量管理") != null && textOrNull("已选 1 项") == null }
            clickText("批量管理")
            await { textOrNull("已选 0 项") != null }
            assertFalse(rowCheckbox(fixture.alpha.title).isChecked)
        }
    }

    @Test fun removingOneSelectedRecordLeavesItsFileAndAllOtherRecordsReadable() = isolated { fixture ->
        mount(fixture) {
            setQuery(fixture.alpha.id)
            assertDisplayedOrder(listOf(fixture.alpha.title), fixture)
            clickText("批量管理")
            await { textOrNull("已选 0 项") != null }
            clickCheckbox(rowCheckbox(fixture.alpha.title))
            await { textOrNull("已选 1 项") != null }
            clickText("移除记录")
            await { textOrNull("移除 1 条记录？") != null }
            clickText("移除记录")
            await { !fixture.engine.busy && fixture.engine.history.size == 3 && textOrNull("移除 1 条记录？") == null }
            assertEquals(setOf(fixture.bravo.uri, fixture.charlie.uri, fixture.delta.uri), fixture.records.history().map { it.uri }.toSet())
            assertEquals("所选记录已移除，文件仍保留", fixture.engine.message)
            await { textOrNull("已选 0 项") != null && textOrNull("没有符合条件的作品") != null }
            assertFixtureFilesUnchanged(fixture)
            setQuery("")
            assertDisplayedOrder(listOf(fixture.bravo.title, fixture.delta.title, fixture.charlie.title), fixture)
            clickText("完成选择")
            clickText("刷新")
            await { fixture.engine.history.size == 3 && textOrNull("批量管理") != null }
            assertEquals(fixture.records.history(), fixture.engine.history)
        }
    }

    @Test fun selectAllOnlyRemovesFilteredRecordsAndIndividualCheckboxUpdatesItsState() = isolated { fixture ->
        mount(fixture) {
            setQuery("猫")
            assertDisplayedOrder(listOf(fixture.bravo.title, fixture.alpha.title), fixture)
            clickText("批量管理")
            await { textOrNull("已选 0 项") != null }
            assertFalse(allCheckbox(0).isChecked)
            clickCheckbox(allCheckbox(0))
            await { textOrNull("已选 2 项") != null }
            assertTrue(allCheckbox(2).isChecked)
            assertTrue(rowCheckbox(fixture.alpha.title).isChecked)
            assertTrue(rowCheckbox(fixture.bravo.title).isChecked)
            clickCheckbox(rowCheckbox(fixture.alpha.title))
            await { textOrNull("已选 1 项") != null }
            assertFalse(rowCheckbox(fixture.alpha.title).isChecked)
            assertTrue(rowCheckbox(fixture.bravo.title).isChecked)
            assertFalse(allCheckbox(1).isChecked)
            clickCheckbox(allCheckbox(1))
            await { textOrNull("已选 2 项") != null }
            assertTrue(allCheckbox(2).isChecked)
            clickText("移除记录")
            await { textOrNull("移除 2 条记录？") != null }
            clickText("移除记录")
            await { !fixture.engine.busy && fixture.engine.history.size == 2 && textOrNull("移除 2 条记录？") == null }
            assertEquals(setOf(fixture.charlie.uri, fixture.delta.uri), fixture.records.history().map { it.uri }.toSet())
            await { textOrNull("已选 0 项") != null && textOrNull("没有符合条件的作品") != null }
            assertFalse(allCheckbox(0).isChecked)
            assertFixtureFilesUnchanged(fixture)
            setQuery("")
            assertDisplayedOrder(listOf(fixture.delta.title, fixture.charlie.title), fixture)
            assertEquals(fixture.records.history(), fixture.engine.history)
        }
    }

    /** UI-G06: a real MediaStore album must delete all selected files without touching its neighbor. */
    @Test fun cancellingThenConfirmingWholeAlbumDeletionRemovesOnlyItsOwnedMediaStoreUris() = isolated { fixture ->
        val owned = linkedSetOf<String>()
        var originalFailure: Throwable? = null
        try {
            val first = publishOwnedHistoryImage(fixture.namespace, "album-first.png", 0xffcc2222.toInt(), owned)
            val second = publishOwnedHistoryImage(fixture.namespace, "album-second.png", 0xff2222cc.toInt(), owned)
            val neighborUri = publishOwnedHistoryImage(fixture.namespace, "neighbor.png", 0xff22cc22.toInt(), owned)
            assertEquals("This test must exercise three distinct real MediaStore files", 3, owned.size)
            val beforeBytes = owned.associateWith(::readOwnedMedia)
            val beforeHashes = beforeBytes.mapValues { sha256(it.value) }
            assertEquals("Album files and the protected neighbor must have different content", 3, beforeHashes.values.toSet().size)
            val albumUris = listOf(first, second)
            val album = fixture.alpha.copy(uri = first, uris = albumUris,
                bytes = albumUris.sumOf { beforeBytes.getValue(it).size.toLong() },
                locationLabel = "本轮独立 MediaStore 图集目录", fileName = "owned-album.png", isAlbum = true,
                albumAssets = albumUris.mapIndexed { index, uri -> SavedAlbumAsset(uri, "image/png", sourceIndex = index) })
            val neighbor = fixture.bravo.copy(uri = neighborUri, uris = listOf(neighborUri),
                bytes = beforeBytes.getValue(neighborUri).size.toLong(),
                locationLabel = "本轮独立 MediaStore 图集目录", fileName = "owned-neighbor.png", isAlbum = true,
                albumAssets = listOf(SavedAlbumAsset(neighborUri, "image/png", sourceIndex = 0)))
            val saved = listOf(album, neighbor, fixture.charlie, fixture.delta)
            fixture.records.writeHistory(saved)
            val mediaFixture = fixture.copy(all = saved)
            mount(mediaFixture) {
                await { fixture.engine.history == fixture.records.history() }
                val original = fixture.records.history()
                assertEquals(albumUris, original.single { it.uri == album.uri }.uris)
                val protectedNeighbor = original.single { it.uri == neighborUri }
                setQuery("猫")
                assertDisplayedOrder(listOf(neighbor.title, album.title), mediaFixture)
                clickText("批量管理")
                await { textOrNull("已选 0 项") != null }
                clickCheckbox(rowCheckbox(album.title))
                await { textOrNull("已选 1 项") != null }
                assertTrue(rowCheckbox(album.title).isChecked)
                assertFalse("The adjacent record must remain unselected", rowCheckbox(neighbor.title).isChecked)
                capture("owned-album-delete-selected")

                clickText("删除文件")
                await { textOrNull("删除 1 项的文件？") != null }
                clickText("取消")
                await { textOrNull("删除 1 项的文件？") == null && textOrNull("已选 1 项") != null }
                assertEquals("Cancelling deletion changed persistent records", original, fixture.records.history())
                assertEquals("Cancelling deletion changed displayed records", original, fixture.engine.history)
                owned.forEach { uri ->
                    assertTrue("Cancelling deletion removed an owned MediaStore row", mediaRowExists(uri))
                    assertEquals("Cancelling deletion changed an original file", beforeHashes.getValue(uri), sha256(readOwnedMedia(uri)))
                }
                assertTrue("Cancelling must retain the selected album", rowCheckbox(album.title).isChecked)
                assertFalse(rowCheckbox(neighbor.title).isChecked)
                capture("owned-album-delete-cancelled")

                clickText("删除文件")
                await { textOrNull("删除 1 项的文件？") != null }
                clickText("确认删除文件")
                await { !fixture.engine.busy && fixture.engine.history.size == 3 &&
                    fixture.engine.history.none { it.uri == album.uri } && textOrNull("删除 1 项的文件？") == null }
                assertEquals("Only the selected whole album should disappear", original.filterNot { it.uri == album.uri }, fixture.records.history())
                assertEquals(fixture.records.history(), fixture.engine.history)
                assertEquals("所选文件与记录已删除", fixture.engine.message)
                albumUris.forEach { uri ->
                    assertFalse("A confirmed album URI still has a MediaStore row", mediaRowExists(uri))
                    assertFalse("A confirmed album URI remains readable", runCatching {
                        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.read() >= 0 } ?: false
                    }.getOrDefault(false))
                }
                assertTrue("Deleting the album removed its adjacent MediaStore file", mediaRowExists(neighborUri))
                assertEquals("Deleting the album altered its adjacent file", beforeHashes.getValue(neighborUri), sha256(readOwnedMedia(neighborUri)))
                assertEquals("Deleting the album altered its adjacent record", protectedNeighbor,
                    fixture.records.history().single { it.uri == neighborUri })
                await { textOrNull("已选 0 项") != null }
                assertDisplayedOrder(listOf(neighbor.title), mediaFixture)
                capture("owned-album-delete-confirmed")
                clickText("完成选择")
                clickText("刷新")
                await { fixture.engine.history == fixture.records.history() && fixture.engine.history.size == 3 }
                assertEquals(protectedNeighbor, fixture.engine.history.single { it.uri == neighborUri })
                assertEquals(beforeHashes.getValue(neighborUri), sha256(readOwnedMedia(neighborUri)))
                println("history_ui_whole_album_delete=verified selected_files=2 protected_neighbor_files=1 cancellation_preserved=true confirmation_deleted_all=true")
            }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            // These URIs were inserted by this method; no path scan or user-history cleanup is used.
            val cleanup = runCatching {
                owned.forEach { uri ->
                    if (mediaRowExists(uri)) assertEquals("Could not clean this method's owned MediaStore row", 1,
                        context.contentResolver.delete(Uri.parse(uri), null, null))
                }
            }
            cleanup.exceptionOrNull()?.let { failure ->
                originalFailure?.let { it.addSuppressed(failure) } ?: throw failure
            }
        }
    }

    private fun publishOwnedHistoryImage(namespace: String, name: String, color: Int, owned: MutableSet<String>): String {
        check(Build.VERSION.SDK_INT >= 29) { "This native MediaStore regression requires scoped storage" }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, namespace + name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/YingxiaHistoryTests/$namespace/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })) { "Could not insert this method's owned MediaStore image" }
        owned += uri.toString()
        check(uri.scheme == "content" && uri.authority == "media") { "The deletion regression must use real MediaStore URIs" }
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use {
                assertTrue("Could not write the owned MediaStore PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        assertEquals("Could not publish the owned MediaStore image", 1,
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null))
        assertTrue(readOwnedMedia(uri.toString()).isNotEmpty())
        return uri.toString()
    }

    private fun readOwnedMedia(uri: String): ByteArray = checkNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use { it.readBytes() }

    private fun mediaRowExists(uri: String): Boolean = context.contentResolver.query(Uri.parse(uri),
        arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.moveToFirst() } ?: false

    private data class Fixture(val namespace: String, val directory: File, val engine: SaverEngine,
        val records: DownloadRecords, val all: List<SavedVideo>, val files: Map<String, File>, val hashes: Map<String, String>) {
        val alpha get() = all[0]
        val bravo get() = all[1]
        val charlie get() = all[2]
        val delta get() = all[3]
    }

    private fun mount(fixture: Fixture, body: () -> Unit) {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val model = SaverViewModel(context.applicationContext as Application)
                activity.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { HistoryScreen(model) } } }
            }
            await { textOrNull("共 4 个作品 · 文件与记录可以分别管理") != null && searchFieldOrNull() != null }
            // MainActivity may perform its normal one-time appearance schema migration on launch.
            // This baseline is taken before any HistoryScreen interaction, without restoring or rewriting user data.
            val protectedPreferences = userPreferences()
            val protectedHistory = DownloadRecords(context).history()
            val background = File(context.filesDir, "appearance/background.jpg")
            val protectedBackground = background.takeIf(File::isFile)?.let(::sha256)
            val siblingStores = listOf("download_tasks", "download_options", "parse_diagnostics", "preview_options")
                .associateWith { context.getSharedPreferences(fixture.namespace + it, Context.MODE_PRIVATE).all.toMap() }
            var originalFailure: Throwable? = null
            try { body() } catch (failure: Throwable) {
                originalFailure = failure
                runCatching { capture("failure-${SystemClock.uptimeMillis()}") }
                throw failure
            } finally {
                val verification = runCatching {
                    assertFixtureFilesUnchanged(fixture)
                    assertEquals("History controls changed real user preferences", protectedPreferences, userPreferences())
                    assertEquals("History controls changed the real user history", protectedHistory, DownloadRecords(context).history())
                    assertEquals("History controls changed the user's private background", protectedBackground,
                        background.takeIf(File::isFile)?.let(::sha256))
                    siblingStores.forEach { (name, previous) ->
                        assertEquals("History controls changed the isolated sibling store $name", previous,
                            context.getSharedPreferences(fixture.namespace + name, Context.MODE_PRIVATE).all.toMap())
                    }
                }
                verification.exceptionOrNull()?.let { failure ->
                    originalFailure?.let { it.addSuppressed(failure) } ?: throw failure
                }
            }
        }
    }

    private fun isolated(body: (Fixture) -> Unit) {
        val namespace = "history_controls_${UUID.randomUUID()}_"
        val directory = File(context.cacheDir, namespace).apply {
            check(canonicalFile.parentFile == context.cacheDir.canonicalFile && mkdir())
        }
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        var engine: SaverEngine? = null
        try {
            val definitions = listOf(
                Triple("A Alpha 猫", "alpha.png", 4_096), Triple("B Bravo 猫", "Needle-Bravo.png", 1_024),
                Triple("C Charlie 狗", "charlie.png", 2_048), Triple("D Delta 鸟", "delta.png", 8_192))
            val files = linkedMapOf<String, File>()
            val saved = definitions.mapIndexed { index, (title, name, size) ->
                val file = File(directory, name)
                val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(listOf(0xffcc2222.toInt(), 0xff22cc22.toInt(), 0xff2222cc.toInt(), 0xffccaa22.toInt())[index])
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
                check(file.length() <= size)
                file.appendBytes(ByteArray(size - file.length().toInt()))
                val uri = Uri.fromFile(file).toString()
                files[uri] = file
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")!!.use { assertEquals(size.toLong(), it.statSize) }
                SavedVideo("900000000000000000${index + 1}", title, uri, file.length(), "本例独立缓存目录",
                    savedAt = listOf(2_000L, 4_000L, 1_000L, 3_000L)[index], mimeType = "image/png",
                    fileName = name, isAlbum = true, watermarkMode = WatermarkMode.CLEAN,
                    albumAssets = listOf(SavedAlbumAsset(uri, "image/png", sourceIndex = 0)))
            }
            val records = DownloadRecords(context, namespace)
            records.writeHistory(saved)
            assertTrue(context.getSharedPreferences(namespace + "download_options", Context.MODE_PRIVATE).edit()
                .putInt("static_image_duration_tenths", 17).putString("naming", "TITLE").commit())
            records.writeQueue(listOf(QueueTask("keep-queue", "https://v.douyin.com/OwnHistoryFixture/", createdAt = 23L)))
            instrumentation.runOnMainSync {
                engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                    .apply { isAccessible = true }.newInstance(context.applicationContext as Application, namespace)
                singleton.set(null, engine)
            }
            body(Fixture(namespace, directory, checkNotNull(engine), records, saved, files, files.mapValues { sha256(it.value) }))
        } finally {
            instrumentation.runOnMainSync {
                engine?.let { (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel() }
                singleton.set(null, previous)
            }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics", "preview_options")
                .forEach { context.deleteSharedPreferences(namespace + it) }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    private fun userPreferences() = listOf("appearance_v1", "downloads", "download_tasks", "download_options",
        "parse_diagnostics", "preview_options", "download_folder", "notification_prompt")
        .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }

    private fun assertFixtureFilesUnchanged(fixture: Fixture) {
        fixture.files.forEach { (uri, file) ->
            assertTrue("Removing a record removed its original own-cache PNG", file.isFile)
            assertEquals("History controls modified a fixture file", fixture.hashes.getValue(uri), sha256(file))
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
            assertEquals("Fixture URI became unreadable or pointed at different bytes", fixture.hashes.getValue(uri), sha256(bytes))
        }
    }

    private fun setQuery(value: String) {
        scrollTop()
        val field = searchField()
        assertTrue("Production history search rejected its edit", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        instrumentation.waitForIdleSync()
        await { searchField().text?.toString() == value.take(200) }
    }

    private fun assertDisplayedOrder(expected: List<String>, fixture: Fixture) {
        scrollTop()
        await { visibleFixtureTitles(fixture).firstOrNull() == expected.firstOrNull() }
        val observed = linkedSetOf<String>()
        repeat(12) {
            visibleFixtureTitles(fixture).forEach(observed::add)
            if (observed.size >= expected.size) {
                assertEquals("The actual production history cards were not in the requested order", expected, observed.toList())
                scrollTop()
                return
            }
            val list = scrollable()
            assertTrue("History list stopped before every expected card was visible", list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        fail("Not all history cards were observed; expected=$expected actual=$observed")
    }

    private fun visibleFixtureTitles(fixture: Fixture): List<String> {
        val known = fixture.all.map { it.title }.toSet()
        return nodes().filter { it.isVisibleToUser && it.text?.toString() in known }
            .sortedBy { bounds(it).top }.map { it.text.toString() }.distinct()
    }

    private fun rowCheckbox(title: String): AccessibilityNodeInfo {
        scrollTop()
        repeat(12) {
            val label = textOrNull(title)
            if (label != null) {
                val rowY = bounds(label).centerY()
                val checkbox = nodes().filter { it.isVisibleToUser && it.isCheckable }
                    .minByOrNull { abs(bounds(it).centerY() - rowY) }
                if (checkbox != null && abs(bounds(checkbox).centerY() - rowY) <=
                    (66 * context.resources.displayMetrics.density).toInt()) return checkbox
            }
            if (!scrollable().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                error("History row '$title' has no visible real checkbox")
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(70)
        }
        error("History row '$title' has no visible real checkbox")
    }

    private fun allCheckbox(count: Int): AccessibilityNodeInfo {
        scrollTop()
        val label = checkNotNull(textOrNull("已选 $count 项")) { "Missing actual history selection count" }
        val labelY = bounds(label).centerY()
        return checkNotNull(nodes().filter { it.isVisibleToUser && it.isCheckable }
            .minByOrNull { abs(bounds(it).centerY() - labelY) }) { "History select-all checkbox is missing" }
            .also { assertTrue("Select-all checkbox was not beside the selection summary",
                abs(bounds(it).centerY() - labelY) < (24 * context.resources.displayMetrics.density).toInt()) }
    }

    private fun clickCheckbox(node: AccessibilityNodeInfo) {
        assertTrue("Production history checkbox rejected selection", clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        scrollTop()
    }

    private fun clickText(value: String) {
        if (textOrNull(value) == null) scrollTop()
        await { textOrNull(value) != null }
        assertTrue("Production history action '$value' was rejected", clickable(checkNotNull(textOrNull(value)))
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun searchFieldOrNull() = nodes().firstOrNull { it.isVisibleToUser && it.isEditable &&
        it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
    private fun searchField() = checkNotNull(searchFieldOrNull()) { "Production history search field is missing" }
    private fun scrollable() = checkNotNull(nodes().firstOrNull { it.isVisibleToUser && it.isScrollable && it.rangeInfo == null }) {
        "Production history lazy list is missing"
    }
    private fun scrollTop() {
        repeat(12) {
            val list = nodes().firstOrNull { it.isVisibleToUser && it.isScrollable && it.rangeInfo == null } ?: return
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
    }
    private fun textOrNull(value: String) = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == value }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && current.packageName?.toString() == context.packageName) {
            current.refresh()
            if (current.isClickable) return current
            current = current.parent
        }
        error("Expected a clickable production history control")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = mutableSetOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !seen.add(node) || !node.refresh() || node.packageName?.toString() != context.packageName) return
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        instrumentation.uiAutomation.windows.forEach { visit(it.root) }
        return result
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(70)
        }
        fail("Expected production history UI state was not reached; texts=${nodes().map { it.text?.toString() }}")
    }
    private fun capture(name: String) {
        val directory = File(context.cacheDir, "history_controls_captures").apply { check(isDirectory || mkdir()) }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(directory, "$name-${UUID.randomUUID()}.png")
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        println("history_ui_capture=${file.absolutePath}")
    }
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
