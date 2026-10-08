package com.local.douyinsaver

/** An album must belong to the requested work and retain its complete image order. */
object AlbumCandidatePolicy {
    data class MotionCandidate(
        val playUrls: List<String> = emptyList(),
        val downloadUrls: List<String> = emptyList(),
        val mediaIds: List<String> = emptyList(),
        val width: Int = 0,
        val height: Int = 0,
        val durationSeconds: Double = 0.0,
        val displayPlaybackUrls: List<String> = emptyList(),
    )

    data class ImageCandidate(
        val urls: List<String>,
        val width: Int = 0,
        val height: Int = 0,
        val displayUrls: List<String> = emptyList(),
        val downloadUrls: List<String> = emptyList(),
        val kind: AlbumAssetKind = AlbumAssetKind.STATIC,
        val mimeType: String = "",
        val motion: MotionCandidate? = null,
        val imageKey: String = "",
    )

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
            // Preserve the source field distinction; an official download image can
            // have different watermarking from the image displayed in the gallery.
            val sources = WatermarkSources.imageSources(image.displayUrls, image.downloadUrls, image.urls)
            val url = image.urls.firstOrNull { it.length <= 32_768 && MediaUrls.isAllowed(it) }
                ?: sources.firstOrNull()?.url
                ?: return null // Do not silently turn an incomplete album into a successful download.
            val motion = image.motion?.let { candidate ->
                val videoSources = WatermarkSources.videoSources(candidate.playUrls, candidate.downloadUrls,
                    mediaIds = candidate.mediaIds, displayPlaybackUrls = candidate.displayPlaybackUrls)
                val videoUrl = videoSources.firstOrNull { it.mode == WatermarkMode.CLEAN }?.url
                    ?: videoSources.firstOrNull()?.url
                videoUrl?.let { ParsedMotion(it, candidate.width.coerceIn(0, 32_768),
                    candidate.height.coerceIn(0, 32_768),
                    candidate.durationSeconds.takeIf { value -> value.isFinite() && value in 0.0..18_000.0 } ?: 0.0,
                    videoSources) }
            }
            ParsedImage(url, image.width.coerceIn(0, 32_768), image.height.coerceIn(0, 32_768), sources,
                if (image.motion != null && image.kind == AlbumAssetKind.STATIC) AlbumAssetKind.DYNAMIC else image.kind,
                image.mimeType.takeIf { it in IMAGE_MIME_TYPES }.orEmpty(), motion, image.imageKey.take(2048))
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
    private val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/heif", "image/heic")
}
