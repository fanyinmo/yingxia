package com.local.douyinsaver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** A still gallery becomes one ordered looping GIF. Sources are never modified or flattened. */
internal class AlbumGifComposer(private val quality: GifExportQuality = GifExportQuality.HIGH_QUALITY) {
    suspend fun compose(
        images: List<LocalAlbumImage>,
        itemDurationMs: Long,
        destination: File,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = compose(images, List(images.size) { itemDurationMs }, destination, onProgress)

    suspend fun compose(
        images: List<LocalAlbumImage>,
        durationsMs: List<Long>,
        destination: File,
        onProgress: suspend (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        AlbumMediaPolicy.validateImages(images.size)
        require(images.size >= 2) { "至少需要两张静态图片才能制作切换 GIF" }
        require(durationsMs.size == images.size && durationsMs.all { it in GifConversionPolicy.MIN_DURATION_MS..Long.MAX_VALUE / 1000L }) {
            "每张图片的 GIF 播放时长至少为 0.1 秒"
        }
        require(!destination.exists() && destination.parentFile?.isDirectory == true) { "GIF 输出文件已存在或保存目录不可用" }
        require(images.none { it.file.canonicalFile == destination.canonicalFile }) { "GIF 输出不能覆盖原图片" }
        val coroutine = currentCoroutineContext()
        val verified = images.map { image ->
            coroutine.ensureActive()
            AlbumMediaValidation.image(image.file).also {
                require(!it.animated) { "图片切换 GIF 仅适用于静态图集；动图请使用动态资源保存或 GIF 转换" }
            }
        }
        val (width, height) = GifConversionPolicy.canvas(verified.first().width, verified.first().height, quality)
        val pixels = IntArray(width * height)
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var owned = false
        var completed = false
        try {
            coroutine.ensureActive()
            owned = destination.createNewFile()
            require(owned) { "GIF 输出文件已存在，请使用新的文件名" }
            destination.outputStream().buffered(64 * 1024).use { output ->
                val encoder = GifEncoder(output, width, height, maximumColors = quality.maximumColors, checkpoint = { coroutine.ensureActive() })
                onProgress(0L, verified.size.toLong())
                verified.forEachIndexed { index, image ->
                    coroutine.ensureActive()
                    val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(image.file)) { decoder, info, _ ->
                        require(!info.isAnimated) { "第 ${index + 1} 张是动图，不能当作静态画面" }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = minOf(1.0, width.toDouble() / info.size.width, height.toDouble() / info.size.height)
                        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    }
                    try {
                        coroutine.ensureActive()
                        canvas.drawColor(Color.BLACK)
                        val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
                        val left = (width - source.width * scale) / 2f
                        val top = (height - source.height * scale) / 2f
                        canvas.drawBitmap(source, null, RectF(left, top, left + source.width * scale, top + source.height * scale), paint)
                        frame.getPixels(pixels, 0, width, 0, 0, width, height)
                        // GIF's unsigned 16-bit delay is per frame; a long hold is split without
                        // allocating duplicate bitmaps or truncating the requested interval.
                        var delay = (durationsMs[index] + 5L) / 10L
                        while (delay > 0) {
                            coroutine.ensureActive()
                            val part = minOf(delay, 65_535L).toInt()
                            encoder.addFrame(pixels, part)
                            delay -= part
                        }
                    } finally { source.recycle() }
                    onProgress(index + 1L, verified.size.toLong())
                }
                encoder.finish()
            }
            coroutine.ensureActive()
            require(destination.length() in 1..GifConversionPolicy.MAX_GIF_BYTES) { "GIF 文件为空或超过大小上限" }
            completed = true
            destination
        } finally {
            frame.recycle()
            if (owned && !completed) destination.delete()
        }
    }
}
