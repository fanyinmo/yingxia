package com.local.douyinsaver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FileNames {
    fun build(content: ParsedVideo, options: DownloadOptions, extension: String): String {
        require(extension.matches(Regex("[a-zA-Z0-9]{1,8}"))) { "不支持的文件格式" }
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val raw = options.fileName.trim().ifBlank {
            when (options.namingRule) {
                NamingRule.ID -> "douyin_${content.id}"
                NamingRule.TITLE -> content.title
                NamingRule.DATE_TITLE -> "${date}_${content.title}"
            }
        }.removeSuffix(".$extension")
        return "${safeStem(raw)}_${UUID.randomUUID().toString().take(8)}.${extension.lowercase(Locale.ROOT)}"
    }

    fun safeStem(raw: String): String {
        val clean = raw.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ").trim(' ', '.').ifBlank { "douyin" }
        val out = StringBuilder()
        var bytes = 0
        clean.codePoints().toArray().forEach { code ->
            val char = String(Character.toChars(code))
            val size = char.toByteArray(Charsets.UTF_8).size
            if (bytes + size <= 110) { out.append(char); bytes += size } else return@forEach
        }
        return out.toString().trim(' ', '.').ifBlank { "douyin" }
    }
}
