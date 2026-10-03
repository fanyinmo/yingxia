package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class PickerColorTest {
    @Test fun hueWheelIncludesEachPrimaryAndSecondaryColor() {
        listOf(0f to "#FF0000", 60f to "#FFFF00", 120f to "#00FF00", 180f to "#00FFFF",
            240f to "#0000FF", 300f to "#FF00FF", 360f to "#FF0000").forEach { (hue, hex) ->
            assertEquals(hex, PickerColor(hue, 1f, 1f).toHex())
        }
    }

    @Test fun importedLegacyRgbColorsRoundTripExactly() {
        listOf("#176B62", "#FFFFFF", "#000000", "#D05B4B", "#43834A", "#C75D91", "#555D66").forEach { hex ->
            assertEquals(hex, PickerColor.fromHex(hex).toHex())
        }
        for (red in 0..255 step 17) for (green in 0..255 step 17) for (blue in 0..255 step 17) {
            val rgb = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            val hex = String.format(java.util.Locale.ROOT, "#%06X", rgb and 0xFFFFFF)
            assertEquals(rgb, PickerColor.fromHex(hex).toArgb())
        }
    }

    @Test fun panelEdgesAllowWhiteBlackAndFullySaturatedHue() {
        assertEquals("#FFFFFF", PickerColor(240f, 0f, 1f).toHex())
        assertEquals("#000000", PickerColor(240f, 1f, 0f).toHex())
        assertEquals("#0000FF", PickerColor(240f, 1f, 1f).toHex())
        assertEquals("#808080", PickerColor(240f, 0f, 0.5f).toHex())
    }

    @Test fun achromaticRgbPreservesPreviousHueAndBlackPreservesSaturation() {
        val blue = PickerColor(240f, 0.8f, 0.7f)
        val white = PickerColor.fromHex("#FFFFFF", blue)
        assertEquals(240f, white.hue, 0f)
        assertEquals(0f, white.saturation, 0f)
        assertEquals("#0000FF", white.copy(saturation = 1f).toHex())
        val black = PickerColor.fromHex("#000000", blue)
        assertEquals(240f, black.hue, 0f)
        assertEquals(0.8f, black.saturation, 0f)
        assertEquals("#3333FF", black.copy(value = 1f).toHex())
    }

    @Test fun finiteClampAndHueWrapKeepTouchPositionsValid() {
        assertEquals("#FF0000", PickerColor(720f, 5f, 10f).toHex())
        assertEquals("#FF00FF", PickerColor(-60f, 1f, 1f).toHex())
        assertEquals("#000000", PickerColor(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN).toHex())
        assertEquals("#2AA1E8", PickerColor.fromHex("invalid").toHex())
    }
}
