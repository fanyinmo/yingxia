package com.local.douyinsaver

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.round

@Composable
internal fun AlbumMotionSaveControls(state: AlbumMotionReadState, enabled: Boolean,
                                     onSave: (AlbumMode) -> Unit, onCancel: () -> Unit,
                                     onVerification: () -> Unit, modifier: Modifier = Modifier,
                                     actionDescription: String = "保存动图", selectionKey: String = actionDescription,
                                     content: ParsedVideo? = null, timingValid: Boolean = true) {
    var selectedFormat by rememberSaveable(selectionKey) { mutableStateOf(AlbumMode.IMAGES.name) }
    val selected = runCatching { AlbumMode.valueOf(selectedFormat) }.getOrDefault(AlbumMode.IMAGES)
    val unknown = content?.let(AlbumActionUiPolicy::requiresFormatChoice) == true
    val mode = if (unknown) AlbumActionUiPolicy.selectedDynamicMode(content, selected) ?: AlbumMode.MOTION_VIDEOS
        else AlbumActionUiPolicy.selectedDynamicMode(content, selected) ?: AlbumMode.IMAGES
    val reading = state.phase == AlbumMotionPhase.READING
    val verifying = state.phase == AlbumMotionPhase.NEEDS_VERIFICATION
    val unavailable = state.phase == AlbumMotionPhase.UNAVAILABLE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (unknown) Text("动态片段默认保存为无声动图，也可选择 GIF。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumActionUiPolicy.dynamicModes(content).forEach { format ->
                FilterChip(selected = mode == format,
                    onClick = { selectedFormat = format.name }, enabled = enabled && !reading && !verifying,
                    modifier = Modifier.weight(1f), label = { Text(when (format) {
                        AlbumMode.MOTION_VIDEOS -> "无声动图"
                        AlbumMode.GIF -> "GIF 动图"
                        else -> "保留动态格式"
                    }) })
            }
        }
        val explanation = when (mode) {
            AlbumMode.IMAGES -> "GIF / WebP 保留原格式；视频型动态素材保存为无声 MP4。图集中的静图也会保留。"
            AlbumMode.MOTION_VIDEOS -> "动态片段保存为无声 MP4，保留来源画面尺寸和帧率。"
            AlbumMode.GIF -> "按下方画质选项生成无声 GIF。分享模式减小体积，清晰模式保留更多细节。"
            else -> "选择保留动态素材或生成 GIF。"
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { mode?.let(onSave) }, enabled = enabled && mode != null &&
            (mode != AlbumMode.GIF || timingValid) && !reading && !verifying,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = actionDescription }) {
            if (reading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (reading) "正在读取动态资源…" else buildString {
                if (unavailable) append("重试 · ")
                append(AlbumActionUiPolicy.saveLabel(content, mode))
            })
        }
        val detail = state.detail.ifBlank {
            when (state.phase) {
                AlbumMotionPhase.READING -> "正在读取这套图集的动态资源…"
                AlbumMotionPhase.NEEDS_VERIFICATION -> "抖音网页需要验证，完成后可继续读取。"
                AlbumMotionPhase.UNAVAILABLE -> "暂未读取到动态资源，仍可保存现有图片。"
                AlbumMotionPhase.AVAILABLE -> "已取得动态资源。"
                else -> ""
            }
        }
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (reading || verifying) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            if (verifying) TextButton(onClick = onVerification) { Text("打开抖音验证页面") }
            TextButton(onClick = onCancel) { Text("取消读取") }
        }
    }
}

