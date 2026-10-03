package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextTest {
    @Test fun keepsUsefulJavascriptErrorsAndCspReasons() {
        val input = "SyntaxError: Unexpected token '<'; TypeError: player is undefined; NotAllowedError: play() requires a gesture; CSP: Refused to load script"
        assertEquals(input, DiagnosticText.clean(input))
    }

    @Test fun retainsOnlyUrlOriginAndRemovesCredentialsPathQueryAndFragment() {
        val result = DiagnosticText.clean("TypeError at https://user:pass@www.douyin.com:443/video/privateId?token=short#secret and https://v.example.com/private.mp4?x=1")
        assertEquals("TypeError at https://www.douyin.com and https://v.example.com", result)
        listOf("user", "pass", "privateId", "short", "secret", "private.mp4").forEach { assertFalse(result.contains(it)) }
    }

    @Test fun hidesEntireSensitiveHeaderLinesAndFoldedValues() {
        val input = "TypeError: failed\nHeaders Cookie: sid=short\n\tcontinuation-secret\nSet-Cookie: abc=def\nAuthorization: Bearer tiny\n\"Proxy-Authorization\": \"Basic private\"\nSyntaxError: bad JSON"
        val result = DiagnosticText.clean(input)
        assertTrue(result.startsWith("TypeError: failed"))
        assertTrue(result.endsWith("SyntaxError: bad JSON"))
        listOf("sid", "short", "continuation-secret", "abc", "def", "tiny", "private").forEach { assertFalse(result.contains(it)) }
    }

    @Test fun hidesShortSecretsInAssignmentsAndQuotedJson() {
        val input = "access_token=a&sessionId=bb password='c d'; {\"csrfToken\":\"ee ff\",\"api_key\":\"gg\"} signature: hh; status=failed"
        val result = DiagnosticText.clean(input)
        assertTrue(result.contains("status=failed"))
        assertTrue(result.contains("access_token=[REDACTED]"))
        assertTrue(result.contains("sessionId=[REDACTED]"))
        assertTrue(result.contains("password=[REDACTED]"))
        listOf("c d", "ee ff", "gg", "hh").forEach { assertFalse(result.contains(it)) }
    }

    @Test fun hidesOpaqueValuesAndBearerOutsideHeaders() {
        val result = DiagnosticText.clean("request abcDEF0123456789abcDEF0123456789abcDEF0123456789 failed; Bearer tiny; TypeError: unavailable")
        assertEquals("request [REDACTED] failed; Bearer [REDACTED]; TypeError: unavailable", result)
    }

    @Test fun hidesNonHttpUrlsAndMalformedUrlPayloads() {
        val result = DiagnosticText.clean("blob:https://video.test/uuid data:text/plain,private file:///sdcard/private content://provider/private //cdn.test/signed/path?x=secret https://bad[host]/private")
        listOf("uuid", "private", "sdcard", "provider", "signed", "secret", "bad[host]").forEach { assertFalse(result.contains(it)) }
        assertTrue(result.contains("[URL]"))
    }

    @Test fun sanitizesBeforeTruncatingAndNormalizesControlCharacters() {
        val result = DiagnosticText.clean("TypeError:\u0000\u200B\n\tpassword=abcde0123456789 error", 36)
        assertEquals("TypeError: password=[REDACTED] error", result)
        assertEquals("", DiagnosticText.clean("password=secret", 0))
        assertEquals("", DiagnosticText.clean("password=secret", -1))
        assertEquals("…", DiagnosticText.clean("error", 1))
    }

    @Test fun limitsLongMessagesWithoutSplittingSurrogatePairs() {
        val result = DiagnosticText.clean("错误😀后面的说明", 4)
        assertEquals("错误…", result)
        assertTrue(result.length <= 4)
        assertEquals(400, DiagnosticText.clean("错误".repeat(300)).length)
    }
}
