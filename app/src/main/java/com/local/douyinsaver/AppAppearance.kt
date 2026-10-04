package com.local.douyinsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class AppearanceStore(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE)
    val backgroundFile: File get() = File(app.filesDir, "appearance/background.jpg")
    var migrationNotice by mutableStateOf(preferences.getBoolean("fusion_notice", false)); private set
    var hasPreviousPanelStyle by mutableStateOf(preferences.contains("previous_panel_opacity")); private set
    var options by mutableStateOf(read()); private set

    private inline fun <reified E : Enum<E>> enum(key: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == preferences.getString(key, null) } ?: fallback

    private fun read(): AppearanceOptions {
        val oldProfile = preferences.getInt("profile_version", 0) < 2 && preferences.contains("background")
        val loaded = AppearanceOptions(
        mode = enum("mode", ThemeMode.SYSTEM), palette = enum("palette", ThemePalette.BLUE),
        customHex = preferences.getString("hex", AppearanceOptions.DEFAULT_CUSTOM_HEX).orEmpty(),
        backgroundRevision = preferences.getLong("background", 0),
        fit = enum("fit", BackgroundFit.CROP), position = enum("position", BackgroundPosition.CENTER),
        blur = preferences.getFloat("blur", if (oldProfile) 0f else 16f),
        darkness = preferences.getFloat("darkness", if (oldProfile) 0.28f else 0.05f),
        cardOpacity = preferences.getFloat("opacity", if (oldProfile) 0.94f else 0.14f),
        textProtection = preferences.getFloat("text_protection", 0.64f),
    ).normalized()
        if (preferences.getInt("profile_version", 0) >= 2) return loaded
        val result = if (oldProfile) loaded.migrateUntouchedLegacyBackground() else loaded
        val editor = preferences.edit().putInt("profile_version", 2)
        if (result != loaded) {
            editor.putFloat("previous_panel_opacity", loaded.cardOpacity).putFloat("previous_panel_blur", loaded.blur)
                .putFloat("previous_panel_darkness", loaded.darkness).putFloat("previous_panel_protection", 0f)
                .putFloat("blur", result.blur).putFloat("darkness", result.darkness).putFloat("opacity", result.cardOpacity)
                .putFloat("text_protection", result.textProtection).putBoolean("fusion_notice", true)
            migrationNotice = true
            hasPreviousPanelStyle = true
        }
        editor.apply()
        return result
    }

    fun update(value: AppearanceOptions) {
        options = value.normalized()
        preferences.edit().putString("mode", options.mode.name).putString("palette", options.palette.name)
            .putString("hex", options.customHex).putLong("background", options.backgroundRevision)
            .putString("fit", options.fit.name).putString("position", options.position.name)
            .putFloat("blur", options.blur).putFloat("darkness", options.darkness)
            .putFloat("opacity", options.cardOpacity).putFloat("text_protection", options.textProtection)
            .putInt("profile_version", 2).apply()
    }

    suspend fun importBackground(uri: Uri) {
        require(uri.scheme == "content") { "请选择一张图片" }
        withContext(Dispatchers.IO) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0) { "图片尺寸无效" }
                val scale = max(width, height) / 1600f
                if (scale > 1f) decoder.setTargetSize((width / scale).roundToInt().coerceAtLeast(1), (height / scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                backgroundFile.parentFile?.mkdirs()
                val atomic = AtomicFile(backgroundFile)
                val output = atomic.startWrite()
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "图片无法保存" }
                    atomic.finishWrite(output)
                } catch (error: Exception) {
                    atomic.failWrite(output)
                    throw error
                }
            } finally { bitmap.recycle() }
        }
        val importingFirstBackground = options.backgroundRevision == 0L
        val value = options.copy(backgroundRevision = System.currentTimeMillis())
        update(if (importingFirstBackground) value.migrateUntouchedLegacyBackground() else value)
    }

    fun removeBackground() {
        update(options.copy(backgroundRevision = 0))
        backgroundFile.delete()
    }

    fun reset() {
        update(AppearanceOptions(backgroundRevision = options.backgroundRevision))
        dismissMigrationNotice()
    }

    fun applySoftBlend() = update(options.softBlend())

    fun dismissMigrationNotice() {
        migrationNotice = false
        preferences.edit().putBoolean("fusion_notice", false).apply()
    }

    fun restorePreviousPanelStyle() {
        if (!hasPreviousPanelStyle) return
        update(options.copy(blur = preferences.getFloat("previous_panel_blur", 0f),
            darkness = preferences.getFloat("previous_panel_darkness", 0.28f),
            cardOpacity = preferences.getFloat("previous_panel_opacity", 0.94f),
            textProtection = preferences.getFloat("previous_panel_protection", 0f)))
        dismissMigrationNotice()
    }
}

