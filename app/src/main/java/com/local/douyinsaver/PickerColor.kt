package com.local.douyinsaver

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** HSV stays independent of its RGB representation, so choosing black does not lose the hue. */
data class PickerColor(val hue: Float, val saturation: Float, val value: Float) {
    fun normalized(): PickerColor = copy(
        hue = if (hue.isFinite()) ((hue % 360f) + 360f) % 360f else 0f,
        saturation = if (saturation.isFinite()) saturation.coerceIn(0f, 1f) else 0f,
        value = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f,
    )

    fun toArgb(): Int {
        val color = normalized()
        val chroma = color.value * color.saturation
        val x = chroma * (1f - abs((color.hue / 60f) % 2f - 1f))
        val offset = color.value - chroma
        val (red, green, blue) = when {
            color.hue < 60f -> Triple(chroma, x, 0f)
            color.hue < 120f -> Triple(x, chroma, 0f)
            color.hue < 180f -> Triple(0f, chroma, x)
            color.hue < 240f -> Triple(0f, x, chroma)
            color.hue < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        fun channel(component: Float) = ((component + offset) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    fun toHex(): String = String.format(Locale.ROOT, "#%06X", toArgb() and 0xFFFFFF)

    companion object {
        fun fromHex(hex: String, previous: PickerColor? = null): PickerColor {
            val normalized = AppearanceOptions.normalizeHex(hex) ?: AppearanceOptions.DEFAULT_CUSTOM_HEX
            val rgb = normalized.drop(1).toInt(16)
            val red = ((rgb shr 16) and 255) / 255f
            val green = ((rgb shr 8) and 255) / 255f
            val blue = (rgb and 255) / 255f
            val maximum = maxOf(red, green, blue)
            val minimum = minOf(red, green, blue)
            val chroma = maximum - minimum
            val hue = if (chroma == 0f) previous?.normalized()?.hue ?: 0f else when (maximum) {
                red -> 60f * (((green - blue) / chroma) % 6f)
                green -> 60f * ((blue - red) / chroma + 2f)
                else -> 60f * ((red - green) / chroma + 4f)
            }
            // RGB black cannot carry saturation either. Keep it when restoring an external black value.
            val saturation = if (maximum == 0f) previous?.normalized()?.saturation ?: 0f else chroma / maximum
            return PickerColor(hue, saturation, maximum).normalized()
        }
    }
}
