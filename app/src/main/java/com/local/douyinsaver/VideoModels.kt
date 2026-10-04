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
}

data class ParsedImage(val url: String, val width: Int = 0, val height: Int = 0,
    val mediaSources: List<MediaSource> = emptyList())

/** Internal source classification and compatibility with existing download records. */
enum class WatermarkMode(val label: String) {
    WATERMARKED("有水印"), CLEAN("无水印"), ORIGINAL("原始版本")
}
/** Separate source addresses, rather than editing an already downloaded frame. */
data class MediaSource(val url: String, val mode: WatermarkMode)

enum class AlbumMode(val label: String) { IMAGES("保存图片"), VIDEO("图片与 BGM 合成视频") }
enum class NamingRule(val label: String) { ID("作品编号"), TITLE("视频标题"), DATE_TITLE("日期与标题") }
data class DownloadOptions(
    val albumMode: AlbumMode = AlbumMode.IMAGES,
    val imageSeconds: Int = 3,
    val fileName: String = "",
    val namingRule: NamingRule = NamingRule.DATE_TITLE,
    val watermarkMode: WatermarkMode = WatermarkMode.CLEAN,
)

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
)

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
