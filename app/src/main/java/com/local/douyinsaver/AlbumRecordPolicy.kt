package com.local.douyinsaver

import kotlin.math.roundToLong

/** A previously saved cover alone must not suppress a newly available live photo. */
object AlbumRecordPolicy {
    fun matches(saved: SavedVideo, content: ParsedVideo, mode: AlbumMode,
        gifStartSeconds: Float = 0f, gifDurationSeconds: Float = 6f,
        imageSeconds: Int = 3, itemDurationSeconds: Double? = null, staticImageSeconds: Double? = null,
        gifExportQuality: GifExportQuality = GifExportQuality.HIGH_QUALITY): Boolean {
        if (!mode.isSupportedExport()) return false
        if (content.isAlbum && mode != AlbumMode.COVERS && saved.albumAssets.any { it.embeddedMotion }) return false
        if (mode == AlbumMode.GIF && saved.gifExportQuality.ifBlank { GifExportQuality.HIGH_QUALITY.name } != gifExportQuality.name) return false
        if (!content.isAlbum) {
            if (mode != AlbumMode.GIF) return !saved.isAlbum && saved.mimeType == "video/mp4"
            if (!gifStartSeconds.isFinite() || !gifDurationSeconds.isFinite() || content.durationSeconds <= 0) return false
            val start = (gifStartSeconds.toDouble() * 1000).roundToLong()
            val duration = minOf((gifDurationSeconds.toDouble() * 1000).roundToLong(), (content.durationSeconds * 1000).roundToLong() - start)
            return !saved.isAlbum && saved.mimeType == "image/gif" && saved.exportMode == AlbumMode.GIF.name &&
                saved.gifStartMs == start && saved.gifDurationMs == duration
        }
        if (mode in listOf(AlbumMode.VIDEO, AlbumMode.GIF) && saved.albumTimingSignature !=
            AlbumTiming.signature(DownloadOptions(imageSeconds = imageSeconds, itemDurationSeconds = itemDurationSeconds,
                staticImageSeconds = staticImageSeconds))) return false
        if (mode == AlbumMode.VIDEO) return saved.isAlbum && saved.mimeType == "video/mp4" && saved.albumAssets.isEmpty()
        if (!saved.isAlbum) return false
        if (mode == AlbumMode.COVERS) return saved.exportMode == AlbumMode.COVERS.name &&
            saved.albumAssets.size == content.images.size
        if (saved.exportMode == AlbumMode.COVERS.name) return false
        if (mode == AlbumMode.GIF && saved.exportMode != AlbumMode.GIF.name) return false
        if (mode != AlbumMode.GIF && saved.exportMode == AlbumMode.GIF.name) return false
        // Old records never stored whether an animated source had been flattened.
        // Keep their files/history, but require a fresh manifest before reusing them.
        if (saved.albumAssets.isEmpty()) return false
        if (mode == AlbumMode.GIF && !content.hasDynamicAlbumAssets && saved.albumSourceCount == content.images.size &&
            saved.albumAssets.size == 1 && saved.albumAssets.single().mimeType == "image/gif") return true
        if (saved.albumAssets.size != content.images.size) return false
        return saved.albumAssets.zip(content.images).all { (asset, image) ->
            val unknown = image.kind == AlbumAssetKind.DYNAMIC ||
                (image.kind == AlbumAssetKind.STATIC && image.motion != null)
            val expectedKind = if (mode == AlbumMode.CONVERT_TO_LIVE && (unknown || image.kind == AlbumAssetKind.ANIMATED)) AlbumAssetKind.LIVE
            else if (unknown) when (mode) {
                AlbumMode.LIVE_PHOTOS -> AlbumAssetKind.LIVE
                AlbumMode.MOTION_VIDEOS, AlbumMode.GIF -> AlbumAssetKind.ANIMATED
                else -> AlbumAssetKind.DYNAMIC
            } else if (image.kind == AlbumAssetKind.LIVE) AlbumAssetKind.ANIMATED else image.kind
            val completeSavedPair = asset.kind != AlbumAssetKind.LIVE ||
                asset.embeddedMotion || (asset.motionUri.isNotBlank() && asset.motionUri in saved.uris)
            completeSavedPair && asset.uri in saved.uris && when {
                expectedKind == AlbumAssetKind.DYNAMIC -> false
                expectedKind == AlbumAssetKind.ANIMATED -> asset.kind == AlbumAssetKind.ANIMATED &&
                    if (mode == AlbumMode.GIF) asset.mimeType == "image/gif"
                    else if (unknown) asset.mimeType == "video/mp4"
                    else (asset.mimeType.startsWith("image/") || asset.mimeType == "video/mp4")
                expectedKind == AlbumAssetKind.LIVE ->
                    if (mode == AlbumMode.GIF) asset.kind == AlbumAssetKind.ANIMATED && asset.mimeType == "image/gif"
                    else asset.kind == AlbumAssetKind.LIVE && asset.embeddedMotion && asset.mimeType == "image/jpeg"
                else -> asset.mimeType.startsWith("image/")
            }
        }
    }

    /** Keep pairing and per-file types when only part of an album could be deleted. */
    fun retainFiles(saved: SavedVideo, remaining: List<String>): SavedVideo {
        require(remaining.isNotEmpty())
        val assets = saved.albumAssets.mapNotNull { asset ->
            when {
                asset.uri in remaining -> asset.copy(motionUri = asset.motionUri.takeIf { it in remaining }.orEmpty())
                asset.motionUri.isNotBlank() && asset.motionUri in remaining ->
                    SavedAlbumAsset(asset.motionUri, "video/mp4", AlbumAssetKind.LIVE)
                else -> null
            }
        }
        val types = remaining.map(saved::mimeTypeFor).distinct()
        return saved.copy(uri = remaining.first(), uris = remaining, coverUri = remaining.first(),
            mimeType = types.singleOrNull() ?: if (types.all { it.startsWith("image/") }) "image/*" else "*/*",
            albumAssets = assets)
    }
}
