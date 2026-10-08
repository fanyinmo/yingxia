package com.local.douyinsaver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeScreen(model: SaverViewModel, download: (Boolean) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var batchDialog by remember { mutableStateOf(false) }
    var batchPreview by remember { mutableStateOf<Pair<String, ParsedVideo>?>(null) }
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
            if (singleTask && model.video == null && model.browsingId != null && model.stage == TaskStage.FAILED) {
                Text("若这是图文作品，可尝试读取官方桌面图集的动态资源。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AlbumMotionCheckControls(model.albumMotionState(), enabled = queueIdle,
                    onCheck = { model.checkFailedShareAlbumMotion() }, onCancel = model::cancelAlbumMotion,
                    onVerification = model::openAlbumMotionVerification)
            }
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
                    onRetry = { model.retryTask(task.key) }, onPreview = { batchPreview = task.key to it },
                    onSave = { model.downloadTask(task.key, mode = it) }, onHistory = { model.selectedPage = AppPage.HISTORY },
                    gifStartSeconds = model.gifStartSeconds, gifDurationSeconds = model.gifDurationSeconds,
                    itemDurationSeconds = model.itemDurationForTask(task.key), staticSeconds = model.staticImageSeconds,
                    onItemDuration = { model.setItemDurationForTask(task.key, it) },
                    onGifSettings = { start, length -> model.gifStartSeconds = start; model.gifDurationSeconds = length },
                    albumMotionState = model.albumMotionState(task.key), onSaveMotion = { model.saveAlbumMotion(task.key, mode = it) },
                    onCheckMotion = { model.checkAlbumMotion(task.key) },
                    gifExportQuality = model.gifExportQuality, onGifQuality = { model.gifExportQuality = it },
                    onCancelMotion = model::cancelAlbumMotion, onVerifyMotion = model::openAlbumMotionVerification)
            }
        }
    }
    if (batchDialog) BatchQueueDialog(onDismiss = { batchDialog = false }, onAdd = { text -> model.enqueueInput(text); batchDialog = false })
    if (previewOpen) model.video?.let {
        VerifiedVideoPreviewDialog(it) { previewOpen = false }
    }
    batchPreview?.let { (key, content) ->
        BatchResultPreview(content, onDismiss = { batchPreview = null }, saveEnabled = queueIdle,
            onSaveItem = { index, mode ->
                batchPreview = null
                if (mode != AlbumMode.COVERS && albumPreviewKind(content.images[index]) != AlbumAssetKind.STATIC)
                    model.saveAlbumMotion(key, mode = mode, selectedImageIndices = listOf(index))
                else model.downloadTask(key, mode = mode, selectedImageIndices = listOf(index))
            })
    }
}

@Composable
private fun DownloadResult(model: SaverViewModel, video: ParsedVideo, download: (Boolean) -> Unit, onPreview: () -> Unit) {
    val context = LocalContext.current
    var optionsExpanded by remember(video.id) { mutableStateOf(false) }
    val selectedAvailable = WatermarkSources.availableForDownload(video, WatermarkMode.CLEAN)
    val selectedContent = model.selectedContent
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(if (video.isAlbum) selectedContent?.images?.firstOrNull()?.url.orEmpty()
                else video.coverUrl,
                Modifier.size(76.dp).clip(MaterialTheme.shapes.small), description = "作品封面")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (video.isAlbum) albumContentLabel(video.images) else "视频已就绪",
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
            AlbumPreview(selectedContent?.images ?: video.images, video.title, video.id, saveEnabled = !model.busy,
                onSaveItem = { index, mode ->
                    if (mode != AlbumMode.COVERS && albumPreviewKind(video.images[index]) != AlbumAssetKind.STATIC)
                        model.saveAlbumMotion(mode = mode, selectedImageIndices = listOf(index))
                    else {
                        model.albumMode = mode
                        model.download(selectedImageIndices = listOf(index))
                    }
                })

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
            Text(if (optionsExpanded) "收起文件名" else "自定义文件名")
        }
        if (optionsExpanded) {
            OutlinedTextField(model.fileName, onValueChange = { model.fileName = it.take(120) }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, singleLine = true, label = { Text("文件名（可选）") },
                supportingText = { Text(if (video.isAlbum) "留空使用默认规则，图片自动加序号" else "留空使用默认规则，不需要扩展名") })
        }
        if (video.isAlbum) {
            GalleryDownloadControls(selectedContent ?: video, !model.busy && selectedAvailable,
                !model.busy && !model.queueRunning && !model.batchSaving, model.albumMotionState(model.selectedTaskKey),
                onSave = { model.albumMode = it; download(false) },
                onSaveMotion = { model.saveAlbumMotion(model.selectedTaskKey, mode = it) },
                onCancel = model::cancelAlbumMotion, onVerification = model::openAlbumMotionVerification,
                selectionKey = video.id, itemDurationSeconds = model.itemDurationSeconds, staticSeconds = model.staticImageSeconds,
                onItemDuration = { model.itemDurationSeconds = it }, coverEnabled = !model.busy && AlbumActionUiPolicy.coversAvailable(video),
                onCheckMotion = { model.checkAlbumMotion() },
                gifExportQuality = model.gifExportQuality, onGifQuality = { model.gifExportQuality = it })
        } else Button(onClick = { model.albumMode = AlbumMode.IMAGES; download(false) }, enabled = !model.busy && selectedAvailable, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Glyph(GlyphKind.DOWNLOAD, size = 20.dp); Spacer(Modifier.width(8.dp)); Text(if (model.duplicate != null) "再次保存视频" else "下载视频")
        }
        if (!video.isAlbum && GifClipUiPolicy.canOffer(video)) GifDownloadControls(selectedContent ?: video, !model.busy && selectedAvailable,
            model.gifStartSeconds, model.gifDurationSeconds,
            quality = model.gifExportQuality, onQuality = { model.gifExportQuality = it }, onSave = { start, length ->
                model.gifStartSeconds = start; model.gifDurationSeconds = length
                model.downloadGif()
            })
    }
}

