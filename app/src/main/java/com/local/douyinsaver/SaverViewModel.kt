package com.local.douyinsaver

import android.app.Application
import android.net.Uri
import android.widget.FrameLayout
import androidx.lifecycle.AndroidViewModel

/** The process-owned engine survives Activity recreation while a foreground download runs. */
class SaverViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = SaverEngine.get(application)
    var input: String get() = engine.input; set(value) { engine.updateInput(value) }
    var selectedPage: AppPage get() = engine.selectedPage; set(value) { engine.selectedPage = value }
    var albumMode: AlbumMode get() = engine.albumMode; set(value) { engine.albumMode = value }
    var imageSeconds: Int get() = engine.imageSeconds; set(value) { engine.imageSeconds = value.coerceIn(2, 10) }
    var fileName: String get() = engine.fileName; set(value) { engine.fileName = value.take(120) }
    var namingRule: NamingRule get() = engine.namingRule; set(value) { engine.updateNamingRule(value) }
    val busy get() = engine.busy
    val stage get() = engine.stage
    val message get() = engine.message
    val video get() = engine.video
    val downloaded get() = engine.downloaded
    val total get() = engine.total
    val history get() = engine.history
    val diagnostics get() = engine.diagnostics
    val downloadFolder get() = engine.downloadFolder
    val downloadLocationLabel get() = engine.downloadLocationLabel
    val queue get() = engine.queue
    val duplicate get() = engine.duplicate
    val browsingId get() = engine.browsingId
    val generation get() = engine.generation
    fun attachParserHost(host: FrameLayout) = engine.attachParserHost(host)
    fun detachParserHost(host: FrameLayout) = engine.detachParserHost(host)
    fun acceptShare(text: String) = engine.acceptShare(text)
    fun clearInput() = engine.clearInput()
    fun resolve() = engine.resolve()
    fun download(force: Boolean = false) = engine.download(force)
    fun cancel() = engine.cancel()
    fun selectDownloadFolder(uri: Uri) = engine.selectDownloadFolder(uri)
    fun resetDownloadFolder() = engine.resetDownloadFolder()
    fun enqueueInput(text: String = input) = engine.enqueueInput(text)
    fun startQueue() = engine.startQueue()
    fun retryTask(key: String) = engine.retryTask(key)
    fun removeTask(key: String) = engine.removeTask(key)
    fun moveTask(key: String, delta: Int) = engine.moveTask(key, delta)
    fun manageHistory(uris: Set<String>, deleteFiles: Boolean) = engine.manageHistory(uris, deleteFiles)
    fun refreshHistory() = engine.refreshHistory()
}
