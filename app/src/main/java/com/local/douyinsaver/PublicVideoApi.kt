package com.local.douyinsaver

import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** One normal request to the web detail endpoint, using only existing site cookies. */
class PublicVideoApi {
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile internal var lastNetworkFailure: PageNetworkFailure? = null
        private set

    fun cancel() {
        cancelled.set(true)
        activeConnection?.disconnect()
    }

    suspend fun read(
        id: String,
        userAgent: String,
        onDiagnostic: (String) -> Unit,
    ): ParsedVideo? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(running.compareAndSet(false, true)) { "视频信息请求正在进行" }
        cancelled.set(false)
        lastNetworkFailure = null
        fun report(message: String) {
            // Diagnostics never contain cookies, response bodies, or signed media URLs.
            runCatching { onDiagnostic(message) }
        }
        try {
            if (!VIDEO_ID.matches(id)) {
                report("detail_api invalid_id")
                return@withContext null
            }
            ensureNotCancelled()
            val requestUrl = "$ENDPOINT?aweme_id=$id"
            val connection = URL(requestUrl).openConnection() as HttpURLConnection
            activeConnection = connection
            try {
                ensureNotCancelled()
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", "https://www.douyin.com/")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                CookieManager.getInstance().getCookie(requestUrl)?.takeIf { it.isNotBlank() }?.let {
                    connection.setRequestProperty("Cookie", it)
                }
                val code = connection.responseCode
                ensureNotCancelled()
                val type = connection.contentType.orEmpty().substringBefore(';')
                    .trim().lowercase(Locale.ROOT).take(96)
                    .takeIf { CONTENT_TYPE.matches(it) }.orEmpty()
                if (code != HttpURLConnection.HTTP_OK) {
                    // Redirects are not followed, so cookies cannot be sent to another origin.
                    report("detail_api http=$code type=$type bytes_read=0 result=http_rejected")
                    return@withContext null
                }
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                    report("detail_api http=$code type=$type bytes_read=0 result=body_too_large")
                    return@withContext null
                }
                val bytes = ByteArrayOutputStream()
                var reachedLimit = false
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        ensureNotCancelled()
                        val remaining = MAX_RESPONSE_BYTES - bytes.size()
                        if (remaining == 0) {
                            reachedLimit = true
                            break
                        }
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count < 0) break
                        if (count > 0) bytes.write(buffer, 0, count)
                    }
                }
                ensureNotCancelled()
                val size = bytes.size()
                report("detail_api http=$code type=$type bytes_read=$size")
                if (reachedLimit || size == 0) {
                    report("detail_api result=${if (reachedLimit) "body_limit_reached" else "empty_body"}")
                    return@withContext null
                }
                val body = bytes.toString(Charsets.UTF_8.name()).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                if (type == "text/html" || !body.startsWith('{')) {
                    report("detail_api result=not_json_object")
                    return@withContext null
                }
                val root = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    report("detail_api result=invalid_json")
                    return@withContext null
                }
                val keys = root.keys().asSequence().take(24)
                    .map { if (SAFE_KEY.matches(it)) it else "other" }.joinToString(",")
                val status = root.opt("status_code")?.toString()
                    ?.takeIf { BUSINESS_CODE.matches(it) } ?: "missing"
                report("detail_api keys=$keys status_code=$status")
                if (status != "missing" && status != "0") {
                    report("detail_api result=business_rejected")
                    return@withContext null
                }
                val detail = root.optJSONObject("aweme_detail")
                if (detail == null || detail.optString("aweme_id") != id) {
                    report("detail_api result=${if (detail == null) "missing_detail" else "id_mismatch"}")
                    return@withContext null
                }
                val imagePost = detail.optJSONObject("image_post_info")
                val imageList = listOf(detail.optJSONArray("images"), imagePost?.optJSONArray("images"),
                    detail.optJSONArray("image_list"), imagePost?.optJSONArray("image_list"))
                    .firstOrNull { it != null && it.length() > 0 }
                if (imageList != null) {
                    if (imageList.length() > AlbumCandidatePolicy.MAX_IMAGES) {
                        report("detail_api result=album_too_large")
                        return@withContext null
                    }
                    val alternates = imageAlternates(detail)
                    val images = (0 until imageList.length()).map { index ->
                        val image = imageList.optJSONObject(index) ?: JSONObject()
                        val matching = (image.opt("uri") as? String)?.takeIf { it.isNotBlank() && it.length <= 2048 }
                            ?.let { alternates[it] }.orEmpty()
                        val originals = listOf(image) + matching
                        val displayUrls = originals.flatMap(::displayImageUrls).distinct().take(64)
                        val downloadUrls = originals.flatMap(::downloadImageUrls).distinct().take(64)
                        val motion = imageMotion(originals)
                        val gif = displayUrls.any { url ->
                            runCatching { java.net.URI(url).path.endsWith(".gif", true) }.getOrDefault(false)
                        }
                        AlbumCandidatePolicy.ImageCandidate((downloadUrls + displayUrls).distinct().take(64),
                            image.optInt("width"), image.optInt("height"), displayUrls, downloadUrls,
                            if (originals.any { OfficialPhotoClipPolicy.declaresLive(it.opt("clip_type")) }) AlbumAssetKind.LIVE
                            else if (motion != null && originals.any { (it.opt("clip_type") as? Number)?.toInt() == 1 }) AlbumAssetKind.ANIMATED
                            else if (motion != null) AlbumAssetKind.DYNAMIC else AlbumAssetKind.STATIC,
                            if (gif) "image/gif" else "", motion, image.optString("uri"))
                    }
                    val music = detail.optJSONObject("music")
                    val address = detail.optJSONObject("video")?.optJSONObject("play_addr")
                    val ownAudio = audioUrls(address)
                    val musicDuration = music?.optDouble("duration", 0.0) ?: 0.0
                    val videoDuration = detail.optJSONObject("video")?.optDouble("duration", 0.0) ?: 0.0
                    val album = AlbumCandidatePolicy.ready(id, "https://www.douyin.com/note/$id", id,
                        detail.optString("desc"), images, (ownAudio + audioUrls(music?.opt("play_url"))).distinct(),
                        if (ownAudio.isNotEmpty() && videoDuration.isFinite() && videoDuration > 0) videoDuration / 1000.0 else musicDuration)
                    report("detail_api result=${if (album == null) "incomplete_album" else "album_candidate"} images=${images.size} " +
                        "live=${album?.images?.count { it.kind == AlbumAssetKind.LIVE } ?: 0} " +
                        "motions=${album?.images?.count { it.motion != null } ?: 0} bgm=${album?.bgmUrl?.isNotBlank() == true}")
                    ensureNotCancelled()
                    return@withContext album
                }
                val video = detail.optJSONObject("video")
                val address = video?.optJSONObject("play_addr") ?: video?.optJSONObject("download_addr")
                val playUrls = video?.let(::videoPlayUrls).orEmpty()
                val downloadUrls = video?.let(::videoDownloadUrls).orEmpty()
                val mediaIds = video?.let(::videoMediaIds).orEmpty()
                val mediaSources = WatermarkSources.videoSources(playUrls, downloadUrls, mediaIds = mediaIds)
                val mediaUrl = (playUrls + downloadUrls).firstOrNull(MediaUrls::isAllowed)
                    ?: mediaSources.firstOrNull { it.mode != WatermarkMode.ORIGINAL }?.url
                val width = video?.optInt("width", 0)?.takeIf { it > 0 }
                    ?: address?.optInt("width", 0) ?: 0
                val height = video?.optInt("height", 0)?.takeIf { it > 0 }
                    ?: address?.optInt("height", 0) ?: 0
                // The web detail response uses milliseconds for video duration.
                val durationMs = video?.optDouble("duration", 0.0)?.takeIf { it.isFinite() && it > 0 }
                    ?: detail.optDouble("duration", 0.0)
                if (mediaUrl == null || width <= 0 || height <= 0 || !durationMs.isFinite() || durationMs <= 0) {
                    report("detail_api result=incomplete_video allowed_url=${mediaUrl != null} width=$width height=$height")
                    return@withContext null
                }
                ensureNotCancelled()
                report("detail_api result=candidate width=$width height=$height duration_ms=$durationMs media_ids=${mediaIds.size} play=${playUrls.size} download=${downloadUrls.size}")
                ParsedVideo(
                    id = id,
                    title = detail.optString("desc").trim().take(500).ifBlank { "抖音视频 $id" },
                    mediaUrl = mediaUrl,
                    durationSeconds = durationMs / 1000.0,
                    width = width,
                    height = height,
                    coverUrl = listOf("origin_cover", "cover", "dynamic_cover")
                        .flatMap { urls(video?.opt(it)) }.firstOrNull(MediaUrls::isAllowed).orEmpty(),
                    mediaSources = mediaSources,
                )
            } finally {
                connection.disconnect()
                activeConnection = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ensureNotCancelled()
            lastNetworkFailure = PageNetworkFailures.exceptionKind(error)
            report("detail_api result=request_failed kind=${error.javaClass.simpleName}")
            null
        } finally {
            running.set(false)
        }
    }

    private fun displayImageUrls(image: JSONObject): List<String> = listOf("url_list", "display_image")
        .flatMap { urls(image.opt(it)) }

    private fun downloadImageUrls(image: JSONObject): List<String> = listOf("download_url", "download_addr",
        "download_url_list").flatMap { urls(image.opt(it)) }

    /** Clip metadata is accepted only from this photo or its same-URI bitrate variants. */
    private fun imageMotion(images: List<JSONObject>): AlbumCandidatePolicy.MotionCandidate? {
        val clips = images.filter { OfficialPhotoClipPolicy.allowsVideo(it.has("clip_type"), it.opt("clip_type")) }
            .mapNotNull { it.optJSONObject("video") }
        if (clips.isEmpty()) return null
        val clip = clips.firstOrNull { videoPlayUrls(it).isNotEmpty() || videoMediaIds(it).isNotEmpty() } ?: clips.first()
        val address = clip.optJSONObject("play_addr") ?: clip.optJSONObject("download_addr")
        return AlbumCandidatePolicy.MotionCandidate(
            clips.flatMap(::videoPlayUrls).distinct().take(64),
            clips.flatMap(::videoDownloadUrls).distinct().take(64),
            clips.flatMap(::videoMediaIds).distinct().take(16),
            clip.optInt("width").takeIf { it > 0 } ?: address?.optInt("width") ?: 0,
            clip.optInt("height").takeIf { it > 0 } ?: address?.optInt("height") ?: 0,
            clip.optDouble("duration", 0.0) / 1000.0,
        )
    }

    private fun imageAlternates(work: JSONObject): Map<String, List<JSONObject>> {
        val gears = work.optJSONArray("img_bitrate") ?: return emptyMap()
        val images = mutableMapOf<String, MutableList<JSONObject>>()
        for (gearIndex in 0 until minOf(gears.length(), 16)) {
            val variants = gears.optJSONObject(gearIndex)?.optJSONArray("images") ?: continue
            for (imageIndex in 0 until minOf(variants.length(), 200)) {
                val image = variants.optJSONObject(imageIndex) ?: continue
                val uri = (image.opt("uri") as? String)?.takeIf { it.isNotBlank() && it.length <= 2048 } ?: continue
                val matching = images.getOrPut(uri) { mutableListOf() }
                if (matching.size < 16) matching += image
            }
        }
        return images
    }

    private fun addressUrls(value: Any?): List<String> = (urls(value) + if (value is JSONObject)
        listOf(value.optString("uri"), value.optString("url")) else emptyList())
        .filter { it.isNotBlank() && it.length <= 32_768 && it.startsWith("https://", true) }
        .distinct().take(64)

    private fun videoRates(video: JSONObject): List<JSONObject> = listOf("bit_rate", "bitrate").flatMap { key ->
        val rates = video.optJSONArray(key)
        (0 until minOf(rates?.length() ?: 0, 16)).mapNotNull { rates?.optJSONObject(it) }
    }.take(16)

    private fun codecAddresses(video: JSONObject, prefix: String): List<Any?> = listOf(prefix, "${prefix}_h264",
        "${prefix}_265", "${prefix}_h265", "${prefix}_bytevc1", "${prefix}_bytevc2").map { video.opt(it) }

    private fun videoPlayAddresses(video: JSONObject) = (listOf(video) + videoRates(video))
        .flatMap { codecAddresses(it, "play_addr") }

    private fun videoPlayUrls(video: JSONObject) = videoPlayAddresses(video).flatMap(::addressUrls).distinct().take(64)

    private fun videoDownloadUrls(video: JSONObject) = (listOf(video) + videoRates(video))
        .flatMap { codecAddresses(it, "download_addr") }.flatMap(::addressUrls).distinct().take(64)

    private fun videoMediaIds(video: JSONObject): List<String> = ((listOf(video.opt("video_id")) +
        videoPlayAddresses(video).map { (it as? JSONObject)?.opt("uri") }).filterIsInstance<String>())
        .filter { it.matches(Regex("[A-Za-z0-9_-]{10,256}")) && it.any(Char::isLetter) }.distinct().take(16)

    private fun urls(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is org.json.JSONArray -> (0 until minOf(value.length(), 64)).map { value.optString(it) }
        is JSONObject -> urls(value.opt("url_list"))
        else -> emptyList()
    }.filter { it.isNotBlank() && it.length <= 32_768 }

    private fun audioUrls(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is org.json.JSONArray -> (0 until minOf(value.length(), 64)).map { value.optString(it) }
        is JSONObject -> listOf(value.optString("uri")) + audioUrls(value.opt("url_list")) + value.optString("url")
        else -> emptyList()
    }.mapNotNull(AlbumCandidatePolicy::secureBgmUrl).distinct().take(64)

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("视频信息请求已取消")
    }

    private companion object {
        const val ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        val VIDEO_ID = Regex("[0-9]{8,24}")
        val CONTENT_TYPE = Regex("[a-z0-9.+/-]*")
        val SAFE_KEY = Regex("[A-Za-z0-9_]{1,64}")
        val BUSINESS_CODE = Regex("-?[0-9]{1,12}")
    }
}
