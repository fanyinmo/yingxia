package com.local.douyinsaver

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPreviewDialog(source: String, title: String, width: Int = 0, height: Int = 0,
                       backwardSeconds: Int = PreviewPlaybackPolicy.DEFAULT_SEEK_SECONDS,
                       forwardSeconds: Int = PreviewPlaybackPolicy.DEFAULT_SEEK_SECONDS,
                       onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val backwardStep = PreviewPlaybackPolicy.seekSeconds(backwardSeconds)
    val forwardStep = PreviewPlaybackPolicy.seekSeconds(forwardSeconds)
    val owner = LocalActivity.current as? LifecycleOwner
    val remote = remember(source) { source.toUri().scheme == "https" }
    val allowed = remember(source) { source.toUri().scheme == "content" || MediaUrls.isAllowed(source) }
    var position by rememberSaveable(source) { mutableLongStateOf(0L) }
    var playRequested by rememberSaveable(source) { mutableStateOf(true) }
    var muted by rememberSaveable(source) { mutableStateOf(false) }
    var expanded by rememberSaveable(source) { mutableStateOf(false) }
    val player = remember(source) {
        if (!allowed) null else {
            val headers = buildMap {
                put("Referer", "https://www.douyin.com/")
                if (remote) CookieManager.getInstance().getCookie(source)?.let { put("Cookie", it) }
            }
            val http = DefaultHttpDataSource.Factory().setUserAgent(ShareLinks.DESKTOP_UA)
                .setDefaultRequestProperties(headers).setConnectTimeoutMs(10_000).setReadTimeoutMs(12_000)
                .setAllowCrossProtocolRedirects(false)
            val data = DefaultDataSource.Factory(context, http)
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build().apply {
                if (BuildConfig.DEBUG) addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                                                          initializedTimestampMs: Long, initializationDurationMs: Long) {
                        Log.d("PreviewPlayback", "video_decoder=$decoderName")
                    }
                })
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                // Route decoded textures through Media3's GL pipeline without altering the image.
                setVideoEffects(emptyList())
                setMediaItem(MediaItem.fromUri(source), position)
                playWhenReady = playRequested
                volume = if (muted) 0f else 1f
                prepare()
            }
        }
    }
    var playbackState by remember(source) { mutableIntStateOf(Player.STATE_IDLE) }
    var duration by remember(source) { mutableLongStateOf(0L) }
    var buffered by remember(source) { mutableLongStateOf(0L) }
    var draggingPosition by remember(source) { mutableStateOf<Long?>(null) }
    var error by remember(source) { mutableStateOf(!allowed) }
    var errorDetail by remember(source) { mutableStateOf(if (allowed) "" else "这个视频地址暂不支持预览，请重新解析") }
    var ratio by remember(source, width, height) { mutableFloatStateOf(PreviewPlaybackPolicy.aspectRatio(width, height)) }

    DisposableEffect(player) {
        fun readState() {
            player?.let {
                playbackState = it.playbackState
                playRequested = it.playWhenReady
                duration = it.duration.takeIf { value -> value != C.TIME_UNSET && value > 0 } ?: 0L
                position = it.currentPosition.coerceAtLeast(0L)
                buffered = it.bufferedPosition.coerceAtLeast(0L)
                error = it.playerError != null
                it.playerError?.let { problem -> errorDetail = previewFailureMessage(problem) }
                val size = it.videoSize
                if (size.width > 0 && size.height > 0) {
                    previewDisplayAspectRatio(size.width, size.height, size.pixelWidthHeightRatio)?.let { value -> ratio = value }
                } else {
                    // The GL sink may not publish VideoSize; its input format still carries display metadata.
                    it.videoFormat?.let { format ->
                        previewDisplayAspectRatio(format.width, format.height, format.pixelWidthHeightRatio,
                            format.rotationDegrees)?.let { value -> ratio = value }
                    }
                }
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { readState() }
            override fun onPlayerError(error: PlaybackException) { readState() }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                previewDisplayAspectRatio(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)?.let { ratio = it }
            }
        }
        player?.addListener(listener)
        player?.volume = if (muted) 0f else 1f
        readState()
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    DisposableEffect(player, owner) {
        var wasBackgrounded = false
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                wasBackgrounded = true
                player?.pause()
                player?.let { position = it.currentPosition.coerceAtLeast(0L) }
                playRequested = false
            } else if (event == Lifecycle.Event.ON_RESUME && wasBackgrounded) {
                wasBackgrounded = false
                player?.let {
                    val length = it.duration
                    if (!it.playWhenReady && it.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)
                        && length != C.TIME_UNSET && length > 1L && it.isCurrentMediaItemSeekable) {
                        val current = it.currentPosition.coerceIn(0L, length)
                        // A same-millisecond seek is optimized away; re-decode the paused frame without playing.
                        val target = if (current < length - 1L) current + 1L else (current - 1L).coerceAtLeast(0L)
                        it.seekTo(target)
                        position = target
                    }
                }
            }
        }
        owner?.lifecycle?.addObserver(lifecycle)
        onDispose { owner?.lifecycle?.removeObserver(lifecycle) }
    }
    LaunchedEffect(player) {
        if (player != null) while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            buffered = player.bufferedPosition.coerceAtLeast(0L)
            delay(250)
        }
    }
    fun togglePlayback() {
        player?.let {
            if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) it.pause()
            else {
                if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                it.play()
            }
        }
    }
    fun seekTo(target: Long) {
        if (duration > 0) {
            val bounded = target.coerceIn(0L, duration)
            player?.seekTo(bounded)
            position = bounded
        }
        draggingPosition = null
    }
    val ended = playbackState == Player.STATE_ENDED
    val buffering = !error && playbackState in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING)
    val playingIntent = playRequested && !ended && !error
    val accent = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(if (expanded) 0.dp else 12.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth > maxHeight
            val compact = wide || expanded
            val contentWidth = minOf(maxWidth, if (expanded) maxWidth else 1040.dp)
            Surface(Modifier.widthIn(max = contentWidth).fillMaxWidth()
                .then(if (expanded) Modifier.fillMaxHeight() else Modifier.heightIn(max = maxHeight)),
                shape = if (expanded) RoundedCornerShape(0.dp) else RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (!compact) Text("视频预览", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(title, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).semantics { contentDescription = "关闭预览" }) {
                            Glyph(GlyphKind.CLOSE, size = 21.dp)
                        }
                    }
                    Box(Modifier.weight(1f, fill = expanded)
                        .then(if (expanded) Modifier else Modifier.height(contentWidth / ratio))
                        .fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                        if (player != null) AndroidView(factory = { viewContext ->
                            (LayoutInflater.from(viewContext).inflate(R.layout.preview_player, FrameLayout(viewContext), false) as PlayerView).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                this.player = player
                            }
                        }, modifier = Modifier.aspectRatio(ratio), onReset = null, onRelease = { it.player = null }, update = {
                            if (it.player !== player) it.player = player
                            it.keepScreenOn = player.isPlaying
                        })
                        when {
                            error -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("暂时无法加载视频", color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text(errorDetail.ifBlank { "可重试，或关闭预览后重新检查视频来源。" }, color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodySmall)
                                if (player != null) OutlinedButton(onClick = { error = false; player.prepare(); player.play() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("重新加载") }
                            }
                            buffering -> CircularProgressIndicator(Modifier.size(32.dp), color = accent, strokeWidth = 2.5.dp)
                            !playingIntent -> IconButton(onClick = ::togglePlayback,
                                modifier = Modifier.size(68.dp).background(Color.Black.copy(alpha = 0.36f), CircleShape)
                                    .semantics { contentDescription = if (ended) "画面重播" else "画面播放" }) {
                                PreviewControlGlyph(if (ended) PreviewControl.REPLAY else PreviewControl.PLAY, Color.White, Modifier.size(30.dp))
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp)) {
                        PreviewTimeline(position, duration, buffered, draggingPosition, !error && duration > 0,
                            onDragPosition = { draggingPosition = it }, onSeek = ::seekTo)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
                            Text(PreviewPlaybackPolicy.formatTime(draggingPosition ?: position), style = timeStyle,
                                modifier = Modifier.semantics { contentDescription = "当前播放时间" })
                            Text(if (duration > 0) PreviewPlaybackPolicy.formatTime(duration) else "--:--", style = timeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { contentDescription = "视频总时长" })
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            PreviewIconButton(if (muted) "取消静音" else "静音", if (muted) PreviewControl.MUTE else PreviewControl.VOLUME,
                                enabled = player != null, onClick = { muted = !muted; player?.volume = if (muted) 0f else 1f })
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PreviewIconButton("后退${backwardStep}秒", PreviewControl.BACKWARD, enabled = !error && duration > 0,
                                    seconds = backwardStep,
                                    onClick = { seekTo(PreviewPlaybackPolicy.seekTarget(player?.currentPosition ?: position, duration, -backwardStep * 1_000L)) })
                                FilledIconButton(onClick = ::togglePlayback, enabled = player != null && !error,
                                    modifier = Modifier.size(56.dp).semantics { contentDescription = if (ended) "重新播放" else if (playingIntent) "暂停视频" else "播放视频" },
                                    shape = CircleShape) {
                                    PreviewControlGlyph(if (ended) PreviewControl.REPLAY else if (playingIntent) PreviewControl.PAUSE else PreviewControl.PLAY,
                                        MaterialTheme.colorScheme.onPrimary, Modifier.size(25.dp))
                                }
                                PreviewIconButton("前进${forwardStep}秒", PreviewControl.FORWARD, enabled = !error && duration > 0,
                                    seconds = forwardStep,
                                    onClick = { seekTo(PreviewPlaybackPolicy.seekTarget(player?.currentPosition ?: position, duration, forwardStep * 1_000L)) })
                            }
                            PreviewIconButton(if (expanded) "收起画面" else "放大画面", if (expanded) PreviewControl.COLLAPSE else PreviewControl.EXPAND,
                                onClick = { expanded = !expanded })
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(5.dp).background(if (playingIntent) accent else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                            Text(when { error -> "加载失败"; buffering -> "正在缓冲…"; ended -> "播放结束"; playingIntent -> "正在播放"; else -> "已暂停" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            Text(if (remote) "在线预览会使用流量" else "本地视频", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun previewFailureMessage(error: PlaybackException): String {
    val http = generateSequence<Throwable>(error) { it.cause?.takeUnless { next -> next === it } }
        .take(8).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return if (http != null) "视频服务器拒绝播放（HTTP ${http.responseCode}），请关闭预览后重新检查来源"
    else FailureMessages.describe(error)
}

private fun previewDisplayAspectRatio(width: Int, height: Int, pixelRatio: Float, rotationDegrees: Int = 0): Float? {
    if (width !in 1..32768 || height !in 1..32768) return null
    val pixel = pixelRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val encodedRatio = width.toFloat() / height * pixel
    val displayRatio = if (rotationDegrees == 90 || rotationDegrees == 270) 1f / encodedRatio else encodedRatio
    return displayRatio.coerceIn(0.25f, 4f)
}

@Composable
private fun PreviewTimeline(position: Long, duration: Long, buffered: Long, dragging: Long?, enabled: Boolean,
                            onDragPosition: (Long?) -> Unit, onSeek: (Long) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val currentDuration by rememberUpdatedState(duration)
    val currentEnabled by rememberUpdatedState(enabled)
    val dragPosition by rememberUpdatedState(onDragPosition)
    val seek by rememberUpdatedState(onSeek)
    val shown = dragging ?: position
    Canvas(Modifier.fillMaxWidth().height(48.dp).semantics {
        contentDescription = "视频播放进度"
        stateDescription = "${PreviewPlaybackPolicy.formatTime(shown)} / ${if (duration > 0) PreviewPlaybackPolicy.formatTime(duration) else "未知时长"}"
        progressBarRangeInfo = ProgressBarRangeInfo(if (duration > 0) (shown.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f, 0f..1f)
        if (!enabled) disabled() else setProgress { fraction -> seek((fraction.coerceIn(0f, 1f) * currentDuration).toLong()); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentEnabled) return@awaitEachGesture
            fun target(x: Float): Long {
                val inset = 10.dp.toPx()
                val width = (size.width - inset * 2f).coerceAtLeast(1f)
                return (((x - inset) / width).coerceIn(0f, 1f) * currentDuration).toLong()
            }
            down.consume()
            var selected = target(down.position.x)
            dragPosition(selected)
            val released = drag(down.id) { change ->
                change.consume()
                selected = target(change.position.x)
                dragPosition(selected)
            }
            if (released) seek(selected) else dragPosition(null)
        }
    }) {
        val start = 10.dp.toPx()
        val end = size.width - start
        val y = size.height / 2f
        fun x(time: Long) = start + (end - start) * (if (duration > 0) time.toDouble() / duration else 0.0).coerceIn(0.0, 1.0).toFloat()
        drawLine(accent.copy(alpha = 0.12f), Offset(start, y), Offset(end, y), 4.dp.toPx(), StrokeCap.Round)
        drawLine(accent.copy(alpha = 0.32f), Offset(start, y), Offset(x(buffered), y), 4.dp.toPx(), StrokeCap.Round)
        drawLine(accent, Offset(start, y), Offset(x(shown), y), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(accent.copy(alpha = 0.12f), if (dragging != null) 13.dp.toPx() else 10.dp.toPx(), Offset(x(shown), y))
        drawCircle(accent, if (dragging != null) 9.dp.toPx() else 7.dp.toPx(), Offset(x(shown), y))
    }
}

@Composable
private fun PreviewIconButton(description: String, control: PreviewControl, enabled: Boolean = true,
                              seconds: Int? = null, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        PreviewControlGlyph(control, LocalContentColor.current, Modifier.size(if (seconds == null) 23.dp else 26.dp), seconds)
    }
}

private enum class PreviewControl { PLAY, PAUSE, REPLAY, BACKWARD, FORWARD, VOLUME, MUTE, EXPAND, COLLAPSE }

@Composable
private fun PreviewControlGlyph(control: PreviewControl, color: Color, modifier: Modifier, seconds: Int? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                val stroke = Stroke(1.7f, cap = StrokeCap.Round)
                fun line(x: Float, y: Float, tx: Float, ty: Float) = drawLine(color, Offset(x, y), Offset(tx, ty), 1.7f, StrokeCap.Round)
                when (control) {
                    PreviewControl.PLAY -> drawPath(Path().apply { moveTo(7f, 4f); lineTo(21f, 12f); lineTo(7f, 20f); close() }, color)
                    PreviewControl.PAUSE -> { drawRect(color, Offset(6f, 5f), Size(4f, 14f)); drawRect(color, Offset(14f, 5f), Size(4f, 14f)) }
                    PreviewControl.VOLUME, PreviewControl.MUTE -> {
                        drawPath(Path().apply { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close() }, color)
                        if (control == PreviewControl.MUTE) { line(16f, 9f, 22f, 15f); line(16f, 15f, 22f, 9f) }
                        else { drawArc(color, -50f, 100f, false, Offset(9f, 4f), Size(14f, 16f), style = stroke)
                            drawArc(color, -50f, 100f, false, Offset(10f, 8f), Size(8f, 8f), style = stroke) }
                    }
                    PreviewControl.REPLAY, PreviewControl.BACKWARD, PreviewControl.FORWARD -> {
                        val forward = control == PreviewControl.FORWARD
                        drawArc(color, if (forward) -70f else -110f, if (forward) 290f else -290f,
                            false, Offset(3f, 3f), Size(18f, 18f), style = stroke)
                        if (forward) { line(17f, 1f, 17f, 6f); line(17f, 6f, 22f, 6f) }
                        else { line(7f, 1f, 7f, 6f); line(7f, 6f, 2f, 6f) }
                    }
                    PreviewControl.EXPAND, PreviewControl.COLLAPSE -> {
                        val inward = control == PreviewControl.COLLAPSE
                        for ((x, y) in listOf(4f to 4f, 20f to 4f, 4f to 20f, 20f to 20f)) {
                            val dx = if (x < 12f) 5f else -5f
                            val dy = if (y < 12f) 5f else -5f
                            val corner = if (inward) Offset(x + dx, y + dy) else Offset(x, y)
                            line(corner.x, corner.y, corner.x + if (inward) -dx else dx, corner.y)
                            line(corner.x, corner.y, corner.x, corner.y + if (inward) -dy else dy)
                        }
                    }
                }
            }
        }
        if (seconds != null) Text(seconds.toString(),
            style = MaterialTheme.typography.labelSmall, fontSize = if (seconds >= 100) 9.sp else 10.sp,
            color = color, fontFamily = FontFamily.Monospace)
    }
}
