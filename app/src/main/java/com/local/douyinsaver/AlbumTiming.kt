package com.local.douyinsaver

import kotlin.math.roundToLong

internal object AlbumTiming {
    fun signature(options: DownloadOptions): String = options.itemDurationSeconds?.let {
        "override=${milliseconds(it)}ms"
    } ?: "auto;static=${AlbumDurationUiPolicy.seconds(staticSeconds(options))}"
    fun staticSeconds(options: DownloadOptions): Double = options.staticImageSeconds ?: options.imageSeconds.toDouble()

    fun staticMilliseconds(options: DownloadOptions): Long = milliseconds(options.itemDurationSeconds ?: staticSeconds(options))
    fun milliseconds(seconds: Double): Long {
        require(seconds.isFinite() && seconds in 0.1..120.0) { "每项播放时长应为 0.1 至 120 秒" }
        return (seconds * 10.0).roundToLong() * 100L
    }

    fun durations(sourceDurationsMs: List<Long?>, options: DownloadOptions): List<Long> =
        sourceDurationsMs.map { source ->
            options.itemDurationSeconds?.let(::milliseconds) ?: source?.takeIf { it > 0 }
                ?: milliseconds(staticSeconds(options))
        }.also { values ->
            require(values.isNotEmpty() && values.sum() <= 900_000L) { "合成总时长超过 15 分钟，请缩短每项时长" }
        }

    fun adjustments(sourceDurationsMs: List<Long?>, durations: List<Long>, selectedIndices: List<Int> = emptyList()): List<DurationAdjustment> =
        sourceDurationsMs.mapIndexedNotNull { index, original ->
            original?.takeIf { kotlin.math.abs(it - durations[index]) >= 50L }?.let {
                DurationAdjustment(selectedIndices.getOrNull(index) ?: index, it / 1000.0, durations[index] / 1000.0)
            }
        }

    /** Repeat whole clips, then trim the final one. Never stretch frames or change playback speed. */
    fun segments(sourceMs: Long, requestedMs: Long): List<Long> {
        require(sourceMs > 0 && requestedMs > 0)
        require(requestedMs <= 900_000L)
        val result = mutableListOf<Long>()
        var remaining = requestedMs
        while (remaining > 0) {
            require(result.size < 10_000) { "素材片段过短，循环次数过多" }
            val next = minOf(sourceMs, remaining)
            result.add(next)
            remaining -= next
        }
        return result
    }
}

internal object AlbumSelection {
    fun indices(content: ParsedVideo, requested: List<Int>): List<Int> {
        if (requested.isEmpty()) return content.images.indices.toList()
        require(content.isAlbum && requested.distinct().size == requested.size && requested.all { it in content.images.indices }) {
            "选择的图片已失效，请重新打开预览"
        }
        return requested.sorted()
    }
    fun select(content: ParsedVideo, requested: List<Int>): ParsedVideo {
        if (requested.isEmpty()) return content
        return content.copy(images = indices(content, requested).map(content.images::get))
    }

    fun correspondingIndices(base: ParsedVideo, candidate: ParsedVideo, requested: List<Int>): List<Int> {
        if (requested.isEmpty()) return emptyList()
        require(base.id == candidate.id) { "动态资源不属于当前作品" }
        val mapped = indices(base, requested).map { sourceIndex ->
            val source = base.images[sourceIndex]
            val addresses = (source.mediaSources.map { it.url } + source.url).toSet()
            val matches = candidate.images.indices.filter { targetIndex ->
                val target = candidate.images[targetIndex]
                (source.imageKey.isNotBlank() && source.imageKey == target.imageKey) ||
                    (target.mediaSources.map { it.url } + target.url).any { it in addresses }
            }
            require(matches.size == 1) { "已读取新图集，但无法确认刚才所选图片的对应关系；请在更新后的预览中重新选择" }
            matches.single()
        }
        require(mapped.distinct().size == mapped.size) { "所选图片的对应关系存在歧义，请在更新后的预览中重新选择" }
        return mapped.sorted()
    }
}
