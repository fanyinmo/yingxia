package com.local.douyinsaver

/** A static album must belong to the requested work and retain its complete image order. */
object AlbumCandidatePolicy {
    data class ImageCandidate(val urls: List<String>, val width: Int = 0, val height: Int = 0)

    fun ready(
        expectedId: String,
        pageUrl: String,
        ownerId: String,
        title: String,
        imageCandidates: List<ImageCandidate>,
        bgmCandidates: List<String> = emptyList(),
        bgmDuration: Double = 0.0,
    ): ParsedVideo? {
        if (ShareLinks.videoId(pageUrl) != expectedId || ownerId != expectedId) return null
        if (imageCandidates.isEmpty() || imageCandidates.size > MAX_IMAGES) return null
        val images = imageCandidates.map { image ->
            val url = image.urls.firstOrNull { it.length <= 32_768 && MediaUrls.isAllowed(it) }
                ?: return null // Do not silently turn an incomplete album into a successful download.
            ParsedImage(url, image.width.coerceIn(0, 32_768), image.height.coerceIn(0, 32_768))
        }
        val music = bgmCandidates.asSequence().mapNotNull(::secureBgmUrl).firstOrNull().orEmpty()
        return ParsedVideo(
            id = expectedId,
            title = title.trim().take(500).ifBlank { "抖音图集 $expectedId" },
            mediaUrl = "",
            durationSeconds = 0.0,
            width = images.first().width,
            height = images.first().height,
            coverUrl = images.first().url,
            images = images,
            bgmUrl = music,
            bgmDurationSeconds = bgmDuration.takeIf { it.isFinite() && it in 0.0..18_000.0 } ?: 0.0,
        )
    }

    /** Upgrade only absolute HTTP media addresses whose resulting TLS origin is already trusted. */
    fun secureBgmUrl(value: String): String? {
        if (value.isBlank() || value.length > 32_768) return null
        val secure = when {
            value.startsWith("https://", true) -> value
            value.startsWith("http://", true) -> "https:" + value.substringAfter(':')
            else -> return null // A URI/content identifier is not a downloadable URL.
        }
        return secure.takeIf(MediaUrls::isAllowed)
    }

    fun musicReadyOrGraceElapsed(candidate: ParsedVideo, missingMusicSince: Long?, now: Long): Boolean =
        !candidate.isAlbum || candidate.bgmUrl.isNotBlank() ||
            (missingMusicSince != null && now >= missingMusicSince && now - missingMusicSince >= MUSIC_GRACE_MS)

    const val MAX_IMAGES = 100
    const val MUSIC_GRACE_MS = 6_000L
}
