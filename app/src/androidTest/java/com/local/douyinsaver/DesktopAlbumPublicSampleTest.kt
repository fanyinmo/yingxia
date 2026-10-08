package com.local.douyinsaver

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Native probe host only: no MainActivity, engine, Compose, preferences or gallery publication. */
class DesktopAlbumProbeActivity : Activity() {
    internal lateinit var probeWebView: WebView
    internal var resolver: DesktopAlbumResolver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        probeWebView = WebView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        // Keep a real, laid-out desktop viewport beneath an opaque cover. GONE/zero-size
        // WebViews do not exercise the same page layout and hydration as the desktop reader.
        root.addView(probeWebView, FrameLayout.LayoutParams(1280, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(TextView(this).apply {
            text = "正在验证指定公开图集，请等待测试结束"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onDestroy() {
        val hadResolver = resolver != null
        try { resolver?.dispose() } finally {
            resolver = null
            try {
                // Resolver.dispose() owns its WebView. A layout-only host has no resolver.
                if (!hadResolver && ::probeWebView.isInitialized) {
                    probeWebView.stopLoading()
                    (probeWebView.parent as? ViewGroup)?.removeView(probeWebView)
                    probeWebView.destroy()
                }
            } finally { super.onDestroy() }
        }
    }
}

/** Explicit public-network opt-in. Every sample gets its own retained, app-private UUID archive. */
@RunWith(AndroidJUnit4::class)
class DesktopAlbumPublicSampleTest {
    @Test fun inspectExplicitDesktopAlbumAndVerifyItsOwnMotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Public desktop requests require run_desktop_album=true",
            arguments.getString("run_desktop_album") == "true")
        val id = requireNotNull(arguments.getString("id")) { "id must be explicit" }
        require(id.matches(Regex("[0-9]{15,22}")))
        val share = ShareLinks.extract(requireNotNull(arguments.getString("shareUrl")) { "shareUrl must be explicit" })
        ShareLinks.videoId(share)?.let { require(it == id) { "shareUrl and id refer to different works" } }
        val context = instrumentation.targetContext
        val started = SystemClock.elapsedRealtime()
        val deadline = started + TOTAL_BUDGET_MS
        val directory = File(context.cacheDir, "desktop_album_${UUID.randomUUID()}")
        check(directory.mkdir() && directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        val report = JSONObject().put("requestedId", id).put("shareHost", host(share))
            .put("runRoot", directory.name).put("version", InstalledTestTarget.versionName).put("instrumentationCompiledVersion", BuildConfig.VERSION_NAME)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("phase", "setup").put("resolverStatus", "UNSTARTED").put("status", "UNSTARTED")
            .put("success", false).put("mediaStoreWrites", 0).put("preferenceWrites", 0)
            .put("validatedVideo", 0).put("verifiedMotion", 0).put("verifiedGif", 0)
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        val result = AtomicReference<DesktopAlbumResult?>(null)
        val activeResolver = AtomicReference<DesktopAlbumResolver?>(null)
        val activeTransfer = AtomicReference<MediaTransfer?>(null)
        val mappingConnection = AtomicReference<HttpURLConnection?>(null)
        val expired = AtomicBoolean(false)
        val generated = linkedSetOf<File>()
        val retained = linkedSetOf<File>()
        var scenario: ActivityScenario<DesktopAlbumProbeActivity>? = null
        val cleanupErrors = mutableListOf<String>()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val watchdog = scheduler.schedule({
            expired.set(true)
            activeTransfer.get()?.cancel()
            mappingConnection.get()?.disconnect()
            Handler(Looper.getMainLooper()).post { activeResolver.get()?.cancel() }
        }, TOTAL_BUDGET_MS, TimeUnit.MILLISECONDS)
        try {
            phase(report, "share_mapping")
            val mapping = JSONObject().put("inputHost", host(share)).put("status", "UNSTARTED")
            report.put("shareMapping", mapping)
            val mapped = try {
                runBlocking(Dispatchers.IO) {
                    withTimeout(minOf(SHARE_MAPPING_BUDGET_MS, remaining(deadline))) {
                        resolveShareMapping(share, minOf(deadline, SystemClock.elapsedRealtime() + SHARE_MAPPING_BUDGET_MS),
                            mappingConnection, mapping)
                    }
                }
            } catch (error: Exception) {
                mapping.put("status", "UNRESOLVED").put("errorType", error.javaClass.simpleName)
                    .put("reason", safeDiagnostic(error.message.orEmpty()))
                httpStatus(error)?.let { mapping.put("httpStatus", it) }
                // An unavailable short link cannot invalidate the explicitly requested desktop id.
                remaining(deadline)
                null
            }
            mapped?.let {
                mapping.put("status", "RESOLVED").put("resolvedId", it.id).put("resolvedHost", host(it.url))
                    .put("sameId", it.id == id)
                check(it.id == id) { "Share mapping points to a different work" }
            }
            phase(report, "setup_webview")
            scenario = ActivityScenario.launch(Intent(context, DesktopAlbumProbeActivity::class.java))
            val layoutDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 5_000L)
            var width = 0
            var height = 0
            do {
                scenario.onActivity { activity ->
                    width = activity.probeWebView.width
                    height = activity.probeWebView.height
                }
                if (width > 0 && height > 0) break
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < layoutDeadline)
            check(width == 1280 && height > 0) { "Desktop WebView did not acquire a real layout" }
            report.put("webViewWidth", width).put("webViewHeight", height)
            phase(report, "resolve")
            scenario.onActivity { activity ->
                report.put("webViewVersion", WebView.getCurrentWebViewPackage()?.versionName.orEmpty())
                activity.resolver = DesktopAlbumResolver(activity.probeWebView, id,
                    onResult = { value -> result.compareAndSet(null, value) },
                    onDiagnostic = { event -> synchronized(diagnostics) {
                        if (diagnostics.size < 100) diagnostics.add(safeDiagnostic(event))
                    } })
                activeResolver.set(activity.resolver)
            }
            val resolverDeadline = minOf(deadline, SystemClock.elapsedRealtime() + RESOLVER_BUDGET_MS)
            while (result.get() == null && SystemClock.elapsedRealtime() < resolverDeadline && !expired.get())
                SystemClock.sleep(100)
            val resolved = result.get()
            report.put("resolverStatus", resolved?.status?.name ?: "TIMEOUT")
                .put("reason", safeDiagnostic(resolved?.reason.orEmpty()))
            resolved?.album?.let { observed ->
                report.put("parsedId", observed.id).put("observedImageCount", observed.images.size)
                    .put("observedMotionCount", observed.images.count { it.motion != null })
                    .put("observedLiveWithoutMotion", observed.images.count { it.kind == AlbumAssetKind.LIVE && it.motion == null })
                    .put("observedKinds", JSONArray(observed.images.map { it.kind.name }))
            }
            // No automated login/captcha interaction or retry with an old resolver session.
            instrumentation.runOnMainSync {
                if (resolved == null) activeResolver.get()?.cancel() else activeResolver.get()?.dispose()
            }
            if (resolved?.status != DesktopAlbumStatus.CANDIDATE || resolved.album == null) {
                report.put("status", resolved?.status?.name ?: "TIMEOUT")
                phase(report, "no_candidate")
                return
            }
            val candidate = checkNotNull(resolved.album)
            check(candidate.id == id && candidate.isAlbum) { "Candidate does not belong to the requested album" }
            AlbumMediaPolicy.validateImages(candidate.images.size)
            val imageReports = candidate.images.mapIndexed { index, image ->
                JSONObject().put("index", index).put("kind", image.kind.name)
                    .put("imageHost", host(image.url)).put("width", image.width).put("height", image.height)
                    .put("mimeType", image.mimeType.takeIf { it.matches(Regex("image/[a-zA-Z0-9.+-]{1,40}")) }.orEmpty())
                    .put("cleanImageSources", image.mediaSources.count { it.mode == WatermarkMode.CLEAN })
                    .put("hasMotion", image.motion != null).put("status", if (image.motion == null) "NO_MOTION" else "PENDING")
                    .apply { image.motion?.let { motion ->
                        put("motionHost", host(motion.url)).put("motionWidth", motion.width).put("motionHeight", motion.height)
                        put("motionDurationSeconds", motion.durationSeconds.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        put("cleanMotionSources", motion.mediaSources.count { it.mode == WatermarkMode.CLEAN })
                    } }
            }
            val motionCount = candidate.images.count { it.motion != null }
            val missingLive = candidate.images.count { it.kind == AlbumAssetKind.LIVE && it.motion == null }
            report.put("parsedId", candidate.id).put("imageCount", candidate.images.size)
                .put("motionCount", motionCount).put("liveWithoutMotion", missingLive)
                .put("orderedImages", JSONArray(imageReports))
                .put("kindCounts", JSONObject().apply { AlbumAssetKind.entries.forEach { kind ->
                    put(kind.name, candidate.images.count { it.kind == kind })
                } })
            // Keep all positions: never filter/reorder the image list or borrow a neighbouring motion.
            val selected = WatermarkSources.select(candidate, WatermarkMode.CLEAN)
            check(selected.images.size == candidate.images.size)
            var downloaded = 0L
            var attempted = 0
            var validatedVideo = 0
            var verifiedMotion = 0
            var verifiedGif = 0
            runBlocking(Dispatchers.IO) {
                withTimeout(remaining(deadline)) {
                    for ((index, image) in selected.images.withIndex()) {
                        val motion = image.motion ?: continue
                        val item = imageReports[index]
                        attempted++
                        item.put("status", "FETCHING").put("phase", "download")
                        phase(report, "download_motion_$index")
                        val prefix = "image-${(index + 1).toString().padStart(3, '0')}-motion"
                        val mp4 = File(directory, "$prefix.mp4").also { generated.add(it) }
                        val gif = File(directory, "$prefix.gif").also { generated.add(it) }
                        val attempts = JSONArray()
                        item.put("attempts", attempts)
                        try {
                            val clean = motion.mediaSources.filter { it.mode == WatermarkMode.CLEAN }.map { it.url }
                            val urls = (listOf(motion.url).filter { it in clean } + clean).distinct().filter(MediaUrls::isAllowed)
                            check(urls.isNotEmpty()) { "No clean motion source" }
                            var accepted = false
                            for (url in urls) {
                                currentCoroutineContext().ensureActive()
                                remaining(deadline)
                                val attempt = JSONObject().put("sourceHost", host(url)).put("status", "FETCHING")
                                attempts.put(attempt)
                                val transfer = MediaTransfer(connections = { uri ->
                                    attempt.put("requestHost", uri.host.orEmpty())
                                    uri.toURL().openConnection() as HttpURLConnection
                                })
                                activeTransfer.set(transfer)
                                try {
                                    val bytes = transfer.fetch(url, mp4, AlbumMediaPolicy.MAX_MOTION_BYTES, "实况视频",
                                        validateUrl = { WatermarkSources.requireSelectedUrl(it, WatermarkMode.CLEAN, motion.mediaSources) }) { done, total ->
                                        currentCoroutineContext().ensureActive()
                                        remaining(deadline)
                                        check(downloaded + done <= AlbumMediaPolicy.MAX_ALBUM_BYTES)
                                        attempt.put("receivedBytes", done).put("expectedBytes", total)
                                    }
                                    item.put("phase", "validate_video")
                                    AlbumMediaValidation.motionVideo(mp4)
                                    downloaded += bytes
                                    retained.add(mp4)
                                    item.put("videoBytes", bytes).put("videoFile", mp4.name).put("validatedVideo", true)
                                    attempt.put("status", "VALIDATED_VIDEO")
                                    validatedVideo++
                                    accepted = true
                                    break
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    attempt.put("status", "REJECTED").put("errorType", error.javaClass.simpleName)
                                        .put("reason", safeDiagnostic(error.message.orEmpty()))
                                    httpStatus(error)?.let { attempt.put("httpStatus", it) }
                                    mp4.delete()
                                } finally { transfer.cancel(); activeTransfer.compareAndSet(transfer, null) }
                            }
                            remaining(deadline)
                            check(accepted) { "All motion sources were rejected" }
                            phase(report, "decode_motion_$index")
                            item.put("phase", "decode_frames")
                            val videoFrames = inspectVideo(mp4, deadline)
                            item.put("videoDurationMs", videoFrames.durationMs)
                                .put("videoFrames", videoFrames.frames).put("distinctVideoFrameHashes", videoFrames.distinct)
                                .put("verifiedMotion", videoFrames.distinct >= 2)
                            if (videoFrames.distinct >= 2) verifiedMotion++
                            val duration = minOf(6_000L, videoFrames.durationMs)
                            check(duration >= GifConversionPolicy.MIN_DURATION_MS) { "Motion is too short for a GIF" }
                            phase(report, "convert_gif_$index")
                            item.put("phase", "convert_gif").put("gifRequestedDurationMs", duration)
                            VideoGifConverter().convert(mp4, gif, 0L, duration) { done, total ->
                                remaining(deadline)
                                item.put("gifProgressFrames", done).put("gifExpectedFrames", total)
                            }
                            item.put("phase", "validate_gif")
                            val animation = inspectGif(gif, deadline)
                            retained.add(gif)
                            val animated = animation.distinct >= 2
                            item.put("gifFile", gif.name).put("gifBytes", gif.length())
                                .put("gifImageBlocks", animation.imageBlocks).put("gifDurationMs", animation.durationMs)
                                .put("gifFrames", animation.frames).put("distinctGifFrameHashes", animation.distinct)
                                .put("verifiedGif", animated).put("phase", "complete")
                                .put("status", when {
                                    videoFrames.distinct < 2 -> "VALIDATED_VIDEO_WITHOUT_VISIBLE_MOTION"
                                    !animated -> "GIF_WITHOUT_VISIBLE_MOTION"
                                    else -> "VERIFIED_MOTION_AND_GIF"
                                })
                            if (videoFrames.distinct >= 2 && animated) verifiedGif++
                        } catch (error: CancellationException) {
                            item.put("status", "TIMEOUT_OR_CANCELLED").put("errorType", error.javaClass.simpleName)
                            throw error
                        } catch (error: Exception) {
                            item.put("status", "FAILED").put("errorType", error.javaClass.simpleName)
                                .put("reason", safeDiagnostic(error.message.orEmpty()))
                            httpStatus(error)?.let { item.put("httpStatus", it) }
                        } finally {
                            report.put("attemptedMotion", attempted).put("validatedVideo", validatedVideo)
                                .put("verifiedMotion", verifiedMotion).put("verifiedGif", verifiedGif)
                        }
                    }
                }
            }
            val success = motionCount > 0 && missingLive == 0 && attempted == motionCount && verifiedGif == motionCount
            report.put("status", if (success) "VERIFIED" else "INCOMPLETE_MOTION_VALIDATION")
                .put("success", success).put("downloadedBytes", downloaded)
            phase(report, "complete")
        } catch (error: Throwable) {
            report.put("status", if (expired.get() || error is CancellationException || error is TimeoutException)
                "TIMEOUT_OR_CANCELLED" else "FAILED")
                .put("errorType", error.javaClass.simpleName).put("reason", safeDiagnostic(error.message.orEmpty()))
            httpStatus(error)?.let { report.put("httpStatus", it) }
            // Real public pages can legitimately be unavailable. The report, rather than a
            // passing HTTP request or JUnit completion, is the authoritative success signal.
        } finally {
            watchdog.cancel(true)
            scheduler.shutdownNow()
            runCatching { activeTransfer.getAndSet(null)?.cancel() }.onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            runCatching { mappingConnection.getAndSet(null)?.disconnect() }.onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            runCatching { instrumentation.runOnMainSync { activeResolver.getAndSet(null)?.dispose() } }
                .onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            runCatching { scenario?.close() }.onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            // Delete only this run's registered partial files; verified artifacts remain exportable.
            generated.filterNot { it in retained }.forEach { file ->
                runCatching {
                    check(file.canonicalFile.parentFile == directory.canonicalFile)
                    if (file.exists()) check(file.delete())
                }.onFailure { cleanupErrors.add(it.javaClass.simpleName) }
            }
            if (cleanupErrors.isNotEmpty()) report.put("success", false).put("status", "CLEANUP_FAILED")
            report.put("cleanupErrors", JSONArray(cleanupErrors)).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("diagnostics", JSONArray(synchronized(diagnostics) { diagnostics.toList() }))
                .put("cacheArtifacts", JSONArray(retained.map { it.name }))
            val json = report.toString(2)
            File(directory, "report.json").writeText(json)
            File(context.cacheDir, "desktop-album-result-$id.json").writeText(json)
            Log.i("DesktopAlbumPublic", "id=$id status=${report.optString("status")} success=${report.optBoolean("success")} " +
                "validatedVideo=${report.optInt("validatedVideo")} verifiedMotion=${report.optInt("verifiedMotion")}")
        }
    }

    private data class FrameEvidence(val durationMs: Long, val frames: JSONArray, val distinct: Int)
    private data class GifEvidence(val durationMs: Long, val imageBlocks: Int, val frames: JSONArray, val distinct: Int)

    /** ShareLinks' official-host redirect rules, with owned sockets and a cooperative short deadline. */
    private suspend fun resolveShareMapping(share: String, deadline: Long,
        active: AtomicReference<HttpURLConnection?>, report: JSONObject): ResolvedShare {
        var url = share
        repeat(7) { hop ->
            currentCoroutineContext().ensureActive()
            remaining(deadline)
            // Once the URL includes an id, the production mapper does no network access.
            if (ShareLinks.videoId(url) != null) return ShareLinks.resolveShare(url)
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            active.set(connection)
            try {
                val timeout = minOf(5_000L, remaining(deadline)).toInt().coerceAtLeast(1)
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                report.put("requestHost", host(url)).put("redirectHops", hop)
                val status = connection.responseCode
                report.put("httpStatus", status)
                currentCoroutineContext().ensureActive()
                remaining(deadline)
                if (status in 300..399) {
                    val location = checkNotNull(connection.getHeaderField("Location")) { "Share redirect has no location" }
                    val next = URI(url).resolve(location)
                    check(next.scheme == "https" && ShareLinks.isShareHost(next.host) && next.userInfo == null &&
                        (next.port == -1 || next.port == 443)) { "Share redirect left official origins" }
                    url = next.toString()
                } else {
                    error("Share mapping did not expose a work id (HTTP $status)")
                }
            } finally { connection.disconnect(); active.compareAndSet(connection, null) }
        }
        error("Share mapping exceeded its redirect limit")
    }

    private suspend fun inspectVideo(file: File, deadline: Long): FrameEvidence {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            check(duration > 0)
            val frames = JSONArray()
            val hashes = linkedSetOf<String>()
            for (time in sampleTimes(duration)) {
                currentCoroutineContext().ensureActive()
                remaining(deadline)
                val bitmap = checkNotNull(retriever.getScaledFrameAtTime(time * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST, 128, 128)) { "Requested video frame did not decode" }
                try {
                    val hash = frameHash(bitmap)
                    hashes.add(hash)
                    frames.put(JSONObject().put("timestampMs", time).put("sha256", hash)
                        .put("width", bitmap.width).put("height", bitmap.height))
                } finally { bitmap.recycle() }
            }
            return FrameEvidence(duration, frames, hashes.size)
        } finally { retriever.release() }
    }

    @Suppress("DEPRECATION")
    private suspend fun inspectGif(file: File, deadline: Long): GifEvidence {
        val image = AlbumMediaValidation.image(file)
        check(image.mimeType == "image/gif" && image.animated) { "Converted output is not a GIF animation" }
        val bytes = file.readBytes()
        check(bytes.size >= 14 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "GIF89a")
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        check(drawable is AnimatedImageDrawable) { "Native decoder did not recognise GIF animation" }
        drawable.stop()
        val (imageBlocks, duration) = gifStructure(bytes)
        check(imageBlocks >= 2 && duration > 0) { "GIF did not contain multiple actual image blocks" }
        val movie = checkNotNull(Movie.decodeByteArray(bytes, 0, bytes.size)) { "GIF frames did not decode" }
        val bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val hashes = linkedSetOf<String>()
        val frames = JSONArray()
        try {
            for (time in sampleTimes(duration)) {
                currentCoroutineContext().ensureActive()
                remaining(deadline)
                bitmap.eraseColor(Color.TRANSPARENT)
                // Movie.setTime() reports whether the current image changed, not decode success.
                // Repeated images are valid GIF frames and must become distinct=1 evidence.
                movie.setTime(time.toInt())
                movie.draw(Canvas(bitmap), 0f, 0f)
                val hash = frameHash(bitmap)
                hashes.add(hash)
                frames.put(JSONObject().put("timestampMs", time).put("sha256", hash))
            }
        } finally { bitmap.recycle() }
        return GifEvidence(duration, imageBlocks, frames, hashes.size)
    }

    private fun gifStructure(bytes: ByteArray): Pair<Int, Long> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        fun skip(count: Int) { repeat(count) { input.readUnsignedByte() } }
        fun ushort() = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun blocks() { while (true) { val size = input.readUnsignedByte(); if (size == 0) return; skip(size) } }
        skip(6)
        check(ushort() in 1..GifConversionPolicy.MAX_EDGE && ushort() in 1..GifConversionPolicy.MAX_EDGE)
        val packed = input.readUnsignedByte()
        skip(2)
        if (packed and 0x80 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        var count = 0
        var delays = 0
        var duration = 0L
        while (true) when (val block = input.readUnsignedByte()) {
            0x21 -> if (input.readUnsignedByte() == 0xf9) {
                check(input.readUnsignedByte() == 4)
                skip(1); duration += ushort() * 10L; delays++; skip(1)
                check(input.readUnsignedByte() == 0)
            } else blocks()
            0x2c -> {
                skip(8)
                val flags = input.readUnsignedByte()
                if (flags and 0x80 != 0) skip(3 * (1 shl ((flags and 7) + 1)))
                input.readUnsignedByte(); blocks(); count++
            }
            0x3b -> break
            else -> error("Unexpected GIF block $block")
        }
        check(count == delays && input.available() == 0 && (bytes.last().toInt() and 255) == 0x3b)
        count to duration
    }

    private fun sampleTimes(duration: Long) = listOf(0L, duration / 4, duration / 2, duration * 3 / 4, duration - 1)
        .map { it.coerceIn(0L, duration - 1) }.distinct()

    private fun frameHash(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        check(pixels.any { Color.alpha(it) > 0 }) { "Decoded frame is entirely transparent" }
        val buffer = ByteBuffer.allocate(pixels.size * 4)
        pixels.forEach { buffer.putInt(it) }
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).also {
        if (it <= 0) throw TimeoutException("Two-minute public sample budget expired")
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull().orEmpty()
    private fun httpStatus(error: Throwable) = Regex("HTTP\\s+(\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    private fun safeDiagnostic(value: String): String {
        // Diagnostics are small event descriptions only; never retain a response body or headers.
        if (value.contains("<html", true) || value.contains("<!doctype", true) ||
            value.contains("__INIT_PROPS__") || value.trimStart().startsWith('{') || value.trimStart().startsWith('['))
            return "[response detail omitted]"
        val limit = if (value.startsWith("desktop_target_images ") ||
            value.startsWith("desktop_work_variants ") || value.startsWith("desktop_limits ")) 4_000 else 600
        return DiagnosticText.clean(value, limit)
    }
    private fun phase(report: JSONObject, value: String) {
        report.put("phase", value)
        Log.i("DesktopAlbumPublic", "phase=$value")
    }

    private companion object {
        const val TOTAL_BUDGET_MS = 120_000L
        const val RESOLVER_BUDGET_MS = 30_500L
        const val SHARE_MAPPING_BUDGET_MS = 10_000L
    }
}
