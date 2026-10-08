package com.local.douyinsaver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
        item { SectionCard {
            StaticImageDurationSettingsControls(model.staticImageSeconds, !model.busy) { model.staticImageSeconds = it }
        } }
        item { SectionCard { PreviewSettingsControls() } }
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

@Composable
internal fun StaticImageDurationSettingsControls(seconds: Double, enabled: Boolean = true, onValue: (Double) -> Unit) {
    var text by rememberSaveable(seconds) { mutableStateOf(AlbumDurationUiPolicy.seconds(seconds)) }
    val parsed = text.takeUnless { it.endsWith('.') }?.let(AlbumDurationUiPolicy::parseOverride)
    Text("静图默认播放时长", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(value = text, onValueChange = { input ->
        if (AlbumDurationUiPolicy.acceptsInput(input)) {
            text = input
            if (!input.endsWith('.')) AlbumDurationUiPolicy.parseOverride(input)?.let(onValue)
        }
    }, enabled = enabled, singleLine = true, shape = RoundedCornerShape(12.dp),
        label = { Text("每张静图默认时长") }, suffix = { Text("秒") }, isError = parsed == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "每张静图默认时长" },
        supportingText = { Text(if (parsed != null) "0.1–120 秒，精确到 0.1 秒，自动保存"
            else "请输入 0.1–120 之间的秒数，精确到 0.1 秒；未保存，仍使用 ${AlbumDurationUiPolicy.seconds(seconds)} 秒") })
    Box(Modifier.fillMaxWidth().semantics { contentDescription = "静图默认播放时长滑块" }) {
        RoundDurationSlider(seconds.toFloat(), enabled) { value ->
            text = AlbumDurationUiPolicy.seconds(value.toDouble())
            onValue(value.toDouble())
        }
    }
    Text("静图自动模式的图片序列 GIF 和 BGM 合成视频使用此时长；动态自动模式仍使用各自原时长。图集卡片的统一播放时长优先。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
