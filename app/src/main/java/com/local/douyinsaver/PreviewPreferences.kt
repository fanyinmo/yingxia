package com.local.douyinsaver

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class PreviewSeekOptions(
    val backwardSeconds: Int = PreviewPlaybackPolicy.DEFAULT_SEEK_SECONDS,
    val forwardSeconds: Int = PreviewPlaybackPolicy.DEFAULT_SEEK_SECONDS,
)

/** Preview preferences stay separate from downloads and can be isolated by runtime tests. */
internal class PreviewPreferences(context: Context, namespace: String = "") {
    private val preferences = context.applicationContext.getSharedPreferences(namespace + "preview_options", Context.MODE_PRIVATE)

    fun read() = PreviewSeekOptions(
        PreviewPlaybackPolicy.seekSeconds(preferences.getInt("backward_seconds", PreviewPlaybackPolicy.DEFAULT_SEEK_SECONDS)),
        PreviewPlaybackPolicy.seekSeconds(preferences.getInt("forward_seconds", PreviewPlaybackPolicy.DEFAULT_SEEK_SECONDS)),
    )

    fun updateBackwardSeconds(value: Int) {
        preferences.edit().putInt("backward_seconds", PreviewPlaybackPolicy.seekSeconds(value)).apply()
    }

    fun updateForwardSeconds(value: Int) {
        preferences.edit().putInt("forward_seconds", PreviewPlaybackPolicy.seekSeconds(value)).apply()
    }
}

@Composable
internal fun PreviewSettingsControls(preferences: PreviewPreferences? = null) {
    val context = LocalContext.current
    val store = preferences ?: remember(context) { PreviewPreferences(context) }
    val initial = remember(store) { store.read() }
    var backward by rememberSaveable(store) { mutableStateOf(initial.backwardSeconds.toString()) }
    var forward by rememberSaveable(store) { mutableStateOf(initial.forwardSeconds.toString()) }
    fun valid(value: String) = value.toIntOrNull()?.let { it in PreviewPlaybackPolicy.MIN_SEEK_SECONDS..PreviewPlaybackPolicy.MAX_SEEK_SECONDS } == true
    Text("预览控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("快捷设置同时调整两侧，输入框可分别设置。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PreviewSeekSetting("后退", backward, valid(backward), Modifier.weight(1f)) { value ->
            backward = value
            if (valid(value)) store.updateBackwardSeconds(value.toInt())
        }
        PreviewSeekSetting("前进", forward, valid(forward), Modifier.weight(1f)) { value ->
            forward = value
            if (valid(value)) store.updateForwardSeconds(value.toInt())
        }
    }
    Text(if (valid(backward) && valid(forward)) "可设置 1–120 秒，自动保存" else "请输入 1–120 之间的秒数",
        style = MaterialTheme.typography.bodySmall,
        color = if (valid(backward) && valid(forward)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    val shortcuts = listOf(5, 10, 15, 30, 60)
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)
    val labelWidthPx = shortcuts.maxOf { measure.measure(AnnotatedString("${it}秒"), style = labelStyle).size.width }
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { collectionInfo = CollectionInfo(1, shortcuts.size) }) {
        // Fit equally sized chips when possible. Larger text gets real extra width and a scrollable row.
        // Account for each actual rounded pixel inset; summing dp first can leave the last border clipped.
        val minimumRowWidth = with(density) {
            val chipWidthPx = maxOf(48.dp.roundToPx(), labelWidthPx + 16.dp.roundToPx() * 2)
            (chipWidthPx * shortcuts.size + 4.dp.roundToPx() * 2 + 2.dp.roundToPx() * (shortcuts.size - 1)).toDp()
        }
        val rowWidth = maxOf(maxWidth, minimumRowWidth)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(rowWidth)
            .padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            shortcuts.forEach { value ->
                FilterChip(selected = backward.toIntOrNull() == value && forward.toIntOrNull() == value,
                    onClick = { backward = value.toString(); forward = value.toString()
                        store.updateBackwardSeconds(value); store.updateForwardSeconds(value) },
                    label = { Text("${value}秒", style = labelStyle, maxLines = 1, softWrap = false) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { contentDescription = "前后各${value}秒" })
            }
        }
    }
}

@Composable
private fun PreviewSeekSetting(direction: String, value: String, valid: Boolean, modifier: Modifier, onInput: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { input ->
        if (input.length <= 3 && input.all { it.isDigit() }) onInput(input)
    }, label = { Text(direction) }, suffix = { Text("秒") }, singleLine = true, shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
        modifier = modifier.semantics { contentDescription = "预览${direction}秒数" })
}
