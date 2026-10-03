package com.local.douyinsaver

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore

/** Owns only files created for the current download. Folder selection never moves existing videos. */
class DownloadStorage(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences("download_folder", 0)

    fun loadFolder(): DownloadFolder? {
        val uri = preferences.getString("tree_uri", null) ?: return null
        return DownloadFolder(uri, preferences.getString("label", null) ?: "自定义文件夹")
    }

    fun selectFolder(uri: Uri): DownloadFolder {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请选择一个可写入的文件夹" }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            error("没有获得文件夹的长期读写权限，请重新选择并点击“使用此文件夹”")
        }
        val folder = DownloadFolder(uri.toString(), folderLabel(uri))
        validateAccess(folder)
        check(preferences.edit().putString("tree_uri", folder.treeUri).putString("label", folder.label).commit()) {
            "下载文件夹设置未保存，请重试"
        }
        return folder
    }

    fun resetFolder() {
        check(preferences.edit().clear().commit()) { "下载文件夹设置未保存，请重试" }
        // Keep previously granted tree access so older download records remain readable.
    }

    fun validateAccess(folder: DownloadFolder) {
        val tree = Uri.parse(folder.treeUri)
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "下载文件夹无效，请在设置中重新选择" }
        val permission = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
        check(permission) { "下载文件夹授权已失效，请在设置中重新选择" }
        try {
            val document = treeDocument(tree)
            resolver.query(document, arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                check(cursor.moveToFirst()) { "下载文件夹已移动或不存在，请在设置中重新选择" }
                check(cursor.getString(0) == Document.MIME_TYPE_DIR) { "下载位置不是文件夹，请重新选择" }
                check(cursor.getInt(1) and Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "所选文件夹无法写入，请选择其他文件夹" }
            } ?: error("无法读取下载文件夹，请在设置中重新选择")
        } catch (_: SecurityException) {
            error("下载文件夹授权已失效，请在设置中重新选择")
        }
    }

    internal fun createPending(name: String, folder: DownloadFolder?, mimeType: String = "video/mp4"): PendingDownload {
        require(mimeType == "video/mp4" || mimeType.startsWith("image/")) { "文件类型不支持" }
        val image = mimeType.startsWith("image/")
        val defaultLocation = if (image) DEFAULT_IMAGE_LOCATION else DEFAULT_LOCATION
        val noun = if (image) "图片" else "视频"
        if (folder == null) {
            val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, defaultLocation)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("无法创建${noun}文件，请检查可用空间")
            return PendingDownload(uri, name, defaultLocation, true)
        }
        validateAccess(folder)
        val parent = treeDocument(Uri.parse(folder.treeUri))
        val extension = name.substringAfterLast('.', "")
        val temporaryName = ".${name.substringBeforeLast('.', name)}.pending${if (extension.isBlank()) "" else ".$extension"}"
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, temporaryName)
            ?: error("无法在所选文件夹创建${noun}，请检查权限和可用空间")
        val pending = PendingDownload(uri, name, folder.label, false)
        try {
            // A new temporary document must support final renaming and cancellation cleanup.
            val required = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
            resolver.query(uri, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) and required == required) {
                    "该文件夹不支持安全保存文件，请选择手机本地文件夹"
                }
            } ?: error("无法读取新建文件，请选择其他文件夹")
            return pending
        } catch (error: Exception) {
            runCatching { pending.cleanup() }
            throw error
        }
    }

    internal inner class PendingDownload(
        var uri: Uri,
        private val finalName: String,
        val locationLabel: String,
        private val mediaStore: Boolean,
    ) {
        private var published = false

        /** Called only after the stream length and actual media contents have been validated. */
        fun publish(): Uri {
            if (mediaStore) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                    "文件未能发布到相册"
                }
            } else {
                uri = DocumentsContract.renameDocument(resolver, uri, finalName)
                    ?: error("文件未能保存到所选文件夹，请检查权限和可用空间")
            }
            published = true
            return uri
        }

        fun cleanup() {
            if (published) return
            deleteOwnedFile()
        }

        /** Album rollback is restricted to handles created by that one in-flight operation. */
        fun rollback() {
            deleteOwnedFile()
        }

        private fun deleteOwnedFile() {
            if (mediaStore) resolver.delete(uri, null, null)
            else DocumentsContract.deleteDocument(resolver, uri)
        }
    }

    private fun treeDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun folderLabel(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        if (tree.authority == "com.android.externalstorage.documents") {
            val volume = id.substringBefore(':')
            val relative = id.substringAfter(':', "")
            val root = if (volume == "primary") "内部存储" else "存储设备 $volume"
            return if (relative.isBlank()) root else "$root/$relative"
        }
        return resolver.query(treeDocument(tree), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "自定义文件夹"
    }

    companion object {
        const val DEFAULT_LOCATION = "Movies/DouyinDownloads"
        const val DEFAULT_IMAGE_LOCATION = "Pictures/DouyinDownloads"
    }
}
