package com.local.douyinsaver

import android.content.Context

/** Store tenths as integers so persisted timing never loses the user's decimal precision. */
internal class AlbumTimingPreferences(context: Context, namespace: String = "") {
    private val preferences = context.applicationContext.getSharedPreferences(namespace + "download_options", Context.MODE_PRIVATE)

    fun readStaticSeconds(): Double {
        val tenths = runCatching { preferences.getInt("static_image_duration_tenths", 0) }.getOrDefault(0)
        if (tenths in 1..1200) return tenths / 10.0
        val legacy = runCatching { preferences.getInt("image_seconds", 3) }.getOrDefault(3)
        return legacy.takeIf { it in 1..120 }?.toDouble() ?: 3.0
    }

    fun updateStaticSeconds(value: Double): Double {
        val tenths = (AlbumTiming.milliseconds(value) / 100L).toInt()
        preferences.edit().putInt("static_image_duration_tenths", tenths).apply()
        return tenths / 10.0
    }
}
