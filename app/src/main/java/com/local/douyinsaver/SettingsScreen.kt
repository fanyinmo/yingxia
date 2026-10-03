package com.local.douyinsaver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(model: SaverViewModel, appearance: AppearanceStore, chooseFolder: () -> Unit, requestNotification: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("设置", "保存习惯与界面外观，都由你决定") }
        if (model.busy) item { OngoingTask(model) }
        item { SectionCard {
            Text("下载位置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(model.downloadLocationLabel, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = chooseFolder, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("选择下载文件夹") }
            if (model.downloadFolder != null) TextButton(onClick = model::resetDownloadFolder, enabled = !model.busy) { Text("恢复默认相册目录") }
            Text("修改位置只影响之后下载的文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("默认文件命名", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NamingRule.entries.forEach { rule -> FilterChip(model.namingRule == rule, { model.namingRule = rule }, enabled = !model.busy, label = { Text(rule.label) }) }
            }
            Text("首页可以临时填写文件名；图集自动添加图片序号。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { SectionCard { AppearanceControls(appearance) } }
        item { SectionCard {
            Text("下载通知", style = MaterialTheme.typography.titleMedium)
            Text("开启通知后可以查看任务进度；拒绝通知权限仍可以在 App 内下载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = requestNotification) { Text("开启通知") }
        } }
        item { SectionCard {
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("图集合成需要作品提供可用 BGM。网页未公开提供的内容可能无法解析。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.diagnostics.isNotBlank()) OutlinedButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("解析诊断", model.diagnostics))
                Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制问题诊断") }
        } }
    }
}
