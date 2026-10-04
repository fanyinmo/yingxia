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
    private val hostField = Regex(
        """(?i)(?<![A-Za-z0-9_.-])["']?(?:host|sourceHost|mediaHost)["']?\s*[:=]\s*(?:"([A-Za-z0-9.-]+)"|'([A-Za-z0-9.-]+)'|([A-Za-z0-9.-]+))(?=$|[\s,;}\]])"""
    )
    private val dnsLabel = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?""")
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
        // Long CDN names are useful origins, not opaque secrets. Exempt only a complete DNS
        // name in an explicit host field or an already-redacted URL origin. This changes
        // logging only: it does not grant the host permission to serve a download.
        val hostRanges = hostField.findAll(result).filter { match ->
            isDnsHost(match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty())
        }.map { it.range }.toList()
        val originRanges = embeddedUrl.findAll(result).filter { match ->
            val uri = runCatching { URI(match.value) }.getOrNull()
            uri?.rawUserInfo == null && isDnsHost(uri?.host.orEmpty()) &&
                uri?.rawQuery == null && uri?.rawFragment == null && uri?.rawPath.orEmpty().isEmpty()
        }.map { it.range }.toList()
        val preservedOrigins = hostRanges + originRanges
        result = opaqueValue.replace(result) { match ->
            if (preservedOrigins.any { it.first <= match.range.first && it.last >= match.range.last })
                match.value else "[REDACTED]"
        }
        result = whitespace.replace(result, " ").trim()
        if (result.length <= limit) return result
        // Keep the limit exact, without splitting a supplementary Unicode character.
        var end = (limit - 1).coerceAtLeast(0)
        if (end > 0 && result[end - 1].isHighSurrogate()) end--
        return result.take(end) + "…"
    }

    private fun isDnsHost(host: String): Boolean {
        if (host.length !in 3..253) return false
        val labels = host.split('.')
        return labels.size >= 2 && labels.all { dnsLabel.matches(it) } &&
            labels.last().length >= 2 && labels.last().any { it in 'a'..'z' || it in 'A'..'Z' }
    }
}