/** The same actions and timing choices are used by single results and queue cards. */
@Composable
internal fun GalleryDownloadControls(content: ParsedVideo, enabled: Boolean, dynamicEnabled: Boolean,
                                     state: AlbumMotionReadState, onSave: (AlbumMode) -> Unit,
                                     onSaveMotion: (AlbumMode) -> Unit, onCancel: () -> Unit,
                                     onVerification: () -> Unit, selectionKey: String,
                                     itemDurationSeconds: Double? = null, staticSeconds: Double = 3.0,
                                     onItemDuration: (Double?) -> Unit = {}, coverEnabled: Boolean = enabled,
                                     onCheckMotion: () -> Unit = { onSaveMotion(AlbumMode.IMAGES) },
                                     gifExportQuality: GifExportQuality = GifExportQuality.HIGH_QUALITY,
                                     onGifQuality: (GifExportQuality) -> Unit = {}) {
    val dynamic = AlbumActionUiPolicy.hasDynamic(content)
    val canComposeGif = AlbumActionUiPolicy.canComposeGif(content)
    val hasBgm = content.bgmUrl.isNotBlank()
    var timingExpanded by rememberSaveable(selectionKey) { mutableStateOf(false) }
    var timingValid by remember(selectionKey) { mutableStateOf(true) }
    val canConvert = enabled && timingValid
    val pendingMotion = state.phase == AlbumMotionPhase.READING
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pendingMotion && !dynamic) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在自动识别图片与动态内容…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancel) { Text("跳过") }
            }
            Text("完成后自动显示对应保存方式；跳过后可先保存现有图片。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (dynamic) {
            AlbumMotionSaveControls(state, dynamicEnabled, onSaveMotion, onCancel, onVerification,
                actionDescription = AlbumActionUiPolicy.primaryLabel(content), selectionKey = selectionKey, content = content,
                timingValid = timingValid)
            TextButton(onClick = { onSave(AlbumMode.COVERS) }, enabled = coverEnabled,
                modifier = Modifier.align(Alignment.End)) { Text("仅保存静态封面") }
        } else {
            Button(onClick = { onSave(AlbumMode.IMAGES) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Glyph(GlyphKind.DOWNLOAD, size = 18.dp); Spacer(Modifier.width(8.dp))
                Text(AlbumActionUiPolicy.primaryLabel(content))
            }
            if (canComposeGif) OutlinedButton(onClick = { onSave(AlbumMode.GIF) },
                enabled = canConvert, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("图片序列合成 GIF") }
            if (state.phase != AlbumMotionPhase.AVAILABLE)
                AlbumMotionCheckControls(state, dynamicEnabled, onCheckMotion, onCancel, onVerification)
        }
        if (!pendingMotion && hasBgm) OutlinedButton(onClick = { onSave(AlbumMode.VIDEO) }, enabled = canConvert,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("素材 + BGM 合成视频") }
        else if (!pendingMotion) Text("暂未读取到 BGM，保存图片和动态素材不受影响。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!pendingMotion && (hasBgm || canComposeGif)) {
            if (canComposeGif) GifQualityControls(gifExportQuality, enabled, onGifQuality)
            TextButton(onClick = { timingExpanded = !timingExpanded }, enabled = enabled,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(if (timingExpanded) "收起时长设置" else when {
                    hasBgm && canComposeGif -> "设置 GIF / 合成视频时长"
                    hasBgm -> "设置合成视频时长"
                    else -> "设置 GIF 时长"
                })
            }
            val total = AlbumDurationUiPolicy.totalSeconds(content, itemDurationSeconds, staticSeconds)
            val timingSummary = buildString {
                if (dynamic) {
                    append(if (itemDurationSeconds == null) "动态 GIF 使用各自原时长"
                        else "动态 GIF 每项 ${AlbumDurationUiPolicy.seconds(itemDurationSeconds)} 秒")
                    if (hasBgm) append(total?.let { " · 合成视频总时长约 ${AlbumDurationUiPolicy.seconds(it)} 秒" }
                        ?: " · 合成视频时长待读取")
                    else if (total == null) append(" · 动态时长待读取")
                } else {
                    append("静图 ${AlbumDurationUiPolicy.seconds(itemDurationSeconds ?: staticSeconds)} 秒/张")
                    val output = when {
                        hasBgm && canComposeGif -> "图片序列 GIF / 合成视频"
                        hasBgm -> "合成视频"
                        else -> "图片序列 GIF"
                    }
                    total?.let { append(" · $output 总时长约 ${AlbumDurationUiPolicy.seconds(it)} 秒") }
                }
            }
            Text(timingSummary,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (timingExpanded) AlbumDurationControls(selectionKey, itemDurationSeconds, staticSeconds, enabled, onItemDuration) {
                timingValid = it
            }
            else if (!timingValid) Text("请展开时长设置，填入有效秒数后再转换 GIF 或合成视频。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun AlbumMotionCheckControls(state: AlbumMotionReadState, enabled: Boolean, onCheck: () -> Unit,
                                      onCancel: () -> Unit, onVerification: () -> Unit) {
    val pending = state.phase in listOf(AlbumMotionPhase.READING, AlbumMotionPhase.NEEDS_VERIFICATION)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (state.phase == AlbumMotionPhase.READING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        TextButton(onClick = onCheck, enabled = enabled && !pending,
            contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (pending) "正在检查动态资源…" else "重新检查动态资源") }
        Spacer(Modifier.weight(1f))
        if (pending) TextButton(onClick = onCancel) { Text("取消") }
    }
    if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.phase == AlbumMotionPhase.NEEDS_VERIFICATION) TextButton(onClick = onVerification) { Text("打开抖音验证页面") }
}

@Composable
private fun AlbumDurationControls(selectionKey: String, seconds: Double?, staticSeconds: Double,
                                  enabled: Boolean, onValue: (Double?) -> Unit, onValidity: (Boolean) -> Unit) {
    var text by remember(selectionKey, seconds, staticSeconds) { mutableStateOf(AlbumDurationUiPolicy.seconds(seconds ?: staticSeconds)) }
    val parsed = AlbumDurationUiPolicy.parseOverride(text)
    LaunchedEffect(parsed) { onValidity(parsed != null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(text, onValueChange = {
                if (AlbumDurationUiPolicy.acceptsInput(it)) {
                    text = it
                    if (!it.endsWith('.')) AlbumDurationUiPolicy.parseOverride(it)?.let(onValue)
                }
            }, enabled = enabled, modifier = Modifier.weight(1f), singleLine = true, shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("统一播放时长") },
                suffix = { Text("秒") }, isError = parsed == null, supportingText = { Text("0.1–120 秒，精确到 0.1 秒") })
            TextButton(onClick = { text = AlbumDurationUiPolicy.seconds(staticSeconds); onValue(null) },
                enabled = enabled && (seconds != null || parsed == null)) { Text("使用原时长") }
        }
        RoundDurationSlider((seconds ?: staticSeconds).toFloat(), enabled) { onValue(it.toDouble()) }
        Text("仅影响 GIF 转换与合成视频。动态素材缩短时截取开头、延长时循环播放，保存前确认；保留原格式不改变时长。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoundDurationSlider(value: Float, enabled: Boolean, range: ClosedFloatingPointRange<Float> = 0.1f..120f,
                                  onValue: (Float) -> Unit) {
    val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val inactive = MaterialTheme.colorScheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(value = value.coerceIn(range), onValueChange = { onValue((round(it * 10) / 10).coerceIn(range)) },
        valueRange = range, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        thumb = {
            Box(Modifier.width(18.dp).height(48.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).background(accent, CircleShape))
            }
        }, track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val start = if (rtl) size.width else 0f
                val end = if (rtl) 0f else size.width
                drawLine(inactive, Offset(start, size.height / 2), Offset(end, size.height / 2), 4.dp.toPx(), StrokeCap.Round)
                drawLine(accent, Offset(start, size.height / 2), Offset(start + (end - start) * fraction, size.height / 2), 4.dp.toPx(), StrokeCap.Round)
            }
        })
}

