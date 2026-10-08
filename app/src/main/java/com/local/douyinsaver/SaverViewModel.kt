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
    var staticImageSeconds: Double get() = engine.staticImageSeconds; set(value) { engine.updateStaticImageSeconds(value) }
    var itemDurationSeconds: Double? get() = engine.itemDurationSeconds; set(value) { engine.setSelectedItemDuration(value) }
    fun itemDurationForTask(key: String) = engine.itemDurationForTask(key)
    fun setItemDurationForTask(key: String, value: Double?) = engine.setItemDurationForTask(key, value)
    val durationAdjustments get() = engine.durationAdjustments
    fun confirmDurationAdjustment() = engine.confirmDurationAdjustment()
    fun returnToDurationEditing() = engine.returnToDurationEditing()
    var gifStartSeconds: Float get() = engine.gifStartSeconds; set(value) { engine.gifStartSeconds = value }
    var gifDurationSeconds: Float get() = engine.gifDurationSeconds; set(value) { engine.gifDurationSeconds = value }
    var gifExportQuality: GifExportQuality get() = engine.gifExportQuality; set(value) { engine.gifExportQuality = value }
    var fileName: String get() = engine.fileName; set(value) { engine.fileName = value.take(120) }
    var namingRule: NamingRule get() = engine.namingRule; set(value) { engine.updateNamingRule(value) }
    val watermarkMode get() = engine.watermarkMode
    val defaultWatermarkMode get() = engine.defaultWatermarkMode
    val batchWatermarkMode get() = engine.batchWatermarkMode
    val busy get() = engine.busy
    val stage get() = engine.stage
    val message get() = engine.message
    val video get() = engine.video
    val selectedContent get() = engine.selectedContent
    val downloaded get() = engine.downloaded
    val total get() = engine.total
    val history get() = engine.history
    val diagnostics get() = engine.diagnostics
    val downloadFolder get() = engine.downloadFolder
    val downloadLocationLabel get() = engine.downloadLocationLabel
    val queue get() = engine.queue
    val queueRunning get() = engine.queueRunning
    val queueResults get() = engine.queueResults
    val selectedTaskKey get() = engine.selectedTaskKey
    val batchSaving get() = engine.batchSaving
    val duplicate get() = engine.duplicate
    val browsingId get() = engine.browsingId
    val generation get() = engine.generation
    val albumMotionVerificationVisible get() = engine.albumMotionVerificationVisible
    fun albumMotionState(taskKey: String? = selectedTaskKey) = engine.albumMotionState(taskKey)
    fun checkAlbumMotion(taskKey: String? = selectedTaskKey) = engine.checkAlbumMotion(taskKey)
    fun saveAlbumMotion(taskKey: String? = selectedTaskKey, force: Boolean = false, mode: AlbumMode = AlbumMode.IMAGES,
        selectedImageIndices: List<Int> = emptyList()) = engine.saveAlbumMotion(taskKey, force, mode, selectedImageIndices)
    fun saveFailedShareAlbumMotion(mode: AlbumMode = AlbumMode.IMAGES) = engine.saveFailedShareAlbumMotion(mode)
    fun checkFailedShareAlbumMotion() = engine.saveFailedShareAlbumMotion(saveOnReady = false)
    fun cancelAlbumMotion() = engine.cancelAlbumMotion()
    fun openAlbumMotionVerification() = engine.openAlbumMotionVerification()
    fun continueAlbumMotionVerification() = engine.continueAlbumMotionVerification()
    fun attachAlbumMotionVerificationHost(host: FrameLayout) = engine.attachAlbumMotionVerificationHost(host)
    fun detachAlbumMotionVerificationHost(host: FrameLayout) = engine.detachAlbumMotionVerificationHost(host)
    fun attachParserHost(host: FrameLayout) = engine.attachParserHost(host)
    fun detachParserHost(host: FrameLayout) = engine.detachParserHost(host)
    fun acceptShare(text: String) = engine.acceptShare(text)
    fun clearInput() = engine.clearInput()
    fun resolve() = engine.resolve()
    fun download(force: Boolean = false, selectedImageIndices: List<Int> = emptyList()) = engine.download(force, selectedImageIndices)
    fun downloadGif(force: Boolean = false) = engine.downloadGif(force)
    fun cancel() = engine.cancel()
    fun selectDownloadFolder(uri: Uri) = engine.selectDownloadFolder(uri)
    fun resetDownloadFolder() = engine.resetDownloadFolder()
    fun enqueueInput(text: String = input) = engine.enqueueInput(text)
    fun startQueue() = engine.startQueue()
    fun stopQueue() = engine.stopQueue()
    fun retryFailedQueue() = engine.retryFailedQueue()
    fun retryTask(key: String) = engine.retryTask(key)
    fun clearQueue() = engine.clearQueue()
    fun removeTask(key: String) = engine.removeTask(key)
    fun moveTask(key: String, delta: Int) = engine.moveTask(key, delta)
    fun moveTaskTo(key: String, targetIndex: Int) = engine.moveTaskTo(key, targetIndex)
    fun viewTaskResult(key: String) = engine.viewTaskResult(key)
    fun downloadTask(key: String, force: Boolean = false, mode: AlbumMode = AlbumMode.IMAGES,
        selectedImageIndices: List<Int> = emptyList()) = engine.downloadTask(key, force, mode, selectedImageIndices)
    fun downloadParsedQueue(keys: Set<String> = emptySet(), mode: AlbumMode = AlbumMode.IMAGES) = engine.downloadParsedQueue(keys, mode)
    fun manageHistory(uris: Set<String>, deleteFiles: Boolean) = engine.manageHistory(uris, deleteFiles)
    fun refreshHistory() = engine.refreshHistory()
}
