package com.local.douyinsaver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(model: SaverViewModel, download: (Boolean) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var batchDialog by remember { mutableStateOf(false) }
    var batchPreview by remember { mutableStateOf<ParsedVideo?>(null) }
    var previewOpen by rememberSaveable(model.video?.id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val queueIdle = !model.busy && !model.queueRunning && !model.batchSaving
    val drag = rememberBatchQueueDragState(list, model.queue, queueIdle,
        with(LocalDensity.current) { 72.dp.toPx() }, model::moveTaskTo)
    val singleTask = model.selectedTaskKey == null && !model.queueRunning && !model.batchSaving
    val parsing = singleTask && model.stage in listOf(TaskStage.RESOLVING, TaskStage.BROWSING, TaskStage.VERIFYING)
    fun resolveNow() { focus.clearFocus(); keyboard?.hide(); model.resolve() }
    LazyColumn(Modifier.fillMaxSize(), state = list, userScrollEnabled = drag.key == null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "home_heading") { PageHeading(stringResource(R.string.app_name), "把喜欢的视频下载到手机") }
        item(key = "single_link") { SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("解析链接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { batchDialog = true }, enabled = !model.batchSaving) { Text("批量添加") }
            }
            OutlinedTextField(value = model.input, onValueChange = { model.input = it.take(16_384) }, modifier = Modifier.fillMaxWidth(),
                enabled = queueIdle, minLines = 2, maxLines = 4, placeholder = { Text("粘贴抖音分享文案或链接\n也可以点下方按钮直接粘贴解析") }, shape = MaterialTheme.shapes.small,
                trailingIcon = {
                    if (model.input.isNotEmpty()) IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); model.clearInput() },
                        enabled = queueIdle, modifier = Modifier.semantics { contentDescription = "清空链接" }) {
                        Glyph(GlyphKind.CLOSE, size = 19.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
            Button(onClick = {
                if (model.input.isBlank()) readClipboard(context)?.let { model.input = it; resolveNow() }
                else resolveNow()
            }, enabled = queueIdle, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (parsing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Glyph(GlyphKind.LINK, size = 19.dp)
                Spacer(Modifier.width(8.dp)); Text(if (parsing) "正在解析…" else if (model.input.isBlank()) "粘贴并解析" else "解析作品")
            }
            if (singleTask && model.stage != TaskStage.IDLE && (model.stage != TaskStage.READY ||
                    (model.message != "解析完成，可以下载视频" && !model.message.startsWith("已解析 ")))) TaskFeedback(model)
            else if (model.video == null || !singleTask) Text("也可以从抖音分享菜单直接发送到这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            model.video?.takeIf { singleTask }?.let { video ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                DownloadResult(model, video, download, onPreview = { if (model.selectedContent != null) previewOpen = true })
            }
        } }
        if (model.queue.isNotEmpty()) {
            item(key = "batch_heading") { BatchQueueHeader(model, onAdd = { batchDialog = true }) }
            itemsIndexed(model.queue, key = { _, task -> task.key }) { index, task ->
                val reorder = queueIdle && task.status == QueueStatus.QUEUED
                BatchQueueTaskCard(task, index, model.queue.size, model.queueResults[task.key],
                    operationsEnabled = queueIdle,
                    canRemove = !model.batchSaving && task.status !in listOf(QueueStatus.PARSING, QueueStatus.RUNNING),
                    canReorder = reorder,
                    canMoveUp = reorder && index > 0 && model.queue[index - 1].status == QueueStatus.QUEUED,
                    canMoveDown = reorder && index < model.queue.lastIndex && model.queue[index + 1].status == QueueStatus.QUEUED,
                    drag = drag, modifier = Modifier.batchQueueDragPlacement(drag, task.key),
                    onMove = { model.moveTask(task.key, it) }, onRemove = { model.removeTask(task.key) },
                    onRetry = { model.retryTask(task.key) }, onPreview = { batchPreview = it },
                    onSave = { model.downloadTask(task.key, mode = it) }, onHistory = { model.selectedPage = AppPage.HISTORY })
            }
        }
    }
    if (batchDialog) BatchQueueDialog(onDismiss = { batchDialog = false }, onAdd = { text -> model.enqueueInput(text); batchDialog = false })
    if (previewOpen) model.video?.let {
        VerifiedVideoPreviewDialog(it) { previewOpen = false }
    }
    batchPreview?.let { BatchResultPreview(it) { batchPreview = null } }
}

@Composable
private fun DownloadResult(model: SaverViewModel, video: ParsedVideo, download: (Boolean) -> Unit, onPreview: () -> Unit) {
    val context = LocalContext.current
    var optionsExpanded by remember(video.id) { mutableStateOf(false) }
    val selectedAvailable = WatermarkSources.available(video, WatermarkMode.CLEAN)
    val selectedContent = model.selectedContent
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(if (video.isAlbum) selectedContent?.images?.firstOrNull()?.url.orEmpty()
                else video.coverUrl,
                Modifier.size(76.dp).clip(MaterialTheme.shapes.small), description = "作品封面")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (video.isAlbum) "图集 · ${video.images.size} 张" else "视频已就绪",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!video.isAlbum) Text("${video.width} × ${video.height}  ·  ${duration(video.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!video.isAlbum) TextButton(onClick = onPreview, enabled = !model.busy && selectedAvailable) { Text("预览") }
        }
        if (!selectedAvailable) {
            Text("暂未读取到可下载地址，请重新解析。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (video.isAlbum) {
            selectedContent?.let { AlbumPreview(it.images) }
            if (selectedAvailable) Text(if (video.bgmUrl.isBlank()) "暂未读取到 BGM，仍可保存原图。" else "已读取作品 BGM，可保存原图或合成视频。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        model.duplicate?.let { existing ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已经保存过此作品", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openSaved(context, existing) }) { Text("打开已有文件") }
            }
        }
        TextButton(onClick = { optionsExpanded = !optionsExpanded }, enabled = !model.busy,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Text(if (optionsExpanded) "收起下载选项" else if (video.isAlbum && video.bgmUrl.isNotBlank()) "文件名与合成设置" else "自定义文件名")
        }
        if (optionsExpanded) {
            OutlinedTextField(model.fileName, onValueChange = { model.fileName = it.take(120) }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, singleLine = true, label = { Text("文件名（可选）") },
                supportingText = { Text(if (video.isAlbum) "留空使用默认规则，图片自动加序号" else "留空使用默认规则，不需要扩展名") })
            if (video.isAlbum && video.bgmUrl.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("每张图片时长", style = MaterialTheme.typography.labelLarge)
                    Text("${model.imageSeconds} 秒", style = MaterialTheme.typography.labelLarge)
                }
                Slider(value = model.imageSeconds.toFloat(), onValueChange = { model.imageSeconds = it.roundToInt().coerceIn(2, 10) },
                    valueRange = 2f..10f, steps = 7, enabled = !model.busy)
            }
        }
        if (video.isAlbum) {
            Button(onClick = { model.albumMode = AlbumMode.IMAGES; download(false) }, enabled = !model.busy && selectedAvailable,
                modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Glyph(GlyphKind.DOWNLOAD, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("保存 ${video.images.size} 张图片")
            }
            OutlinedButton(onClick = { model.albumMode = AlbumMode.VIDEO; download(false) },
                enabled = !model.busy && selectedAvailable && video.bgmUrl.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("图片 + BGM 合成视频")
            }
            if (selectedAvailable && video.bgmUrl.isNotBlank()) Text("${model.imageSeconds} 秒 / 张 · 视频约 ${model.imageSeconds * video.images.size} 秒",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Button(onClick = { download(false) }, enabled = !model.busy && selectedAvailable, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Glyph(GlyphKind.DOWNLOAD, size = 20.dp); Spacer(Modifier.width(8.dp)); Text(if (model.duplicate != null) "再次保存视频" else "下载视频")
        }
    }
}

@Composable
private fun BatchQueueDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("批量添加链接") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("可以粘贴多条分享文案或链接，按队列顺序处理。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it.take(32_768) }, minLines = 5, maxLines = 9, placeholder = { Text("每行一条链接或分享文案") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            TextButton(onClick = { readClipboard(context)?.let { text = it } }) { Text("从剪贴板粘贴") }
        }
    }, confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("加入队列") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun TaskFeedback(model: SaverViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(model.message, style = MaterialTheme.typography.bodyMedium, color = when (model.stage) {
            TaskStage.FAILED -> MaterialTheme.colorScheme.error; TaskStage.DONE -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        if (model.stage in listOf(TaskStage.DOWNLOADING, TaskStage.SAVING)) {
            if (model.total > 0) {
                val progress = (model.downloaded.toFloat() / model.total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                val composing = model.stage == TaskStage.SAVING && model.total == 100L && model.video?.isAlbum == true && model.albumMode == AlbumMode.VIDEO
                Text(if (composing) "合成进度 ${(progress * 100).toInt()}%" else "${megabytes(model.downloaded)} / ${megabytes(model.total)} MB · ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (model.stage == TaskStage.SAVING) "正在处理，请稍候…" else "已处理 ${megabytes(model.downloaded)} MB", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (model.busy && model.stage != TaskStage.CANCELLING) TextButton(onClick = model::cancel) { Text("取消任务") }
        if (model.stage == TaskStage.DONE) TextButton(onClick = { model.selectedPage = AppPage.HISTORY }) { Text("查看下载记录") }
    }
}
