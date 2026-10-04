package com.local.douyinsaver

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Source selection never modifies signed CDN links or substitutes an unrelated work. */
object WatermarkSources {
    fun videoSources(playUrls: List<String>, downloadUrls: List<String> = emptyList(),
        unclassifiedUrls: List<String> = emptyList(), mediaIds: List<String> = emptyList()): List<MediaSource> {
        val play = allowed(playUrls)
        val download = allowed(downloadUrls)
        val sources = mutableListOf<MediaSource>()
        (play + download + allowed(unclassifiedUrls)).distinct().forEach { sources += MediaSource(it, WatermarkMode.ORIGINAL) }
        (play + download + allowed(unclassifiedUrls)).distinct().forEach { url ->
            val entry = playbackVariants(url)
            if (entry.isNotEmpty()) sources += entry
            else explicitMode(url)?.let { sources += MediaSource(url, it) }
        }
        // An independent playback address can be selected without rewriting a signed download URL.
        // A lone unclassified CDN URL is not sufficient evidence for the clean option.
        download.filterNot { playbackVariants(it).isNotEmpty() || ambiguousSwitch(it) }.forEach { url ->
            sources += MediaSource(url, explicitMode(url) ?: WatermarkMode.WATERMARKED)
        }
        if (download.isNotEmpty()) play.filterNot {
            it in download || playbackVariants(it).isNotEmpty() || ambiguousSwitch(it)
        }.forEach { url ->
            sources += MediaSource(url, explicitMode(url) ?: WatermarkMode.CLEAN)
        }
        // Only opaque media identifiers read from this work's video address may form platform entries.
        mediaIds.filter(::isMediaId).distinct().take(16).forEach { id ->
            sources += playbackVariants("https://aweme.snssdk.com/aweme/v1/play/?video_id=$id")
        }
        return unique(sources)
    }

    fun imageSources(displayUrls: List<String>, downloadUrls: List<String> = emptyList(),
        unclassifiedUrls: List<String> = emptyList()): List<MediaSource> {
        val display = allowed(displayUrls)
        val download = allowed(downloadUrls)
        val sources = mutableListOf<MediaSource>()
        val original = (display + download + allowed(unclassifiedUrls)).distinct()
        original.forEach { sources += MediaSource(it, WatermarkMode.ORIGINAL) }
        original.forEach { url ->
            explicitMode(url)?.let { sources += MediaSource(url, it) }
        }
        // These fields are extracted from the exact work, with bitrate variants paired by image URI.
        // A normal gallery source does not require a separate marked download derivative to exist.
        // This selects the displayed rendition; it does not erase a logo embedded by its author.
        val cleanDisplay = display.filterNot { ambiguousSwitch(it) || explicitMode(it) == WatermarkMode.WATERMARKED }
        val displayedRequests = cleanDisplay.map { withoutFragment(URI(it)) }.toSet()
        download.filterNot { ambiguousSwitch(it) || withoutFragment(URI(it)) in displayedRequests }.forEach { url ->
            sources += MediaSource(url, explicitMode(url) ?: WatermarkMode.WATERMARKED)
        }
        cleanDisplay.forEach { url ->
            sources += MediaSource(url, WatermarkMode.CLEAN)
        }
        return unique(sources)
    }

    fun available(content: ParsedVideo, mode: WatermarkMode): Boolean =
        if (content.isAlbum) content.images.all { choose(it.url, it.mediaSources, mode) != null }
        else candidates(content, mode).isNotEmpty()

    /** All alternatives remain in the requested rendition; direct CDN URLs precede playback entries. */
    fun candidates(content: ParsedVideo, mode: WatermarkMode): List<String> {
        if (content.isAlbum) return emptyList()
        val known = content.mediaSources.filter { it.mode == mode }.map { it.url }
        val original = content.mediaUrl.takeIf { mode == WatermarkMode.ORIGINAL }
        // Parsed sources are already bounded; do not truncate an appended platform entry behind CDN variants.
        return allowed(listOfNotNull(original) + known, 641).sortedBy { if (MediaUrls.isPlaybackEntry(it)) 1 else 0 }
    }

    fun select(content: ParsedVideo, mode: WatermarkMode): ParsedVideo {
        if (content.isAlbum) {
            val images = content.images.mapIndexed { index, image ->
                val url = choose(image.url, image.mediaSources, mode)
                    ?: throw IllegalArgumentException("第 ${index + 1} 张图片暂未读取到可用地址，请重新解析")
                image.copy(url = url)
            }
            return content.copy(images = images, coverUrl = images.first().url)
        }
        val url = candidates(content, mode).firstOrNull()
            ?: throw IllegalArgumentException("暂未读取到可用的视频地址，请重新解析")
        return content.copy(mediaUrl = url)
    }

    /** Check every real request, including fresh redirects that were not seen by the header probe. */
    fun requireSelectedUrl(url: String, mode: WatermarkMode, sources: List<MediaSource>): URI {
        val uri = MediaUrls.requireAllowed(url)
        if (mode == WatermarkMode.ORIGINAL) return uri
        // ORIGINAL carries no rendition evidence and must not collide with a classified source.
        val request = withoutFragment(uri)
        val conflicting = sources.any { source ->
            source.mode != mode && source.mode != WatermarkMode.ORIGINAL &&
                runCatching { withoutFragment(URI(source.url)) == request }.getOrDefault(false)
        }
        val explicit = explicitMode(url)
        val markedEntry = uri.host.equals("aweme.snssdk.com", true) && uri.rawPath == "/aweme/v1/playwm/"
        require(!conflicting && !ambiguousSwitch(url) &&
            (explicit == null || explicit == mode) && !(mode == WatermarkMode.CLEAN && markedEntry)) {
            "媒体地址与解析结果不一致，请重新解析"
        }
        // An unseen, trusted CDN redirect is allowed; this check never classifies it as a clean source.
        return uri
    }