/** Video clipping uses the actual source range; gallery sequencing has its own actions. */
internal object GifClipUiPolicy {
    fun canOffer(content: ParsedVideo): Boolean = !content.isAlbum || content.images.any { it.motion != null }

    fun sourceComplete(content: ParsedVideo): Boolean = !content.isAlbum ||
        (content.images.any { !it.motion?.url.isNullOrBlank() } &&
            content.images.none { (it.kind in listOf(AlbumAssetKind.LIVE, AlbumAssetKind.DYNAMIC) || it.motion != null) && it.motion?.url.isNullOrBlank() })

    fun knownDuration(content: ParsedVideo): Float? {
        val values = if (content.isAlbum) content.images.mapNotNull { it.motion?.durationSeconds }
            else listOf(content.durationSeconds)
        return values.filter { it.isFinite() && it > 0.0 }.minOrNull()?.toFloat()?.takeIf { it.isFinite() }
    }

    fun defaults(content: ParsedVideo, start: Float, length: Float): Pair<Float, Float> {
        val total = knownDuration(content)
        val boundedStart = (start.takeIf { it.isFinite() } ?: 0f).coerceAtLeast(0f)
            .let { if (total != null) it.coerceAtMost((total - 0.1f).coerceAtLeast(0f)) else it }
        val boundedLength = (length.takeIf { it.isFinite() } ?: 6f).coerceAtLeast(0.1f)
            .let { if (total != null && total >= 0.1f) it.coerceAtMost(total - boundedStart).coerceAtLeast(0.1f) else it }
        return boundedStart to boundedLength
    }

    fun problem(content: ParsedVideo, start: Float?, length: Float?): String? {
        if (!sourceComplete(content)) return "动态片段尚未读取完整，请重新解析。"
        val total = knownDuration(content)
        if (total != null && total < 0.1f) return "视频不足 0.1 秒，暂不支持保存 GIF。"
        if (start == null || length == null || !start.isFinite() || !length.isFinite()) return "请输入有效的秒数。"
        if (start < 0f) return "起始时间不能小于 0 秒。"
        if (length < 0.1f) return "截取时长至少为 0.1 秒。"
        if (!(start + length).isFinite()) return "截取范围无效，请调整秒数。"
        if (total != null && start + length > total + 0.001f) return "截取范围超出视频时长，请缩短时长或提前起点。"
        return null
    }

    fun remaining(content: ParsedVideo, start: Float): Float? = knownDuration(content)
        ?.minus(start)?.takeIf { start.isFinite() && start >= 0f && it >= 0.09999f }?.coerceAtLeast(0.1f)

    fun seconds(value: Float): String = java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}

