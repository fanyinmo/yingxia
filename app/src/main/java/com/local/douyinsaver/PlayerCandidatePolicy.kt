package com.local.douyinsaver

/** A page path alone does not associate the first DOM video with the requested work. */
object PlayerCandidatePolicy {
    fun ready(
        expectedId: String,
        pageUrl: String,
        ownerId: String,
        mediaUrl: String,
        title: String,
        readyState: Int,
        width: Int,
        height: Int,
        duration: Double,
    ): ParsedVideo? {
        if (ShareLinks.videoId(pageUrl) != expectedId || ownerId != expectedId) return null
        if (!MediaUrls.isAllowed(mediaUrl)) return null
        if (readyState < 1 || width <= 0 || height <= 0 || !duration.isFinite() || duration <= 0) return null
        return ParsedVideo(
            id = expectedId,
            title = title.trim().take(500).ifBlank { "抖音视频 $expectedId" },
            mediaUrl = mediaUrl,
            durationSeconds = duration,
            width = width,
            height = height,
        )
    }
}
