package com.local.douyinsaver

import java.net.URI

/** One official note-route attempt after the exact video route repeatedly returns its empty error page. */
internal object AlbumPageFallback {
    fun initialPageUrl(expectedId: String, initialUrl: String?): String = initialUrl
        ?.takeIf { trustedMobilePage(expectedId, it) != null }
        ?: "https://www.iesdouyin.com/share/video/$expectedId/"

    fun isNotePageUrl(expectedId: String, pageUrl: String): Boolean =
        trustedMobilePage(expectedId, pageUrl)?.path?.matches(Regex("/share/note/${Regex.escape(expectedId)}/?")) == true

    private fun trustedMobilePage(expectedId: String, pageUrl: String): URI? {
        if (pageUrl.length > 16_384) return null
        val page = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        return page.takeIf {
            it.scheme.equals("https", true) && it.userInfo == null && it.port in listOf(-1, 443) &&
                ShareLinks.isShareHost(it.host) && ShareLinks.videoId(pageUrl) == expectedId &&
                it.path.matches(Regex("/share/(?:video|note|slides)/${Regex.escape(expectedId)}/?"))
        }
    }

    fun afterEmptyVideoPage(expectedId: String, pageUrl: String, routeMatches: Boolean,
        observations: Int, triedNotePage: Boolean, captchaVisible: Boolean): Boolean {
        if (triedNotePage || !routeMatches || captchaVisible || observations < 3) return false
        val page = runCatching { URI(pageUrl) }.getOrNull() ?: return false
        return page.scheme.equals("https", true) && page.userInfo == null && page.port in listOf(-1, 443) &&
            ShareLinks.isShareHost(page.host) && ShareLinks.videoId(pageUrl) == expectedId &&
            page.path.matches(Regex("/(?:share/)?video/${Regex.escape(expectedId)}/?"))
    }
}
