package com.local.douyinsaver

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Keep the selected rendition; a large file never triggers a lower-quality retry. */
internal object GifSharePolicy {
    data class Profile(val edge: Int, val fps: Int, val colors: Int)
    fun profiles(quality: GifExportQuality): List<Profile> =
        listOf(Profile(quality.maximumEdge, quality.maximumFramesPerSecond, quality.maximumColors))

    fun canvas(width: Int, height: Int, profile: Profile): Pair<Int, Int> {
        GifConversionPolicy.canvas(width, height) // Validate the original geometry.
        val edge = maxOf(width, height)
        if (edge <= profile.edge) return width to height
        return maxOf(1, (width.toLong() * profile.edge / edge).toInt()) to
            maxOf(1, (height.toLong() * profile.edge / edge).toInt())
    }

    fun frameRate(source: Double, profile: Profile): Double =
        minOf(source.takeIf { it.isFinite() && it > 0 } ?: profile.fps.toDouble(), profile.fps.toDouble())

    suspend fun encode(quality: GifExportQuality, attempt: suspend (Profile, Long) -> File): File {
        currentCoroutineContext().ensureActive()
        return attempt(profiles(quality).single(), GifConversionPolicy.MAX_GIF_BYTES)
    }
}
