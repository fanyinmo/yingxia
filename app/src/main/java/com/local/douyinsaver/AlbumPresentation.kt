package com.local.douyinsaver

/** Group covers and their motion clips without counting a live photo twice. */
internal fun albumContentLabel(images: List<ParsedImage>): String {
    val live = images.count { albumPreviewKind(it) == AlbumAssetKind.LIVE }
    val animated = images.count { albumPreviewKind(it) == AlbumAssetKind.ANIMATED }
    val dynamic = images.count { albumPreviewKind(it) == AlbumAssetKind.DYNAMIC }
    return buildString {
        append("图集 · ${images.size} ${if (live + animated + dynamic > 0) "项" else "张"}")
        if (live > 0) append(" · $live 动态素材")
        if (animated > 0) append(" · $animated 动图")
        if (dynamic > 0) append(" · $dynamic 动态素材")
    }
}

internal fun albumAssetLabel(kind: AlbumAssetKind): String = when (kind) {
    AlbumAssetKind.STATIC -> "图片"
    AlbumAssetKind.ANIMATED -> "动图"
    AlbumAssetKind.LIVE -> "动态素材"
    AlbumAssetKind.DYNAMIC -> "动态素材"
}

internal fun albumPreviewKind(image: ParsedImage): AlbumAssetKind =
    if (image.kind == AlbumAssetKind.STATIC && image.motion != null) AlbumAssetKind.DYNAMIC else image.kind

internal fun savedAlbumImages(saved: SavedVideo): List<ParsedImage> {
    if (saved.albumAssets.isNotEmpty()) return saved.albumAssets.map { item ->
        if (item.mimeType.startsWith("video/")) ParsedImage("", kind = if (item.kind == AlbumAssetKind.STATIC) AlbumAssetKind.DYNAMIC else item.kind,
            motion = ParsedMotion(item.uri))
        else ParsedImage(item.uri, kind = when {
            item.embeddedMotion -> AlbumAssetKind.LIVE
            item.motionUri.isNotBlank() && item.kind == AlbumAssetKind.STATIC -> AlbumAssetKind.DYNAMIC
            else -> item.kind
        }, mimeType = item.mimeType,
            motion = if (item.embeddedMotion) ParsedMotion(item.uri, embeddedInPhoto = true)
                else item.motionUri.takeIf(String::isNotBlank)?.let { ParsedMotion(it) })
    }
    // Earlier image-only records do not contain a per-item manifest.
    if (saved.isAlbum || saved.mimeType.startsWith("image/")) {
        if (!saved.mimeType.startsWith("image/")) return emptyList()
        return saved.uris.map { ParsedImage(it, mimeType = saved.mimeType,
            kind = if (saved.exportMode == AlbumMode.GIF.name) AlbumAssetKind.ANIMATED else AlbumAssetKind.STATIC) }
    }
    return emptyList()
}

internal fun savedContentLabel(saved: SavedVideo): String {
    if (!saved.isAlbum && saved.mimeType == "image/gif") return "GIF 动图"
    val images = savedAlbumImages(saved)
    return if (images.isNotEmpty()) albumContentLabel(images)
    else if (saved.isAlbum) "图集合成视频" else "视频"
}

internal object AlbumActionUiPolicy {
    fun hasDynamic(content: ParsedVideo): Boolean = content.images.any { albumPreviewKind(it) != AlbumAssetKind.STATIC }
    fun coversAvailable(content: ParsedVideo): Boolean = content.isAlbum && WatermarkSources.available(
        content.copy(images = content.images.map { it.copy(kind = AlbumAssetKind.STATIC, motion = null) }), WatermarkMode.CLEAN)
    fun canComposeGif(content: ParsedVideo): Boolean = content.isAlbum &&
        (hasDynamic(content) || content.images.size >= 2)
    fun requiresFormatChoice(content: ParsedVideo): Boolean = content.images.any { albumPreviewKind(it) == AlbumAssetKind.DYNAMIC }
    fun dynamicModes(content: ParsedVideo?): List<AlbumMode> =
        if (content != null && requiresFormatChoice(content)) listOf(AlbumMode.MOTION_VIDEOS, AlbumMode.GIF)
        else listOf(AlbumMode.IMAGES, AlbumMode.GIF)
    fun selectedDynamicMode(content: ParsedVideo?, mode: AlbumMode): AlbumMode? = mode.takeIf { it in dynamicModes(content) }
    fun primaryLabel(content: ParsedVideo): String {
        val kinds = content.images.map(::albumPreviewKind).toSet()
        return when {
            AlbumAssetKind.DYNAMIC in kinds -> "保存动态素材"
            AlbumAssetKind.LIVE in kinds && AlbumAssetKind.ANIMATED in kinds -> "保存动态素材"
            AlbumAssetKind.LIVE in kinds -> "保存动态素材"
            AlbumAssetKind.ANIMATED in kinds -> "保存动图"
            else -> "保存 ${content.images.size} 张图片"
        }
    }
    fun saveLabel(content: ParsedVideo?, mode: AlbumMode?): String = when (mode) {
        AlbumMode.LIVE_PHOTOS -> "已移除格式"
        AlbumMode.CONVERT_TO_LIVE -> "已移除格式"
        AlbumMode.MOTION_VIDEOS -> "保存为无声动图"
        AlbumMode.GIF -> "保存 GIF 动图"
        AlbumMode.IMAGES -> content?.let(::primaryLabel) ?: "保存动图"
        else -> "请选择保存格式"
    }
}

/** Source durations are hints for presentation; confirmation uses decoded files in the engine. */
internal object AlbumDurationUiPolicy {
    fun totalSeconds(content: ParsedVideo, override: Double?, staticSeconds: Int): Double? = totalSeconds(content, override, staticSeconds.toDouble())
    fun totalSeconds(content: ParsedVideo, override: Double?, staticSeconds: Double): Double? {
        if (content.images.isEmpty()) return null
        if (override != null) return override.takeIf { it.isFinite() && it in 0.1..120.0 }?.times(content.images.size)
        return content.images.fold(0.0) { sum, image ->
            val seconds = if (albumPreviewKind(image) == AlbumAssetKind.STATIC) staticSeconds
                else image.motion?.durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            sum + seconds
        }
    }
    fun acceptsInput(value: String): Boolean = value.length <= 7 && value.matches(Regex("[0-9]*\\.?[0-9]?"))
    fun parseOverride(value: String): Double? = value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..120.0 }
    fun seconds(value: Double): String = java.math.BigDecimal.valueOf(value)
        .setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    private fun measuredSeconds(value: Double): String = java.math.BigDecimal.valueOf(value)
        .setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    fun adjustmentDescription(adjustment: DurationAdjustment): String =
        "第 ${adjustment.index + 1} 项：原时长 ${measuredSeconds(adjustment.originalSeconds)} 秒 → ${measuredSeconds(adjustment.requestedSeconds)} 秒，" +
            if (adjustment.requestedSeconds < adjustment.originalSeconds) "截取开头。" else "循环播放至设置时长。"
}
