package com.local.douyinsaver

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Explicit diagnostic only. Test completion is not a claim that either website loaded. */
@RunWith(AndroidJUnit4::class)
class WebViewConnectivityTest {
    @Test
    fun compareNativeHttpsAndAnOrdinaryAttachedWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Network diagnostics require run_connectivity_probe=true",
            InstrumentationRegistry.getArguments().getString("run_connectivity_probe") == "true")
        val application = instrumentation.targetContext.applicationContext as Application
        val directory = File(application.cacheDir, "webview_connectivity_${UUID.randomUUID()}")
        check(directory.mkdir() && directory.canonicalFile.parentFile == application.cacheDir.canonicalFile)
        val started = SystemClock.elapsedRealtime()
        val installed = application.packageManager.getPackageInfo(application.packageName, 0)
        val report = JSONObject().put("completed", false).put("installedVersionName", installed.versionName)
            .put("installedVersionCode", installed.longVersionCode).put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT).put("permissionsChanged", false).put("cookiesRead", false)
            .put("tlsVerificationChanged", false).put("productionParserUsed", false)
            .put("testCompletionIsNetworkAcceptance", false)
        val results = JSONArray()
        report.put("targets", results)
        var scenario: ActivityScenario<MainActivity>? = null
        fun save() {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(directory, "connectivity.json").writeText(report.toString(2))
        }
        println("webview_connectivity_directory=${directory.absolutePath}")
        try {
            // Refuse to attach a new Activity while an existing production task owns its host.
            instrumentation.runOnMainSync {
                val engine = SaverEngine.get(application)
                check(!engine.busy && !engine.queueRunning && !engine.batchSaving) {
                    "An existing task is active; refusing to disturb its Activity or parser host"
                }
            }
            save()
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            for (target in TARGETS) {
                val result = JSONObject().put("targetHost", URI(target).host)
                    .put("targetLabel", if (URI(target).host == "example.org") "public_example" else "official_work")
                results.put(result)
                result.put("native", nativeRequest(target))
                save()
                result.put("webView", webViewRequest(launched, target))
                save()
            }
            report.put("completed", true)
        } catch (error: Throwable) {
            report.put("failureType", error.javaClass.simpleName)
                .put("failure", DiagnosticText.clean(error.message.orEmpty(), 600))
            throw error
        } finally {
            try { scenario?.close() }
            catch (error: Exception) {
                report.put("activityCleanupFailure", error.javaClass.simpleName)
            }
            save()
        }
    }

    private fun nativeRequest(target: String): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val active = AtomicReference<HttpURLConnection?>()
        val executor = Executors.newSingleThreadExecutor()
        val result = JSONObject().put("ended", "UNSTARTED").put("contentAccepted", false)
        val observedHops = Collections.synchronizedList(mutableListOf<JSONObject>())
        val future = executor.submit<JSONObject> {
            var next = URI(target)
            val hops = JSONArray()
            for (hop in 0..5) {
                val remaining = NATIVE_BUDGET_MS - (SystemClock.elapsedRealtime() - started)
                if (remaining <= 0) throw TimeoutException("Native HTTPS budget ended")
                check(allowed(next)) { "Redirect outside the explicit public hosts" }
                val connection = next.toURL().openConnection() as HttpURLConnection
                active.set(connection)
                try {
                    connection.connectTimeout = remaining.toInt()
                    connection.readTimeout = remaining.toInt()
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    val code = connection.responseCode
                    val observed = JSONObject().put("hop", hop).put("host", next.host).put("status", code)
                    hops.put(observed)
                    observedHops.add(observed)
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) return@submit JSONObject().put("hops", hops)
                            .put("ended", "REDIRECT_WITHOUT_LOCATION").put("contentAccepted", false)
                        next = next.resolve(location)
                    } else return@submit JSONObject().put("hops", hops).put("ended", "HTTP_RESPONSE")
                        .put("finalHost", next.host).put("finalStatus", code)
                        .put("contentAccepted", code in 200..299)
                } finally {
                    connection.disconnect()
                    active.compareAndSet(connection, null)
                }
            }
            JSONObject().put("hops", hops).put("ended", "REDIRECT_LIMIT").put("contentAccepted", false)
        }
        try {
            return future.get(NATIVE_BUDGET_MS, TimeUnit.MILLISECONDS)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
        } catch (error: Exception) {
            val cause = error.cause ?: error
            result.put("ended", if (error is TimeoutException) "TIMEOUT" else "ERROR")
                .put("failureType", cause.javaClass.simpleName)
                .put("failure", DiagnosticText.clean(cause.message.orEmpty(), 400))
                .put("hops", JSONArray(synchronized(observedHops) { observedHops.toList() }))
            return result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
        } finally {
            active.getAndSet(null)?.disconnect()
            future.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun webViewRequest(scenario: ActivityScenario<MainActivity>, target: String): JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = SystemClock.elapsedRealtime()
        val callbacks = Collections.synchronizedList(mutableListOf<JSONObject>())
        val settled = CountDownLatch(1)
        val terminal = AtomicReference("TIMEOUT")
        val result = JSONObject().put("contentAccepted", false)
        var browser: WebView? = null
        var ownedRoot: FrameLayout? = null
        fun event(name: String, url: String, code: Int? = null) {
            val entry = JSONObject().put("event", name).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("host", safeHost(url))
            if (code != null) entry.put("code", code)
            callbacks.add(entry)
        }
        try {
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                ownedRoot = root
                val web = WebView(activity)
                browser = web
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val blocked = request.isForMainFrame && !allowed(runCatching { URI(request.url.toString()) }.getOrNull())
                        if (blocked) {
                            event("main_redirect_blocked", request.url.toString())
                            terminal.set("REDIRECT_BLOCKED"); settled.countDown()
                        }
                        return blocked
                    }
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        event("page_started", url)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        event("page_finished", url)
                        if (allowed(runCatching { URI(url) }.getOrNull())) {
                            terminal.compareAndSet("TIMEOUT", "PAGE_FINISHED"); settled.countDown()
                        }
                    }
                    override fun onPageCommitVisible(view: WebView, url: String) {
                        event("page_commit_visible", url)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            event("main_network_error", request.url.toString(), error.errorCode)
                            terminal.set("MAIN_NETWORK_ERROR"); settled.countDown()
                        }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) {
                            event("main_http_error", request.url.toString(), response.statusCode)
                            terminal.set("MAIN_HTTP_ERROR"); settled.countDown()
                        }
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        event("renderer_gone", view.url.orEmpty())
                        terminal.set("RENDERER_GONE"); settled.countDown()
                        return true
                    }
                }
                root.addView(web, FrameLayout.LayoutParams(-1, -1))
                root.addView(TextView(activity).apply {
                    text = "正在检查公开网页连接"
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }, FrameLayout.LayoutParams(-1, -1))
                activity.findViewById<ViewGroup>(android.R.id.content).addView(root, ViewGroup.LayoutParams(-1, -1))
                result.put("providerVersion", WebView.getCurrentWebViewPackage()?.versionName.orEmpty())
                web.loadUrl(target)
            }
            settled.await((WEB_BUDGET_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0),
                TimeUnit.MILLISECONDS)
            val snapshotDone = CountDownLatch(1)
            val document = AtomicReference<JSONObject?>()
            instrumentation.runOnMainSync {
                browser?.let { web ->
                    result.put("viewAttached", web.isAttachedToWindow).put("viewWidth", web.width)
                        .put("viewHeight", web.height).put("currentHost", safeHost(web.url.orEmpty()))
                    web.evaluateJavascript(DOCUMENT_STATS) { encoded ->
                        try {
                            val text = JSONTokener(encoded).nextValue() as? String
                            document.set(text?.let(::JSONObject))
                        } catch (error: Exception) {
                            event("document_extract_error", web.url.orEmpty())
                        } finally { snapshotDone.countDown() }
                    }
                } ?: snapshotDone.countDown()
            }
            snapshotDone.await(minOf(2000L,
                (WEB_BUDGET_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0)), TimeUnit.MILLISECONDS)
            document.get()?.let { stats ->
                stats.put("title", DiagnosticText.clean(stats.optString("title"), 200))
                result.put("document", stats)
                result.put("contentAccepted", terminal.get() == "PAGE_FINISHED" &&
                    stats.optString("state") == "complete" && stats.optString("title").isNotBlank() &&
                    stats.optInt("bodyChars") > 0 && stats.optString("host") == result.optString("currentHost") &&
                    allowed(runCatching { URI("https://${stats.optString("host")}") }.getOrNull()))
            }
            return result
        } catch (error: Exception) {
            terminal.set("PROBE_ERROR")
            result.put("failureType", error.javaClass.simpleName)
                .put("failure", DiagnosticText.clean(error.message.orEmpty(), 400))
            return result
        } finally {
            instrumentation.runOnMainSync {
                try {
                    browser?.apply { stopLoading(); (parent as? ViewGroup)?.removeView(this); destroy() }
                } catch (error: Exception) {
                    result.put("viewCleanupFailure", error.javaClass.simpleName)
                } finally {
                    (ownedRoot?.parent as? ViewGroup)?.removeView(ownedRoot)
                    browser = null; ownedRoot = null
                }
            }
            result.put("ended", terminal.get()).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("ownedViewsRemoved", true)
                .put("callbacks", JSONArray(synchronized(callbacks) { callbacks.toList() }))
        }
    }

    private fun safeHost(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

    private fun allowed(uri: URI?): Boolean = uri != null && uri.scheme == "https" && uri.userInfo == null &&
        uri.port in listOf(-1, 443) && uri.host in HOSTS

    companion object {
        private const val NATIVE_BUDGET_MS = 10_000L
        private const val WEB_BUDGET_MS = 20_000L
        private val HOSTS = setOf("example.org", "www.example.org", "www.iesdouyin.com", "iesdouyin.com",
            "www.douyin.com", "douyin.com", "v.douyin.com")
        private val TARGETS = listOf("https://example.org/",
            "https://www.iesdouyin.com/share/video/7684581692060830976/")
        private const val DOCUMENT_STATS = """JSON.stringify({scheme:location.protocol,host:location.hostname,state:document.readyState,title:document.title.slice(0,200),bodyChars:document.body?document.body.innerText.length:0,videos:document.querySelectorAll('video').length})"""
    }
}
