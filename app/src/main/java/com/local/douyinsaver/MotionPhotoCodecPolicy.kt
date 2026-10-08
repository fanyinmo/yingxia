package com.local.douyinsaver

/** Applies only to the video codec inside a Motion Photo container, not general downloads.
 * Android Motion Photo 1.0, "Video container contents", allows AVC, HEVC and AV1.
 * These are codec MIME values from MediaExtractor/MediaFormat; video/mp4 is a
 * container MIME and must not be used as a substitute for a compatible video track.
 */
internal object MotionPhotoCodecPolicy {
    private val videoMimeTypes = setOf("video/avc", "video/hevc", "video/av01")

    fun requireCompatibleVideoMime(mime: String?) {
        require(mime != null && mime in videoMimeTypes) {
            "该动态资源的视频编码暂不兼容实况照片（仅支持 AVC、HEVC 或 AV1），请选择保存原始动态资源"
        }
    }
}
