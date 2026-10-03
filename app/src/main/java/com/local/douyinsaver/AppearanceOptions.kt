package com.local.douyinsaver

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色"), DYNAMIC("系统取色") }
enum class ThemePalette(val label: String, val hex: String) {
    MINT("薄荷", "#176B62"), BLUE("天蓝", "#2AA1E8"), PURPLE("紫藤", "#7650A5"), NEUTRAL("岩灰", "#555D66"), CUSTOM("自选", "#2AA1E8")
}
enum class BackgroundFit(val label: String) { CROP("铺满裁剪"), FIT("完整显示") }
enum class BackgroundPosition(val label: String) { TOP("顶部"), CENTER("居中"), BOTTOM("底部") }

data class AppearanceOptions(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.BLUE,
    val customHex: String = DEFAULT_CUSTOM_HEX,
    val backgroundRevision: Long = 0,
    val fit: BackgroundFit = BackgroundFit.CROP,
    val position: BackgroundPosition = BackgroundPosition.CENTER,
    val blur: Float = 16f,
    val darkness: Float = 0.05f,
    val cardOpacity: Float = 0.14f,
    val textProtection: Float = 0.64f,
) {
    val primaryHex get() = if (palette == ThemePalette.CUSTOM) customHex else palette.hex
    fun normalized() = copy(
        customHex = normalizeHex(customHex) ?: DEFAULT_CUSTOM_HEX,
        backgroundRevision = backgroundRevision.coerceAtLeast(0),
        blur = finiteClamp(blur, 0f, 24f, 16f),
        darkness = finiteClamp(darkness, 0f, 0.8f, 0.05f),
        cardOpacity = finiteClamp(cardOpacity, 0f, 1f, 0.14f),
        textProtection = finiteClamp(textProtection, 0f, 0.85f, 0.64f),
    )

    fun softBlend() = copy(blur = 16f, darkness = 0.05f, cardOpacity = 0.14f, textProtection = 0.64f)

    /** Called once by the preference-schema migration, not each time the app starts. */
    fun migrateUntouchedLegacyBackground(): AppearanceOptions =
        if (backgroundRevision > 0 && kotlin.math.abs(blur) < 0.0001f && kotlin.math.abs(cardOpacity - 0.94f) < 0.0001f) {
            copy(blur = 16f, cardOpacity = 0.14f,
                darkness = if (kotlin.math.abs(darkness - 0.28f) < 0.0001f) 0.05f else darkness,
                textProtection = 0.64f)
        } else this

    companion object {
        const val DEFAULT_CUSTOM_HEX = "#2AA1E8"
        fun normalizeHex(input: String): String? {
            val value = input.trim().removePrefix("#")
            return if (value.matches(Regex("[A-Fa-f0-9]{6}"))) "#${value.uppercase(java.util.Locale.ROOT)}" else null
        }
        private fun finiteClamp(value: Float, min: Float, max: Float, fallback: Float): Float =
            if (value.isFinite()) value.coerceIn(min, max) else fallback
    }
}
