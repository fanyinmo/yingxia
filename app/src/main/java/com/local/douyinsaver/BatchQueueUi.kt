package com.local.douyinsaver

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun BatchQueueHeader(model: SaverViewModel, onAdd: () -> Unit) {
    var clearConfirmation by remember { mutableStateOf(false) }
    val parsed = model.queue.count { model.queueResults.containsKey(it.key) }
    val waiting = model.queue.count { it.status == QueueStatus.QUEUED }
    val failed = model.queue.count { it.status == QueueStatus.FAILED || it.status == QueueStatus.CANCELLED }
    val idle = !model.busy && !model.queueRunning && !model.batchSaving
    val savable = model.queue.any { task ->
        model.queueResults[task.key]?.let { WatermarkSources.available(it, WatermarkMode.CLEAN) } == true }
    LaunchedEffect(idle, model.queue.isEmpty()) {
        if (!idle || model.queue.isEmpty()) clearConfirmation = false
    }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("批量任务 · ${model.queue.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onAdd, enabled = !model.batchSaving) { Text("添加链接") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(buildString {
                append("$parsed 已解析 · $waiting 待解析")
                if (failed > 0) append(" · $failed 未成功")
            }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { clearConfirmation = true }, enabled = idle && model.queue.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "清空全部任务" }) { Text("清空全部") }
        }
        if (model.queue.any { it.status in listOf(QueueStatus.FAILED, QueueStatus.CANCELLED) && !model.queueResults.containsKey(it.key) }) {
            TextButton(onClick = model::retryFailedQueue, enabled = idle, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("重试未成功")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (model.queueRunning) model.stopQueue() else model.startQueue() },
                enabled = model.queueRunning || (idle && waiting > 0), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                if (model.queueRunning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                else Glyph(GlyphKind.LINK, size = 18.dp)
                Spacer(Modifier.width(7.dp)); Text(if (model.queueRunning) "暂停解析" else "解析全部")
            }
            OutlinedButton(onClick = { model.downloadParsedQueue() }, enabled = idle && savable,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (model.batchSaving) "正在保存…" else "保存已解析") }
        }
        Text("已有文件会自动跳过；批量保存时，图集保存为图片。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (idle) "长按手柄或点上下移调整待解析顺序；左右滑动可移除任务。"
            else "正在处理任务，排序暂时锁定。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val saving = model.batchSaving || (model.selectedTaskKey != null &&
            model.stage in listOf(TaskStage.DOWNLOADING, TaskStage.SAVING, TaskStage.CANCELLING))
        if (saving || (model.selectedTaskKey != null && model.busy && !model.queueRunning)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(model.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.total > 0L) {
                val progress = (model.downloaded.toFloat() / model.total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("保存进度 ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = model::cancel, enabled = model.stage != TaskStage.CANCELLING,
                modifier = Modifier.align(Alignment.End)) {
                Text(if (model.stage == TaskStage.CANCELLING) "正在取消…" else if (saving) "取消保存" else "取消任务")
            }
        }
    }
    if (clearConfirmation && idle && model.queue.isNotEmpty()) AlertDialog(
        onDismissRequest = { clearConfirmation = false },
        title = { Text("清空全部任务？") },
        text = { Text("仅移除全部任务及解析结果，单链接内容、已保存文件和下载记录都会保留。") },
        confirmButton = {
            TextButton(onClick = { clearConfirmation = false; model.clearQueue() }, enabled = idle,
                modifier = Modifier.semantics { contentDescription = "确认清空全部任务" }) { Text("清空全部") }
        },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } })
}

