package com.local.douyinsaver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val pickerColorSaver = listSaver<PickerColor, Float>(
    save = { listOf(it.hue, it.saturation, it.value) },
    restore = { PickerColor(it[0], it[1], it[2]).normalized() },
)

private val commonThemeColors = listOf(
    "暖红" to "#D05B4B", "琥珀" to "#C07822", "草绿" to "#43834A", "湖青" to "#007D8C",
    "海蓝" to "#3B65BB", "紫藤" to "#8B54A2", "玫瑰" to "#C75D91", "岩灰" to "#555D66",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomColorPicker(colorHex: String, onColorChanged: (String) -> Unit, modifier: Modifier = Modifier) {
    var color by rememberSaveable(stateSaver = pickerColorSaver) { mutableStateOf(PickerColor.fromHex(colorHex)) }
    var lastKnownHex by rememberSaveable { mutableStateOf(color.toHex()) }
    val currentCallback by rememberUpdatedState(onColorChanged)

    LaunchedEffect(colorHex) {
        val incoming = AppearanceOptions.normalizeHex(colorHex) ?: AppearanceOptions.DEFAULT_CUSTOM_HEX
        // Keep full HSV precision for our own updates. Round-tripping RGB would discard hue at white/black.
        if (incoming != lastKnownHex) color = PickerColor.fromHex(incoming, color)
        lastKnownHex = incoming
    }
    val commit: (PickerColor) -> Unit = { next ->
        color = next.normalized()
        lastKnownHex = color.toHex()
        currentCallback(lastKnownHex)
    }
    val currentColor by rememberUpdatedState(color)
    val commitColor by rememberUpdatedState(commit)
    val displayColor = Color(color.toArgb())
    val saturationPercent = (color.saturation * 100).roundToInt()
    val brightnessPercent = (color.value * 100).roundToInt()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(displayColor)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .semantics {
                    contentDescription = "当前主题色"
                    stateDescription = "${hueDescription(color.hue)}，饱和度 $saturationPercent%，亮度 $brightnessPercent%"
                })
            Column {
                Text("当前主题色", style = MaterialTheme.typography.labelLarge)
                Text("拖动调色盘，颜色会即时应用", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Canvas(Modifier.fillMaxWidth().height(176.dp).clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = "选择饱和度和亮度"
                stateDescription = "饱和度 $saturationPercent%，亮度 $brightnessPercent%"
                customActions = listOf(
                    CustomAccessibilityAction("增加饱和度") { commitColor(currentColor.copy(saturation = currentColor.saturation + 0.1f)); true },
                    CustomAccessibilityAction("降低饱和度") { commitColor(currentColor.copy(saturation = currentColor.saturation - 0.1f)); true },
                    CustomAccessibilityAction("增加亮度") { commitColor(currentColor.copy(value = currentColor.value + 0.1f)); true },
                    CustomAccessibilityAction("降低亮度") { commitColor(currentColor.copy(value = currentColor.value - 0.1f)); true },
                )
            }
            .pointerInput(Unit) {
                fun choose(position: Offset) {
                    if (size.width > 0 && size.height > 0) commitColor(currentColor.copy(
                        saturation = (position.x / size.width).coerceIn(0f, 1f),
                        value = 1f - (position.y / size.height).coerceIn(0f, 1f),
                    ))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    choose(down.position)
                    // Consume from the first touch, so dragging vertically here cannot scroll Settings.
                    drag(down.id) { change -> choose(change.position); change.consume() }
                }
            }) {
            val hueColor = Color(PickerColor(color.hue, 1f, 1f).toArgb())
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val radius = 8.dp.toPx()
            val center = Offset((color.saturation * size.width).coerceIn(radius, size.width - radius),
                ((1f - color.value) * size.height).coerceIn(radius, size.height - radius))
            drawCircle(displayColor, radius, center)
            drawCircle(Color.Black, radius, center, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, radius, center, style = Stroke(2.dp.toPx()))
        }

        Text("色相", style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(48.dp)
            .semantics {
                contentDescription = "选择色相"
                stateDescription = hueDescription(color.hue)
                progressBarRangeInfo = ProgressBarRangeInfo(color.hue.coerceIn(0f, 359f), 0f..359f)
                setProgress { hue -> commitColor(currentColor.copy(hue = hue.coerceIn(0f, 359f))); true }
            }
            .pointerInput(Unit) {
                fun choose(position: Offset) {
                    val inset = 12.dp.toPx()
                    val width = size.width - inset * 2f
                    if (width > 0f) commitColor(currentColor.copy(hue = ((position.x - inset) / width).coerceIn(0f, 1f) * 359.9f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    choose(down.position)
                    drag(down.id) { change -> choose(change.position); change.consume() }
                }
            }) {
            val inset = 12.dp.toPx()
            drawRoundRect(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green,
                Color.Cyan, Color.Blue, Color.Magenta, Color.Red), startX = inset, endX = size.width - inset),
                topLeft = Offset(inset, (size.height - 18.dp.toPx()) / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2f, 18.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
            val center = Offset(inset + color.hue / 360f * (size.width - inset * 2f), size.height / 2f)
            drawCircle(Color(PickerColor(color.hue, 1f, 1f).toArgb()), 11.dp.toPx(), center)
            drawCircle(Color.Black, 11.dp.toPx(), center, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, 11.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }

        Text("常用颜色", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            commonThemeColors.forEach { (name, hex) ->
                val swatch = Color(PickerColor.fromHex(hex).toArgb())
                val chosen = color.toHex() == hex
                Box(Modifier.size(48.dp)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { commit(PickerColor.fromHex(hex, color)) })
                    .semantics { contentDescription = "常用色，$name" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(swatch)
                        .border(if (chosen) 2.dp else 1.dp,
                            if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (chosen) Text("✓", color = if (swatch.luminance() > 0.4f) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

private fun hueDescription(hue: Float): String = when {
    hue < 30f || hue >= 330f -> "红色调"
    hue < 90f -> "黄色调"
    hue < 150f -> "绿色调"
    hue < 210f -> "青色调"
    hue < 270f -> "蓝色调"
    else -> "紫色调"
}
