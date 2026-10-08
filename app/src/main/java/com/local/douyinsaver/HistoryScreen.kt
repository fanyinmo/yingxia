package com.local.douyinsaver

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryScreen(model: SaverViewModel) {
    val context = LocalContext.current
    val owner = LocalActivity.current as? LifecycleOwner
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmation by remember { mutableStateOf<Boolean?>(null) }
    var album by remember { mutableStateOf<SavedVideo?>(null) }
    DisposableEffect(owner) {
        model.refreshHistory()
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshHistory() }
        owner?.lifecycle?.addObserver(listener)
        onDispose { owner?.lifecycle?.removeObserver(listener) }
    }
    val filtered = remember(model.history, query, sort) {
        val list = model.history.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.fileName.contains(query.trim(), true) || it.id.contains(query.trim()) }
        when (sort) { 1 -> list.sortedBy { it.savedAt }; 2 -> list.sortedBy { it.title }; 3 -> list.sortedByDescending { it.bytes }; else -> list.sortedByDescending { it.savedAt } }
    }
    LaunchedEffect(model.history) { selected = selected.intersect(model.history.map { it.uri }.toSet()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("下载记录", "共 ${model.history.size} 个作品 · 文件与记录可以分别管理") }
        if (model.busy) item { OngoingTask(model) }
        item { SectionCard {
            OutlinedTextField(query, { query = it.take(200) }, label = { Text("搜索标题、文件名或作品编号") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("最新", "最早", "标题", "大小").forEachIndexed { index, label -> FilterChip(sort == index, { sort = index }, label = { Text(label) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { model.refreshHistory() }, enabled = !model.busy) { Text("刷新") }
                TextButton(onClick = { selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "完成选择" else "批量管理") }
            }
            if (selecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filtered.isNotEmpty() && filtered.all { it.uri in selected }, onCheckedChange = { checked ->
                        selected = if (checked) selected + filtered.map { it.uri } else selected - filtered.map { it.uri }.toSet()
                    })
                    Text("已选 ${selected.size} 项", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { confirmation = false }, enabled = selected.isNotEmpty() && !model.busy) { Text("移除记录") }
                    OutlinedButton(onClick = { confirmation = true }, enabled = selected.isNotEmpty() && !model.busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除文件") }
                }
            }
            if (model.stage == TaskStage.FAILED) Text(model.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } }
        if (filtered.isEmpty()) item { SectionCard {
            Text(if (model.history.isEmpty()) "还没有下载记录" else "没有符合条件的作品", style = MaterialTheme.typography.titleMedium)
            Text(if (model.history.isEmpty()) "回到首页，粘贴链接开始下载。" else "试试其他标题或作品编号。", style = MaterialTheme.typography.bodySmall)
        } }
        items(filtered, key = { it.uri }) { saved -> SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(saved.uri in selected, onCheckedChange = { checked -> selected = if (checked) selected + saved.uri else selected - saved.uri })
                val thumbnailSource = saved.coverUri.ifBlank { saved.uri }
                MediaThumbnail(thumbnailSource, Modifier.size(66.dp).clip(MaterialTheme.shapes.small),
                    video = thumbnailSource in saved.uris && saved.mimeTypeFor(thumbnailSource).startsWith("video/"),
                    description = "已保存作品缩略图")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(saved.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${megabytes(saved.bytes)} MB · ${savedTime(saved.savedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(savedContentLabel(saved),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (saved.fileName.isNotBlank()) Text(saved.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(saved.locationLabel, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { selecting = true; selected = selected + saved.uri }) { Text("管理") }
                OutlinedButton(onClick = {
                    if (savedAlbumImages(saved).isNotEmpty()) album = saved else openSaved(context, saved)
                }) { Text(if (savedAlbumImages(saved).any { it.kind != AlbumAssetKind.STATIC }) "查看图集"
                    else if (savedAlbumImages(saved).isNotEmpty()) "查看图片" else "播放视频") }
            }
        } }
    }
    confirmation?.let { deleteFiles -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(if (deleteFiles) "删除 ${selected.size} 项的文件？" else "移除 ${selected.size} 条记录？") },
        text = { Text(if (deleteFiles) "将删除所选作品对应的本地文件和记录。图集会一并删除原图、动图和动态片段，此操作无法撤销。"
            else "只从 App 中移除记录，本地文件会继续保留在原来的文件夹中。") },
        confirmButton = { TextButton(onClick = { model.manageHistory(selected, deleteFiles); selected = emptySet(); confirmation = null },
            colors = ButtonDefaults.textButtonColors(contentColor = if (deleteFiles) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
            Text(if (deleteFiles) "确认删除文件" else "移除记录") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
    album?.let { saved -> AlertDialog(onDismissRequest = { album = null }, title = { Text("已保存的图集") },
        text = { AlbumPreview(savedAlbumImages(saved), saved.title, saved.id) },
        confirmButton = { TextButton(onClick = { album = null }) { Text("关闭") } }) }
}