val LocalAppearance = staticCompositionLocalOf { AppearanceOptions() }

@Composable
fun SaverAppearance(store: AppearanceStore, content: @Composable () -> Unit) {
    val options = store.options
    val dark = when (options.mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val seed = Color(0xFF000000L or options.primaryHex.removePrefix("#").toLong(16))
    val primary = readableAccent(seed, dark)
    val onPrimary = if (primary.luminance() > 0.179f) Color.Black else Color.White
    val baseScheme = if (options.mode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = lerp(seed, Color(0xFF101716), 0.60f), onPrimaryContainer = lerp(seed, Color.White, 0.80f),
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = lerp(seed, Color(0xFF101716), 0.55f), onSecondaryContainer = lerp(seed, Color.White, 0.88f),
        tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = lerp(seed, Color(0xFF101716), 0.55f), onTertiaryContainer = lerp(seed, Color.White, 0.88f),
        surfaceTint = primary, inversePrimary = readableAccent(seed, false),
        background = Color(0xFF101615), surface = Color(0xFF1B2321), surfaceContainer = Color(0xFF1B2321),
        surfaceContainerLow = Color(0xFF1B2321), surfaceContainerHighest = Color(0xFF2B3532),
        onSurface = Color(0xFFE6EEE9), onSurfaceVariant = Color(0xFFB3BFB8), outlineVariant = Color(0xFF3A4942),
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary, primaryContainer = lerp(seed, Color.White, 0.86f),
        secondary = primary, onSecondary = onPrimary, tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = lerp(seed, Color.White, 0.90f), onTertiaryContainer = Color(0xFF17251F), surfaceTint = primary,
        onPrimaryContainer = Color(0xFF17251F), secondaryContainer = lerp(seed, Color.White, 0.9f),
        onSecondaryContainer = Color(0xFF17251F), background = lerp(seed, Color.White, 0.97f),
        surface = Color.White, surfaceContainer = Color.White, surfaceContainerLow = Color.White,
        surfaceContainerHighest = lerp(seed, Color.White, 0.94f), onSurface = Color(0xFF1C2923),
        onSurfaceVariant = Color(0xFF64736A), outlineVariant = Color(0xFFDCE5DF),
    )
    val scheme = if (options.backgroundRevision > 0 && options.textProtection > 0f) {
        // Compute against the hardest image value for this theme, before any panel is painted.
        val tint = backdropTint(baseScheme)
        val rawImage = if (dark) Color.White else Color.Black
        val shaded = Color.Black.copy(alpha = options.darkness).compositeOver(rawImage)
        val worstBackground = tint.copy(alpha = options.textProtection).compositeOver(shaded)
        val protectedPrimary = readableOverImage(baseScheme.primary, worstBackground, dark)
        baseScheme.copy(
            primary = protectedPrimary,
            onPrimary = if (protectedPrimary.luminance() > 0.179f) Color.Black else Color.White,
            onSurface = readableOverImage(baseScheme.onSurface, worstBackground, dark),
            onSurfaceVariant = readableOverImage(baseScheme.onSurfaceVariant, worstBackground, dark),
        )
    } else baseScheme
    val window = LocalActivity.current?.window
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = scheme.background.toArgb()
                window.navigationBarColor = scheme.surface.toArgb()
            }
        }
    }
    CompositionLocalProvider(LocalAppearance provides options) {
        MaterialTheme(colorScheme = scheme, shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ), content = content)
    }
}

private fun backdropTint(scheme: ColorScheme) = lerp(scheme.background, scheme.primaryContainer, 0.12f)

private fun readableOverImage(foreground: Color, background: Color, dark: Boolean): Color {
    fun contrast(first: Color, second: Color): Float =
        (max(first.luminance(), second.luminance()) + 0.05f) / (minOf(first.luminance(), second.luminance()) + 0.05f)
    val target = if (dark) Color.White else Color.Black
    // With protection intentionally very low/off, one tone cannot contrast with every photo pixel.
    if (contrast(target, background) < 4.5f) return foreground
    var result = foreground
    repeat(24) {
        if (contrast(result, background) >= 4.5f) return result
        result = lerp(result, target, 0.14f)
    }
    return target
}