@Composable
internal fun BatchQueueTaskCard(task: QueueTask, index: Int, count: Int, parsed: ParsedVideo?,
                               operationsEnabled: Boolean, canRemove: Boolean,
                               canReorder: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
                               drag: BatchQueueDragState, modifier: Modifier = Modifier,
                               onMove: (Int) -> Unit, onRemove: () -> Unit, onRetry: () -> Unit,
                               onPreview: (ParsedVideo) -> Unit, onSave: (AlbumMode) -> Unit,
                               onHistory: () -> Unit) {
    val selected = remember(parsed) { parsed?.let { runCatching { WatermarkSources.select(it, WatermarkMode.CLEAN) }.getOrNull() } }
    val current = task.status == QueueStatus.PARSING || task.status == QueueStatus.RUNNING
    val readyToSave = selected != null && operationsEnabled
    SwipeRevealQueueTask(task.key, index, task.title, canRemove, modifier, onRemove) {
        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (canReorder) Box(Modifier.size(48.dp).batchQueueDragHandle(drag, task.key, true)
                    .semantics {
                        contentDescription = "拖动排序任务 ${index + 1}"
                        stateDescription = "长按后上下拖动"
                        customActions = buildList {
                            if (canMoveUp) add(CustomAccessibilityAction("上移任务") { onMove(-1); true })
                            if (canMoveDown) add(CustomAccessibilityAction("下移任务") { onMove(1); true })
                        }
                    }, contentAlignment = Alignment.Center) { QueueControlGlyph(QueueControl.DRAG) }
                else if (parsed != null) MediaThumbnail(if (parsed.isAlbum) selected?.images?.firstOrNull()?.url.orEmpty()
                    else parsed.coverUrl, Modifier.size(56.dp).clip(MaterialTheme.shapes.small), description = "任务 ${index + 1} 封面")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("任务 ${index + 1} / $count", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(parsed?.title ?: if (task.title == "待解析作品") "待解析链接" else task.title,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                }
                if (current) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                Text(task.status.label, style = MaterialTheme.typography.labelSmall,
                    color = if (task.status == QueueStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            if (parsed == null) Text(task.source.removePrefix("https://"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text(if (parsed.isAlbum) "图集 · ${parsed.images.size} 张"
                    else "视频 · ${duration(parsed.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected == null) Text("暂未读取到可下载地址，请重新解析。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.message.isNotBlank()) Text(task.message, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == QueueStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (parsed != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPreview(parsed) }, enabled = selected != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "预览任务 ${index + 1}" }) { Text("预览") }
                    Button(onClick = { onSave(AlbumMode.IMAGES) }, enabled = readyToSave,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "保存任务 ${index + 1}" }) {
                        Text(if (task.status == QueueStatus.DONE) "再次保存" else if (parsed.isAlbum) "保存图片" else "保存视频")
                    }
                }
                if (task.status == QueueStatus.DONE) TextButton(onClick = onHistory,
                    modifier = Modifier.align(Alignment.End)) { Text("查看记录") }
                if (parsed.isAlbum) {
                    if (parsed.bgmUrl.isNotBlank()) OutlinedButton(onClick = { onSave(AlbumMode.VIDEO) },
                        enabled = readyToSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("图片 + BGM 合成视频") }
                    else Text("暂未读取到 BGM，仍可保存图片。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canReorder || task.status in listOf(QueueStatus.FAILED, QueueStatus.CANCELLED, QueueStatus.READY, QueueStatus.DONE)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (canReorder) {
                        TextButton(onClick = { onMove(-1) }, enabled = canMoveUp,
                            modifier = Modifier.semantics { contentDescription = "上移任务 ${index + 1}" }) {
                            QueueControlGlyph(QueueControl.UP); Spacer(Modifier.width(4.dp)); Text("上移")
                        }
                        TextButton(onClick = { onMove(1) }, enabled = canMoveDown,
                            modifier = Modifier.semantics { contentDescription = "下移任务 ${index + 1}" }) {
                            QueueControlGlyph(QueueControl.DOWN); Spacer(Modifier.width(4.dp)); Text("下移")
                        }
                    }
                    if (task.status in listOf(QueueStatus.FAILED, QueueStatus.CANCELLED, QueueStatus.READY, QueueStatus.DONE)) TextButton(
                        onClick = onRetry, enabled = operationsEnabled,
                        modifier = Modifier.semantics { contentDescription = "重试任务 ${index + 1}" }) {
                        Text(if (task.status in listOf(QueueStatus.READY, QueueStatus.DONE)) "重新解析" else "重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeRevealQueueTask(key: String, index: Int, title: String, enabled: Boolean,
                                 modifier: Modifier, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val distance = with(LocalDensity.current) { 92.dp.toPx() }
    var offset by remember(key) { mutableFloatStateOf(0f) }
    var dragging by remember(key) { mutableStateOf(false) }
    var confirmation by remember(key) { mutableStateOf(false) }
    val latestRemove by rememberUpdatedState(onRemove)
    val animated by animateFloatAsState(if (dragging) offset else when {
        offset > distance / 2f -> distance; offset < -distance / 2f -> -distance; else -> 0f
    }, label = "queue_delete_reveal")
    LaunchedEffect(enabled) { if (!enabled) { offset = 0f; confirmation = false; dragging = false } }
    Box(modifier.clip(MaterialTheme.shapes.large).background(if (abs(offset) > 0f || abs(animated) > 0f)
        MaterialTheme.colorScheme.errorContainer else androidx.compose.ui.graphics.Color.Transparent)
        .semantics {
            if (enabled) customActions = listOf(CustomAccessibilityAction("显示移除任务按钮") { offset = -distance; true })
        }) {
        if (abs(offset) > distance / 2f) TextButton(onClick = { confirmation = true }, enabled = enabled,
            modifier = Modifier.align(if (offset > 0f) Alignment.CenterStart else Alignment.CenterEnd)
                .width(92.dp).heightIn(min = 48.dp).semantics { contentDescription = "移除任务 ${index + 1}" },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("移除") }
        Box(Modifier.offset { IntOffset((if (dragging) offset else animated).roundToInt(), 0) }
            .then(if (enabled) Modifier.pointerInput(key, distance) {
                detectHorizontalDragGestures(onDragStart = { dragging = true },
                    onDragCancel = { dragging = false; offset = 0f },
                    onDragEnd = { dragging = false; offset = if (abs(offset) > distance / 2f) if (offset > 0f) distance else -distance else 0f },
                    onHorizontalDrag = { change, amount -> change.consume(); offset = (offset + amount).coerceIn(-distance, distance) })
            } else Modifier)) { content() }
    }
    if (confirmation && enabled) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text("移除这条任务？") },
        text = { Text("只移除任务列表中的“${title.take(60)}”，已保存的文件会保留。") },
        confirmButton = { TextButton(onClick = { confirmation = false; latestRemove() }) { Text("移除任务") } },
        dismissButton = { TextButton(onClick = { confirmation = false; offset = 0f }) { Text("取消") } })
}

@Composable
internal fun BatchResultPreview(content: ParsedVideo, onDismiss: () -> Unit) {
    if (content.isAlbum) AlertDialog(onDismissRequest = onDismiss, title = { Text(content.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { AlbumPreview(runCatching { WatermarkSources.select(content, WatermarkMode.CLEAN).images }.getOrDefault(emptyList())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭预览") } })
    else VerifiedVideoPreviewDialog(content, onDismiss)
}

private enum class QueueControl { DRAG, UP, DOWN }

@Composable
private fun QueueControlGlyph(control: QueueControl) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(19.dp)) {
        val stroke = size.minDimension * 0.085f
        if (control == QueueControl.DRAG) {
            for (row in 0..2) for (column in 0..1) drawCircle(color, stroke,
                Offset(size.width * (0.34f + column * 0.32f), size.height * (0.22f + row * 0.28f)))
        } else {
            val tip = if (control == QueueControl.UP) 0.28f else 0.72f
            val tail = if (control == QueueControl.UP) 0.62f else 0.38f
            drawLine(color, Offset(size.width * 0.23f, size.height * tail), Offset(size.width * 0.5f, size.height * tip),
                stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.5f, size.height * tip), Offset(size.width * 0.77f, size.height * tail),
                stroke, StrokeCap.Round)
        }
    }
}
