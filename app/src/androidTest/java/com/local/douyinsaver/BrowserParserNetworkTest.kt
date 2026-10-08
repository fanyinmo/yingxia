package com.local.douyinsaver

import android.os.SystemClock
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList

/** Runs the production error entry and real WebView/Handler lifecycle; no network or user state. */
@RunWith(AndroidJUnit4::class)
class BrowserParserNetworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val id = "7693831622341313766"
    private val url = "https://www.iesdouyin.com/share/video/$id/"

    @Test fun genericEmptyShareReloadsTheExactUrlAndSharesTheTransportRetryBudget() = withParser { view, parser, errors, trace ->
        main {
            view.response = JSONObject.quote(JSONObject().put("page", url).put("stats", JSONObject()
                .put("state", "complete").put("itemMatches", true).put("videos", 0)
                .put("knownErrors", JSONArray(listOf("抱歉出错了", "请尝试在抖音内观看")))).toString())
        }
        await { view.loads.size == 2 }
        assertEquals(listOf(url, url), view.loads)
        assertTrue(errors.isEmpty())
        assertTrue(trace.any { it.contains("kind=EMPTY_SHARE_DATA") })
        main { parser.receivedNetworkError(true, -2, url) }
        assertEquals(listOf(PageNetworkFailures.browserMessage(-2)), errors)
        assertEquals(1, trace.count { it.startsWith("page_network_retry ") })
    }

    @Test fun sameWorkDnsFailureRetriesOnceThenReportsDnsInsteadOfMissingWork() = withParser { view, parser, errors, trace ->
        main { parser.receivedNetworkError(true, -2, url) }
        await { view.loads.size == 2 }
        assertEquals(listOf(url, url), view.loads)
        assertTrue(errors.isEmpty())
        main { parser.receivedNetworkError(true, -2, url); parser.receivedNetworkError(true, -2, url) }
        assertEquals(listOf(PageNetworkFailures.browserMessage(-2)), errors)
        assertEquals(1, trace.count { it.startsWith("page_network_retry ") })
        SystemClock.sleep(1100)
        assertEquals(2, view.loads.size)
    }

    @Test fun cancellingPendingRetryCannotLoadAgainOrReportFailure() = withParser { view, parser, errors, _ ->
        main { parser.receivedNetworkError(true, -2, url); parser.dispose() }
        SystemClock.sleep(1200)
        assertEquals(listOf(url), view.loads)
        assertTrue(errors.isEmpty())
    }

    @Test fun failedSecondaryResourceDoesNotRetryOrAbortMainDocument() = withParser { view, parser, errors, trace ->
        main { parser.receivedNetworkError(false, -2, url) }
        SystemClock.sleep(1100)
        assertEquals(listOf(url), view.loads)
        assertTrue(errors.isEmpty())
        assertFalse(trace.any { it.startsWith("page_network_retry ") })
    }

    @Test fun tlsFailureCannotTriggerRetryOrWeakenSecurity() = withParser { view, parser, errors, trace ->
        main { parser.receivedNetworkError(true, -11, url) }
        SystemClock.sleep(1100)
        assertEquals(listOf(url), view.loads)
        assertEquals(listOf(PageNetworkFailures.browserMessage(-11)), errors)
        assertFalse(trace.any { it.startsWith("page_network_retry ") })
    }

    private fun withParser(body: (RecordingWebView, BrowserParser, List<String>, List<String>) -> Unit) {
        val errors = CopyOnWriteArrayList<String>()
        val trace = CopyOnWriteArrayList<String>()
        lateinit var view: RecordingWebView
        lateinit var parser: BrowserParser
        main {
            view = RecordingWebView()
            parser = BrowserParser(view, id, { error("No page result is supplied by this controlled WebView") },
                errors::add, trace::add, initialUrl = url)
        }
        try { body(view, parser, errors, trace) }
        finally { main { parser.dispose() } }
    }

    private inner class RecordingWebView : WebView(instrumentation.targetContext) {
        val loads = CopyOnWriteArrayList<String>()
        var response: String? = null
        override fun loadUrl(url: String) { loads.add(url) }
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            response?.let { resultCallback?.onReceiveValue(it) }
        }
    }

    private fun main(body: () -> Unit) = instrumentation.runOnMainSync(body)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("Expected retry callback did not arrive within its bounded window", condition())
    }
}
