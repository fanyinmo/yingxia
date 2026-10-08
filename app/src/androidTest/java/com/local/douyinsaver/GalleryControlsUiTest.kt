package com.local.douyinsaver

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Own-app Compose controls with local metadata and fixture images, never public parsing/downloads. */
@RunWith(AndroidJUnit4::class)
class GalleryControlsUiTest {
    companion object {
        private val captureDirectoryName = "gallery_ui_${UUID.randomUUID()}"
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun image(kind: AlbumAssetKind = AlbumAssetKind.STATIC, seconds: Double = 2.7) = ParsedImage(
        DynamicPreviewFixtureProvider.GIF_URI.toString(), 16, 16, kind = kind,
        motion = if (kind in listOf(AlbumAssetKind.LIVE, AlbumAssetKind.DYNAMIC))
            ParsedMotion("content://fixture/never-played", 16, 16, seconds) else null)

    private fun album(vararg images: ParsedImage) = ParsedVideo("ui-gallery", "自制图集界面", "", 0.0, 0, 0,
        images = images.toList())

    @Test fun removedLiveActionsLeaveOnlyAnimationGifAndBgmControls() = isolated { _ ->
        val content = mutableStateOf(album(image(AlbumAssetKind.ANIMATED)).copy(bgmUrl = "https://fixture/bgm"))
        val saves = mutableListOf<AlbumMode>()
        mount {
            GalleryDownloadControls(content.value, true, true, AlbumMotionReadState(), { saves += it },
                { saves += it }, {}, {}, content.value.id)
        }.use {
            await { textOrNull("保存动图") != null }
            assertNull(textOrNull("转实况"))
            assertNull(textOrNull("实况照片"))
            assertNull(textOrNull("用动态片段生成实况"))
            assertNotNull(textOrNull("保留动态格式"))
            assertNotNull(textOrNull("GIF 动图"))
            clickText("保存动图")
            await { saves.lastOrNull() == AlbumMode.IMAGES }
            reveal("素材 + BGM 合成视频")
            assertNotNull(textOrNull("素材 + BGM 合成视频"))
            capture("rc21-animation-without-live-actions")
            instrumentation.runOnMainSync { content.value = album(image(AlbumAssetKind.DYNAMIC)) }
            await { textOrNull("无声动图") != null }
            assertNull(textOrNull("实况照片"))
            clickText("无声动图")
            await { textOrNull("保存为无声动图") != null }
            clickText("保存为无声动图")
            await { saves.lastOrNull() == AlbumMode.MOTION_VIDEOS }
            capture("rc21-dynamic-formats")
        }
    }

    @Test fun staticGalleryHasNoDynamicFormatPanelAndRequiresTwoPicturesForSequenceGif() = isolated { _ ->
        val content = mutableStateOf(album(image()))
        val saves = mutableListOf<AlbumMode>()
        var checks = 0
        mount {
            GalleryDownloadControls(content.value, true, true, AlbumMotionReadState(),
                onSave = { saves += it }, onSaveMotion = { error("A source check must not save") },
                onCancel = {}, onVerification = {}, selectionKey = "static", onCheckMotion = { checks++ })
        }.use {
            await { textOrNull("保存 1 张图片") != null }
            assertNull(textOrNull("保留动态格式"))
            assertNull(textOrNull("GIF 动图"))
            assertNull(textOrNull("用动态片段生成实况"))
            assertNull(textOrNull("图片序列合成 GIF"))
            capture("static-single")
            clickText("重新检查动态资源")
            await { checks == 1 }
            assertTrue(saves.isEmpty())
            clickText("保存 1 张图片")
            await { saves == listOf(AlbumMode.IMAGES) }
            instrumentation.runOnMainSync { content.value = album(image(), image()) }
            await { textOrNull("图片序列合成 GIF") != null }
            assertNull(textOrNull("保留动态格式"))
            capture("static-sequence")
            clickText("图片序列合成 GIF")
            await { saves.lastOrNull() == AlbumMode.GIF }
        }
    }

    @Test fun nativeAnimationAndKnownLiveHaveDifferentDefaultActionsAndLiveCanComposeBgm() = isolated { _ ->
        val content = mutableStateOf(album(image(AlbumAssetKind.ANIMATED)))
        val saves = mutableListOf<AlbumMode>()
        mount {
            GalleryDownloadControls(content.value, true, true, AlbumMotionReadState(), { saves += it },
                { saves += it }, {}, {}, content.value.id)
        }.use {
            await { textOrNull("保存动图") != null }
            assertNotNull(textOrNull("保留动态格式"))
            assertNull(textOrNull("实况照片"))
            assertNotNull(textOrNull("动态 GIF 使用各自原时长 · 动态时长待读取"))
            clickText("保存动图")
            await { saves == listOf(AlbumMode.IMAGES) }
            clickText("GIF 动图")
            val explanation = "按下方画质选项生成 GIF，无声音。分享模式减小尺寸、帧率和颜色；清晰模式保留更多细节。设置统一时长后按所选时长截取或循环。"
            try {
                reveal(explanation)
                assertNotNull(textOrNull(explanation))
                capture("known-animation-gif-explanation")
            } catch (failure: Throwable) {
                runCatching { capture("known-animation-gif-explanation-failure") }
                throw failure
            }
            clickText("保存 GIF 动图")
            await { saves.lastOrNull() == AlbumMode.GIF }
            clickText("转实况")
            clickText("动图转实况照片")
            await { saves.lastOrNull() == AlbumMode.CONVERT_TO_LIVE }
            assertEquals(AlbumAssetKind.ANIMATED, content.value.images.single().kind)
            instrumentation.runOnMainSync {
                content.value = album(image(AlbumAssetKind.LIVE)).copy(id = "known-live", bgmUrl = "content://fixture/never-read-audio")
            }
            await { textOrNull("保存实况") != null }
            assertNull(textOrNull("用动态片段生成实况"))
            assertNotNull(textOrNull("动态 GIF 使用各自原时长 · 合成视频总时长约 2.7 秒"))
            capture("known-live-with-bgm")
            clickText("保存实况")
            await { saves.lastOrNull() == AlbumMode.IMAGES }
            clickText("素材 + BGM 合成视频")
            await { saves.lastOrNull() == AlbumMode.VIDEO }
            clickText("仅保存静态封面")
            await { saves.lastOrNull() == AlbumMode.COVERS }
        }
    }

    @Test fun automaticReadHoldsStaticCompositionUntilTheDynamicResultArrives() = isolated { _ ->
        val content = mutableStateOf(album(image()).copy(bgmUrl = "content://fixture/never-read-audio"))
        val state = mutableStateOf(AlbumMotionReadState(AlbumMotionPhase.READING))
        val saves = mutableListOf<AlbumMode>()
        mount {
            GalleryDownloadControls(content.value, false, false, state.value,
                { saves += it }, { saves += it }, {}, {}, "automatic-motion")
        }.use {
            await { textOrNull("正在自动识别图片与动态内容…") != null }
            assertNull(textOrNull("保存 1 张图片"))
            assertNull(textOrNull("素材 + BGM 合成视频"))
            assertNotNull(textOrNull("跳过"))
            assertTrue(saves.isEmpty())
            capture("automatic-motion-reading")
            instrumentation.runOnMainSync {
                content.value = album(image(AlbumAssetKind.ANIMATED))
                state.value = AlbumMotionReadState(AlbumMotionPhase.AVAILABLE)
            }
            await { textOrNull("保存动图") != null }
            assertNull(textOrNull("保存 1 张图片"))
            assertNotNull(textOrNull("仅保存静态封面"))
            assertNull(textOrNull("重新检查动态资源"))
            capture("automatic-motion-ready")
        }
    }

    @Test fun unknownMotionNeverDefaultsToLiveAndRequiresAnExplicitFormatChoice() = isolated { _ ->
        val saves = mutableListOf<AlbumMode>()
        mount {
            GalleryDownloadControls(album(image(AlbumAssetKind.DYNAMIC)), true, true, AlbumMotionReadState(),
                { saves += it }, { saves += it }, {}, {}, "unknown")
        }.use {
            await { textOrNull("请选择保存格式") != null }
            assertFalse(clickable(text("请选择保存格式")).isEnabled)
            assertNotNull(textOrNull("网页未明确标注动态素材类型，请选择保存格式。"))
            assertNull(textOrNull("保留动态格式"))
            assertTrue(saves.isEmpty())
            capture("unknown-needs-format")
            clickText("实况照片")
            clickText("保存为实况照片")
            await { saves == listOf(AlbumMode.LIVE_PHOTOS) }
            clickText("无声动图")
            capture("unknown-silent-motion")
            clickText("保存为无声动图")
            await { saves.lastOrNull() == AlbumMode.MOTION_VIDEOS }
            clickText("GIF 动图")
            clickText("保存 GIF 动图")
            await { saves.lastOrNull() == AlbumMode.GIF }
            reveal("用动态片段生成实况")
            reveal("已移除的实况转换说明")
            assertNotNull(textOrNull("已移除的实况转换说明"))
            capture("unknown-explicit-generated-live")
            clickText("用动态片段生成实况")
            await { saves.lastOrNull() == AlbumMode.CONVERT_TO_LIVE }
        }
    }

    @Test fun unknownGeneratedLiveCoverRequiresAnExplicitClickAndWaitsForMotionReadingOrVerification() = isolated { _ ->
        val content = album(image(AlbumAssetKind.DYNAMIC))
        val state = mutableStateOf(AlbumMotionReadState(AlbumMotionPhase.READING))
        val saves = mutableListOf<AlbumMode>()
        mount {
            GalleryDownloadControls(content, true, true, state.value, { saves += it }, { saves += it },
                {}, {}, "unknown-generated-live")
        }.use {
            await { textOrNull("用动态片段生成实况") != null }
            assertActionEnabled("用动态片段生成实况", false)
            assertTrue(saves.isEmpty())
            instrumentation.runOnMainSync { state.value = AlbumMotionReadState(AlbumMotionPhase.NEEDS_VERIFICATION) }
            await { textOrNull("打开抖音验证页面") != null }
            assertActionEnabled("用动态片段生成实况", false)
            assertTrue(saves.isEmpty())
            instrumentation.runOnMainSync { state.value = AlbumMotionReadState(AlbumMotionPhase.AVAILABLE) }
            await { clickable(text("用动态片段生成实况")).isEnabled }
            assertFalse(clickable(text("请选择保存格式")).isEnabled)
            assertNotNull(textOrNull("实况照片"))
            assertNotNull(textOrNull("无声动图"))
            assertNotNull(textOrNull("GIF 动图"))
            assertNull(textOrNull("转实况"))
            assertTrue(saves.isEmpty())
            clickText("用动态片段生成实况")
            await { saves == listOf(AlbumMode.CONVERT_TO_LIVE) }
            assertEquals(AlbumAssetKind.DYNAMIC, content.images.single().kind)
        }
    }

    @Test fun invalidCompositionDurationDoesNotDisableOriginalPicturesAndDynamicFormats() = isolated { _ ->
        val content = mutableStateOf(album(image(AlbumAssetKind.ANIMATED)).copy(id = "invalid-native-gif",
            bgmUrl = "content://fixture/never-read-audio"))
        val seconds = mutableStateOf<Double?>(null)
        val saves = mutableListOf<AlbumMode>()
        mount {
            GalleryDownloadControls(content.value, true, true, AlbumMotionReadState(), { saves += it },
                { saves += it }, {}, {}, content.value.id, itemDurationSeconds = seconds.value,
                onItemDuration = { seconds.value = it }, onCheckMotion = {})
        }.use {
            val cases = listOf(image(AlbumAssetKind.ANIMATED), image(AlbumAssetKind.LIVE),
                image(AlbumAssetKind.ANIMATED).copy(motion = ParsedMotion("content://fixture/never-played", durationSeconds = 2.7)))
            cases.forEachIndexed { index, item ->
                instrumentation.runOnMainSync {
                    content.value = album(item).copy(id = "invalid-original-$index", bgmUrl = "content://fixture/never-read-audio")
                }
                await { textOrNull("设置 GIF / 合成视频时长") != null }
                clickText("设置 GIF / 合成视频时长")
                await { editables().size == 1 }
                setText(editables().single(), "")
                assertActionEnabled("素材 + BGM 合成视频", false)
                val label = if (item.kind == AlbumAssetKind.LIVE) "保存实况" else "保存动图"
                assertActionEnabled(label, true)
                clickText(label)
                assertEquals(AlbumMode.IMAGES, saves.last())
                clickText("GIF 动图")
                assertActionEnabled("保存 GIF 动图", false)
                assertActionEnabled("仅保存静态封面", true)
                clickText("仅保存静态封面")
                assertEquals(AlbumMode.COVERS, saves.last())
            }
            instrumentation.runOnMainSync {
                content.value = album(image(AlbumAssetKind.DYNAMIC)).copy(id = "invalid-unknown", bgmUrl = "content://fixture/never-read-audio")
            }
            await { textOrNull("设置 GIF / 合成视频时长") != null }
            clickText("设置 GIF / 合成视频时长")
            await { editables().size == 1 }
            setText(editables().single(), "")
            clickText("实况照片")
            assertActionEnabled("保存为实况照片", true)
            clickText("保存为实况照片")
            assertEquals(AlbumMode.LIVE_PHOTOS, saves.last())
            clickText("无声动图")
            assertActionEnabled("保存为无声动图", true)
            clickText("保存为无声动图")
            assertEquals(AlbumMode.MOTION_VIDEOS, saves.last())
            clickText("GIF 动图")
            assertActionEnabled("保存 GIF 动图", false)
            assertActionEnabled("素材 + BGM 合成视频", false)
            assertActionEnabled("用动态片段生成实况", true)
            instrumentation.runOnMainSync {
                content.value = album(image(), image()).copy(id = "invalid-static", bgmUrl = "content://fixture/never-read-audio")
            }
            await { textOrNull("设置 GIF / 合成视频时长") != null }
            clickText("设置 GIF / 合成视频时长")
            await { editables().size == 1 }
            setText(editables().single(), "")
            assertActionEnabled("保存 2 张图片", true)
            clickText("保存 2 张图片")
            assertEquals(AlbumMode.IMAGES, saves.last())
            assertActionEnabled("图片序列合成 GIF", false)
            assertActionEnabled("素材 + BGM 合成视频", false)
        }
    }

    @Test fun previewPagingPreservesOriginalIndicesAndUnknownItemAsksForFormat() = isolated { _ ->
        val saves = mutableListOf<Pair<Int, AlbumMode>>()
        val images = listOf(image(AlbumAssetKind.DYNAMIC), image(), image())
        mount { AlbumPreview(images, "本地逐项预览", "per-item", onSaveItem = { index, mode -> saves += index to mode }) }.use {
            try {
                clickText("2 / 3 · 图片")
                capture("preview-second-item-opened")
                await { textOrNull("图片 · 2 / 3") != null }
                clickText("下一张")
                await { textOrNull("图片 · 3 / 3") != null }
                assertNull(textOrNull("用动态片段生成实况"))
                clickText("保存这张图片")
                await { saves == listOf(2 to AlbumMode.IMAGES) }
                clickText("1 / 3 · 动态素材")
                clickText("保存这项动态素材")
                await { textOrNull("选择保存格式") != null }
                capture("preview-per-item-format")
                assertEquals(1, saves.size)
                clickText("保存为无声动图")
                await { saves.lastOrNull() == (0 to AlbumMode.MOTION_VIDEOS) }
                clickText("1 / 3 · 动态素材")
                clickText("仅保存这张静态封面")
                await { saves.lastOrNull() == (0 to AlbumMode.COVERS) }
                clickText("1 / 3 · 动态素材")
                reveal("用动态片段生成实况")
                reveal("已移除的实况转换说明")
                assertNotNull(textOrNull("已移除的实况转换说明"))
                capture("preview-explicit-generated-live")
                clickText("用动态片段生成实况")
                await { saves.lastOrNull() == (0 to AlbumMode.CONVERT_TO_LIVE) }
                assertEquals(listOf(AlbumAssetKind.DYNAMIC, AlbumAssetKind.STATIC, AlbumAssetKind.STATIC), images.map { it.kind })
            } catch (failure: Throwable) {
                runCatching { capture("preview-assertion-failure") }
                throw failure
            }
        }
    }

    @Test fun tenthsInputAndRoundSliderStaySynchronizedAndResetToAutomatic() = isolated { _ ->
        val seconds = mutableStateOf<Double?>(null)
        val content = album(image(), image())
        mount {
            GalleryDownloadControls(content, true, true, AlbumMotionReadState(), {}, {}, {}, {}, "tenths",
                itemDurationSeconds = seconds.value, onItemDuration = { seconds.value = it }, onCheckMotion = {})
        }.use {
            clickText("设置 GIF 时长")
            await { editables().size == 1 && sliderOrNull() != null }
            setText(editables().single(), "0.1")
            await { seconds.value == 0.1 && abs(checkNotNull(sliderOrNull()).rangeInfo.current - 0.1f) < 0.0001f }
            assertNotNull(textOrNull("静图 0.1 秒/张 · 图片序列 GIF 总时长约 0.2 秒"))
            setProgress(2.7f)
            await { abs((seconds.value ?: 0.0) - 2.7) < 0.0001 && editables().singleOrNull()?.text?.toString() == "2.7" }
            assertNotNull(textOrNull("静图 2.7 秒/张 · 图片序列 GIF 总时长约 5.4 秒"))
            capture("duration-tenths-and-slider")
            clickText("使用原时长")
            await { seconds.value == null && textOrNull("静图 3 秒/张 · 图片序列 GIF 总时长约 6 秒") != null }
            assertNotNull(textOrNull("静图 3 秒/张 · 图片序列 GIF 总时长约 6 秒"))
        }
    }

    @Test fun taskDurationEditorsDoNotChangeAnotherTaskOrSingleResult() = isolated { engine ->
        val selected = mutableStateOf("task-a")
        val model = SaverViewModel(context.applicationContext as Application)
        instrumentation.runOnMainSync {
            setState(engine, "queue", listOf(QueueTask("task-a", "local-a"), QueueTask("task-b", "local-b")))
            model.itemDurationSeconds = 7.6
        }
        mount {
            val key = selected.value
            Text(key)
            Row {
                TextButton(onClick = { selected.value = "task-a" }) { Text("查看任务 A") }
                TextButton(onClick = { selected.value = "task-b" }) { Text("查看任务 B") }
            }
            GalleryDownloadControls(album(image(), image()), true, true, AlbumMotionReadState(), {}, {}, {}, {}, key,
                itemDurationSeconds = model.itemDurationForTask(key),
                onItemDuration = { model.setItemDurationForTask(key, it) }, onCheckMotion = {})
        }.use {
            clickText("设置 GIF 时长")
            await { editables().size == 1 }
            setText(editables().single(), "1.3")
            await { model.itemDurationForTask("task-a") == 1.3 }
            assertNull(model.itemDurationForTask("task-b"))
            clickText("查看任务 B")
            clickText("设置 GIF 时长")
            await { editables().size == 1 }
            setText(editables().single(), "0.4")
            await { model.itemDurationForTask("task-b") == 0.4 }
            capture("queue-task-b-independent-duration")
            assertEquals(1.3, model.itemDurationForTask("task-a")!!, 0.0001)
            assertEquals(7.6, model.itemDurationSeconds!!, 0.0001)
            clickText("查看任务 A")
            clickText("设置 GIF 时长")
            await { editables().singleOrNull()?.text?.toString() == "1.3" }
            assertEquals(0.4, model.itemDurationForTask("task-b")!!, 0.0001)
        }
    }

    @Test fun measuredDurationWarningRequiresContinueOrReturnToEditing() = isolated { engine ->
        val model = SaverViewModel(context.applicationContext as Application)
        val first = CompletableDeferred<Boolean>()
        fun warning(answer: CompletableDeferred<Boolean>) = instrumentation.runOnMainSync {
            SaverEngine::class.java.getDeclaredField("durationAnswer").apply { isAccessible = true }.set(engine, answer)
            setState(engine, "durationAdjustments", listOf(DurationAdjustment(0, 3.0, 1.5), DurationAdjustment(2, 3.0, 6.0)))
        }
        warning(first)
        mount { DurationAdjustmentDialog(model) }.use {
            await { textOrNull("设置时长与素材原时长不同") != null }
            assertNotNull(textOrNull("第 1 项：原时长 3 秒 → 1.5 秒，截取开头。"))
            assertNotNull(textOrNull("第 3 项：原时长 3 秒 → 6 秒，循环播放至设置时长。"))
            assertFalse(first.isCompleted)
            capture("measured-duration-confirmation")
            clickText("继续")
            await { first.isCompleted && model.durationAdjustments.isEmpty() }
            assertTrue(runBlocking { first.await() })
            val second = CompletableDeferred<Boolean>()
            warning(second)
            await { textOrNull("设置时长与素材原时长不同") != null }
            clickText("返回修改")
            await { second.isCompleted && model.durationAdjustments.isEmpty() }
            assertFalse(runBlocking { second.await() })
        }
    }

    @Test fun bulkUnknownFormatIsExplicitAndOnlyChangesUnknownItems() = isolated { engine ->
        val cover = "https://p3.douyinpic.com/gallery_ui_source.jpg"
        val clip = "https://v3.douyinvod.com/gallery_ui_motion.mp4"
        val unknown = album(ParsedImage(cover, kind = AlbumAssetKind.DYNAMIC,
            mediaSources = listOf(MediaSource(cover, WatermarkMode.CLEAN)),
            motion = ParsedMotion(clip, durationSeconds = 2.7,
                mediaSources = listOf(MediaSource(clip, WatermarkMode.CLEAN))))).copy(id = "bulk-unknown")
        val known = album(ParsedImage(cover, mediaSources = listOf(MediaSource(cover, WatermarkMode.CLEAN)))).copy(id = "bulk-static")
        val calls = CopyOnWriteArrayList<Pair<String, AlbumMode>>()
        val model = SaverViewModel(context.applicationContext as Application)
        instrumentation.runOnMainSync {
            setState(engine, "queue", listOf(QueueTask("unknown", "local-unknown", status = QueueStatus.READY),
                QueueTask("known", "local-known", status = QueueStatus.READY)))
            setState(engine, "queueResults", mapOf("unknown" to unknown, "known" to known))
            engine.queueSaveOverride = { content, options ->
                calls += content.id to options.albumMode
                SavedVideo(content.id, content.title, "content://fixture/never-written-${content.id}", 42,
                    mimeType = "image/jpeg", isAlbum = true, watermarkMode = WatermarkMode.CLEAN,
                    exportMode = options.albumMode.name)
            }
        }
        mount { BatchQueueHeader(model, onAdd = {}) }.use {
            clickText("保存已解析")
            await { textOrNull("选择动态素材保存格式") != null }
            assertFalse(clickable(text("开始保存")).isEnabled)
            assertNull(textOrNull("保存 GIF 动图"))
            capture("bulk-format-two-choices")
            assertTrue(calls.isEmpty())
            clickText("取消")
            await { textOrNull("选择动态素材保存格式") == null }
            assertTrue(calls.isEmpty())
            assertFalse(model.batchSaving)
            clickText("保存已解析")
            clickText("保存为无声动图")
            clickText("开始保存")
            await { calls.size == 2 && !model.batchSaving }
            assertEquals(listOf("bulk-unknown" to AlbumMode.MOTION_VIDEOS, "bulk-static" to AlbumMode.IMAGES), calls.toList())
        }
    }

    @Test fun videoGifFullSourceAndRemainingRangesKeepActualLengthAndTenthsStart() = isolated { _ ->
        val saves = mutableListOf<Pair<Float, Float>>()
        val video = ParsedVideo("video-ui", "本地 GIF 范围", "", 178.723991, 1280, 720)
        mount { GifDownloadControls(video, true, onSave = { start, length -> saves += start to length }) }.use {
            await { textOrNull("视频全长 178.7 秒；GIF 最长可覆盖完整视频，从中间开始则最长到视频结尾。") != null }
            clickText("截取设置")
            clickText("完整视频")
            capture("video-gif-complete-source")
            clickText("保存 GIF")
            await { saves.size == 1 }
            assertEquals(0f, saves.last().first, 0.0001f)
            assertEquals(178.723991f, saves.last().second, 0.001f)
            await { editables().size == 2 }
            val start = editables().single { it.text?.toString() == "0" }
            setText(start, "0.1")
            clickText("从起点到结尾")
            clickText("保存 GIF")
            await { saves.size == 2 }
            assertEquals(0.1f, saves.last().first, 0.0001f)
            assertEquals(178.623991f, saves.last().second, 0.001f)
        }
    }

    private fun mount(content: @Composable ColumnScope.() -> Unit): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            } }
        } }
        instrumentation.waitForIdleSync()
        return scenario
    }

    private fun capture(name: String) {
        assertTrue("Only the own fixture UI may be captured", nodes().any { it.isVisibleToUser })
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.cacheDir, captureDirectoryName).apply { check(isDirectory || mkdirs()) }
        val destination = File(directory, "$name.png")
        try { destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        println("gallery_ui_capture=${destination.absolutePath}")
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue("The gallery timing edit was not accepted", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        instrumentation.waitForIdleSync()
    }
    private fun setProgress(value: Float) {
        val slider = checkNotNull(sliderOrNull()) { "Missing gallery duration slider" }
        assertTrue("The duration slider rejected a tenths value", slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
        instrumentation.waitForIdleSync()
    }
    private fun sliderOrNull() = nodes().firstOrNull { it.isVisibleToUser && it.rangeInfo != null &&
        it.actionList.any { action -> action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id } }
    private fun editables() = nodes().filter { it.isVisibleToUser && it.isEditable &&
        it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }

    private fun clickText(value: String) {
        reveal(value)
        assertTrue("The '$value' action was not accepted", clickable(text(value)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun assertActionEnabled(value: String, enabled: Boolean) {
        reveal(value)
        assertEquals("Unexpected enabled state for '$value'", enabled, clickable(text(value)).isEnabled)
    }
    private fun reveal(value: String) {
        repeat(10) {
            if (textOrNull(value) != null) return
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(70)
        }
        repeat(10) {
            if (textOrNull(value) != null) return
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(70)
        }
        error("Missing own-app gallery action '$value'; nodes=${nodes().map { it.text?.toString() }}")
    }
    private fun text(value: String) = checkNotNull(textOrNull(value)) { "Missing '$value'" }
    private fun textOrNull(value: String) = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == value }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && current.packageName?.toString() == context.packageName) {
            current.refresh()
            if (current.isClickable) return current
            current = current.parent
        }
        error("Expected an own-app clickable gallery control")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val visited = mutableSetOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !visited.add(node) || !node.refresh() || node.packageName?.toString() != context.packageName) return
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
        runCatching { capture("failure-${SystemClock.uptimeMillis()}") }
        fail("Expected gallery UI state was not reached; nodes=${nodes().map { it.text?.toString() }}")
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(engine: SaverEngine, name: String, value: T) {
        val field = SaverEngine::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(engine) as MutableState<T>).value = value
    }
    private fun isolated(body: (SaverEngine) -> Unit) {
        val namespace = "gallery_controls_ui_${UUID.randomUUID()}_"
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        lateinit var engine: SaverEngine
        instrumentation.runOnMainSync {
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(context.applicationContext as Application, namespace)
            singleton.set(null, engine)
        }
        try { body(engine) } finally {
            instrumentation.runOnMainSync {
                (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(engine) as CoroutineScope).cancel()
                singleton.set(null, previous)
            }
            listOf("downloads", "download_tasks", "download_options", "parse_diagnostics")
                .forEach { context.deleteSharedPreferences(namespace + it) }
        }
    }
}
