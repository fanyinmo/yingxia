package com.local.douyinsaver

import java.net.URI

/** Small, redacted excerpts for diagnostics. Never pass whole response bodies or headers here. */
object DiagnosticText {
    private val sensitiveHeader = Regex(
        """(?i)\b(?:set-cookie|cookie|authorization|proxy-authorization)["']?\s*[:=]"""
    )
    private val embeddedUrl = Regex("""(?i)\b(?:blob:)?(?:https?|wss?)://[^\s<>"'\\]+""")
    private val otherUrl = Regex("""(?i)\b(?:data|file|content):[^\s<>"']+""")
    private val relativeUrl = Regex("""(?<![:/])//[A-Za-z0-9\[][^\s<>"'\\]*""")
    private val sensitiveValue = Regex(
        """(?i)(?<![A-Za-z0-9_.-])(["']?[A-Za-z0-9_.-]*(?:token|session|password|passwd|secret|credential|signature|ticket|csrf|api[-_]?key|authorization|cookie)[A-Za-z0-9_.-]*["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;&}\]]+)"""
    )
    private val bearer = Regex("""(?i)\bBearer\s+[^\s,;]+""")
    private val opaqueValue = Regex("""[A-Za-z0-9_+/.=%~-]{32,}""")
    private val invisibleControls = Regex("""[\p{Cc}\p{Cf}&&[^\r\n\t]]""")
    private val whitespace = Regex("""\s+""")

    fun clean(text: String, limit: Int = 400): String {
        if (limit <= 0 || text.isEmpty()) return ""

        // Redact complete header lines, including folded continuation lines, before shortening.
        var headerContinuation = false
        val withoutHeaders = invisibleControls.replace(text, "").lineSequence().joinToString("\n") { line ->
            val continuation = headerContinuation && line.firstOrNull()?.isWhitespace() == true
            headerContinuation = sensitiveHeader.containsMatchIn(line) || continuation
            if (headerContinuation) "[REDACTED_HEADER]" else line
        }
        var result = embeddedUrl.replace(withoutHeaders) { match ->
            if (match.value.startsWith("blob:", ignoreCase = true)) {
                "[URL]"
            } else {
                val uri = runCatching { URI(match.value) }.getOrNull()
                val host = uri?.host
                if (uri == null || host.isNullOrBlank()) "[URL]" else "${uri.scheme.lowercase()}://$host"
            }
        }
        result = otherUrl.replace(result, "[URL]")
        result = relativeUrl.replace(result, "[URL]")
        result = sensitiveValue.replace(result) { "${it.groupValues[1]}[REDACTED]" }
        result = bearer.replace(result, "Bearer [REDACTED]")
        result = opaqueValue.replace(result, "[REDACTED]")
        result = whitespace.replace(result, " ").trim()
        if (result.length <= limit) return result
        // Keep the limit exact, without splitting a supplementary Unicode character.
        var end = (limit - 1).coerceAtLeast(0)
        if (end > 0 && result[end - 1].isHighSurrogate()) end--
        return result.take(end) + "…"
    }
}