@Composable
internal fun GifDownloadControls(content: ParsedVideo, enabled: Boolean, startSeconds: Float = 0f,
                                 durationSeconds: Float = 6f, quality: GifExportQuality = GifExportQuality.HIGH_QUALITY,
                                 onQuality: (GifExportQuality) -> Unit = {}, onSave: (Float, Float) -> Unit) {
    if (content.isAlbum) return
    var expanded by remember(content.id) { mutableStateOf(false) }
    val defaults = remember(content, startSeconds, durationSeconds) { GifClipUiPolicy.defaults(content, startSeconds, durationSeconds) }
    var startText by remember(content.id, defaults) { mutableStateOf(AlbumDurationUiPolicy.seconds(defaults.first.toDouble())) }
    var lengthText by remember(content.id, defaults) { mutableStateOf(AlbumDurationUiPolicy.seconds(defaults.second.toDouble())) }
    var entireRemaining by remember(content.id, defaults) { mutableStateOf(
        GifClipUiPolicy.remaining(content, defaults.first)?.let { kotlin.math.abs(it - defaults.second) < 0.001f } == true) }
    val start = startText.toFloatOrNull()
    val length = if (entireRemaining && start != null) GifClipUiPolicy.remaining(content, start) else lengthText.toFloatOrNull()
    val problem = GifClipUiPolicy.problem(content, start, length)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GifQualityControls(quality, enabled, onQuality)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (problem == null) onSave(checkNotNull(start), checkNotNull(length)) },
                enabled = enabled && problem == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("保存 GIF")
            }
            TextButton(onClick = { expanded = !expanded }, enabled = enabled) { Text(if (expanded) "收起设置" else "截取设置") }
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = entireRemaining && start == 0f, onClick = {
                    startText = "0"; entireRemaining = true
                }, enabled = enabled && GifClipUiPolicy.knownDuration(content) != null, label = { Text("完整视频") })
                FilterChip(selected = entireRemaining && start != 0f, onClick = { entireRemaining = true },
                    enabled = enabled && start?.let { GifClipUiPolicy.remaining(content, it) } != null, label = { Text("从起点到结尾") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(startText, { if (AlbumDurationUiPolicy.acceptsInput(it)) startText = it }, singleLine = true, enabled = enabled,
                    label = { Text("起始") }, suffix = { Text("秒") }, shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(if (entireRemaining) length?.let { AlbumDurationUiPolicy.seconds(it.toDouble()) }.orEmpty() else lengthText,
                    { if (AlbumDurationUiPolicy.acceptsInput(it)) { entireRemaining = false; lengthText = it } }, singleLine = true, enabled = enabled,
                    label = { Text("时长") }, suffix = { Text("秒") }, shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            val remaining = start?.let { GifClipUiPolicy.remaining(content, it) }
            val sliderEnd = remaining?.let { kotlin.math.floor(it * 10f) / 10f }
            if (sliderEnd != null && sliderEnd > 0.1f) RoundDurationSlider(length ?: 0.1f, enabled, 0.1f..sliderEnd) {
                entireRemaining = false; lengthText = AlbumDurationUiPolicy.seconds(it.toDouble())
            }
        }
        Text(if (problem != null) problem else "从 $startText 秒开始 · ${length?.let { AlbumDurationUiPolicy.seconds(it.toDouble()) }} 秒 · 无声音",
            style = MaterialTheme.typography.bodySmall,
            color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text("精确到 0.1 秒 · 最长边 ${quality.maximumEdge} 像素 · 最高 ${quality.maximumFramesPerSecond} 帧/秒 · ${quality.maximumColors} 色",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (GifClipUiPolicy.knownDuration(content) == null) Text("视频时长将在读取后确认，目前可指定截取范围。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text("视频全长 ${AlbumDurationUiPolicy.seconds(checkNotNull(GifClipUiPolicy.knownDuration(content)).toDouble())} 秒；GIF 最长可覆盖完整视频，从中间开始则最长到视频结尾。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun GifQualityControls(quality: GifExportQuality, enabled: Boolean, onQuality: (GifExportQuality) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GifExportQuality.entries.forEach { option ->
            FilterChip(selected = quality == option, onClick = { onQuality(option) }, enabled = enabled,
                modifier = Modifier.weight(1f), label = { Text(if (option == GifExportQuality.SHARE) "分享用 GIF" else "清晰 GIF") })
        }
    }
    Text(if (quality == GifExportQuality.SHARE) "降低尺寸、帧率与颜色以减少体积，保留所选完整时长。较长片段建议保存 MP4；聊天入口的播放支持可能不同。"
        else "保留更多画面细节，体积会明显大于 MP4；GIF 最多 256 色，无法等同原视频画质。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                val gif = model.stage == TaskStage.SAVING && model.albumMode == AlbumMode.GIF
                val composing = model.stage == TaskStage.SAVING && model.total == 100L && model.video?.isAlbum == true && model.albumMode == AlbumMode.VIDEO
                Text(if (gif) "GIF 生成进度 ${(progress * 100).toInt()}%" else if (composing) "合成进度 ${(progress * 100).toInt()}%" else "${megabytes(model.downloaded)} / ${megabytes(model.total)} MB · ${(progress * 100).toInt()}%",
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
