package com.local.douyinsaver

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** DIAGNOSTIC_ONLY: collection completion is never source/download acceptance.
 * No Activity, global engine, MediaStore writes, account interaction, TLS override,
 * arbitrary object keys, source HTML, media URLs or cookie values enter the report. */
@RunWith(AndroidJUnit4::class)
class OfficialWorkDataShapeDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private val runtimeNames = setOf("WebViewChromiumPrefs", "AwOriginVisitLoggerPrefs")

    @Test
    fun inspectOnlyTheSafeShapeOfOneExplicitOfficialWorkOnMobileAndDesktop() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires run_work_shape_diagnostic=true", args.getString("run_work_shape_diagnostic") == "true")
        val share = ShareLinks.extract(requireNotNull(args.getString("shareUrl")))
        val expected = requireNotNull(args.getString("expectedId"))
        require(expected.matches(Regex("[0-9]{15,22}")))
        val started = SystemClock.elapsedRealtime()
        deadline = started + TOTAL_MS
        val root = File(context.cacheDir, "official_work_shape_${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val report = JSONObject().put("scope", "DIAGNOSTIC_ONLY").put("collectorCompleted", false)
            .put("sourceAccepted", false).put("downloadAccepted", false).put("mediaStoreWrites", 0)
            .put("uiUsed", false).put("activityUsed", false).put("windowAttached", false)
            .put("inputInjected", false).put("permissionsChanged", false).put("tlsVerificationChanged", false)
            .put("testPreferenceWrites", 0).put("expectedId", expected)
            .put("installedVersionName", installed.versionName).put("installedVersionCode", installed.longVersionCode)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("totalBudgetMs", TOTAL_MS).put("pageBudgetMs", PAGE_MS)
            .put("cookieBoundary", "OBSERVED_OFFICIAL_SCOPES_EXISTING_PAIRS_IN_MEMORY_ONLY_NO_JAR_RESTORE")
            .put("runtimePreferenceBoundary", "EXACT_PROVIDER_NAMES_HASH_COUNT_ONLY_NO_RESTORE_OR_DELETE")
        val pageReports = JSONArray()
        report.put("pages", pageReports)
        val mobile = "https://www.iesdouyin.com/share/video/$expected/"
        val desktop = "https://www.douyin.com/video/$expected"
        val cookieScopes = linkedMapOf("MOBILE" to mobile, "DESKTOP" to desktop, "SUPPLIED_SHARE" to share)
        val businessBefore = preferences(existingPreferenceNames() - runtimeNames)
        val runtimeBefore = preferences(runtimeNames)
        val historyBefore = DownloadRecords(application).history().toList()
        val background = File(context.filesDir, "appearance/background.jpg")
        val backgroundBefore = background.takeIf(File::isFile)?.let(::fileHash)
        var acceptCookieBefore = false
        var cookiesBefore: Map<String, List<String>> = emptyMap()
        var failure: Throwable? = null
        fun save() {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(root, "shape.json").writeText(report.toString(2))
        }
        println("official_work_shape_directory=${root.absolutePath}")
        try {
            main {
                acceptCookieBefore = CookieManager.getInstance().acceptCookie()
                cookiesBefore = cookiePairs(cookieScopes)
            }
            val resolved = resolveExpectedShare(share)
            report.put("shareIdMatchesExpected", resolved == expected)
            assertEquals("Official share mapping differs from the explicit expected work", expected, resolved)
            save()
            pageReports.put(observePage("MOBILE", mobile, expected, false))
            save()
            pageReports.put(observePage("DESKTOP", desktop, expected, true))
            report.put("collectorCompleted", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failureType", error.javaClass.simpleName)
        } finally {
            val cleanupErrors = mutableListOf<String>()
            fun protect(label: String, action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    cleanupErrors += "$label:${error.javaClass.simpleName}"
                    if (failure == null) failure = error
                }
            }
            protect("cookies") {
                main(cleanup = true) {
                    val after = cookiePairs(cookieScopes)
                    val summary = JSONArray()
                    for ((label, before) in cookiesBefore) {
                        val remaining = after.getValue(label).toMutableList()
                        var missing = 0
                        for (pair in before) if (!remaining.remove(pair)) missing++
                        summary.put(JSONObject().put("scope", label).put("beforePairCount", before.size)
                            .put("afterPairCount", after.getValue(label).size).put("missingExistingPairCount", missing)
                            .put("newPairCount", remaining.size))
                    }
                    report.put("cookieScopeProtection", summary)
                    report.put("preexistingObservedCookiesPreserved", (0 until summary.length()).all {
                        summary.getJSONObject(it).getInt("missingExistingPairCount") == 0
                    })
                    assertEquals("Cookie acceptance was changed", acceptCookieBefore, CookieManager.getInstance().acceptCookie())
                    assertTrue("An existing observed cookie pair changed", report.getBoolean("preexistingObservedCookiesPreserved"))
                }
            }
            protect("business_preferences") { assertEquals(businessBefore, preferences(businessBefore.keys)) }
            protect("history") { assertEquals(historyBefore, DownloadRecords(application).history()) }
            protect("background") { assertEquals(backgroundBefore, background.takeIf(File::isFile)?.let(::fileHash)) }
            protect("runtime_preferences") {
                report.put("runtimePreferencesBefore", runtimeSummary(runtimeBefore))
                    .put("runtimePreferencesAfter", runtimeSummary(preferences(runtimeNames)))
            }
            report.put("protectionChecksPassed", cleanupErrors.isEmpty()).put("cleanupErrors", JSONArray(cleanupErrors))
                .put("existingBusinessPreferenceCount", businessBefore.size)
                .put("existingHistoryCount", historyBefore.size).put("sourceAccepted", false)
                .put("downloadAccepted", false)
            save()
        }
        failure?.let { throw it }
        assertTrue("Only diagnostic collection completion is asserted", report.getBoolean("collectorCompleted"))
        assertFalse(report.getBoolean("sourceAccepted"))
    }

    /** A short share must actually resolve to this work; expectedId alone is not mapping evidence. */
    private fun resolveExpectedShare(share: String): String {
        ShareLinks.videoId(share)?.let { return it }
        val active = AtomicReference<HttpURLConnection?>()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "official-shape-share").apply { isDaemon = true } }
        val shareDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 20_000L)
        val future = executor.submit<String> {
            var next = URI(share)
            repeat(7) {
                require(allowedOfficial(next))
                ShareLinks.videoId(next.toString())?.let { return@submit it }
                val remaining = shareDeadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw TimeoutException()
                val connection = next.toURL().openConnection() as HttpURLConnection
                active.set(connection)
                try {
                    connection.connectTimeout = minOf(4_000L, remaining).toInt()
                    connection.readTimeout = minOf(4_000L, remaining).toInt()
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", ShareLinks.DESKTOP_UA)
                    val code = connection.responseCode
                    require(code in setOf(301, 302, 303, 307, 308))
                    next = next.resolve(requireNotNull(connection.getHeaderField("Location")))
                } finally {
                    connection.disconnect(); active.compareAndSet(connection, null)
                }
            }
            error("Official share redirect limit")
        }
        try { return future.get((shareDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
        finally {
            active.getAndSet(null)?.disconnect(); future.cancel(true); executor.shutdownNow()
            assertTrue("The bounded share worker did not finish", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun observePage(label: String, pageUrl: String, id: String, desktop: Boolean): JSONObject {
        val started = SystemClock.elapsedRealtime()
        // Reserve the last five seconds of each page's 45-second budget for
        // releasing its owned WebView even after a stalled callback.
        val end = minOf(deadline - 10_000L, started + PAGE_MS - 5_000L)
        require(end > started)
        val callbacks = Collections.synchronizedList(mutableListOf<JSONObject>())
        val alive = AtomicBoolean(true)
        val terminal = AtomicReference("BUDGET_FINISHED")
        val result = JSONObject().put("mode", label).put("scope", "DIAGNOSTIC_ONLY")
            .put("sourceAccepted", false).put("workShapeProven", false)
        val observations = mutableListOf<JSONObject>()
        var view: WebView? = null
        var host: FrameLayout? = null
        fun callback(event: String, code: Int? = null, mainFrame: Boolean = true) {
            if (!alive.get()) return
            synchronized(callbacks) {
                if (callbacks.size >= 32) return
                val item = JSONObject().put("event", event).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("mainFrame", mainFrame)
                code?.let { item.put("code", it) }
                callbacks += item
            }
        }
        val script = if (desktop) desktopShapeScript(id) else mobileShapeScript(id)
        try {
            main(limit = end) {
                val ownedHost = FrameLayout(context)
                host = ownedHost
                val browser = WebView(context)
                view = browser
                ownedHost.addView(browser, FrameLayout.LayoutParams(1280, 1920))
                ownedHost.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                ownedHost.layout(0, 0, 1280, 1920)
                assertFalse(ownedHost.isAttachedToWindow); assertTrue(ownedHost.windowToken == null)
                browser.setOnTouchListener { _, _ -> true }
                browser.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                browser.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true
                    allowFileAccess = false; allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(true)
                    if (desktop) {
                        userAgentString = DesktopAlbumPagePolicy.desktopUserAgent(WebSettings.getDefaultUserAgent(context))
                        useWideViewPort = true; loadWithOverviewMode = true
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
                browser.setDownloadListener { _, _, _, _, _ -> callback("DOWNLOAD_NAVIGATION_BLOCKED") }
                browser.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage) = true
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                        cb.invoke(origin, false, false)
                    }
                    override fun onJsAlert(web: WebView, url: String, message: String, answer: JsResult): Boolean { answer.cancel(); return true }
                    override fun onJsConfirm(web: WebView, url: String, message: String, answer: JsResult): Boolean { answer.cancel(); return true }
                    override fun onJsPrompt(web: WebView, url: String, message: String, defaultValue: String, answer: JsPromptResult): Boolean { answer.cancel(); return true }
                }
                browser.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean {
                        val blocked = request.isForMainFrame && !DesktopAlbumPagePolicy.isWorkPage(id, request.url.toString())
                        if (blocked) callback("MAIN_NAVIGATION_OUTSIDE_WORK_BLOCKED")
                        return blocked
                    }
                    override fun onPageStarted(web: WebView, url: String, icon: Bitmap?) { callback("PAGE_STARTED") }
                    override fun onPageFinished(web: WebView, url: String) { callback("PAGE_FINISHED") }
                    override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                        callback("NETWORK_ERROR", error.errorCode, request.isForMainFrame)
                    }
                    override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        callback("HTTP_ERROR", response.statusCode, request.isForMainFrame)
                    }
                    override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                        callback("RENDERER_ENDED"); terminal.set("RENDERER_ENDED"); alive.set(false)
                        host?.removeView(web); web.destroy(); view = null
                        return true
                    }
                }
                browser.loadUrl(pageUrl)
            }
            while (alive.get() && SystemClock.elapsedRealtime() < end) {
                val value = AtomicReference<String?>()
                val done = CountDownLatch(1)
                try {
                    main(limit = end) {
                        view?.evaluateJavascript(script) { encoded ->
                            if (alive.get()) value.set(encoded)
                            done.countDown()
                        } ?: done.countDown()
                    }
                } catch (_: TimeoutException) {
                    callback("MAIN_CALLBACK_TIMEOUT"); terminal.set("MAIN_CALLBACK_TIMEOUT"); break
                }
                if (!done.await(minOf(5_000L, (end - SystemClock.elapsedRealtime()).coerceAtLeast(1)), TimeUnit.MILLISECONDS)) {
                    callback("JAVASCRIPT_CALLBACK_TIMEOUT")
                } else value.get()?.let { encoded ->
                    try {
                        require(encoded.length <= 1_048_576)
                        val jsonText = JSONTokener(encoded).nextValue() as? String ?: error("Unexpected diagnostic type")
                        val decoded = JSONObject(jsonText)
                        require(decoded.optString("schema") == "OFFICIAL_WORK_SAFE_SHAPE_V1")
                        // Rebuild the fixed schema at the Android boundary too.
                        // Never persist the evaluated object itself or free strings.
                        val observation = sanitizeObservation(decoded, label)
                        observation.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        observations += observation
                        if (observations.size > 24) observations.removeAt(0)
                        if (observation.optInt("payloadWorkShapeCount") > 0) result.put("workShapeProven", true)
                    } catch (_: Exception) { callback("DIAGNOSTIC_DECODE_ERROR") }
                }
                val remaining = end - SystemClock.elapsedRealtime()
                if (remaining > 0) SystemClock.sleep(minOf(1_000L, remaining))
            }
        } finally {
            alive.set(false)
            main(cleanup = true) {
                view?.let { owned ->
                    host?.removeView(owned); owned.stopLoading(); owned.webChromeClient = null
                    owned.webViewClient = WebViewClient(); owned.setDownloadListener(null); owned.setOnTouchListener(null); owned.destroy()
                }
                view = null; host?.removeAllViews(); host = null
            }
            result.put("ended", terminal.get()).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("observations", JSONArray(observations)).put("callbacks", JSONArray(synchronized(callbacks) { callbacks.toList() }))
                .put("sourceAccepted", false).put("observationCount", observations.size)
        }
        return result
    }

    /** Instruments the existing exact-owner traversal; its raw result never leaves JavaScript. */
    private fun desktopShapeScript(id: String): String {
        var script = DesktopAlbumScript.extract(id, allowVideo = true)
        val declaration = "const clipEligible = new WeakSet(), imageDiagnostics = new WeakMap();"
        check(script.contains(declaration))
        script = script.replace(declaration,
            "const shapeStrictMotion = (value, refs) => motion(value, refs, true);\n$SHAPE_HELPERS\nconst safeShapes = [];\n$declaration")
        val ownerMatch = "stats.matchingOwners++;"
        check(script.split(ownerMatch).size == 2)
        script = script.replace(ownerMatch, "$ownerMatch\nif (safeShapes.length < 16) safeShapes.push(shapeWork(value, entry.refs, entry.source));")
        val returned = "return JSON.stringify(result);"
        check(script.split(returned).size == 3) // wrong page early return + final return
        val safeReturn = """
            return JSON.stringify({schema:'OFFICIAL_WORK_SAFE_SHAPE_V1', mode:'DESKTOP', state:state,
                pageMatches:stats.pageMatches, matchedWorkShapeCount:safeShapes.length,
                payloadWorkShapeCount:safeShapes.filter(x=>x.payloadPresent).length,
                shapes:safeShapes, gate:shapeGate(),
                scan:{matchingOwners:stats.matchingOwners, visitedNodes:stats.visitedNodes,
                    rscChunks:stats.rscChunks,rscRecords:stats.rscRecords,jsonRoots:stats.jsonRoots,
                    hydrationRoots:stats.hydrationRoots,truncated:stats.truncated,
                    objectKeyLimitCount:stats.limits.objectKeys,queueNodeLimitCount:stats.limits.queueNodes,
                    acceptedVideoCandidateCount:stats.videoCandidates,acceptedAlbumCandidateCount:stats.albumCandidates,
                    acceptedImageCount:stats.images,declaredImageCount:stats.declaredImages,
                    conflictingAlbums:stats.conflictingAlbums},
                workShapeMissing:safeShapes.length===0,payloadWorkShapeMissing:!safeShapes.some(x=>x.payloadPresent)});
        """.trimIndent()
        // A wrong-page early return executes before helper declarations. It needs
        // only a fixed empty diagnostic, never the original result/page URL.
        val first = script.indexOf(returned)
        script = script.replaceRange(first, first + returned.length,
            "return JSON.stringify({schema:'OFFICIAL_WORK_SAFE_SHAPE_V1',mode:'DESKTOP',state:state,pageMatches:false,matchedWorkShapeCount:0,payloadWorkShapeCount:0,shapes:[],workShapeMissing:true,payloadWorkShapeMissing:true});")
        script = script.replace(returned, safeReturn)
        return script
    }

    private fun mobileShapeScript(id: String): String = """
        (function(){'use strict'; const expectedId='$id';
            const resolve=(x,refs)=>x;
            const shapeStrictMotion=null;
            $SHAPE_HELPERS
            const route=window._ROUTER_DATA;
            const loader=route&&route.loaderData;
            const page=loader&&(loader['video_(id)/page']||loader['note_(id)/page']||loader['slides_(id)/page']);
            const info=page&&page.videoInfoRes;
            const types=shapeFields(info,['status_code','statusCode','item_list','itemList','items','aweme_list','aweme_detail','data','code','message','status_msg']);
            const containers=[];
            ['item_list','itemList','items','aweme_list'].forEach(k=>{
                const value=info&&info[k]; containers.push({field:k,type:shapeType(value),count:Array.isArray(value)?Math.min(value.length,100000):0});
            });
            const knownChildren=['videoInfoRes','item_list','itemList','items','aweme_list','aweme_detail','awemeDetail','data','item','itemInfo','aweme','imagePostInfo','image_post_info'];
            const queue=[{value:info,depth:0},{value:page&&page.aweme_detail,depth:0}],seen=new Set(),shapes=[];
            let visited=0,truncated=false;
            for(let cursor=0;cursor<queue.length&&cursor<2000;cursor++){
                const record=queue[cursor],value=record.value;
                if(!value||typeof value!=='object'||seen.has(value)||record.depth>10)continue;
                seen.add(value);visited++;
                if(Array.isArray(value)){
                    if(value.length>256)truncated=true;
                    value.slice(0,256).forEach(x=>queue.push({value:x,depth:record.depth+1}));continue;
                }
                const ids=[value.aweme_id,value.awemeId].filter(x=>x!==undefined&&x!==null&&x!=='');
                if(ids.length&&ids.every(x=>typeof x==='string'&&x===expectedId)&&shapes.length<16)
                    shapes.push(shapeWork(value,new Map(),'router'));
                knownChildren.forEach(k=>{const child=value[k];if(child&&typeof child==='object')queue.push({value:child,depth:record.depth+1});});
            }
            if(queue.length>2000)truncated=true;
            let pageMatches=false;
            try{const u=new URL(location.href),m=u.pathname.match(/^\/share\/(?:video|note|slides)\/(\d{15,22})\/?$/);
                pageMatches=u.protocol==='https:'&&!u.username&&!u.password&&(!u.port||u.port==='443')&&
                    (u.hostname==='iesdouyin.com'||u.hostname.endsWith('.iesdouyin.com')||u.hostname==='douyin.com'||u.hostname.endsWith('.douyin.com'))&&!!m&&m[1]===expectedId;
            }catch(_){}
            return JSON.stringify({schema:'OFFICIAL_WORK_SAFE_SHAPE_V1',mode:'MOBILE',state:shapeState(),pageMatches:pageMatches,
                routeType:shapeType(route),itemIdMatches:!!page&&page.itemId===expectedId,
                videoInfoResPresent:!!page&&Object.prototype.hasOwnProperty.call(page,'videoInfoRes'),videoInfoResType:shapeType(info),
                videoInfoResFields:types,videoInfoResStatusCode:shapeNumber(info&&info.status_code),
                videoInfoResCamelStatusCode:shapeNumber(info&&info.statusCode),itemContainers:containers,
                matchedWorkShapeCount:shapes.length,payloadWorkShapeCount:shapes.filter(x=>x.payloadPresent).length,
                shapes:shapes,workShapeMissing:shapes.length===0,payloadWorkShapeMissing:!shapes.some(x=>x.payloadPresent),
                scan:{visitedNodes:visited,truncated:truncated},gate:shapeGate()});
        })();
    """.trimIndent()

    private fun sanitizeObservation(input: JSONObject, mode: String): JSONObject {
        val output = JSONObject().put("schema", "OFFICIAL_WORK_SAFE_SHAPE_V1").put("mode", mode)
            .put("state", enumValue(input, "state", setOf("loading", "interactive", "complete")))
        listOf("pageMatches", "itemIdMatches", "videoInfoResPresent", "workShapeMissing", "payloadWorkShapeMissing")
            .forEach { if (input.has(it)) output.put(it, boolean(input, it)) }
        listOf("matchedWorkShapeCount", "payloadWorkShapeCount").forEach { output.put(it, count(input, it, 16)) }
        if (input.has("routeType")) output.put("routeType", enumValue(input, "routeType", VALUE_TYPES))
        if (input.has("videoInfoResType")) output.put("videoInfoResType", enumValue(input, "videoInfoResType", VALUE_TYPES))
        listOf("videoInfoResStatusCode", "videoInfoResCamelStatusCode").forEach {
            if (input.has(it)) output.put(it, number(input, it))
        }
        if (input.has("videoInfoResFields")) output.put("videoInfoResFields", fields(input.optJSONArray("videoInfoResFields"), INFO_FIELDS))
        input.optJSONArray("itemContainers")?.let { rows ->
            output.put("itemContainers", JSONArray(listOf("item_list", "itemList", "items", "aweme_list").map { name ->
                val row = (0 until minOf(8, rows.length())).mapNotNull { rows.optJSONObject(it) }.firstOrNull { it.optString("field") == name }
                JSONObject().put("field", name).put("type", row?.let { enumValue(it, "type", VALUE_TYPES) } ?: "missing")
                    .put("count", row?.let { count(it, "count", 100_000) } ?: 0)
            }))
        }
        val shapes = input.optJSONArray("shapes") ?: JSONArray()
        output.put("shapes", JSONArray((0 until minOf(16, shapes.length())).mapNotNull { index ->
            val shape = shapes.optJSONObject(index) ?: return@mapNotNull null
            val safe = JSONObject().put("provenance", enumValue(shape, "provenance", setOf("rsc", "router", "render", "hydration", "dom")))
                .put("videoType", enumValue(shape, "videoType", VALUE_TYPES))
                .put("fields", fields(shape.optJSONArray("fields"), WORK_FIELDS))
                .put("videoFields", fields(shape.optJSONArray("videoFields"), VIDEO_FIELDS))
            listOf("payloadPresent", "excludedByImageDeclaration", "ordinaryVideoDimensionsAndDurationPresent", "strictPolicyMotionPresent")
                .forEach { safe.put(it, boolean(shape, it)) }
            listOf("workType", "width", "height", "durationSeconds", "strictPolicyWidth", "strictPolicyHeight", "strictPolicyDurationSeconds")
                .forEach { safe.put(it, number(shape, it)) }
            listOf("imageCount", "declaredImageCount", "rateCount", "playAddressCandidateCount", "downloadAddressCandidateCount", "videoIdCount",
                "strictPolicyPlayCount", "strictPolicyDownloadCount", "strictPolicyMediaIdCount", "strictPolicyDisplayPlaybackCount")
                .forEach { safe.put(it, count(shape, it, 100_000)) }
            safe
        }))
        input.optJSONObject("scan")?.let { scan ->
            val safe = JSONObject()
            listOf("matchingOwners", "visitedNodes", "rscChunks", "rscRecords", "jsonRoots", "hydrationRoots", "objectKeyLimitCount", "queueNodeLimitCount",
                "acceptedVideoCandidateCount", "acceptedAlbumCandidateCount", "acceptedImageCount", "declaredImageCount")
                .forEach { if (scan.has(it)) safe.put(it, count(scan, it, 1_000_000)) }
            listOf("truncated", "conflictingAlbums").forEach { if (scan.has(it)) safe.put(it, boolean(scan, it)) }
            output.put("scan", safe)
        }
        input.optJSONObject("gate")?.let { gate ->
            val safe = JSONObject()
            listOf("captchaVisible", "loginGateVisible", "errorPagePhrase", "watchInAppPhrase", "deletedPhrase", "privatePhrase")
                .forEach { safe.put(it, boolean(gate, it)) }
            output.put("gate", safe)
        }
        return output
    }

    private fun fields(rows: JSONArray?, allowed: List<String>) = JSONArray(allowed.map { field ->
        val row = rows?.let { list -> (0 until minOf(32, list.length())).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optString("field") == field } }
        JSONObject().put("field", field).put("present", row?.let { boolean(it, "present") } ?: false)
            .put("type", row?.let { enumValue(it, "type", VALUE_TYPES) } ?: "missing")
    })
    private fun boolean(value: JSONObject, key: String) = value.opt(key) as? Boolean ?: false
    private fun enumValue(value: JSONObject, key: String, allowed: Set<String>) = value.optString(key).takeIf { it in allowed } ?: "unknown"
    private fun count(value: JSONObject, key: String, limit: Int): Int = (value.opt(key) as? Number)?.toDouble()
        ?.takeIf { it.isFinite() }?.toLong()?.coerceIn(0, limit.toLong())?.toInt() ?: 0
    private fun number(value: JSONObject, key: String): Any = (value.opt(key) as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 1_000_000_000.0 } ?: JSONObject.NULL

    private fun main(cleanup: Boolean = false, limit: Long = deadline, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { action(); return }
        val done = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val posted = Runnable {
            try { if (active.get()) action() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
        }
        check(mainHandler.post(posted))
        val remaining = if (cleanup) 5_000L else minOf(5_000L, (minOf(deadline, limit) - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        if (!done.await(remaining, TimeUnit.MILLISECONDS)) {
            active.set(false); mainHandler.removeCallbacks(posted)
            throw TimeoutException("Bounded diagnostic main callback")
        }
        failure.get()?.let { throw it }
    }

    private fun allowedOfficial(uri: URI) = uri.scheme == "https" && uri.rawUserInfo == null &&
        uri.port in setOf(-1, 443) && ShareLinks.isShareHost(uri.host)

    private fun cookiePairs(scopes: Map<String, String>): Map<String, List<String>> = scopes.mapValues { (_, url) ->
        CookieManager.getInstance().getCookie(url).orEmpty().split(';').map(String::trim).filter { it.contains('=') }.sorted()
    }

    private fun existingPreferenceNames(): Set<String> = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }.toSet() +
        setOf("downloads", "download_tasks", "download_options", "download_folder", "parse_diagnostics", "appearance_v1", "preview_options", "notification_prompt")

    private fun preferences(names: Set<String>): Map<String, Map<String, *>> = names.associateWith { name ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    }

    private fun runtimeSummary(values: Map<String, Map<String, *>>): JSONArray = JSONArray(values.keys.sorted().map { name ->
        val canonical = JSONObject()
        values.getValue(name).keys.sorted().forEach { key ->
            val value = values.getValue(name)[key]
            canonical.put(key, if (value is Set<*>) JSONArray(value.map { it.toString() }.sorted()) else value ?: JSONObject.NULL)
        }
        JSONObject().put("namespace", name).put("keyCount", values.getValue(name).size)
            .put("sha256", hash(canonical.toString().toByteArray(Charsets.UTF_8)))
    })

    private fun fileHash(file: File) = hash(file.readBytes())
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOTAL_MS = 120_000L
        const val PAGE_MS = 45_000L
        val VALUE_TYPES = setOf("missing", "null", "array", "object", "string", "number", "boolean", "other")
        val INFO_FIELDS = listOf("status_code", "statusCode", "item_list", "itemList", "items", "aweme_list", "aweme_detail", "data", "code", "message", "status_msg")
        val WORK_FIELDS = listOf("awemeId", "aweme_id", "awemeType", "aweme_type", "desc", "title", "video", "images", "image_list",
            "imagePostInfo", "image_post_info", "imageCount", "image_count", "music")
        val VIDEO_FIELDS = listOf("playAddr", "play_addr", "downloadAddr", "download_addr", "bitRateList", "bit_rate", "bitrate",
            "videoId", "video_id", "width", "height", "duration", "durationSeconds", "cover", "originCover", "origin_cover")
        val SHAPE_HELPERS = """
            const shapeType=x=>x===undefined?'missing':x===null?'null':Array.isArray(x)?'array':
                ['string','number','boolean','object'].includes(typeof x)?typeof x:'other';
            const shapeNumber=x=>typeof x==='number'&&Number.isFinite(x)&&Math.abs(x)<=1000000000?x:null;
            const shapeState=()=>['loading','interactive','complete'].includes(document.readyState)?document.readyState:'unknown';
            const shapeFields=(x,allowed)=>allowed.map(field=>({field:field,present:!!x&&Object.prototype.hasOwnProperty.call(x,field),
                type:shapeType(x&&x[field])}));
            const shapeVisible=e=>{if(!e||typeof e.getBoundingClientRect!=='function')return false;
                const r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;
                const s=window.getComputedStyle(e);return !s||s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';};
            const shapeGate=()=>{
                const captcha=Array.from(document.querySelectorAll('[id*="captcha"],[class*="captcha"],iframe[src*="captcha"]')).slice(0,200).some(shapeVisible);
                const login=Array.from(document.querySelectorAll('dialog,[role="dialog"],[aria-modal="true"],[class*="login-modal"],[class*="loginModal"],[class*="login-dialog"],[class*="loginDialog"],[data-e2e="login-page"]'))
                    .slice(0,200).some(e=>shapeVisible(e)&&/登[录陆]/.test(String(e.innerText||e.textContent||'').slice(0,2000)));
                const body=String(document.body&&document.body.innerText||'').slice(0,20000);
                return {captchaVisible:captcha,loginGateVisible:login,errorPagePhrase:body.includes('抱歉出错了'),
                    watchInAppPhrase:body.includes('请尝试在抖音内观看'),deletedPhrase:body.includes('作品已删除'),privatePhrase:body.includes('私密视频')};
            };
            const shapeUrlCount=(raw,refs,depth)=>{
                if((depth||0)>5)return 0;const x=resolve(raw,refs);
                if(typeof x==='string')return /^https?:\/\//i.test(x)?1:0;
                if(Array.isArray(x))return x.slice(0,64).reduce((n,v)=>n+shapeUrlCount(v,refs,(depth||0)+1),0);
                if(!x||typeof x!=='object')return 0;
                return ['urlList','url_list','url','src','uri'].reduce((n,k)=>n+shapeUrlCount(x[k],refs,(depth||0)+1),0);
            };
            const shapeWork=(work,refs,source)=>{
                const video=resolve(work.video,refs),post=resolve(work.imagePostInfo||work.image_post_info,refs);
                const v=video&&typeof video==='object'&&!Array.isArray(video)?video:{};
                const p=post&&typeof post==='object'&&!Array.isArray(post)?post:{};
                const arrays=[work.images,work.image_list,p.images,p.image_list].map(x=>resolve(x,refs));
                const imageCount=Math.max(0,...arrays.map(x=>Array.isArray(x)?Math.min(x.length,100000):0));
                const declared=Math.max(imageCount,...[work.imageCount,work.image_count,p.imageCount,p.image_count].map(x=>typeof x==='number'&&Number.isFinite(x)?Math.min(Math.max(x,0),100000):0));
                const rateArrays=[v.bitRateList,v.bit_rate,v.bitrate].map(x=>resolve(x,refs)).filter(Array.isArray);
                const rates=rateArrays.flatMap(x=>x.slice(0,16)).map(x=>resolve(x,refs)).filter(x=>x&&typeof x==='object'&&!Array.isArray(x));
                const entries=[v,...rates];
                const playCount=entries.reduce((n,x)=>n+['playAddr','play_addr','play_addr_h264','play_addr_265','play_addr_h265','play_addr_bytevc1','play_addr_bytevc2'].reduce((m,k)=>m+shapeUrlCount(x[k],refs,0),0),0);
                const downloadCount=entries.reduce((n,x)=>n+['downloadAddr','download_addr','download_addr_h264','download_addr_h265'].reduce((m,k)=>m+shapeUrlCount(x[k],refs,0),0),0);
                const idValues=[v.videoId,v.video_id,...entries.map(x=>resolve(x.playAddr||x.play_addr,refs)).filter(x=>x&&typeof x==='object').map(x=>x.uri)];
                const ids=new Set(idValues.filter(x=>typeof x==='string'&&/^[A-Za-z0-9_-]{10,256}$/.test(x)&&/[A-Za-z]/.test(x)));
                const addressObjects=entries.flatMap(x=>['playAddr','play_addr','play_addr_h264','play_addr_h265'].map(k=>resolve(x[k],refs)))
                    .flatMap(x=>Array.isArray(x)?x:[x]).map(x=>resolve(x,refs)).filter(x=>x&&typeof x==='object'&&!Array.isArray(x));
                const widths=[...entries,...addressObjects].map(x=>shapeNumber(x.width)).filter(x=>x!==null),heights=[...entries,...addressObjects].map(x=>shapeNumber(x.height)).filter(x=>x!==null);
                const width=widths.length?Math.max(...widths):null,height=heights.length?Math.max(...heights):null;
                const duration=shapeNumber(v.durationSeconds)!==null?shapeNumber(v.durationSeconds):shapeNumber(v.duration)!==null?shapeNumber(v.duration)/1000:null;
                const workType=shapeNumber(work.awemeType===undefined?work.aweme_type:work.awemeType);
                const strict=typeof shapeStrictMotion==='function'?shapeStrictMotion(work.video,refs):null;
                return {provenance:['rsc','router','render','hydration','dom'].includes(source)?source:'unknown',
                    payloadPresent:imageCount>0||!!video&&typeof video==='object'&&['playAddr','play_addr','downloadAddr','download_addr','bitRateList','width','height','duration','durationSeconds'].some(k=>Object.prototype.hasOwnProperty.call(video,k)),
                    fields:shapeFields(work,['awemeId','aweme_id','awemeType','aweme_type','desc','title','video','images','image_list','imagePostInfo','image_post_info','imageCount','image_count','music']),
                    videoFields:shapeFields(v,['playAddr','play_addr','downloadAddr','download_addr','bitRateList','bit_rate','bitrate','videoId','video_id','width','height','duration','durationSeconds','cover','originCover','origin_cover']),
                    videoType:shapeType(video),workType:workType,imageCount:imageCount,declaredImageCount:declared,
                    rateCount:rates.length,playAddressCandidateCount:playCount,downloadAddressCandidateCount:downloadCount,
                    videoIdCount:ids.size,width:width,height:height,durationSeconds:duration,
                    strictPolicyMotionPresent:!!strict,strictPolicyPlayCount:strict?strict.playUrls.length:0,
                    strictPolicyDownloadCount:strict?strict.downloadUrls.length:0,strictPolicyMediaIdCount:strict?strict.mediaIds.length:0,
                    strictPolicyDisplayPlaybackCount:strict?strict.displayPlaybackUrls.length:0,
                    strictPolicyWidth:strict?strict.width:0,strictPolicyHeight:strict?strict.height:0,
                    strictPolicyDurationSeconds:strict?strict.durationSeconds:0,
                    excludedByImageDeclaration:imageCount>0||declared>0||[2,68,150].includes(workType),
                    ordinaryVideoDimensionsAndDurationPresent:width>0&&width<=16384&&height>0&&height<=16384&&duration>0};
            };
        """.trimIndent()
    }
}
