package com.local.douyinsaver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ParseRecord(val id: String, val title: String, val isAlbum: Boolean,
    val parsedAt: Long = System.currentTimeMillis()) {
    val source: String get() = workUrl(id, isAlbum)
    companion object {
        fun workUrl(id: String, isAlbum: Boolean): String {
            require(id.matches(Regex("[0-9]{15,22}"))) { "作品编号无效" }
            return "https://www.douyin.com/${if (isAlbum) "note" else "video"}/$id"
        }
    }
}

/** Work metadata only: never persist expiring media URLs, cookies or downloaded files. */
internal class ParseRecords(context: Context, namespace: String = "") {
    private val preferences = context.applicationContext.getSharedPreferences("${namespace}parse_records", 0)
    @Synchronized fun history(): List<ParseRecord> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until minOf(array.length(), 200)).mapNotNull { index ->
            runCatching {
                val row = array.getJSONObject(index)
                ParseRecord(row.getString("id"), row.optString("title"), row.optBoolean("isAlbum"), row.optLong("parsedAt"))
                    .also { it.source }
            }.getOrNull()
        }.distinctBy { it.id }.sortedByDescending { it.parsedAt }
    }.getOrDefault(emptyList())
    @Synchronized fun save(record: ParseRecord) {
        record.source // Validate before touching existing history.
        write((listOf(record.copy(title = record.title.take(500))) + history()).distinctBy { it.id }.take(200))
    }
    @Synchronized fun remove(id: String) = write(history().filterNot { it.id == id })
    private fun write(rows: List<ParseRecord>) {
        val array = JSONArray()
        rows.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("isAlbum", it.isAlbum).put("parsedAt", it.parsedAt)) }
        check(preferences.edit().putString("items", array.toString()).commit()) { "解析记录未能保存，请检查可用空间" }
    }
}
