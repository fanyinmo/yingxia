package com.local.douyinsaver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DownloadRecords(context: Context, namespace: String = "") {
    private val completed = context.getSharedPreferences("${namespace}downloads", 0)
    private val tasks = context.getSharedPreferences("${namespace}download_tasks", 0)

    @Synchronized fun history(): List<SavedVideo> = readArray(completed.getString("completed", "[]")) { obj ->
        val uri = obj.getString("uri")
        val uris = strings(obj.optJSONArray("uris")).ifEmpty { listOf(uri) }
        SavedVideo(obj.getString("id"), obj.getString("title"), uri, obj.getLong("bytes"),
            obj.optString("locationLabel", DownloadStorage.DEFAULT_LOCATION), obj.optLong("savedAt", 0),
            obj.optString("mimeType", "video/mp4"), uris,
            obj.optString("coverUri", ""), obj.optString("fileName", ""), obj.optBoolean("isAlbum"),
            runCatching { WatermarkMode.valueOf(obj.optString("watermarkMode")) }.getOrNull(),
            readAssets(obj.optJSONArray("albumAssets"), uris),
            obj.optString("exportMode", ""), obj.optLong("gifStartMs", 0L), obj.optLong("gifDurationMs", 0L),
            obj.optInt("albumSourceCount", 0), obj.optString("albumTimingSignature", ""), obj.optString("gifExportQuality", ""))
    }

    @Synchronized fun save(saved: SavedVideo) = writeHistory((listOf(saved) + history()).distinctBy { it.uri })
    @Synchronized fun writeHistory(items: List<SavedVideo>) {
        val array = JSONArray()
        items.take(1000).forEach { saved -> array.put(JSONObject().put("id", saved.id).put("title", saved.title)
            .put("uri", saved.uri).put("bytes", saved.bytes).put("locationLabel", saved.locationLabel)
            .put("savedAt", saved.savedAt).put("mimeType", saved.mimeType).put("uris", JSONArray(saved.uris))
            .put("coverUri", saved.coverUri).put("fileName", saved.fileName).put("isAlbum", saved.isAlbum)
            .put("watermarkMode", saved.watermarkMode?.name ?: JSONObject.NULL)
            .put("exportMode", saved.exportMode).put("gifStartMs", saved.gifStartMs).put("gifDurationMs", saved.gifDurationMs)
            .put("albumSourceCount", saved.albumSourceCount)
            .put("albumTimingSignature", saved.albumTimingSignature)
            .put("gifExportQuality", saved.gifExportQuality)
            .put("albumAssets", JSONArray().apply {
                saved.albumAssets.take(100).forEach { asset -> put(JSONObject().put("uri", asset.uri)
                    .put("mimeType", asset.mimeType).put("kind", asset.kind.name).put("motionUri", asset.motionUri)
                    .put("embeddedMotion", asset.embeddedMotion).put("sourceIndex", asset.sourceIndex)) }
            })) }
        check(completed.edit().putString("completed", array.toString()).commit()) { "文件已保存，但记录写入失败，请到下载目录查看" }
    }

    fun queue(): List<QueueTask> = readArray(tasks.getString("queue", "[]")) { obj ->
        QueuePolicy.recover(QueueTask(obj.getString("key"), obj.getString("source"),
            obj.optString("title", "待解析作品"), runCatching { QueueStatus.valueOf(obj.getString("status")) }.getOrDefault(QueueStatus.FAILED),
            obj.optString("message"), obj.optLong("createdAt", 0)))
    }

    fun writeQueue(items: List<QueueTask>) {
        // Persist only the small task metadata. Signed media URLs and parsed source
        // variants stay in SaverEngine memory and are explicitly re-parsed on restart.
        val array = JSONArray()
        items.distinctBy { it.key }.take(200).forEach { task -> array.put(JSONObject().put("key", task.key).put("source", task.source)
            .put("title", task.title.take(500)).put("status", task.status.name).put("message", task.message.take(500)).put("createdAt", task.createdAt)) }
        check(tasks.edit().putString("queue", array.toString()).commit()) { "任务队列未能保存，请检查手机可用空间" }
    }

    private fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0))
        .mapNotNull { array?.optString(it)?.takeIf(String::isNotBlank) }.take(200)
    private fun readAssets(array: JSONArray?, uris: List<String>): List<SavedAlbumAsset> =
        (0 until minOf(array?.length() ?: 0, 100)).mapNotNull { index ->
            val obj = array?.optJSONObject(index) ?: return@mapNotNull null
            val uri = obj.optString("uri").takeIf { it.isNotBlank() && it in uris } ?: return@mapNotNull null
            val mime = obj.optString("mimeType").takeIf { it.startsWith("image/") || it == "video/mp4" }
                ?: return@mapNotNull null
            SavedAlbumAsset(uri, mime,
                runCatching { AlbumAssetKind.valueOf(obj.optString("kind")) }.getOrDefault(AlbumAssetKind.STATIC),
                obj.optString("motionUri").takeIf { it.isNotBlank() && it in uris && it != uri }.orEmpty(),
                obj.optBoolean("embeddedMotion", false), obj.optInt("sourceIndex", -1))
        }
    private fun <T> readArray(raw: String?, decode: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until minOf(array.length(), 1000)).mapNotNull { index ->
            runCatching { decode(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
