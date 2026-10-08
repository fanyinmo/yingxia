package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class PageNetworkFailuresTest {
    private val id = "7693831622341313766"
    private val url = "https://www.iesdouyin.com/share/video/$id/?token=keep_original"

    @Test fun genericEmptyShareMayRetryOnlyOnceForTheExactConfirmedWork() {
        val errors = listOf("抱歉出错了", "请尝试在抖音内观看")
        assertTrue(PageNetworkFailures.canRetryEmptyPage(id, url, 0, true, errors, false))
        assertFalse(PageNetworkFailures.canRetryEmptyPage(id, url, 1, true, errors, false))
        assertFalse(PageNetworkFailures.canRetryEmptyPage(id, url, 0, false, errors, false))
        assertFalse(PageNetworkFailures.canRetryEmptyPage(id, url.replace(id, "7693831622341313767"), 0, true, errors, false))
    }

    @Test fun emptyPageRecoveryDoesNotRetryKnownPrivateDeletedOrVerificationPages() {
        for (gate in listOf("作品已删除", "私密视频", "验证码"))
            assertFalse(PageNetworkFailures.canRetryEmptyPage(id, url, 0, true, listOf("抱歉出错了", gate), false))
        assertFalse(PageNetworkFailures.canRetryEmptyPage(id, url, 0, true, listOf("抱歉出错了"), true))
        assertFalse(PageNetworkFailures.canRetryEmptyPage(id, url, 0, true, emptyList(), false))
    }

    @Test fun transientSameWorkRetryIsBoundedToOneAttempt() {
        for (code in listOf(-2, -6, -8)) {
            assertTrue(PageNetworkFailures.canRetry(code, 0, id, url))
            assertFalse(PageNetworkFailures.canRetry(code, 1, id, url))
            assertFalse(PageNetworkFailures.canRetry(code, -1, id, url))
        }
    }

    @Test fun tlsAndNonTransportErrorsNeverRetry() {
        for (code in listOf(-11, -1, -7, -10, -12)) assertFalse(PageNetworkFailures.canRetry(code, 0, id, url))
    }

    @Test fun retryCannotEscapeWorkOriginOrHttpsBoundary() {
        for (untrusted in listOf(url.replace("https:", "http:"), url.replace(id, "7693831622341313767"),
            url.replace("www.iesdouyin.com", "www.iesdouyin.com.attacker.invalid"),
            url.replace("www.iesdouyin.com", "user:secret@www.iesdouyin.com"),
            url.replace("www.iesdouyin.com", "www.iesdouyin.com:58001"), "not a URI")) {
            assertFalse(untrusted, PageNetworkFailures.canRetry(-2, 0, id, untrusted))
        }
    }

    @Test fun wrappedTransportErrorsKeepTheirActualCategory() {
        assertEquals(PageNetworkFailure.DNS, PageNetworkFailures.exceptionKind(RuntimeException(UnknownHostException("private"))))
        assertEquals(PageNetworkFailure.TIMEOUT, PageNetworkFailures.exceptionKind(SocketTimeoutException()))
        assertEquals(PageNetworkFailure.CONNECTION, PageNetworkFailures.exceptionKind(ConnectException()))
        assertEquals(PageNetworkFailure.TLS, PageNetworkFailures.exceptionKind(SSLHandshakeException("private")))
        assertNull(PageNetworkFailures.exceptionKind(IllegalStateException("403")))
    }

    @Test fun allFailedSourcesDoNotHideKnownDnsFailureAsExpiredWork() {
        val dns = PageNetworkFailures.browserMessage(-2)
        assertEquals(dns, PageNetworkFailures.unavailableMessage(dns))
        assertEquals(dns, PageNetworkFailures.unavailableMessage("no data", PageNetworkFailure.DNS))
        assertEquals(dns, PageNetworkFailures.unavailableMessage("no data", desktopReason = dns))
        assertFalse(dns.contains("失效"))
    }

    @Test fun unknownServerStringsAndUrlsDoNotBecomeDisplayedNetworkReasons() {
        val message = PageNetworkFailures.unavailableMessage("https://private.invalid/?token=secret", desktopReason = "DNS secret")
        assertTrue(message.contains("暂未返回"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("private"))
        assertTrue(PageNetworkFailures.browserMessage(-77).contains("-77"))
    }
}
