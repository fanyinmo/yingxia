package com.local.douyinsaver

data class ParsedVideo(
    val id: String,
    val title: String,
    val mediaUrl: String,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val coverUrl: String = "",
    val images: List<ParsedImage> = emptyList(),
    val bgmUrl: String = "",
    val bgmDurationSeconds: Double = 0.0,
    val mediaSources: List<MediaSource> = emptyList(),
) {
    val isAlbum: Boolean get() = images.isNotEmpty()
    val hasDynamicAlbumAssets: Boolean get() = images.any { it.kind != AlbumAssetKind.STATIC || it.motion != null }
}

data class ParsedImage(val url: String, val width: Int = 0, val height: Int = 0,
    val mediaSources: List<MediaSource> = emptyList(),
    val kind: AlbumAssetKind = AlbumAssetKind.STATIC,
    val mimeType: String = "",
    val motion: ParsedMotion? = null,
    val imageKey: String = "")

enum class AlbumAssetKind(val label: String) { STATIC("图片"), ANIMATED("动图"), LIVE("动态素材"), DYNAMIC("动态素材") }

/** The motion belongs to this exact album image; the soundtrack is a separate resource. */
data class ParsedMotion(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationSeconds: Double = 0.0,
    val mediaSources: List<MediaSource> = emptyList(),
    val embeddedInPhoto: Boolean = false,
)

/** One album item may publish an image and its matching short video. */
data class SavedAlbumAsset(
    val uri: String,
    val mimeType: String,
    val kind: AlbumAssetKind = AlbumAssetKind.STATIC,
    val motionUri: String = "",
    val embeddedMotion: Boolean = false,
    val sourceIndex: Int = -1,
)

/** Internal source classification and compatibility with existing download records. */
enum class WatermarkMode(val label: String) {
    WATERMARKED("有水印"), CLEAN("无水印"), ORIGINAL("原始版本")
}
/** Separate source addresses, rather than editing an already downloaded frame. */
data class MediaSource(val url: String, val mode: WatermarkMode)

enum class AlbumMode(val label: String) { IMAGES("保存素材"), VIDEO("素材与 BGM 合成视频"), GIF("保存 GIF"), COVERS("保存静态封面"),
    LIVE_PHOTOS("已移除格式"), MOTION_VIDEOS("保存为无声动图"), CONVERT_TO_LIVE("已移除格式") }
enum class AlbumMotionPhase { IDLE, READING, NEEDS_VERIFICATION, AVAILABLE, UNAVAILABLE }
data class AlbumMotionReadState(val phase: AlbumMotionPhase = AlbumMotionPhase.IDLE, val detail: String = "")
enum class NamingRule(val label: String) { ID("作品编号"), TITLE("视频标题"), DATE_TITLE("日期与标题") }
data class DownloadOptions(
    val albumMode: AlbumMode = AlbumMode.IMAGES,
    val imageSeconds: Int = 3,
    val fileName: String = "",
    val namingRule: NamingRule = NamingRule.DATE_TITLE,
    val watermarkMode: WatermarkMode = WatermarkMode.CLEAN,
    val gifStartSeconds: Float = 0f,
    val gifDurationSeconds: Float = 6f,
    /** null uses each motion's real duration and the static-image default. */
    val itemDurationSeconds: Double? = null,
    /** Empty means the whole album; non-empty indices retain their original order. */
    val selectedImageIndices: List<Int> = emptyList(),
    /** Persisted decimal static-image default; null retains legacy imageSeconds callers. */
    val staticImageSeconds: Double? = null,
    /** Explicit export tradeoff; defaults keep existing API callers' quality unchanged. */
    val gifExportQuality: GifExportQuality = GifExportQuality.HIGH_QUALITY,
)

data class DurationAdjustment(val index: Int, val originalSeconds: Double, val requestedSeconds: Double)

data class DownloadFolder(val treeUri: String, val label: String)

data class SavedVideo(
    val id: String,
    val title: String,
    val uri: String,
    val bytes: Long,
    val locationLabel: String = DownloadStorage.DEFAULT_LOCATION,
    val savedAt: Long = System.currentTimeMillis(),
    val mimeType: String = "video/mp4",
    val uris: List<String> = listOf(uri),
    val coverUri: String = "",
    val fileName: String = "",
    val isAlbum: Boolean = false,
    val watermarkMode: WatermarkMode? = null,
    val albumAssets: List<SavedAlbumAsset> = emptyList(),
    val exportMode: String = "",
    val gifStartMs: Long = 0L,
    val gifDurationMs: Long = 0L,
    val albumSourceCount: Int = 0,
    val albumTimingSignature: String = "",
    val gifExportQuality: String = "",
) {
    fun mimeTypeFor(targetUri: String): String = albumAssets.firstOrNull { it.uri == targetUri }?.mimeType
        ?: "video/mp4".takeIf { albumAssets.any { it.motionUri.isNotBlank() && it.motionUri == targetUri } }
        ?: mimeType
}

enum class QueueStatus(val label: String) {
    QUEUED("待解析"), PARSING("解析中"), READY("已解析"), RUNNING("保存中"),
    FAILED("失败"), DONE("已保存"), CANCELLED("已取消")
}
data class QueueTask(
    val key: String,
    val source: String,
    val title: String = "待解析作品",
    val status: QueueStatus = QueueStatus.QUEUED,
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TaskStage { IDLE, RESOLVING, BROWSING, VERIFYING, READY, DOWNLOADING, CANCELLING, SAVING, DONE, FAILED, CANCELLED }

internal fun AlbumMode.isSupportedExport(): Boolean = this != AlbumMode.LIVE_PHOTOS && this != AlbumMode.CONVERT_TO_LIVE
