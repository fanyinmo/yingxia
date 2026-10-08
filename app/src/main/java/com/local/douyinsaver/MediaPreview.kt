package com.local.douyinsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

private object PreviewImages {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val slots = Semaphore(3)

    /** Decode the original drawable so GIF and animated WebP keep their frames. */
    suspend fun loadDrawable(context: Context, source: String, privateCopy: File): Drawable = withContext(Dispatchers.IO) {
        slots.withPermit {
            var decoded: Drawable? = null
            try {
                currentCoroutineContext().ensureActive()
                val uri = Uri.parse(source)
                val decodedSource = when (uri.scheme) {
                    "content" -> {
                        // AnimatedImageDrawable retains its decoder's input until GC.
                        // Close the provider input before decoding a unique private copy,
                        // so an open animation cannot hold a MediaProvider/FUSE lease.
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            privateCopy.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(count.toLong() <= GifConversionPolicy.MAX_GIF_BYTES - copied) { "预览文件超过 2 GB 上限" }
                                    output.write(buffer, 0, count)
                                    copied += count
                                }
                                require(copied > 0) { "预览文件为空" }
                            }
                        } ?: error("无法读取预览文件")
                        currentCoroutineContext().ensureActive()
                        ImageDecoder.createSource(privateCopy)
                    }
                    "https" -> ImageDecoder.createSource(ByteBuffer.wrap(readImage(MediaUrls.requireAllowed(source).toString())))
                    else -> error("Unsupported image source")
                }
                decoded = ImageDecoder.decodeDrawable(decodedSource) { decoder, info, _ ->
                    val ratio = max(info.size.width, info.size.height) / 1280f
                    if (ratio > 1f) decoder.setTargetSize((info.size.width / ratio).roundToInt().coerceAtLeast(1),
                        (info.size.height / ratio).roundToInt().coerceAtLeast(1))
                }
                currentCoroutineContext().ensureActive()
                checkNotNull(decoded)
            } catch (failure: Throwable) {
                releasePreviewDrawable(decoded)
                privateCopy.delete()
                throw failure
            }
        }
    }

    suspend fun load(context: Context, source: String, video: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        if (source.isBlank()) return@withContext null
        val key = "$video:$source"
        cache.get(key)?.let { return@withContext it }
        slots.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val result = runCatching {
                val uri = Uri.parse(source)
                if (video && uri.scheme == "content") {
                    val reader = MediaMetadataRetriever()
                    try {
                        reader.setDataSource(context, uri)
                        reader.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                    } finally { reader.release() }
                } else if (uri.scheme == "content") {
                    decode(ImageDecoder.createSource(context.contentResolver, uri))
                } else if (uri.scheme == "https" && MediaUrls.isAllowed(source)) {
                    decode(ImageDecoder.createSource(ByteBuffer.wrap(readImage(source))))
                } else null
            }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (result != null) cache.put(key, result)
            result
        }
    }

    private fun decode(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val ratio = max(info.size.width, info.size.height) / 720f
        if (ratio > 1f) decoder.setTargetSize((info.size.width / ratio).roundToInt().coerceAtLeast(1),
            (info.size.height / ratio).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    private suspend fun readImage(source: String): ByteArray {
        var url = source
        repeat(5) {
            currentCoroutineContext().ensureActive()
            val current = MediaUrls.requireAllowed(url)
            val connection = current.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 12_000
                connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                connection.setRequestProperty("Referer", "https://www.douyin.com/")
                val code = connection.responseCode
                if (code in 300..399) { url = MediaUrls.redirect(current, connection.getHeaderField("Location")); return@repeat }
                check(code == 200 && connection.contentLengthLong <= 20L * 1024 * 1024)
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 20 * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally { connection.disconnect() }
        }
        error("Preview unavailable")
    }
}

private fun releasePreviewDrawable(drawable: Drawable?) {
    (drawable as? AnimatedImageDrawable)?.let { animation ->
        animation.stop()
        animation.clearAnimationCallbacks()
    }
    drawable?.callback = null
}

@Composable
internal fun AlbumImagePreview(source: String, modifier: Modifier = Modifier, description: String, expectAnimation: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val owner = LocalActivity.current as? LifecycleOwner
    var attempt by remember(source) { mutableIntStateOf(0) }
    var drawable by remember(source, attempt) { mutableStateOf<Drawable?>(null) }
    var failed by remember(source, attempt) { mutableStateOf(false) }
    var playRequested by remember(source, attempt) { mutableStateOf(true) }
    // Every attempt owns one immutable file; it is never shared with another preview.
    val privateCopy = remember(context, source, attempt) { File(context.cacheDir, "album_image_preview_${UUID.randomUUID()}.cache") }
    DisposableEffect(privateCopy) {
        onDispose {
            releasePreviewDrawable(drawable)
            privateCopy.delete()
        }
    }
    LaunchedEffect(source, attempt) {
        try {
            drawable = PreviewImages.loadDrawable(context, source, privateCopy)
        } catch (cancelled: CancellationException) {
            privateCopy.delete()
            throw cancelled
        } catch (_: Exception) {
            privateCopy.delete()
            failed = true
        }
    }

    DisposableEffect(drawable, owner, playRequested) {
        val animation = drawable as? AnimatedImageDrawable
        if (playRequested && owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false) animation?.start()
        else animation?.stop()
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) animation?.stop()
            else if (event == Lifecycle.Event.ON_RESUME && playRequested) animation?.start()
        }
        owner?.lifecycle?.addObserver(listener)
        onDispose { owner?.lifecycle?.removeObserver(listener); animation?.stop() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (drawable != null) AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                modifier = Modifier.fillMaxSize(), onReset = null, onRelease = { view ->
                    releasePreviewDrawable(view.drawable)
                    view.setImageDrawable(null)
                }, update = { view ->
                    view.contentDescription = description
                    if (view.drawable !== drawable) view.setImageDrawable(drawable)
                })
            else if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("图片预览暂不可用", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { attempt++ }) { Text("重新加载") }
            } else CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        }
        if (drawable is AnimatedImageDrawable) TextButton(onClick = { playRequested = !playRequested },
            modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (playRequested) "暂停动图" else "播放动图") }
        else if (expectAnimation && drawable != null) Text("当前预览未读取到动画帧；保存时会检查原文件是否包含动态内容。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MediaThumbnail(source: String, modifier: Modifier = Modifier, video: Boolean = false,
                   description: String? = null, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(null, source, video) { value = null; value = PreviewImages.load(context, source, video) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = contentScale)
        else Text(if (video) "▶" else "▧", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AlbumPreview(images: List<ParsedImage>, title: String = "图集", identity: String = "album-preview",
                 saveEnabled: Boolean = true, onSaveItem: ((Int, AlbumMode) -> Unit)? = null) {
    var selected by remember(images) { mutableStateOf<Int?>(null) }
    var motionPreview by remember(images) { mutableStateOf<ParsedImage?>(null) }
    var formatSelection by remember(images) { mutableStateOf<Int?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(images) { index, image ->
            Card(onClick = { selected = index }, modifier = Modifier.width(132.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor())) {
                MediaThumbnail(image.url.ifBlank { image.motion?.url.orEmpty() }, Modifier.fillMaxWidth().height(166.dp),
                    video = image.url.isBlank() && image.motion != null, description = "图集第 ${index + 1} 张")
                Text("${index + 1} / ${images.size} · ${albumAssetLabel(albumPreviewKind(image))}",
                    Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    selected?.let { initial ->
        var index by remember(initial) { mutableIntStateOf(initial) }
        val previewScroll = rememberScrollState()
        LaunchedEffect(index) { previewScroll.scrollTo(0) }
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp), contentAlignment = Alignment.Center) {
                val previewHeightLimit = (maxHeight * 0.55f).coerceIn(120.dp, 420.dp)
                val contentWidth = (maxWidth.coerceAtMost(600.dp) - 28.dp).coerceAtLeast(1.dp)
                Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(14.dp).verticalScroll(previewScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val image = images[index]
                    val kind = albumPreviewKind(image)
                    Text("${albumAssetLabel(kind)} · ${index + 1} / ${images.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    val ratio = when {
                        image.width > 0 && image.height > 0 -> image.width.toFloat() / image.height
                        image.motion != null && image.motion.width > 0 && image.motion.height > 0 -> image.motion.width.toFloat() / image.motion.height
                        else -> 0.8f
                    }
                    // A fixed full width plus aspectRatio can force a square image
                    // past the height limit in landscape. Fit inside a bounded frame.
                    val previewSize = Modifier.fillMaxWidth().height((contentWidth / ratio).coerceIn(120.dp, previewHeightLimit))
                    if (image.url.isBlank() && image.motion != null) {
                        MediaThumbnail(image.motion.url, previewSize, video = true, description = "动态片段封面", contentScale = ContentScale.Fit)
                        Text("封面已不存在，保留的动态片段仍可播放。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else AlbumImagePreview(image.url, previewSize, description = "图集第 ${index + 1} 张",
                        expectAnimation = kind == AlbumAssetKind.ANIMATED && image.motion == null)
                    if (kind != AlbumAssetKind.STATIC) {
                        val motion = image.motion
                        if (motion != null && motion.url.isNotBlank()) OutlinedButton(onClick = { motionPreview = image },
                            modifier = Modifier.fillMaxWidth()) { Text(when (kind) {
                                AlbumAssetKind.LIVE -> "播放动态片段"; AlbumAssetKind.ANIMATED -> "播放动图"; else -> "播放动态片段"
                            }) }
                        else if (kind in listOf(AlbumAssetKind.LIVE, AlbumAssetKind.DYNAMIC)) Text("动态片段尚未读取到；保存时会再次检查，当前可预览封面。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    onSaveItem?.let { save ->
                        Button(onClick = {
                            if (kind == AlbumAssetKind.DYNAMIC) formatSelection = index
                            else { selected = null; save(index, AlbumMode.IMAGES) }
                        }, enabled = saveEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(when (kind) {
                                AlbumAssetKind.STATIC -> "保存这张图片"; AlbumAssetKind.ANIMATED -> "保存这张动图"
                                AlbumAssetKind.LIVE -> "保存这项动态素材"; AlbumAssetKind.DYNAMIC -> "保存这项动态素材"
                            })
                        }
                        if (kind != AlbumAssetKind.STATIC && image.url.isNotBlank()) TextButton(
                            onClick = { selected = null; save(index, AlbumMode.COVERS) }, enabled = saveEnabled,
                            modifier = Modifier.align(Alignment.End)) { Text("仅保存这张静态封面") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { index-- }, enabled = index > 0) { Text("上一张") }
                        Text("${index + 1} / ${images.size}", Modifier.align(Alignment.CenterVertically))
                        TextButton(onClick = { index++ }, enabled = index < images.lastIndex) { Text("下一张") }
                    }
                    TextButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
            }
        }
    }
    formatSelection?.let { index ->
        onSaveItem?.let { save ->
            AlertDialog(onDismissRequest = { formatSelection = null }, title = { Text("选择保存格式") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("网页未明确标注这项动态素材的类型，请选择保存格式。", style = MaterialTheme.typography.bodyMedium)
                        listOf(AlbumMode.MOTION_VIDEOS, AlbumMode.GIF).forEach { mode ->
                            OutlinedButton(onClick = {
                                formatSelection = null; selected = null; save(index, mode)
                            }, enabled = saveEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(AlbumActionUiPolicy.saveLabel(null, mode))
                            }
                        }
                        Text("无声动图保留来源画面尺寸和帧率；GIF 按当前选择的画质转换。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, confirmButton = {}, dismissButton = { TextButton(onClick = { formatSelection = null }) { Text("取消") } })
        }
    }
    motionPreview?.let { image ->
        image.motion?.let { motion ->
            val dismiss = { motionPreview = null }
            val previewTitle = "$title · ${albumAssetLabel(albumPreviewKind(image))}"
            if (motion.embeddedInPhoto) EmbeddedMotionPreviewDialog(motion, previewTitle, dismiss)
            else if (Uri.parse(motion.url).scheme in listOf("content", "file")) {
                val context = LocalContext.current
                val preferences = remember(context) { PreviewPreferences(context).read() }
                VideoPreviewDialog(motion.url, previewTitle, motion.width, motion.height,
                    preferences.backwardSeconds, preferences.forwardSeconds, dismiss)
            } else VerifiedVideoPreviewDialog(ParsedVideo(identity, previewTitle, motion.url,
                motion.durationSeconds, motion.width, motion.height, coverUrl = image.url, mediaSources = motion.mediaSources), dismiss)
        }
    }
}

@Composable
private fun EmbeddedMotionPreviewDialog(motion: ParsedMotion, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val temporary = remember(motion.url) { File(context.cacheDir, "motion_preview_${UUID.randomUUID()}.mp4") }
    var prepared by remember(motion.url) { mutableStateOf(false) }
    var error by remember(motion.url) { mutableStateOf<String?>(null) }
    DisposableEffect(temporary) { onDispose { temporary.delete() } }
    LaunchedEffect(motion.url) {
        try {
            withContext(Dispatchers.IO) {
                EmbeddedMotionReader.extractForPreview(context, Uri.parse(motion.url), temporary)
            }
            prepared = true
        } catch (cancelled: CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (failure: Exception) {
            temporary.delete()
            error = "无法读取图片中的动态片段，请检查文件是否完整。"
        }
    }
    if (prepared) {
        val preferences = remember(context) { PreviewPreferences(context).read() }
        VideoPreviewDialog(Uri.fromFile(temporary).toString(), title, motion.width, motion.height,
            preferences.backwardSeconds, preferences.forwardSeconds, onDismiss)
    } else AlertDialog(onDismissRequest = onDismiss, title = { Text(if (error == null) "读取动态内容" else "动态预览失败") },
        text = {
            if (error != null) Text(error.orEmpty())
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在准备播放…")
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(if (error == null) "取消" else "关闭") } })
}
