package com.local.douyinsaver

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

/** Reads public page data under the opaque native UI. Does not interact with login/verification. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class BrowserParser(
    private val webView: WebView,
    private val videoId: String,
    private val onResult: (ParsedVideo) -> Unit,
    private val onError: (String) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var disposed = false
    private var completed = false
    private var previous: ParsedVideo? = null
    private var lastSnapshot = ""
    private var consoleErrors = 0
    private var resourceErrors = 0
    private var decodeErrors = 0
    private var emptyPageObservations = 0
    private var triedNotePage = false
    private var missingMusicSince: Long? = null
    private var stableAlbum: ParsedVideo? = null
    private var lastReason = "页面尚未完成加载"
    private val timeout = Runnable {
        val album = stableAlbum
        if (album != null) {
            trace("album_bgm_wait_ended images=${album.images.size} bgm=${album.bgmUrl.isNotBlank()}")
            succeed(album)
        } else fail("暂时无法解析：$lastReason")
    }
    private fun trace(message: String) = onDiagnostic(DiagnosticText.clean(message, 2400))

    private val poll = object : Runnable {
        override fun run() {
            if (disposed || completed) return
            webView.evaluateJavascript(PublicPageScript.extract(videoId)) { value ->
                if (disposed || completed) return@evaluateJavascript
                try {
                    val encoded = JSONTokener(value).nextValue() as? String
                        ?: throw IllegalStateException("Page extraction returned no JSON string")
                    val json = JSONObject(encoded)
                    val stats = json.optJSONObject("stats") ?: JSONObject()
                    val entries = json.optJSONArray("candidates")?.let { array ->
                        (0 until minOf(array.length(), 128)).mapNotNull { array.optJSONObject(it) }
                    }?.takeIf { it.isNotEmpty() } ?: listOf(json)
                    val albums = json.optJSONArray("albums")?.let { array ->
                        (0 until minOf(array.length(), 16)).mapNotNull { array.optJSONObject(it) }
                    }.orEmpty()
                    val album = albums.firstNotNullOfOrNull { item ->
                        val imageList = item.optJSONArray("images") ?: return@firstNotNullOfOrNull null
                        if (imageList.length() > AlbumCandidatePolicy.MAX_IMAGES) return@firstNotNullOfOrNull null
                        val images = (0 until imageList.length()).map { index ->
                            val image = imageList.optJSONObject(index) ?: JSONObject()
                            AlbumCandidatePolicy.ImageCandidate(strings(image, "urls"),
                                image.optInt("width"), image.optInt("height"))
                        }
                        AlbumCandidatePolicy.ready(videoId, json.optString("page"), item.optString("owner"),
                            item.optString("title"), images, strings(item, "bgmUrls"), item.optDouble("bgmDuration", 0.0))
                    }
                    val candidate = album ?: entries.firstNotNullOfOrNull { item ->
                        PlayerCandidatePolicy.ready(videoId, json.optString("page"),
                            item.optString("owner"), item.optString("url"), item.optString("title"),
                            item.optInt("ready"), item.optInt("width"), item.optInt("height"),
                            item.optDouble("duration", 0.0))?.copy(
                                coverUrl = strings(item, "coverUrls").firstOrNull(MediaUrls::isAllowed).orEmpty())
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (candidate?.isAlbum == true && candidate.bgmUrl.isBlank()) {
                        if (missingMusicSince == null) missingMusicSince = now
                    } else if (candidate != null) missingMusicSince = null
                    val url = candidate?.let { if (it.isAlbum) it.images.first().url else it.mediaUrl }
                        ?: entries.firstOrNull()?.optString("url").orEmpty()
                    lastReason = when {
                        candidate?.isAlbum == true && !AlbumCandidatePolicy.musicReadyOrGraceElapsed(candidate, missingMusicSince, now) ->
                            "图集图片已确认，正在读取作品 BGM"
                        candidate?.isAlbum == true -> "目标图集信息已确认"
                        candidate != null -> "目标视频信息已确认"
                        stats.optString("state") != "complete" -> "页面尚未完成加载"
                        albums.isNotEmpty() -> "图集图片来源不完整或暂不支持"
                        entries.any { it.optString("url").isNotBlank() } &&
                            entries.none { MediaUrls.isAllowed(it.optString("url")) } -> "页面提供的视频来源尚不支持"
                        url.isNotBlank() && candidate == null -> "视频信息不完整，尚未确认目标作品"
                        else -> "抖音页面没有提供可下载的视频信息"
                    }
                    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
                    val snapshot = "page_stats=$stats structured=${json.optBoolean("structured")} " +
                        "mediaHost=$host choices=${entries.size} albums=${albums.size} images=${candidate?.images?.size ?: 0} " +
                        "bgm=${candidate?.bgmUrl?.isNotBlank() == true} candidate=${candidate != null} reason=$lastReason"
                    if (snapshot != lastSnapshot) { lastSnapshot = snapshot; trace(snapshot) }
                    if (candidate != null && candidate == previous) {
                        if (candidate.isAlbum) stableAlbum = candidate
                        if (AlbumCandidatePolicy.musicReadyOrGraceElapsed(candidate, missingMusicSince, now)) {
                            succeed(candidate)
                            return@evaluateJavascript
                        }
                    }
                    previous = candidate
                    val canonical = json.optString("canonical")
                    if (candidate == null && !triedNotePage &&
                        ShareLinks.videoId(canonical) == videoId &&
                        runCatching { java.net.URI(canonical).path }.getOrNull()?.startsWith("/note/") == true) {
                        triedNotePage = true
                        emptyPageObservations = 0
                        trace("album_page_selected id=$videoId")
                        webView.loadUrl("https://www.iesdouyin.com/share/note/$videoId/")
                    }
                    val errors = stats.optJSONArray("knownErrors")
                    val known = (0 until (errors?.length() ?: 0)).map { errors!!.optString(it) }
                    val recognizedEmptyPage = stats.optString("state") == "complete" &&
                        ShareLinks.videoId(json.optString("page")) == videoId &&
                        stats.optInt("videos") == 0 && candidate == null &&
                        ("抱歉出错了" in known || "请尝试在抖音内观看" in known)
                    emptyPageObservations = if (recognizedEmptyPage) emptyPageObservations + 1 else 0
                    if (emptyPageObservations >= 3) {
                        fail("抖音分享页提示需在抖音内观看，没有提供视频信息")
                        return@evaluateJavascript
                    }
                } catch (error: Exception) {
                    if (decodeErrors++ < 3) trace("extract_error type=${error.javaClass.simpleName} message=${error.message}")
                    lastReason = "页面数据读取异常"
                }
                handler.postDelayed(this, 800)
            }
        }
    }

    private fun succeed(result: ParsedVideo) {
        if (disposed || completed) return
        completed = true
        handler.removeCallbacksAndMessages(null)
        onResult(result)
    }

    private fun strings(json: JSONObject, name: String): List<String> = json.optJSONArray(name)?.let { array ->
        (0 until minOf(array.length(), 64)).map { array.optString(it) }
            .filter { it.isNotBlank() && it.length <= 32_768 }
    }.orEmpty()

    init {
        require(videoId.matches(Regex("\\d{10,25}")))
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView.setOnTouchListener { _, _ -> true }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = WebSettings.getDefaultUserAgent(webView.context)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        trace("WebView=${WebView.getCurrentWebViewPackage()?.versionName} expectedId=$videoId mode=mobile")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && consoleErrors++ < 8) {
                    val host = runCatching { java.net.URI(message.sourceId()).host }.getOrNull().orEmpty()
                    trace("script_error sourceHost=$host line=${message.lineNumber()} message=${message.message()}")
                }
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                trace("page_started host=${android.net.Uri.parse(url).host}")
            }
            override fun onPageFinished(view: WebView, url: String) {
                trace("page_finished host=${android.net.Uri.parse(url).host} id=${ShareLinks.videoId(url)}")
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && (request.url.scheme != "https" || !ShareLinks.isShareHost(request.url.host))

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame || resourceErrors++ < 8)
                    trace("network_error main=${request.isForMainFrame} code=${error.errorCode} host=${request.url.host}")
                if (request.isForMainFrame) fail("抖音页面连接失败（错误码 ${error.errorCode}）")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame || resourceErrors++ < 8)
                    trace("http_error main=${request.isForMainFrame} code=${response.statusCode} host=${request.url.host}")
                if (request.isForMainFrame) fail("抖音页面返回访问错误（HTTP ${response.statusCode}）")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail("视频解析组件已停止，请重新解析")
                dispose()
                return true
            }
        }
        webView.loadUrl("https://www.iesdouyin.com/share/video/$videoId/")
        handler.postDelayed(timeout, 60_000)
        handler.postDelayed(poll, 800)
    }

    private fun fail(message: String) {
        if (disposed || completed) return
        completed = true
        trace("parser_failed: $message")
        handler.removeCallbacksAndMessages(null)
        onError(message)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        handler.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }
}

