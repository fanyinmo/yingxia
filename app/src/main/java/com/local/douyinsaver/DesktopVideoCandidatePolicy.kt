package com.local.douyinsaver

/** Desktop fallback is provenance evidence, not HTTP/decoding verification. */
internal object DesktopVideoCandidatePolicy {
    data class Candidate(val owner: String, val title: String, val playUrls: List<String>,
        val downloadUrls: List<String>, val mediaIds: List<String>, val displayPlaybackUrls: List<String>,
        val coverUrls: List<String>, val width: Int, val height: Int, val durationSeconds: Double)

    fun ready(expectedId: String, page: String, candidates: List<Candidate>): ParsedVideo? {
        if (!DesktopAlbumPagePolicy.isWorkPage(expectedId, page)) return null
        val owned = candidates.take(16).filter { it.owner == expectedId && it.width in 1..16384 &&
            it.height in 1..16384 && it.durationSeconds.isFinite() && it.durationSeconds > 0 }
        if (owned.isEmpty()) return null
        val sources = WatermarkSources.videoSources(owned.flatMap { it.playUrls }, owned.flatMap { it.downloadUrls },
            mediaIds = owned.flatMap { it.mediaIds }, displayPlaybackUrls = owned.flatMap { it.displayPlaybackUrls })
        val clean = sources.firstOrNull { it.mode == WatermarkMode.CLEAN } ?: return null
        val preferred = owned.maxBy { it.width.toLong() * it.height }
        return ParsedVideo(expectedId, preferred.title.trim().take(500).ifBlank { "抖音视频 $expectedId" }, clean.url,
            preferred.durationSeconds, preferred.width, preferred.height,
            coverUrl = owned.flatMap { it.coverUrls }.firstOrNull(MediaUrls::isAllowed).orEmpty(), mediaSources = sources)
    }
}