/** Shared by page headings and every panel; 0% means this layer paints no background. */
@Composable
fun appearancePanelColor(): Color {
    val options = LocalAppearance.current
    val scheme = MaterialTheme.colorScheme
    return if (options.backgroundRevision <= 0) scheme.surface
    else lerp(scheme.surface, scheme.primaryContainer, 0.20f).copy(alpha = options.cardOpacity)
}

private fun readableAccent(seed: Color, dark: Boolean): Color {
    var color = if (dark) lerp(seed, Color.White, 0.55f) else seed
    val surfaceLuminance = if (dark) Color(0xFF1B2321).luminance() else 1f
    repeat(20) {
        val luminance = color.luminance()
        val contrast = (max(luminance, surfaceLuminance) + 0.05f) / (minOf(luminance, surfaceLuminance) + 0.05f)
        if (contrast >= 4.5f) return color
        color = lerp(color, if (dark) Color.White else Color.Black, 0.12f)
    }
    return color
}

@Composable
fun AppearanceBackdrop(store: AppearanceStore, modifier: Modifier = Modifier) {
    val options = store.options
    val bitmap by produceState<Bitmap?>(null, options.backgroundRevision, options.blur.toInt()) {
        if (options.backgroundRevision <= 0) value = null
        else {
            // Avoid recalculating every intermediate thumb position; keep the previous image visible.
            delay(80)
            value = withContext(Dispatchers.IO) {
            runCatching {
                currentCoroutineContext().ensureActive()
                val decoded = android.graphics.BitmapFactory.decodeFile(store.backgroundFile.absolutePath) ?: return@runCatching null
                if (options.blur < 1f) decoded else blurBackground(decoded, options.blur.toInt())
            }.getOrNull()
            }
        }
    }
    // Always opaque underneath the chosen image and translucent cards, including on all 3 pages.
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        bitmap?.let { image ->
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (options.fit == BackgroundFit.CROP) ContentScale.Crop else ContentScale.Fit,
                alignment = if (options.fit == BackgroundFit.CROP) Alignment.Center else when (options.position) {
                    BackgroundPosition.TOP -> Alignment.TopCenter
                    BackgroundPosition.CENTER -> Alignment.Center
                    BackgroundPosition.BOTTOM -> Alignment.BottomCenter
                })
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = options.darkness)))
            // One continuous protective tint beneath the entire UI, independent of panel opacity.
            Box(Modifier.fillMaxSize().background(backdropTint(MaterialTheme.colorScheme).copy(alpha = options.textProtection)))
        }
    }
}

/** Small separable box blur, also works on Android 10/11 where platform RenderEffect is absent. */
private suspend fun blurBackground(source: Bitmap, radius: Int): Bitmap {
    val factor = max(source.width, source.height) / 800f
    val input = if (factor > 1) Bitmap.createScaledBitmap(source, (source.width / factor).toInt().coerceAtLeast(1),
        (source.height / factor).toInt().coerceAtLeast(1), true).also { source.recycle() } else source
    val width = input.width
    val height = input.height
    try {
    var pixels = IntArray(width * height)
    input.getPixels(pixels, 0, width, 0, 0, width, height)
    val r = radius.coerceIn(1, 24)
    val window = r * 2 + 1
    repeat(2) {
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            var red = 0; var green = 0; var blue = 0
            fun add(x: Int, sign: Int) {
                val c = pixels[y * width + x.coerceIn(0, width - 1)]
                red += ((c shr 16) and 255) * sign; green += ((c shr 8) and 255) * sign; blue += (c and 255) * sign
            }
            for (x in -r..r) add(x, 1)
            for (x in 0 until width) {
                horizontal[y * width + x] = (255 shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
                add(x - r, -1); add(x + r + 1, 1)
            }
        }
        val vertical = IntArray(pixels.size)
        for (x in 0 until width) {
            currentCoroutineContext().ensureActive()
            var red = 0; var green = 0; var blue = 0
            fun add(y: Int, sign: Int) {
                val c = horizontal[y.coerceIn(0, height - 1) * width + x]
                red += ((c shr 16) and 255) * sign; green += ((c shr 8) and 255) * sign; blue += (c and 255) * sign
            }
            for (y in -r..r) add(y, 1)
            for (y in 0 until height) {
                vertical[y * width + x] = (255 shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
                add(y - r, -1); add(y + r + 1, 1)
            }
        }
        pixels = vertical
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } finally { input.recycle() }
}
