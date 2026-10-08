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
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal enum class DesktopAlbumStatus { CANDIDATE, NEEDS_VERIFICATION, UNAVAILABLE, FAILED, CANCELLED }

/** CANDIDATE is page provenance only; downloading and decoding still have to verify the media. */
internal data class DesktopAlbumResult(
    val status: DesktopAlbumStatus,
    val album: ParsedVideo? = null,
    val reason: String = "",
)

/** Reads the same work's desktop album, retaining its page for an explicit manual verification. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal class DesktopAlbumResolver(
    private val webView: WebView,
    private val id: String,
    private val onResult: (DesktopAlbumResult) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
    private val timeoutMs: Long = TIMEOUT_MS,
    private val allowVideo: Boolean = false,
    private val allowStaticAlbums: Boolean = false,
) {
    private enum class Phase { POLLING, NEEDS_VERIFICATION, MANUAL_VERIFICATION, SETTLED, DISPOSED }

    private val handler = Handler(Looper.getMainLooper())
    private var phase = Phase.SETTLED
    private var generation = 0
    private var timeout: Runnable? = null
    private var poll: Runnable? = null
    private var hydratedSince: Long? = null
    private var previousSignature: List<String>? = null
    private var stableObservations = 0
    private var lastAlbum: ParsedVideo? = null
    private var lastReason = "桌面图集页面尚未完成加载"
    private var lastSnapshot = ""
    private var lastTargetSnapshot = ""
    private var lastLimitsSnapshot = ""
    private var lastVariantsSnapshot = ""
    private var decodedObservations = 0
    private var decodeErrors = 0
    private var consoleErrors = 0
    private var resourceErrors = 0
    private var blockedNavigations = 0

    init {
        require(timeoutMs in 1_000L..TIMEOUT_MS) { "动态资源读取时限无效" }
        require(id.matches(Regex("\\d{15,22}"))) { "作品编号格式不正确" }
        setInteractive(false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = DesktopAlbumPagePolicy.desktopUserAgent(WebSettings.getDefaultUserAgent(webView.context))
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.setDownloadListener { _, _, _, _, _ -> trace("desktop_download_navigation_blocked") }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && consoleErrors++ < 4)
                    trace("desktop_script_error count=$consoleErrors sourceHost=${DesktopAlbumPagePolicy.diagnosticHost(message.sourceId())} " +
                        "line=${message.lineNumber()} message=${DiagnosticText.clean(message.message(), 160)}")
                return true
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message): Boolean {
                trace("desktop_new_window_blocked manual=${phase == Phase.MANUAL_VERIFICATION}")
                return false
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
                if (phase == Phase.MANUAL_VERIFICATION) false else { result.cancel(); true }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
                if (phase == Phase.MANUAL_VERIFICATION) false else { result.cancel(); true }

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String,
                result: JsPromptResult): Boolean =
                if (phase == Phase.MANUAL_VERIFICATION) false else { result.cancel(); true }

            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && blockNavigation(request.url.toString())

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blockNavigation(url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (phase == Phase.DISPOSED) return
                if (blockNavigation(url)) {
                    view.stopLoading()
                    if (phase == Phase.POLLING) settle(DesktopAlbumStatus.UNAVAILABLE, "桌面页面跳转未提供同一作品的信息")
                    return
                }
                previousSignature = null
                stableObservations = 0
                hydratedSince = null
                trace("desktop_page_started sameWork=${ShareLinks.videoId(url) == id} manual=${phase == Phase.MANUAL_VERIFICATION}")
            }

            override fun onPageCommitVisible(view: WebView, url: String) { prepareViewport(url) }
            override fun onPageFinished(view: WebView, url: String) { prepareViewport(url) }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame || resourceErrors++ < 4)
                    trace("desktop_network_error main=${request.isForMainFrame} code=${error.errorCode} " +
                        "host=${DesktopAlbumPagePolicy.diagnosticHost(request.url.toString())}")
                if (request.isForMainFrame && phase == Phase.POLLING)
                    settle(DesktopAlbumStatus.FAILED, PageNetworkFailures.browserMessage(error.errorCode))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame || resourceErrors++ < 4)
                    trace("desktop_http_error main=${request.isForMainFrame} code=${response.statusCode} " +
                        "host=${DesktopAlbumPagePolicy.diagnosticHost(request.url.toString())}")
                if (request.isForMainFrame && phase == Phase.POLLING)
                    settle(DesktopAlbumStatus.FAILED, "桌面图集页面返回访问错误（HTTP ${response.statusCode}）")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (phase != Phase.DISPOSED && phase != Phase.SETTLED)
                    settle(DesktopAlbumStatus.FAILED, "桌面图集解析组件已停止，请重新解析")
                dispose()
                return true
            }
        }
        trace("desktop_config chromeMajor=${DesktopAlbumPagePolicy.chromeVersion(webView.settings.userAgentString).substringBefore('.')} viewport=$DESKTOP_WIDTH")
        startReading()
    }

    /** Enables only an explicitly displayed page; no automatic login or verification interaction. */
    fun enterVerification() {
        if (phase == Phase.DISPOSED) return
        stopReading()
        phase = Phase.MANUAL_VERIFICATION
        setInteractive(true)
        webView.requestFocus()
        trace("desktop_verification_entered")
    }

    /** Keeps this WebView's session, then starts a fresh bounded read of the original work. */
    fun resumeAfterVerification() {
        if (phase == Phase.DISPOSED) return
        setInteractive(false)
        trace("desktop_verification_resumed")
        startReading()
    }

    fun cancel() {
        if (phase == Phase.DISPOSED) return
        val notifyCancellation = phase != Phase.SETTLED
        stopReading()
        phase = Phase.SETTLED
        trace("desktop_cancelled")
        try {
            if (notifyCancellation)
                onResult(DesktopAlbumResult(DesktopAlbumStatus.CANCELLED, reason = "已取消桌面图集解析"))
        }
        finally { dispose() }
    }

    /** Disposal is silent, so a cancelled or superseded request cannot be reported as a failure. */
    fun dispose() {
        if (phase == Phase.DISPOSED) return
        stopReading()
        phase = Phase.DISPOSED
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.setDownloadListener(null)
        webView.setOnTouchListener(null)
        webView.destroy()
    }

    private fun startReading() {
        stopReading()
        phase = Phase.POLLING
        hydratedSince = null
        previousSignature = null
        stableObservations = 0
        lastAlbum = null
        lastReason = "桌面图集页面尚未完成加载"
        lastSnapshot = ""
        lastTargetSnapshot = ""
        lastLimitsSnapshot = ""
        lastVariantsSnapshot = ""
        decodedObservations = 0
        decodeErrors = 0
        val activeGeneration = generation
        timeout = Runnable {
            if (!isReading(activeGeneration)) return@Runnable
            settle(if (decodedObservations == 0 && decodeErrors > 0) DesktopAlbumStatus.FAILED else DesktopAlbumStatus.UNAVAILABLE,
                if (decodedObservations == 0 && decodeErrors > 0) "桌面图集页面数据读取失败" else "桌面图集读取超时：$lastReason")
        }
        poll = object : Runnable {
            override fun run() {
                if (!isReading(activeGeneration)) return
                webView.evaluateJavascript(DesktopAlbumScript.extract(id, allowVideo)) { value ->
                    if (!isReading(activeGeneration)) return@evaluateJavascript
                    try {
                        require(value.length <= MAX_EXTRACTION_LENGTH)
                        val encoded = JSONTokener(value).nextValue() as? String
                            ?: throw IllegalArgumentException("invalid extraction type")
                        decodedObservations++
                        readObservation(JSONObject(encoded))
                    } catch (error: Exception) {
                        previousSignature = null
                        stableObservations = 0
                        lastReason = "桌面页面数据尚未读取完成"
                        if (decodeErrors++ < 3) trace("desktop_extract_error type=${error.javaClass.simpleName}")
                    }
                    if (isReading(activeGeneration)) handler.postDelayed(this, POLL_MS)
                }
            }
        }
        webView.loadUrl(noteUrl())
        timeout?.let { handler.postDelayed(it, timeoutMs) }
        poll?.let { handler.postDelayed(it, POLL_MS) }
    }

    private fun readObservation(json: JSONObject) {
        val stats = json.optJSONObject("stats") ?: JSONObject()
        val state = stats.optString("state").takeIf { it in PAGE_STATES } ?: "unknown"
        val status = json.optString("status").takeIf { it in SCRIPT_STATUSES } ?: "unknown"
        val provenance = stats.optString("provenance").takeIf { it in PROVENANCE } ?: "unknown"
        val sameWork = DesktopAlbumPagePolicy.isWorkPage(id, json.optString("page")) && json.optString("owner") == id
        val gate = stats.optString("gate").takeIf { it in GATES } ?: "none"
        val actualGate = (gate == "captcha" && stats.optBoolean("captchaVisible")) ||
            (gate == "login" && stats.optBoolean("loginGateVisible"))
        val pageMatches = DesktopAlbumPagePolicy.isWorkPage(id, json.optString("page"))
        val count = json.optJSONArray("images")?.length() ?: 0
        val snapshot = "desktop_observation state=$state status=$status sameWork=$sameWork " +
            "images=${count.coerceIn(0, 10_000)} videos=${stats.optInt("videoCandidates").coerceIn(0, 10_000)} declared=${stats.optInt("declaredImages").coerceIn(0, 10_000)} " +
            "motions=${stats.optInt("motions").coerceIn(0, 10_000)} missingPosters=${stats.optInt("missingPosters").coerceIn(0, 10_000)} " +
            "matchingOwners=${stats.optInt("matchingOwners").coerceIn(0, 10_000)} provenance=$provenance gate=$gate " +
            "scripts=${stats.optInt("scripts").coerceIn(0, 10_000)} rscChunks=${stats.optInt("rscChunks").coerceIn(0, 10_000)} " +
            "rscRecords=${stats.optInt("rscRecords").coerceIn(0, 10_000)} jsonRoots=${stats.optInt("jsonRoots").coerceIn(0, 10_000)} " +
            "hydrationRoots=${stats.optInt("hydrationRoots").coerceIn(0, 10_000)} visitedNodes=${stats.optInt("visitedNodes").coerceIn(0, 100_000)} " +
            "albumCandidates=${stats.optInt("albumCandidates").coerceIn(0, 10_000)} domPairedImages=${stats.optInt("domPairedImages").coerceIn(0, 10_000)} " +
            "truncated=${stats.optBoolean("truncated")} conflictingAlbums=${stats.optBoolean("conflictingAlbums")}"
        if (snapshot != lastSnapshot) { lastSnapshot = snapshot; trace(snapshot) }
        targetImageSummary(stats)?.let { summary ->
            if (summary != lastTargetSnapshot) {
                lastTargetSnapshot = summary
                trace("desktop_target_images $summary", 4_000)
            }
        }
        scanLimitSummary(stats)?.let { summary ->
            if (summary != lastLimitsSnapshot) {
                lastLimitsSnapshot = summary
                trace("desktop_limits $summary", 4_000)
            }
        }
        workVariantSummary(stats)?.let { summary ->
            if (summary != lastVariantsSnapshot) {
                lastVariantsSnapshot = summary
                trace("desktop_work_variants $summary", 4_000)
            }
        }
        if (pageMatches && status == "needs_verification" && actualGate) {
            stopReading()
            phase = Phase.NEEDS_VERIFICATION
            lastReason = if (gate == "captcha") "桌面页面需要完成验证" else "桌面页面需要登录后继续"
            onResult(DesktopAlbumResult(DesktopAlbumStatus.NEEDS_VERIFICATION, reason = lastReason))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (pageMatches && state in setOf("interactive", "complete") && hydratedSince == null) hydratedSince = now
        val observed = parseAlbum(json, stats) ?: if (allowVideo) parseVideo(json, stats) else null
        val album = observed?.first
        val motionCount = album?.images?.count { it.motion != null } ?: 0
        val clean = album?.let { WatermarkSources.available(it, WatermarkMode.CLEAN) } == true
        if (album != null && clean) lastAlbum = album
        val hydrationElapsed = hydratedSince?.let { now >= it && now - it >= HYDRATION_GRACE_MS } == true
        // Only the share-data fallback may settle a complete cover/native-animation
        // array. Wait for hydration first so late image-owned clips retain priority.
        // A LIVE/DYNAMIC hint with no clip is never a completed static asset.
        val completeStillAlbum = allowStaticAlbums && album?.isAlbum == true && state == "complete" &&
            hydrationElapsed && album.images.all { it.motion == null &&
                it.kind in listOf(AlbumAssetKind.STATIC, AlbumAssetKind.ANIMATED) }
        val ready = status == "candidate" && album != null && clean &&
            (motionCount > 0 || allowVideo && !album.isAlbum || completeStillAlbum)
        if (ready) {
            val signature = observed!!.second
            stableObservations = if (signature == previousSignature) stableObservations + 1 else 1
            previousSignature = signature
            lastReason = if (album!!.isAlbum && motionCount > 0) "已读取同一作品的逐图动态候选，等待信息稳定"
                else if (album.isAlbum) "已读取同一作品的完整图片候选，等待信息稳定"
                else "已读取同一作品的视频备用来源，等待信息稳定"
            if (stableObservations >= 2) {
                settle(DesktopAlbumStatus.CANDIDATE, if (album.isAlbum && motionCount > 0) "已读取逐图动态候选，仍需下载与解码验证"
                    else if (album.isAlbum) "已读取完整图片候选，仍需下载与解码验证"
                    else "已读取同一作品的视频备用来源，仍需下载与解码验证", album)
                return
            }
        } else {
            previousSignature = null
            stableObservations = 0
            lastReason = when {
                !sameWork -> if (allowVideo) "尚未读取到完整且绑定同一作品的视频" else "尚未读取到完整且绑定同一作品的图集"
                album == null -> if (allowVideo) "视频备用来源尚不完整" else "图集图片顺序或来源尚不完整"
                !clean -> "部分图片或动态素材尚未提供符合现有来源规则的地址"
                motionCount == 0 -> "当前桌面来源未提供逐图动态片段"
                else -> "图集页面仍在载入动态信息"
            }
        }
        // document.complete does not mean the video's later fetch/hydration has arrived.
        // An empty ordinary-video fallback retains its existing bounded deadline.
        if (!ready && pageMatches && state == "complete" && status != "loading" && hydrationElapsed &&
            (!allowVideo || observed != null))
            settle(DesktopAlbumStatus.UNAVAILABLE, lastReason)
    }

    private fun parseVideo(json: JSONObject, stats: JSONObject): Pair<ParsedVideo, List<String>>? {
        if (!allowVideo || !DesktopAlbumPagePolicy.isWorkPage(id, json.optString("page")) ||
            json.optString("owner") != id || (json.optJSONArray("images")?.length() ?: 0) != 0 ||
            stats.optInt("declaredImages") != 0 ||
            stats.optBoolean("conflictingAlbums")) return null
        val list = json.optJSONArray("videoCandidates") ?: return null
        val candidates = (0 until minOf(list.length(), 16)).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            DesktopVideoCandidatePolicy.Candidate(item.optString("owner"), item.optString("title"),
                strings(item, "playUrls"), strings(item, "downloadUrls"), strings(item, "mediaIds"),
                strings(item, "displayPlaybackUrls"), strings(item, "coverUrls"),
                item.optInt("width"), item.optInt("height"), item.optDouble("durationSeconds"))
        }
        val video = DesktopVideoCandidatePolicy.ready(id, json.optString("page"), candidates) ?: return null
        val signature = listOf(video.id, video.width.toString(), video.height.toString(), video.durationSeconds.toString()) +
            video.mediaSources.filter { it.mode == WatermarkMode.CLEAN }
                .map { DesktopAlbumPagePolicy.mediaIdentity(it.url) }.distinct().sorted()
        return video to signature
    }

    private fun parseAlbum(json: JSONObject, stats: JSONObject): Pair<ParsedVideo, List<String>>? {
        if (!DesktopAlbumPagePolicy.isWorkPage(id, json.optString("page")) || json.optString("owner") != id) return null
        val list = json.optJSONArray("images") ?: return null
        if (list.length() !in 1..AlbumCandidatePolicy.MAX_IMAGES ||
            stats.optInt("declaredImages", list.length()) != list.length() || stats.optInt("missingPosters") != 0 ||
            stats.optBoolean("conflictingAlbums")) return null
        // Validate every original slot before accepting an image-owned playback role from the script.
        if ((0 until list.length()).any { index ->
            val image = list.optJSONObject(index)
            image == null || !image.has("sourceIndex") || image.optInt("sourceIndex", -1) != index
        }) return null
        val signature = mutableListOf<String>()
        val images = (0 until list.length()).map { index ->
            val image = list.optJSONObject(index) ?: return null
            val motion = image.optJSONObject("motion")?.let { clip ->
                AlbumCandidatePolicy.MotionCandidate(strings(clip, "playUrls"), strings(clip, "downloadUrls"),
                    strings(clip, "mediaIds"), clip.optInt("width"), clip.optInt("height"),
                    clip.optDouble("durationSeconds", 0.0), strings(clip, "displayPlaybackUrls"))
            }
            val urls = strings(image, "urls")
            val display = strings(image, "displayUrls")
            val download = strings(image, "downloadUrls")
            val kind = runCatching { AlbumAssetKind.valueOf(image.optString("kind")) }.getOrDefault(AlbumAssetKind.STATIC)
            signature += listOf(index.toString(), image.optString("imageKey").take(512), kind.name,
                (urls + display + download).map(DesktopAlbumPagePolicy::mediaIdentity).distinct().sorted().joinToString(";"),
                motion?.mediaIds?.distinct()?.sorted()?.joinToString(";").orEmpty(),
                (motion?.playUrls.orEmpty() + motion?.downloadUrls.orEmpty())
                    .map(DesktopAlbumPagePolicy::mediaIdentity).distinct().sorted().joinToString(";"),
                motion?.displayPlaybackUrls.orEmpty().map(DesktopAlbumPagePolicy::mediaIdentity).distinct().sorted().joinToString(";"))
            AlbumCandidatePolicy.ImageCandidate(urls, image.optInt("width"), image.optInt("height"),
                display, download, kind, image.optString("mimeType"), motion, image.optString("imageKey"))
        }
        val album = AlbumCandidatePolicy.ready(id, json.optString("page"), json.optString("owner"),
            json.optString("title"), images, strings(json, "bgmUrls"), json.optDouble("bgmDuration", 0.0)) ?: return null
        return album to signature
    }

    /** Rebuild diagnostic entries from flags/counts and fixed field names; never echo page objects. */
    private fun targetImageSummary(stats: JSONObject): String? {
        val list = stats.optJSONArray("targetImages") ?: return null
        val entries = JSONArray()
        repeat(minOf(list.length(), 12)) { index ->
            val source = list.optJSONObject(index) ?: return@repeat
            val entry = JSONObject().put("sourceIndex", source.optInt("sourceIndex", -1).coerceIn(-1, AlbumCandidatePolicy.MAX_IMAGES))
                .put("kind", source.optString("kind").takeIf { it in setOf("STATIC", "ANIMATED", "LIVE", "DYNAMIC") } ?: "unknown")
            TARGET_FLAGS.forEach { key -> if (source.opt(key) is Boolean) entry.put(key, source.optBoolean(key)) }
            TARGET_COUNTS.forEach { key -> if (source.opt(key) is Number) entry.put(key, source.optInt(key).coerceIn(0, 1_000)) }
            TARGET_TYPES.forEach { key -> source.optString(key).takeIf { it in VALUE_TYPES }?.let { entry.put(key, it) } }
            listOf("clip_type", "clipType").forEach { key ->
                val value = source.opt(key) as? Number
                if (value != null && value.toDouble().isFinite() && value.toDouble() in -1_000_000.0..1_000_000.0)
                    entry.put(key, value)
            }
            val fields = JSONObject()
            source.optJSONObject("fields")?.let { observed ->
                TARGET_IMAGE_FIELDS.forEach { key -> if (observed.opt(key) is Boolean) fields.put(key, observed.optBoolean(key)) }
            }
            entry.put("fields", fields)
            source.optJSONArray("videoKeys")?.let { keys ->
                entry.put("videoKeys", JSONArray((0 until minOf(keys.length(), 32)).mapNotNull { keyIndex ->
                    (keys.opt(keyIndex) as? String)?.takeIf { it in TARGET_VIDEO_FIELDS }
                }.distinct()))
            }
            listOf("hosts", "playAddrHosts", "play_addrHosts").forEach { key ->
                source.optJSONArray(key)?.let { hosts ->
                    entry.put(key, JSONArray((0 until minOf(hosts.length(), 16)).mapNotNull { hostIndex ->
                        (hosts.opt(hostIndex) as? String)?.let(DesktopAlbumPagePolicy::diagnosticHostname)?.takeIf { it.isNotBlank() }
                    }.distinct().take(2)))
                }
            }
            entries.put(entry)
        }
        val summary = JSONObject().put("items", entries)
        fun updateOmitted() {
            val omitted = stats.optInt("targetImagesOmitted").coerceIn(0, 10_000) + (list.length() - entries.length()).coerceAtLeast(0)
            summary.put("omitted", omitted.coerceAtMost(10_000))
        }
        updateOmitted()
        // Keep a complete JSON summary; do not cut through a diagnostic object when it is large.
        while (summary.toString().length > 3_600 && entries.length() > 0) {
            entries.remove(entries.length() - 1)
            updateOmitted()
        }
        return summary.toString()
    }

    private fun scanLimitSummary(stats: JSONObject): String? {
        val source = stats.optJSONObject("limits") ?: return null
        val limits = JSONObject()
        SCAN_LIMITS.forEach { key -> if (source.opt(key) is Number) limits.put(key, source.optInt(key).coerceIn(0, 1_000_000)) }
        return limits.toString()
    }

    private fun workVariantSummary(stats: JSONObject): String? {
        val source = stats.optJSONObject("workVariants") ?: return null
        val variants = JSONObject()
        WORK_VARIANTS.forEach { key -> source.optJSONObject(key)?.let { observed ->
            val entry = JSONObject()
            if (observed.opt("present") is Boolean) entry.put("present", observed.optBoolean("present"))
            VARIANT_COUNTS.forEach { name -> if (observed.opt(name) is Number)
                entry.put(name, observed.optInt(name).coerceIn(0, 1_000_000)) }
            variants.put(key, entry)
        } }
        return variants.toString()
    }

    private fun prepareViewport(url: String) {
        if (phase == Phase.DISPOSED || !DesktopAlbumPagePolicy.isWorkPage(id, url)) return
        // The desktop UA alone does not change a responsive page's device-width viewport.
        webView.evaluateJavascript("""
            (function(){try {
              var root=document.documentElement;
              if(root)root.style.minWidth='${DESKTOP_WIDTH}px';
              var meta=document.querySelector('meta[name="viewport"]');
              if(!meta&&document.head){meta=document.createElement('meta');meta.name='viewport';document.head.appendChild(meta);}
              if(meta)meta.content='width=$DESKTOP_WIDTH';
            }catch(e){} return true;})()
        """.trimIndent(), null)
        trace("desktop_page_visible sameWork=true viewport=$DESKTOP_WIDTH")
    }

    private fun blockNavigation(url: String): Boolean {
        val blocked = !DesktopAlbumPagePolicy.isNavigationAllowed(id, url, phase == Phase.MANUAL_VERIFICATION)
        if (blocked && blockedNavigations++ < 4) trace("desktop_navigation_blocked count=$blockedNavigations")
        return blocked
    }

    private fun setInteractive(enabled: Boolean) {
        if (!enabled) webView.clearFocus()
        webView.isFocusable = enabled
        webView.isFocusableInTouchMode = enabled
        webView.importantForAccessibility = if (enabled) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView.setOnTouchListener(if (enabled) null else View.OnTouchListener { _, _ -> true })
    }

    private fun settle(status: DesktopAlbumStatus, reason: String, album: ParsedVideo? = lastAlbum) {
        if (phase == Phase.DISPOSED || phase == Phase.SETTLED) return
        stopReading()
        phase = Phase.SETTLED
        setInteractive(false)
        trace("desktop_result status=${status.name} images=${album?.images?.size ?: 0} motions=${album?.images?.count { it.motion != null } ?: 0}")
        onResult(DesktopAlbumResult(status, album, reason))
    }

    private fun stopReading() {
        generation++
        handler.removeCallbacksAndMessages(null)
        poll = null
        timeout = null
    }

    private fun isReading(activeGeneration: Int) = phase == Phase.POLLING && generation == activeGeneration
    private fun noteUrl() = "https://www.douyin.com/${if (allowVideo) "video" else "note"}/$id"
    private fun trace(message: String, limit: Int = 700) = onDiagnostic(DiagnosticText.clean(message, limit.coerceIn(0, 4_000)))
    private fun strings(json: JSONObject, name: String): List<String> = json.optJSONArray(name)?.let { array ->
        (0 until minOf(array.length(), 64)).mapNotNull { index -> (array.opt(index) as? String)
            ?.takeIf { it.isNotBlank() && it.length <= 32_768 } }
    }.orEmpty()

    private companion object {
        const val POLL_MS = 800L
        const val TIMEOUT_MS = 30_000L
        const val HYDRATION_GRACE_MS = 8_000L
        const val DESKTOP_WIDTH = 1280
        const val MAX_EXTRACTION_LENGTH = 8_388_608
        val PAGE_STATES = setOf("loading", "interactive", "complete", "unknown")
        val SCRIPT_STATUSES = setOf("candidate", "loading", "unavailable", "needs_verification")
        val PROVENANCE = setOf("rsc", "router", "render", "hydration", "dom")
        val GATES = setOf("none", "captcha", "login")
        val TARGET_FLAGS = setOf("ownVideoPresent", "ownVideoObject", "mediaIdPresent", "candidateMotion")
        val TARGET_COUNTS = setOf("playAddrUrls", "play_addrUrls", "bitrateCount", "playUrls", "downloadUrls", "mediaIds", "displayPlaybackUrls")
        val TARGET_TYPES = setOf("ownVideoType", "playAddrType", "play_addrType")
        val VALUE_TYPES = setOf("missing", "object", "array", "string", "number", "boolean", "null")
        val TARGET_IMAGE_FIELDS = setOf("uri", "imageUri", "imageId", "image_id", "urlList", "url_list", "displayImage",
            "display_image", "downloadUrl", "download_url", "downloadAddr", "download_addr", "downloadUrlList", "download_url_list",
            "width", "height", "video", "livePhotoInfo", "live_photo_info", "clip_type", "clipType", "mimeType", "mime_type")
        val TARGET_VIDEO_FIELDS = setOf("playAddr", "play_addr", "downloadAddr", "download_addr", "bitRateList", "bit_rate", "bitrate",
            "width", "height", "duration", "durationSeconds", "videoId", "video_id")
        val SCAN_LIMITS = setOf("roots", "jsonString", "jsonLiteral", "scriptSize", "scriptTotal", "scriptCount", "rscChunks",
            "rscRecords", "windowRscTotal", "workImages", "albumCandidates", "objectKeys", "queueNodes", "variantImages")
        val WORK_VARIANTS = setOf("img_bitrate", "imgBitrate", "imageBitrate", "image_bit_rate", "imagePostInfo", "image_post_info")
        val VARIANT_COUNTS = setOf("entries", "images", "scannedImages", "videoObjects", "playUrls", "downloadUrls", "mediaIds")
    }
}

