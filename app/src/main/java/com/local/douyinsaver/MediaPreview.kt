package com.local.douyinsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.webkit.CookieManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

private object PreviewImages {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val slots = Semaphore(3)

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
fun AlbumPreview(images: List<ParsedImage>) {
    var selected by remember(images) { mutableStateOf<Int?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(images) { index, image ->
            Card(onClick = { selected = index }, modifier = Modifier.width(132.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor())) {
                MediaThumbnail(image.url, Modifier.fillMaxWidth().height(166.dp), description = "图集第 ${index + 1} 张")
                Text("${index + 1} / ${images.size}", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    selected?.let { initial ->
        var index by remember(initial) { mutableIntStateOf(initial) }
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(14.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaThumbnail(images[index].url, Modifier.fillMaxWidth().heightIn(min = 250.dp, max = 480.dp).aspectRatio(0.8f),
                        description = "图集第 ${index + 1} 张", contentScale = ContentScale.Fit)
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

@Composable
fun VideoPreviewDialog(source: String, title: String, onDismiss: () -> Unit) {
    val allowed = remember(source) { Uri.parse(source).scheme == "content" || MediaUrls.isAllowed(source) }
    var playback by remember { mutableStateOf<VideoView?>(null) }
    var error by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(true) }
    val owner = LocalActivity.current as? LifecycleOwner
    val currentPlayback = playback
    DisposableEffect(currentPlayback, owner) {
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) currentPlayback?.pause() }
        owner?.lifecycle?.addObserver(listener)
        onDispose { owner?.lifecycle?.removeObserver(listener); currentPlayback?.stopPlayback() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                if (allowed && !error) Box(Modifier.fillMaxWidth().height(330.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView(factory = { context -> VideoView(context).apply {
                        playback = this
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        setOnPreparedListener { preparing = false; start() }
                        setOnErrorListener { _, _, _ -> error = true; preparing = false; true }
                        val headers = mutableMapOf("User-Agent" to ShareLinks.DESKTOP_UA, "Referer" to "https://www.douyin.com/")
                        if (Uri.parse(source).scheme == "https") CookieManager.getInstance().getCookie(source)?.let { headers["Cookie"] = it }
                        setVideoURI(Uri.parse(source), headers)
                    } }, modifier = Modifier.fillMaxSize())
                    if (preparing) CircularProgressIndicator(color = Color.White)
                } else Text("暂时无法预览，可以保存后使用本地播放器查看。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("预览会使用网络流量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭预览") }
            }
        }
    }
}