@Composable
internal fun DurationAdjustmentDialog(model: SaverViewModel) {
    val adjustments = model.durationAdjustments
    if (adjustments.isEmpty()) return
    AlertDialog(onDismissRequest = model::returnToDurationEditing, title = { Text("设置时长与素材原时长不同") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("以下动态素材会按设置时长处理，播放速度保持不变。")
                adjustments.forEach { Text(AlbumDurationUiPolicy.adjustmentDescription(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }, confirmButton = { TextButton(onClick = model::confirmDurationAdjustment) { Text("继续") } },
        dismissButton = { TextButton(onClick = model::returnToDurationEditing) { Text("返回修改") } })
}

@Composable
internal fun AlbumMotionVerificationDialog(model: SaverViewModel) {
    Dialog(onDismissRequest = model::cancelAlbumMotion,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("抖音官方页面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = model::cancelAlbumMotion) { Text("关闭") }
                }
                Text("请手动完成页面提示的验证，再点继续读取。",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AndroidView(factory = { context ->
                    FrameLayout(context).apply {
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        model.attachAlbumMotionVerificationHost(this)
                    }
                }, modifier = Modifier.fillMaxWidth().weight(1f), onReset = null,
                    onRelease = model::detachAlbumMotionVerificationHost)
                Button(onClick = model::continueAlbumMotionVerification,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) {
                    Text("继续读取")
                }
            }
        }
    }
}