/** Pure navigation and stability rules; identity keys are never logged or used to rewrite media URLs. */
internal object DesktopAlbumPagePolicy {
    private val chrome = Regex("Chrome/(\\d+(?:\\.\\d+){0,3})")
    private val loginPaths = Regex("^/(?:passport|login|oauth|sso|connect)(?:/|$)", RegexOption.IGNORE_CASE)
    private val identityQueryKeys = setOf("video_id", "item_id", "uri")

    fun chromeVersion(defaultUserAgent: String): String =
        chrome.find(defaultUserAgent)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("当前 WebView 未提供 Chromium 版本")

    fun desktopUserAgent(defaultUserAgent: String): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeVersion(defaultUserAgent)} Safari/537.36"

    fun isWorkPage(id: String, url: String): Boolean = officialHttps(url)?.let {
        ShareLinks.isShareHost(it.host) && ShareLinks.videoId(url) == id
    } == true

    fun isNavigationAllowed(id: String, url: String, manualVerification: Boolean): Boolean {
        val uri = officialHttps(url) ?: return false
        if (isWorkPage(id, url)) return true
        if (!manualVerification || ShareLinks.videoId(url) != null) return false
        val host = uri.host.lowercase()
        return loginPaths.containsMatchIn(uri.path.orEmpty()) ||
            host in setOf("sso.douyin.com", "sso.iesdouyin.com", "passport.bytedance.com", "sso.bytedance.com")
    }

    fun mediaIdentity(url: String): String = runCatching {
        val uri = URI(url)
        val identity = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            if (key in identityQueryKeys) "$key=${pair.substringAfter('=', "")}" else null
        }.sorted().joinToString("&")
        "${uri.host.orEmpty().lowercase()}${uri.rawPath.orEmpty()}?$identity"
    }.getOrDefault("")

    fun diagnosticHost(url: String): String = diagnosticHostname(runCatching { URI(url).host }.getOrNull().orEmpty())

    fun diagnosticHostname(value: String): String {
        if (value.length !in 3..253) return ""
        val labels = value.split('.')
        return value.lowercase().takeIf { labels.size >= 2 && labels.last().any { it.isLetter() } &&
            labels.all { it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) } }.orEmpty()
    }

    private fun officialHttps(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf { uri ->
        uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
            (ShareLinks.isShareHost(uri.host) || uri.host?.lowercase() in setOf("passport.bytedance.com", "sso.bytedance.com"))
    }
}
