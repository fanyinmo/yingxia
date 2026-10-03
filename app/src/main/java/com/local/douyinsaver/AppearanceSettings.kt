package com.local.douyinsaver

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceControls(store: AppearanceStore) {
    val options = store.options
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importing = true; error = ""
            try { store.importBackground(uri) }
            catch (_: Exception) { error = "这张图片暂时无法读取，请换一张图片重试" }
            finally { importing = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("所有调整即时预览，并在下次打开时保留。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("明暗模式", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(selected = options.mode == mode, onClick = { store.update(options.copy(mode = mode)) }, label = { Text(mode.label) })
            }
        }
        if (options.mode == ThemeMode.DYNAMIC) Text(
            if (Build.VERSION.SDK_INT >= 31) "颜色取自手机的系统配色；切换其他模式可使用下面的自选主色。" else "系统取色需要 Android 12；当前显示所选主题色。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("主题色", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePalette.entries.forEach { palette ->
                FilterChip(selected = options.palette == palette, onClick = { store.update(options.copy(palette = palette)) },
                    label = { Text(palette.label) }, leadingIcon = {
                        val value = if (palette == ThemePalette.CUSTOM) options.customHex else palette.hex
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF000000L or value.drop(1).toLong(16))))
                    })
            }
        }
        if (options.palette == ThemePalette.CUSTOM) {
            Text("会根据明暗模式调整显示色，让文字和按钮保持清晰。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CustomColorPicker(options.customHex, onColorChanged = { hex ->
                store.update(store.options.copy(customHex = hex))
            })
        }
        HorizontalDivider()
        Text("图片背景", style = MaterialTheme.typography.labelLarge)
        Text("图片贯穿首页、下载记录和设置；整体虚化与柔和色调让各板块融入同一张背景。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !importing) {
                if (importing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                Text(if (options.backgroundRevision > 0) "更换图片" else "选择图片")
            }
            if (options.backgroundRevision > 0) TextButton(onClick = store::removeBackground, enabled = !importing) { Text("移除背景") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (options.backgroundRevision > 0) {
            if (store.migrationNotice) {
                Text("已将旧版未调整的默认背景改为柔和融合效果，原背景图片和裁切方式已保留。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = store::restorePreviousPanelStyle) { Text("恢复更新前板块") }
                    TextButton(onClick = store::dismissMigrationNotice) { Text("知道了") }
                }
            }
            OutlinedButton(onClick = store::applySoftBlend) { Text("应用柔和融合效果") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackgroundFit.entries.forEach { fit -> FilterChip(options.fit == fit, { store.update(options.copy(fit = fit)) }, label = { Text(fit.label) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackgroundPosition.entries.forEach { position -> FilterChip(options.position == position,
                    { store.update(options.copy(position = position)) }, label = { Text(position.label) }) }
            }
            AppearanceSlider("整页背景模糊", options.blur, 0f..24f, "${options.blur.toInt()}") { store.update(options.copy(blur = it)) }
            AppearanceSlider("背景暗度", options.darkness, 0f..0.8f, "${(options.darkness * 100).toInt()}%") { store.update(options.copy(darkness = it)) }
            AppearanceSlider("板块背景不透明度", options.cardOpacity, 0f..1f, "${(options.cardOpacity * 100).toInt()}%") { store.update(options.copy(cardOpacity = it)) }
            Text("0% 完全透明，100% 为实色底。只调整板块底色，文字与按钮保持显示。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("文字保护", style = MaterialTheme.typography.labelLarge)
                    Text("整页统一的主题色调", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = options.textProtection > 0f, onCheckedChange = { store.update(options.copy(textProtection = if (it) 0.64f else 0f)) })
            }
            if (options.textProtection > 0f) AppearanceSlider("文字保护强度", options.textProtection, 0f..0.85f,
                "${(options.textProtection * 100).toInt()}%") { store.update(options.copy(textProtection = it)) }
            Text("与板块不透明度独立。降低或关闭保护，图片会更明显；强对比图片可能影响阅读。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (store.hasPreviousPanelStyle && !store.migrationNotice) TextButton(onClick = store::restorePreviousPanelStyle) { Text("恢复更新前板块样式") }
        }
        Box(Modifier.fillMaxWidth().height(144.dp).clip(MaterialTheme.shapes.medium)) {
            AppearanceBackdrop(store, Modifier.fillMaxSize())
            Card(Modifier.align(Alignment.Center).padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = appearancePanelColor(), contentColor = MaterialTheme.colorScheme.onSurface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("效果预览", fontWeight = FontWeight.SemiBold); Text("你的下载空间", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {}, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("主色") }
                }
            }
        }
        TextButton(onClick = store::reset, enabled = !importing) { Text(if (options.backgroundRevision > 0) "恢复默认外观（保留背景图片）" else "恢复默认外观") }
    }
}

@Composable
private fun AppearanceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onValue: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall); Text(display, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value, onValueChange = onValue, valueRange = range)
    }
}