    /** Null means the retained original source has no independently confirmed watermark status. */
    fun actualMode(content: ParsedVideo, requested: WatermarkMode): WatermarkMode? {
        if (requested == WatermarkMode.ORIGINAL) return null
        if (requested == WatermarkMode.CLEAN) return WatermarkMode.CLEAN.takeIf { available(content, requested) }
        val classified = if (content.isAlbum) content.images.all { image ->
            image.mediaSources.any { it.mode == requested && MediaUrls.isAllowed(it.url) }
        } else content.mediaSources.any { it.url == content.mediaUrl && it.mode == requested && MediaUrls.isAllowed(it.url) }
        return requested.takeIf { classified }
    }

    private fun choose(original: String, sources: List<MediaSource>, mode: WatermarkMode): String? =
        sources.firstOrNull { it.mode == mode && it.url.length <= 32_768 && MediaUrls.isAllowed(it.url) }?.url
            ?: original.takeIf { mode == WatermarkMode.ORIGINAL && MediaUrls.isAllowed(it) }

    private fun allowed(urls: List<String>, limit: Int = 64) = urls.filter {
        it.length <= 32_768 && MediaUrls.isAllowed(it)
    }.distinct().take(limit)
    private fun unique(sources: List<MediaSource>) = sources.distinctBy { it.url to it.mode }.take(640)
    private fun withoutFragment(uri: URI) = URI(uri.toString().substringBefore('#'))

    private fun ambiguousSwitch(url: String): Boolean {
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        return query.split('&').any { decode(it.substringBefore('='))?.lowercase(Locale.ROOT) in listOf("watermark", "water_mark", "wm") } &&
            explicitMode(url) == null
    }

    private fun explicitMode(url: String): WatermarkMode? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val parts = uri.rawQuery.orEmpty().split('&')
        val switches = parts.mapNotNull { part ->
            val key = decode(part.substringBefore('='))?.lowercase(Locale.ROOT)
            if (key !in listOf("watermark", "water_mark", "wm")) null
            else decode(part.substringAfter('=', ""))?.lowercase(Locale.ROOT)
        }.distinct()
        val path = decode(uri.rawPath).orEmpty().lowercase(Locale.ROOT)
        val markedTransform = Regex("(?:~|/|[,:])(?:watermark|watermark[-_]v\\d+)(?:[,:/.]|$)").containsMatchIn(path) ||
            Regex("~tplv-dy-aweme-images(?:-v\\d+)?-watermark(?:[-_]v\\d+)?(?:[,:/.]|$)").containsMatchIn(path)
        if (switches.size == 1) {
            if (switches.single() in listOf("1", "true", "yes")) return WatermarkMode.WATERMARKED
            if (switches.single() in listOf("0", "false", "no")) return WatermarkMode.CLEAN.takeUnless { markedTransform }
        }
        if (switches.isNotEmpty()) return null
        // Explicit CDN transform commands, not a substring of a host or arbitrary filename.
        if (markedTransform) return WatermarkMode.WATERMARKED
        return null
    }

    private fun playbackVariants(url: String): List<MediaSource> {
        val uri = runCatching { MediaUrls.requireAllowed(url) }.getOrNull() ?: return emptyList()
        if (!uri.host.equals("aweme.snssdk.com", true) ||
            uri.rawPath !in listOf("/aweme/v1/play/", "/aweme/v1/playwm/")) return emptyList()
        if (ambiguousSwitch(url)) return emptyList()
        val parts = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
        val ids = parts.filter { decode(it.substringBefore('=')) == "video_id" }
        val id = ids.singleOrNull()?.substringAfter('=', "")?.let(::decode) ?: return emptyList()
        if (!isMediaId(id)) return emptyList()
        // These two public playback endpoints choose the platform watermark rendition.
        // Preserve encoded values and all unrelated parameters byte-for-byte.
        val rest = parts.filterNot { decode(it.substringBefore('=')) in listOf("watermark", "water_mark", "wm") }
        fun address(path: String, watermark: String): String =
            "${uri.scheme}://${uri.rawAuthority}$path?${(if (rest.size == parts.size) rest else rest + "watermark=$watermark").joinToString("&")}" +
                (uri.rawFragment?.let { "#$it" } ?: "")
        val clean = if (uri.rawPath == "/aweme/v1/play/" && explicitMode(url) != WatermarkMode.WATERMARKED) url
            else address("/aweme/v1/play/", "0")
        val marked = if (uri.rawPath == "/aweme/v1/playwm/") url else address("/aweme/v1/playwm/", "1")
        return listOf(MediaSource(clean, WatermarkMode.CLEAN), MediaSource(marked, WatermarkMode.WATERMARKED))
    }

    private fun decode(raw: String?) = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    private fun isMediaId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{10,256}")) && id.any(Char::isLetter)
}
