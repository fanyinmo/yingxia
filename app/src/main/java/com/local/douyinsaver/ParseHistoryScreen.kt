package com.local.douyinsaver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ParseHistoryScreen(model: SaverViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val records = model.parseHistory.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.id.contains(query.trim()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("解析记录", "最近 ${model.parseHistory.size} 个作品 · 重新解析获取最新资源，不自动下载") }
        item { OutlinedTextField(query, { query = it }, label = { Text("搜索标题或作品编号") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (model.busy || model.queueRunning || model.batchSaving) item { OngoingTask(model) }
        if (records.isEmpty()) item { SectionCard {
            Text(if (model.parseHistory.isEmpty()) "还没有解析记录" else "没有符合条件的作品")
            Text("成功解析后自动记录，清空任务不会移除这里的记录。", style = MaterialTheme.typography.bodySmall)
        } }
        items(records, key = { it.id }) { record -> SectionCard {
            Text(record.title.ifBlank { "作品 ${record.id}" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text("${if (record.isAlbum) "图集" else "视频"} · ${savedTime(record.parsedAt)}", style = MaterialTheme.typography.bodySmall)
            Text(record.id, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { model.removeParseRecord(record.id) }) { Text("移除记录") }
                OutlinedButton(onClick = { model.reparseRecord(record) }, enabled = !model.busy && !model.queueRunning && !model.batchSaving) { Text("重新解析") }
            }
        } }
    }
}
