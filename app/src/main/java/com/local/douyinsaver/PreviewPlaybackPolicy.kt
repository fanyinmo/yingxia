package com.local.douyinsaver

import java.util.Locale

internal object PreviewPlaybackPolicy {
    const val DEFAULT_SEEK_SECONDS = 10
    const val MIN_SEEK_SECONDS = 1
    const val MAX_SEEK_SECONDS = 120

    fun seekSeconds(value: Int): Int = value.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)

    fun formatTime(positionMs: Long): String {
        val seconds = positionMs.coerceAtLeast(0L) / 1000L
        return if (seconds >= 3600L) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    /** Extreme/unknown metadata can shape the viewport, never crop the actual FIT video. */
    fun aspectRatio(width: Int, height: Int): Float =
        if (width in 1..32768 && height in 1..32768) (width.toFloat() / height).coerceIn(0.25f, 4f)
        else 9f / 16f

    fun seekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val position = positionMs.coerceIn(0L, durationMs)
        return if (deltaMs >= 0L) {
            if (deltaMs >= durationMs - position) durationMs else position + deltaMs
        } else if (deltaMs <= -position) 0L else position + deltaMs
    }
}
