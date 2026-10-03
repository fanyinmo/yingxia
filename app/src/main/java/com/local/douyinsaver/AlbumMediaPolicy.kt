package com.local.douyinsaver

/** Limits also apply to responses with missing or misleading Content-Length headers. */
internal object AlbumMediaPolicy {
    const val MAX_IMAGES = 100
    const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    const val MAX_ALBUM_BYTES = 512L * 1024 * 1024
    const val MAX_AUDIO_BYTES = 64L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_IMAGE_EDGE = 32_768
    const val MAX_IMAGE_PIXELS = 80_000_000L

    fun validateImages(count: Int) {
        require(count in 1..MAX_IMAGES) { "图集需包含 1 至 $MAX_IMAGES 张图片" }
    }

    fun durationMs(count: Int, imageSeconds: Int): Long {
        validateImages(count)
        require(imageSeconds in 1..15) { "每张图片的播放时长应为 1 至 15 秒" }
        val duration = count.toLong() * imageSeconds * 1_000L
        require(duration <= 900_000L) { "合成视频总时长超过 15 分钟，请缩短每张图片的播放时长" }
        return duration
    }

    fun imageExtension(mime: String, width: Int, height: Int): String {
        require(width in 1..MAX_IMAGE_EDGE && height in 1..MAX_IMAGE_EDGE &&
            width.toLong() * height <= MAX_IMAGE_PIXELS) { "图片尺寸无效或过大，请重新解析" }
        return when (mime.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heif", "image/heic" -> "heic"
            "image/avif" -> "avif"
            else -> error("服务器没有返回手机支持的图片，请重新解析")
        }
    }

    /** All frames share one codec-friendly canvas and retain their aspect ratio by letterboxing. */
    fun canvas(width: Int, height: Int): Pair<Int, Int> = when {
        width <= 0 || height <= 0 -> throw IllegalArgumentException("图片尺寸无效")
        width == height -> 720 to 720
        width < height -> 720 to 1280
        else -> 1280 to 720
    }

    fun fitsBytes(size: Long, maximum: Long): Boolean = size in 1..maximum

    // Negative presentation timestamps are valid for AAC priming; only -1 track index means EOS.
    fun samplePresent(expectedTrack: Int, actualTrack: Int, bytes: Long): Boolean =
        expectedTrack >= 0 && actualTrack == expectedTrack && bytes > 0
}
