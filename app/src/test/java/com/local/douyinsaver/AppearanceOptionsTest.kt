package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class AppearanceOptionsTest {
    @Test fun acceptsOnlyOpaqueSixDigitColors() {
        assertEquals("#AB12CD", AppearanceOptions.normalizeHex(" ab12cd "))
        assertEquals("#176B62", AppearanceOptions.normalizeHex("#176b62"))
        listOf("#123", "#00112233", "blue", "#gggggg", "#123456;url(x)").forEach { assertNull(AppearanceOptions.normalizeHex(it)) }
    }
    @Test fun clampsInvalidPersistedSliderValues() {
        val result = AppearanceOptions(blur = Float.NaN, darkness = 5f, cardOpacity = -2f, backgroundRevision = -1).normalized()
        assertEquals(16f, result.blur, 0f); assertEquals(0.8f, result.darkness, 0f)
        assertEquals(0f, result.cardOpacity, 0f); assertEquals(0L, result.backgroundRevision)
    }
    @Test fun invalidCustomColorFallsBackWithoutChangingPreset() {
        assertEquals("#2AA1E8", AppearanceOptions(palette = ThemePalette.CUSTOM, customHex = "oops").normalized().primaryHex)
        assertEquals("#2AA1E8", AppearanceOptions(palette = ThemePalette.BLUE, customHex = "#FFFFFF").normalized().primaryHex)
    }
    @Test fun defaultsUseThinPanelsAndIndependentTextProtection() {
        val defaults = AppearanceOptions()
        assertEquals(0L, defaults.backgroundRevision)
        assertEquals(0.14f, defaults.cardOpacity, 0f)
        assertEquals(16f, defaults.blur, 0f)
        assertEquals(0.64f, defaults.textProtection, 0f)
        assertEquals(ThemeMode.SYSTEM, defaults.mode)
        assertEquals(ThemePalette.BLUE, defaults.palette)
        assertEquals("#2AA1E8", defaults.primaryHex)
    }
    @Test fun acceptsEntireOpacityRangeWithoutHiddenMinimum() {
        listOf(0f, 0.01f, 0.4f, 0.6f, 1f).forEach { opacity ->
            assertEquals(opacity, AppearanceOptions(cardOpacity = opacity).normalized().cardOpacity, 0f)
        }
    }
    @Test fun protectionCanBeDisabledWithoutChangingPanelOpacity() {
        val options = AppearanceOptions(cardOpacity = 0f, textProtection = 0f).normalized()
        assertEquals(0f, options.cardOpacity, 0f)
        assertEquals(0f, options.textProtection, 0f)
        assertEquals(0.85f, options.copy(textProtection = 5f).normalized().textProtection, 0f)
    }
    @Test fun legacyDefaultMigrationKeepsImageCropAndTheme() {
        val legacy = AppearanceOptions(backgroundRevision = 123L, blur = 0f, cardOpacity = 0.94f, darkness = 0.28f,
            palette = ThemePalette.PURPLE, fit = BackgroundFit.FIT, position = BackgroundPosition.BOTTOM)
        val migrated = legacy.migrateUntouchedLegacyBackground()
        assertEquals(123L, migrated.backgroundRevision)
        assertEquals(legacy.palette, migrated.palette)
        assertEquals(legacy.fit, migrated.fit)
        assertEquals(legacy.position, migrated.position)
        assertEquals(16f, migrated.blur, 0f)
        assertEquals(0.14f, migrated.cardOpacity, 0f)
        assertEquals(migrated, migrated.migrateUntouchedLegacyBackground())
    }
    @Test fun migrationPreservesDeliberatePanelAndDarknessAdjustments() {
        val custom = AppearanceOptions(backgroundRevision = 123L, blur = 5f, cardOpacity = 0.7f, darkness = 0.4f)
        assertEquals(custom, custom.migrateUntouchedLegacyBackground())
        val adjustedDarkness = custom.copy(blur = 0f, cardOpacity = 0.94f).migrateUntouchedLegacyBackground()
        assertEquals(0.4f, adjustedDarkness.darkness, 0f)
        val noImage = AppearanceOptions(blur = 0f, cardOpacity = 0.94f)
        assertEquals(noImage, noImage.migrateUntouchedLegacyBackground())
    }
    @Test fun softPresetKeepsChosenImageAndPosition() {
        val original = AppearanceOptions(backgroundRevision = 321L, position = BackgroundPosition.TOP, fit = BackgroundFit.FIT, palette = ThemePalette.BLUE)
        assertEquals(original.backgroundRevision, original.softBlend().backgroundRevision)
        assertEquals(original.position, original.softBlend().position)
        assertEquals(original.fit, original.softBlend().fit)
        assertEquals(original.palette, original.softBlend().palette)
    }
}
