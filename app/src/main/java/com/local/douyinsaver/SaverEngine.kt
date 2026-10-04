package com.local.douyinsaver

import android.app.Application
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import android.provider.DocumentsContract

class SaverEngine private constructor(private val application: Application, private val namespace: String = "") {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private val records = DownloadRecords(application, namespace)
    private val optionsPreferences = application.getSharedPreferences("${namespace}download_options", 0)
    // Older preferences and records remain intact, but cannot change the download rendition.
    val defaultWatermarkMode: WatermarkMode get() = WatermarkMode.CLEAN
    val watermarkMode: WatermarkMode get() = WatermarkMode.CLEAN
    val batchWatermarkMode: WatermarkMode get() = WatermarkMode.CLEAN
    var albumMode by mutableStateOf(AlbumMode.IMAGES)
    var imageSeconds by mutableIntStateOf(optionsPreferences.getInt("image_seconds", 3).coerceIn(2, 10))
    var fileName by mutableStateOf("")
    var namingRule by mutableStateOf(runCatching { NamingRule.valueOf(optionsPreferences.getString("naming", "DATE_TITLE")!!) }
        .getOrDefault(NamingRule.DATE_TITLE)); private set
    var queue by mutableStateOf(records.queue()); private set
    private var activeTaskKey: String? = null
    var selectedTaskKey by mutableStateOf<String?>(null); private set
    var queueRunning by mutableStateOf(false); private set
    var queueResults by mutableStateOf<Map<String, ParsedVideo>>(emptyMap()); private set
    var batchSaving by mutableStateOf(false); private set
    private var queueDuplicates: Map<String, List<SavedVideo>> = emptyMap()
    private var queueEpoch = 0
    private var queueAdvanceJob: Job? = null
    private var batchAdvanceJob: Job? = null
    private var batchEpoch = 0
    private var pendingBatchKeys: List<String> = emptyList()
    private var batchOptions: DownloadOptions? = null
    private var batchSaved = 0
    private var batchSkipped = 0
    private var batchFailed = 0
    private data class SingleSelection(val video: ParsedVideo, val duplicates: List<SavedVideo>,
        val stage: TaskStage, val albumMode: AlbumMode, val fileName: String, val downloaded: Long, val total: Long)
    private var singleSelection: SingleSelection? = null
    /** Deterministic fixture dispatch is available only to isolated instrumentation engines. */
    internal var queueParseOverride: ((QueueTask) -> Unit)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换解析来源" }; field = value }
    internal var queueSaveOverride: (suspend (ParsedVideo, DownloadOptions) -> SavedVideo)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换保存方式" }; field = value }
    private var duplicateCandidates by mutableStateOf<List<SavedVideo>>(emptyList())
    val duplicate: SavedVideo? get() {
        val matchingKind = duplicateCandidates.filter {
            if (video?.isAlbum == true && albumMode == AlbumMode.IMAGES) it.mimeType.startsWith("image/")
            else it.mimeType == "video/mp4"
        }
        return matchingKind.firstOrNull { it.watermarkMode == WatermarkMode.CLEAN }
    }
    private val parsePreferences = application.getSharedPreferences("${namespace}parse_diagnostics", 0)
    var input by mutableStateOf(parsePreferences.getString("last_url", "").orEmpty()); private set
    var selectedPage by mutableStateOf(AppPage.HOME)
    var stage by mutableStateOf(TaskStage.IDLE); private set
    var message by mutableStateOf("粘贴一条抖音分享文案，开始解析"); private set
    var browsingId by mutableStateOf<String?>(null); private set
    var generation by mutableIntStateOf(0); private set
    var video by mutableStateOf<ParsedVideo?>(null); private set
    val selectedContent: ParsedVideo? get() = video?.let {
        runCatching { WatermarkSources.select(it, WatermarkMode.CLEAN) }.getOrNull()
    }
    var downloaded by mutableLongStateOf(0); private set
    var total by mutableLongStateOf(-1); private set
    var history by mutableStateOf(loadHistory()); private set
    var diagnostics by mutableStateOf(parsePreferences.getString("summary", "").orEmpty()); private set
    private val storage = DownloadStorage(application)
    var downloadFolder by mutableStateOf(storage.loadFolder()); private set
    val downloadLocationLabel get() = downloadFolder?.label ?: if (video?.isAlbum == true && albumMode == AlbumMode.IMAGES)
        DownloadStorage.DEFAULT_IMAGE_LOCATION else DownloadStorage.DEFAULT_LOCATION
    private var settingFolder by mutableStateOf(false)
    private var job: Job? = null
    private var parserHost: FrameLayout? = null
    private var parser: BrowserParser? = null
    private var probe: MediaProbe? = null
    private var publicApi: PublicVideoApi? = null
    private var apiAttempted = false
    private val downloader = ContentDownloader(application)
    val busy get() = settingFolder || stage in listOf(TaskStage.RESOLVING, TaskStage.BROWSING,
        TaskStage.VERIFYING, TaskStage.DOWNLOADING, TaskStage.CANCELLING, TaskStage.SAVING)

    fun attachParserHost(host: FrameLayout) {
        if (parserHost !== host) stopParser()
        parserHost = host
        host.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        startParserIfReady()
        if (queueRunning && !busy) advanceQueue()
    }

    fun detachParserHost(host: FrameLayout) {
        if (parserHost !== host) return
        stopParser()
        parserHost = null
        if (stage == TaskStage.BROWSING) message = "返回 App 后继续解析；已开始的下载可在后台进行"
    }

    private fun startParserIfReady() {
        val host = parserHost ?: return
        val id = browsingId ?: return
        if (stage != TaskStage.BROWSING || parser != null) return
        val current = generation
        var browser: WebView? = null
        try {
            browser = WebView(host.context)
            host.addView(browser, FrameLayout.LayoutParams(-1, -1))
            parser = BrowserParser(browser, id, { parsed(current, it) }, { parseFailed(current, it) },
                { if (current == generation) trace(it) })
        } catch (_: Exception) {
            host.removeAllViews()
            browser?.destroy()
            parseFailed(current, "无法启动视频解析，请更新手机的 Android System WebView 后重试")
        }
    }

    private fun stopParser() {
        parser?.dispose()
        parser = null
    }

    private fun stopForegroundService() {
        // Isolated engines created by instrumentation must not stop the process-owned service.
        if (namespace.isEmpty()) DownloadForegroundService.stop(application)
    }

    private fun trace(event: String) {
        val clean = DiagnosticText.clean(event, 2400)
        Log.i("DyParse", clean)
        diagnostics = (diagnostics.lineSequence().filter { it.isNotBlank() }.toList() + clean)
            .takeLast(40).joinToString("\n").takeLast(16_000)
        parsePreferences.edit().putString("summary", diagnostics).apply()
    }

    fun acceptShare(text: String) {
        if (!busy && !queueRunning && !batchSaving) { updateInput(text); selectedPage = AppPage.HOME }
    }

    fun updateInput(text: String) {
        if (busy || queueRunning || batchSaving) return
        val value = text.take(16_384)
        if (value == input) return
        input = value
        invalidateInputResult("输入已修改，请重新解析")
        persistInput()
    }

    fun clearInput() {
        if (busy || queueRunning || batchSaving) return
        input = ""
        invalidateInputResult("输入已清空，请重新解析")
        persistInput()
    }

    private fun persistInput() {
        val editor = parsePreferences.edit()
        if (input.isBlank()) editor.remove("last_url") else editor.putString("last_url", input)
        editor.apply()
    }

    private fun retireReadyTask(detail: String) {
        if (activeTaskKey?.let { queueResults[it] } == null && queue.any { it.key == activeTaskKey && it.status == QueueStatus.READY }) {
            updateTask(QueueStatus.CANCELLED, detail)
        }
    }

    private fun stopCurrentParsing() {
        generation++
        job?.cancel()
        job = null
        probe?.cancel()
        probe = null
        publicApi?.cancel()
        publicApi = null
        stopParser()
        browsingId = null
        apiAttempted = false
    }

    private fun resetResult() {
        video = null
        duplicateCandidates = emptyList()
        fileName = ""
        albumMode = AlbumMode.IMAGES
        downloaded = 0
        total = -1
    }

    private fun invalidateInputResult(detail: String) {
        singleSelection = null
        retireReadyTask(detail)
        activeTaskKey = null
        selectedTaskKey = null
        queueRunning = false
        queueEpoch++
        queueAdvanceJob?.cancel()
        stopCurrentParsing()
        resetResult()
        stage = TaskStage.IDLE
        message = "粘贴一条抖音分享文案，开始解析"
        stopForegroundService()
    }

    fun resolve() {
        if (busy || queueRunning || batchSaving) return
        queueRunning = false
        val source = runCatching { ShareLinks.extract(input) }.getOrElse {
            invalidateInputResult("已重新解析其他输入")
            fail(IllegalArgumentException(it.message))
            return
        }
        beginResolve(source)
    }

    private fun beginResolve(source: String, taskKey: String? = null) {
        val current = prepareResolve(source, taskKey)
        job = scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { ShareLinks.resolveVideoId(source) }
                if (current != generation) return@launch
                if (taskKey == null) parsePreferences.edit().putString("last_url", ShareLinks.extract(source)).apply()
                browsingId = id
                trace("share_resolved id=$id")
                stage = TaskStage.BROWSING
                message = "正在读取视频信息，请稍候…"
                startParserIfReady()
            } catch (_: CancellationException) {
                if (current == generation) stage = TaskStage.CANCELLED
            } catch (error: Exception) { if (current == generation) { fail(error); advanceQueueLater() } }
        }
    }

    private fun prepareResolve(source: String, taskKey: String?): Int {
        if (activeTaskKey != taskKey) retireReadyTask("已切换作品，请重新解析此任务")
        stopCurrentParsing()
        activeTaskKey = taskKey
        selectedTaskKey = taskKey
        if (taskKey != null) {
            queueResults = queueResults - taskKey
            queueDuplicates = queueDuplicates - taskKey
        }
        val current = generation
        diagnostics = ""
        trace("version=${BuildConfig.VERSION_NAME} android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
        resetResult()
        stage = TaskStage.RESOLVING
        updateTask(QueueStatus.PARSING)
        message = "正在识别分享链接…"
        return current
    }

    private fun parsed(current: Int, result: ParsedVideo) {
        if (current != generation || stage != TaskStage.BROWSING) return
        stopParser()
        stage = TaskStage.VERIFYING
        trace("media_probe_started")
        message = "正在检查视频是否可以下载…"
        val currentProbe = MediaProbe()
        probe = currentProbe
        job = scope.launch {
            try {
                val mainHandler = Handler(Looper.getMainLooper())
                val verified = if (result.isAlbum) result else currentProbe.verify(result) { event ->
                    mainHandler.post { if (current == generation) trace(event) }
                }
                if (current != generation) return@launch
                val duplicates = withContext(Dispatchers.IO) { accessibleDuplicates(verified.id) }
                if (current != generation || stage != TaskStage.VERIFYING) return@launch
                acceptVerifiedResult(current, verified, duplicates)
            } catch (_: CancellationException) {
                if (current == generation) { stage = TaskStage.CANCELLED; message = "任务已取消" }
            } catch (error: Exception) { if (current == generation) { fail(error); advanceQueueLater() } }
            finally { if (probe === currentProbe) probe = null }
        }
    }

    private fun acceptVerifiedResult(current: Int, verified: ParsedVideo, duplicates: List<SavedVideo>) {
        if (current != generation || stage != TaskStage.VERIFYING) return
        if (verified.isAlbum) {
            val cleanImages = verified.images.count { image -> image.mediaSources.any { it.mode == WatermarkMode.CLEAN } }
            val markedImages = verified.images.count { image -> image.mediaSources.any { it.mode == WatermarkMode.WATERMARKED } }
            trace("album_sources images=${verified.images.size} clean_images=$cleanImages marked_images=$markedImages " +
                "bgm=${verified.bgmUrl.isNotBlank()}")
        }
        if (!WatermarkSources.available(verified, WatermarkMode.CLEAN)) {
            fail(IllegalArgumentException(if (verified.isAlbum) "这套图集暂时无法获取完整图片，请重新解析后重试"
                else "这个视频暂时无法获取下载来源，请重新解析后重试"))
            advanceQueueLater()
            return
        }
        video = verified
        duplicateCandidates = duplicates
        activeTaskKey?.let { key ->
            queueResults = queueResults + (key to verified)
            queueDuplicates = queueDuplicates + (key to duplicates)
        }
        trace("media_probe_passed")
        stage = TaskStage.READY
        updateTask(QueueStatus.READY, title = verified.title)
        message = if (verified.isAlbum) "已解析 ${verified.images.size} 张图片，请选择保存方式" else "解析完成，可以下载视频"
        if (!queueRunning && activeTaskKey != null) restoreSingleSelection()
        advanceQueueLater()
    }

    private fun parseFailed(current: Int, detail: String) {
        if (current != generation || stage != TaskStage.BROWSING) return
        stopParser()
        val id = browsingId
        if (id == null || apiAttempted) {
            browsingId = null
            stage = TaskStage.FAILED
            message = detail
            updateTask(QueueStatus.FAILED, detail)
            if (!queueRunning) restoreSingleSelection()
            advanceQueueLater()
            return
        }
        apiAttempted = true
        stage = TaskStage.RESOLVING
        message = "正在确认作品信息…"
        val source = PublicVideoApi()
        publicApi = source
        val mainHandler = Handler(Looper.getMainLooper())
        val userAgent = WebSettings.getDefaultUserAgent(application)
        job = scope.launch {
            try {
                val result = source.read(id, userAgent) { event ->
                    mainHandler.post { if (current == generation) trace(event) }
                }
                if (current != generation) return@launch
                if (result != null) {
                    stage = TaskStage.BROWSING
                    parsed(current, result)
                } else {
                    stage = TaskStage.FAILED
                    message = "抖音网页暂未返回这个作品的可下载数据，请稍后重试"
                    trace("all_sources_unavailable page_reason=$detail")
                    updateTask(QueueStatus.FAILED, message)
                    if (!queueRunning) restoreSingleSelection()
                    advanceQueueLater()
                }
            } catch (_: CancellationException) {
                // cancel() owns the current state; stale results must not overwrite a new task.
            } catch (error: Exception) {
                if (current == generation) { fail(error); advanceQueueLater() }
            } finally { if (publicApi === source) publicApi = null }
        }
    }

    fun selectDownloadFolder(uri: Uri) {
        if (busy || queueRunning || batchSaving) return
        settingFolder = true
        scope.launch {
            try {
                downloadFolder = withContext(Dispatchers.IO) { storage.selectFolder(uri) }
                if (stage == TaskStage.FAILED) stage = if (video == null) TaskStage.IDLE else TaskStage.READY
                message = "保存位置已更新"
            } catch (error: Exception) { fail(error) }
            finally { settingFolder = false }
        }
    }

    fun resetDownloadFolder() {
        if (busy || queueRunning || batchSaving) return
        settingFolder = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { storage.resetFolder() }
                downloadFolder = null
                if (stage == TaskStage.FAILED) stage = if (video == null) TaskStage.IDLE else TaskStage.READY
                message = "已恢复默认相册目录"
            } catch (error: Exception) { fail(error) }
            finally { settingFolder = false }
        }
    }

    fun download(force: Boolean = false) {
        val selected = video ?: return
        if (busy || queueRunning) return
        val options = if (batchSaving) checkNotNull(batchOptions).copy(watermarkMode = WatermarkMode.CLEAN)
            else DownloadOptions(albumMode, imageSeconds.coerceIn(2, 10), fileName, namingRule, WatermarkMode.CLEAN)
        if (!WatermarkSources.available(selected, options.watermarkMode)) {
            val detail = if (selected.isAlbum) "这套图集暂时无法获取完整图片，请重新解析后重试"
                else "这个视频暂时无法获取下载来源，请重新解析后重试"
            message = detail
            return
        }
        if (!force && duplicate != null) {
            message = "已有可读取的文件，已跳过重复保存"
            updateTask(QueueStatus.DONE, message)
            return
        }
        if (selected.isAlbum && options.albumMode == AlbumMode.VIDEO && selected.bgmUrl.isBlank()) {
            message = "作品没有提供可读取的 BGM，可以选择保存图片"; return
        }
        try {
            if (queueSaveOverride == null) DownloadForegroundService.start(application, false)
        } catch (error: Exception) {
            message = "无法启动后台下载，请回到首页点击下载后重试"
            trace("service_start_failed type=${error.javaClass.simpleName}")
            return
        }
        optionsPreferences.edit().putInt("image_seconds", options.imageSeconds).apply()
        val folder = downloadFolder
        stage = TaskStage.DOWNLOADING
        updateTask(QueueStatus.RUNNING, title = selected.title)
        trace("download_started id=${selected.id} watermark=${options.watermarkMode}")
        message = if (selected.isAlbum) "正在下载图集素材…" else "正在下载，可切换到其他应用"
        downloaded = 0
        total = -1
        val current = generation
        job = scope.launch {
            var committed: SavedVideo? = null
            try {
                var lastUpdate = 0L
                val saved = withContext(Dispatchers.IO) {
                    queueSaveOverride?.invoke(selected, options)?.also { saveHistory(it); committed = it }
                        ?: downloader.download(selected, folder, options, onProgress = { read, size ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 250 || read == size) {
                            lastUpdate = now
                            withContext(Dispatchers.Main) {
                                downloaded = read; total = size
                                DownloadForegroundService.progress(message, read, size,
                                    selected.isAlbum && options.albumMode == AlbumMode.VIDEO && stage == TaskStage.SAVING)
                            }
                        }
                    }, onSaving = {
                        withContext(Dispatchers.Main) {
                            stage = TaskStage.SAVING
                            message = if (selected.isAlbum && options.albumMode == AlbumMode.VIDEO) "正在合成视频并保存…" else "正在检查文件并保存…"
                            DownloadForegroundService.progress(message, 0, -1, selected.isAlbum && options.albumMode == AlbumMode.VIDEO)
                        }
                    }, onSaved = { saveHistory(it); committed = it }, onDiagnostic = { event ->
                        Handler(Looper.getMainLooper()).post { if (current == generation) trace(event) }
                    })
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    history = loadHistory()
                    stage = TaskStage.DONE
                    message = "已保存 · " + saved.locationLabel
                    trace("download_saved id=${saved.id} requested=${options.watermarkMode.name} actual=${saved.watermarkMode?.name ?: "UNKNOWN"} bytes=${saved.bytes} location=${saved.locationLabel}")
                    updateTask(QueueStatus.DONE, message)
                    if (batchSaving) batchSaved++
                    rememberSavedDuplicate(saved)
                    if (namespace.isEmpty()) DownloadForegroundService.complete(application, saved)
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    history = loadHistory()
                    if (committed != null) {
                        stage = TaskStage.DONE
                        message = "文件已保存完成"
                        updateTask(QueueStatus.DONE, message)
                        rememberSavedDuplicate(committed!!)
                        if (namespace.isEmpty()) DownloadForegroundService.complete(application, committed!!)
                    } else {
                        stage = TaskStage.CANCELLED
                        message = "下载已取消"
                        updateTask(QueueStatus.CANCELLED, message)
                    }
                }
            } catch (error: Exception) {
                if (stage == TaskStage.CANCELLING) {
                    stage = TaskStage.CANCELLED
                    message = "下载已取消"
                    updateTask(QueueStatus.CANCELLED, message)
                } else { fail(error); if (batchSaving) batchFailed++ }
            } finally {
                if (batchSaving) advanceBatchLater() else {
                    restoreSingleSelection()
                    stopForegroundService()
                }
            }
        }
    }

    fun cancel() {
        if (queueRunning) { stopQueue(); return }
        if (batchSaving) {
            batchSaving = false
            batchOptions = null
            batchEpoch++
            pendingBatchKeys = emptyList()
            batchAdvanceJob?.cancel()
        }
        if (!busy || settingFolder || stage == TaskStage.CANCELLING) {
            if (!busy) restoreSingleSelection()
            stopForegroundService()
            return
        }
        queueRunning = false
        if (stage == TaskStage.DOWNLOADING || stage == TaskStage.SAVING) {
            stage = TaskStage.CANCELLING
            message = "正在取消下载并清理文件…"
            job?.cancel()
            downloader.cancel()
            return
        }
        generation++
        job?.cancel()
        probe?.cancel()
        probe = null
        publicApi?.cancel()
        publicApi = null
        stopParser()
        browsingId = null
        stage = TaskStage.CANCELLED
        message = "任务已取消，可以重新解析"
        updateTask(QueueStatus.CANCELLED, message)
        restoreSingleSelection()
        stopForegroundService()
    }

    private fun fail(error: Exception) {
        trace("failure type=${error.javaClass.simpleName} message=${error.message}")
        stage = TaskStage.FAILED
        message = FailureMessages.describe(error)
        updateTask(QueueStatus.FAILED, message)
        if (!queueRunning && !batchSaving && activeTaskKey != null) restoreSingleSelection()
    }

    private fun loadHistory(): List<SavedVideo> = records.history()
    private fun saveHistory(saved: SavedVideo) = records.save(saved)
    private fun rememberSavedDuplicate(saved: SavedVideo) {
        duplicateCandidates = (listOf(saved) + duplicateCandidates).distinctBy { it.uri }
        // Different share links can resolve to the same work. Newly committed files
        // must be visible to every result before the next bulk item is activated.
        queueResults.filterValues { it.id == saved.id }.keys.forEach { key ->
            queueDuplicates = queueDuplicates + (key to
                (listOf(saved) + queueDuplicates[key].orEmpty()).distinctBy { it.uri })
        }
        singleSelection?.takeIf { it.video.id == saved.id }?.let { selection ->
            singleSelection = selection.copy(duplicates =
                (listOf(saved) + selection.duplicates).distinctBy { it.uri })
        }
    }

    fun updateNamingRule(value: NamingRule) {
        if (busy || queueRunning || batchSaving) return
        namingRule = value
        optionsPreferences.edit().putString("naming", value.name).apply()
    }

    @Deprecated("Downloads always use the clean source")
    @Suppress("UNUSED_PARAMETER")
    fun updateWatermarkMode(value: WatermarkMode) = Unit

    @Deprecated("Downloads always use the clean source")
    @Suppress("UNUSED_PARAMETER")
    fun updateBatchWatermarkMode(value: WatermarkMode) = Unit

    @Deprecated("Downloads always use the clean source")
    @Suppress("UNUSED_PARAMETER")
    fun setDefaultVersion(value: WatermarkMode) = Unit

    private fun updateTask(status: QueueStatus, detail: String = "", title: String? = null) {
        val key = activeTaskKey ?: return
        queue = queue.map { if (it.key == key) it.copy(status = status, message = detail.take(500), title = title ?: it.title) else it }
        persistQueue()
    }
    private fun persistQueue() { runCatching { records.writeQueue(queue) }.onFailure { trace("queue_save_failed type=${it.javaClass.simpleName}") } }

    fun enqueueInput(text: String = input) {
        try {
            val links = ShareLinks.extractAll(text)
            val existing = queue.filter { it.status !in listOf(QueueStatus.DONE, QueueStatus.CANCELLED) }.map { it.source }.toSet()
            val new = links.filterNot { it in existing }.map { QueueTask(UUID.randomUUID().toString(), it) }
            require(queue.size + new.size <= 200) { "任务列表已满，请先移除已完成的任务" }
            queue = queue + new
            persistQueue()
            if (!busy && !queueRunning && !batchSaving) message = "已加入 ${new.size} 个任务，点击开始全部解析"
        } catch (error: Exception) { if (!busy) message = error.message ?: "未能添加任务" }
    }
    fun startQueue() {
        if (busy || queueRunning || batchSaving) return
        if (queue.none { it.status == QueueStatus.QUEUED }) { message = "没有等待解析的任务，已有结果可逐项保存"; return }
        captureSingleSelection()
        queueEpoch++
        queueRunning = true
        advanceQueue()
    }
    fun stopQueue() {
        if (!queueRunning) return
        queueRunning = false
        queueEpoch++
        queueAdvanceJob?.cancel()
        queueAdvanceJob = null
        if (busy && stage in listOf(TaskStage.RESOLVING, TaskStage.BROWSING, TaskStage.VERIFYING)) {
            stopCurrentParsing()
            updateTask(QueueStatus.QUEUED, "解析已暂停，重新开始后继续")
            stage = TaskStage.CANCELLED
        }
        restoreSingleSelection()
        message = "批量解析已暂停，已解析的结果仍可保存"
    }
    fun retryFailedQueue() {
        if (busy || queueRunning || batchSaving) return
        queue = queue.map { task ->
            if (task.status in listOf(QueueStatus.FAILED, QueueStatus.CANCELLED) && task.key !in queueResults)
                task.copy(status = QueueStatus.QUEUED, message = "") else task
        }
        persistQueue()
        startQueue()
    }
    private fun advanceQueueLater() {
        if (!queueRunning) return
        queueAdvanceJob?.cancel()
        val epoch = queueEpoch
        queueAdvanceJob = scope.launch {
            kotlinx.coroutines.delay(150)
            if (epoch == queueEpoch && queueRunning) advanceQueue()
        }
    }
    private fun advanceQueue() {
        if (busy || !queueRunning) return
        val next = QueuePolicy.nextToParse(queue)
        if (next == null) {
            queueRunning = false
            restoreSingleSelection()
            message = "批量解析完成 · ${queueResults.size} 个作品可保存"
            return
        }
        if (parserHost == null && queueParseOverride == null) {
            message = "队列已暂停，打开 App 后可继续解析下一项"
            return
        }
        dispatchQueueParse(next)
    }
    private fun dispatchQueueParse(task: QueueTask) {
        val fixture = queueParseOverride
        if (fixture == null) beginResolve(task.source, task.key) else {
            prepareResolve(task.source, task.key)
            stage = TaskStage.VERIFYING
            try { fixture(task) } catch (error: Exception) { fail(error); advanceQueueLater() }
        }
    }
    fun retryTask(key: String) {
        if (busy || queueRunning || batchSaving) return
        val task = queue.firstOrNull { it.key == key } ?: return
        captureSingleSelection()
        queueRunning = false
        selectedPage = AppPage.HOME
        dispatchQueueParse(task)
    }
    fun clearQueue() {
        if (busy || queueRunning || batchSaving) return
        // Commit the empty task list first; a storage error must not pretend that
        // the queue is gone when it would reappear after the process restarts.
        try { records.writeQueue(emptyList()) } catch (error: Exception) {
            trace("queue_clear_failed type=${error.javaClass.simpleName}")
            message = FailureMessages.describe(error)
            return
        }
        queueEpoch++
        batchEpoch++
        queueAdvanceJob?.cancel()
        queueAdvanceJob = null
        batchAdvanceJob?.cancel()
        batchAdvanceJob = null
        pendingBatchKeys = emptyList()
        batchOptions = null
        restoreSingleSelection()
        queue = emptyList()
        queueResults = emptyMap()
        queueDuplicates = emptyMap()
        message = "已清空全部任务，单链接内容和已保存记录已保留"
    }
    fun removeTask(key: String) {
        if (batchSaving || (busy && key == activeTaskKey)) return
        if (key == activeTaskKey) {
            stopCurrentParsing()
            activeTaskKey = null
            selectedTaskKey = null
            resetResult()
            stage = TaskStage.IDLE
        }
        queue = queue.filterNot { it.key == key }
        queueResults = queueResults - key
        queueDuplicates = queueDuplicates - key
        persistQueue()
    }
    fun moveTask(key: String, delta: Int) {
        if (busy || queueRunning || batchSaving) return
        queue = QueuePolicy.move(queue, key, delta)
        persistQueue()
    }
    fun moveTaskTo(key: String, targetIndex: Int) {
        if (busy || queueRunning || batchSaving) return
        queue = QueuePolicy.moveTo(queue, key, targetIndex)
        persistQueue()
    }

    fun viewTaskResult(key: String) {
        if (busy || queueRunning || batchSaving) return
        activateQueueResult(key)
    }
    private fun activateQueueResult(key: String): Boolean {
        if (queue.none { it.key == key }) return false
        val result = queueResults[key] ?: run { message = "解析结果已失效，请重新解析此任务"; return false }
        captureSingleSelection()
        stopCurrentParsing()
        activeTaskKey = key
        selectedTaskKey = key
        resetResult()
        video = result
        duplicateCandidates = queueDuplicates[key].orEmpty()
        stage = TaskStage.READY
        message = "已查看此作品，可选择保存方式"
        selectedPage = AppPage.HOME
        return true
    }
    fun downloadTask(key: String, force: Boolean = false, mode: AlbumMode = AlbumMode.IMAGES) {
        if (busy || queueRunning || batchSaving) return
        if (!activateQueueResult(key)) return
        albumMode = mode
        download(force)
        if (!busy) restoreSingleSelection()
    }
    fun downloadParsedQueue(keys: Set<String> = emptySet()) {
        if (busy || queueRunning || batchSaving) return
        pendingBatchKeys = QueuePolicy.keysToSave(queue, queueResults.keys, keys)
        if (pendingBatchKeys.isEmpty()) { message = "没有可保存的新解析结果"; return }
        batchOptions = DownloadOptions(AlbumMode.IMAGES, imageSeconds.coerceIn(2, 10), "", namingRule, WatermarkMode.CLEAN)
        batchSaved = 0; batchSkipped = 0; batchFailed = 0
        batchEpoch++
        batchSaving = true
        trace("batch_started requested=${batchWatermarkMode.name} default=${defaultWatermarkMode.name} items=${pendingBatchKeys.size}")
        advanceBatch()
    }
    private fun advanceBatchLater() {
        if (!batchSaving) return
        batchAdvanceJob?.cancel()
        val epoch = batchEpoch
        batchAdvanceJob = scope.launch {
            kotlinx.coroutines.delay(150)
            if (epoch == batchEpoch && batchSaving) advanceBatch()
        }
    }
    private fun advanceBatch() {
        if (!batchSaving || busy) return
        while (pendingBatchKeys.isNotEmpty()) {
            val key = pendingBatchKeys.first()
            pendingBatchKeys = pendingBatchKeys.drop(1)
            if (!activateQueueResult(key)) { batchFailed++; continue }
            albumMode = AlbumMode.IMAGES
            val result = video ?: continue
            val requested = checkNotNull(batchOptions).watermarkMode
            if (!WatermarkSources.available(result, requested)) {
                updateTask(QueueStatus.FAILED, "暂时无法获取完整下载来源，请重新解析后重试")
                batchFailed++
                continue
            }
            if (duplicate != null) {
                updateTask(QueueStatus.DONE, "已有可读取的文件，已跳过重复保存")
                batchSkipped++
                continue
            }
            download()
            if (busy) return
            updateTask(QueueStatus.FAILED, message)
            batchFailed++
        }
        batchSaving = false
        batchOptions = null
        stopForegroundService()
        restoreSingleSelection()
        message = "批量保存完成 · 已保存 $batchSaved 项 · 已跳过 $batchSkipped 项 · 失败 $batchFailed 项"
    }
    private fun captureSingleSelection() {
        if (singleSelection != null || selectedTaskKey != null) return
        video?.let { singleSelection = SingleSelection(it, duplicateCandidates, stage, albumMode, fileName, downloaded, total) }
    }
    private fun restoreSingleSelection() {
        val saved = singleSelection
        if (saved == null) {
            if (activeTaskKey != null || selectedTaskKey != null) {
                activeTaskKey = null
                selectedTaskKey = null
                browsingId = null
                resetResult()
                stage = TaskStage.IDLE
            }
            return
        }
        singleSelection = null
        activeTaskKey = null
        selectedTaskKey = null
        browsingId = null
        video = saved.video
        duplicateCandidates = saved.duplicates
        stage = saved.stage
        albumMode = saved.albumMode
        fileName = saved.fileName
        downloaded = saved.downloaded
        total = saved.total
    }
    private fun isSavedAccessible(saved: SavedVideo): Boolean =
        saved.uris.all { uri -> runCatching { application.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false }.getOrDefault(false) }
    private fun accessibleDuplicates(id: String): List<SavedVideo> = records.history().filter { it.id == id }.filter(::isSavedAccessible)
    private fun updateDuplicateCaches(refreshed: Map<String, List<SavedVideo>>) {
        queueDuplicates = queueResults.mapValues { (_, content) -> refreshed[content.id].orEmpty() }
        duplicateCandidates = video?.id?.let { refreshed[it] }.orEmpty()
        singleSelection?.let { selection -> singleSelection = selection.copy(duplicates = refreshed[selection.video.id].orEmpty()) }
    }
    private suspend fun refreshDuplicateCaches(current: Int) {
        val ids = (queueResults.values.map { it.id } + listOfNotNull(video?.id, singleSelection?.video?.id)).distinct()
        val (snapshot, refreshed) = withContext(Dispatchers.IO) {
            val snapshot = records.history()
            snapshot to ids.associateWith { id -> snapshot.filter { it.id == id }.filter(::isSavedAccessible) }
        }
        // A concurrent completed download already populated its fresh candidates.
        // An older history refresh must not overwrite those newly committed files.
        if (current != generation || records.history() != snapshot) return
        updateDuplicateCaches(refreshed)
    }
    fun refreshHistory() {
        val current = generation
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { records.history() }
            val latest = records.history()
            history = if (latest == loaded) loaded else latest
            refreshDuplicateCaches(current)
        }
    }
    fun manageHistory(uris: Set<String>, deleteFiles: Boolean) {
        if (busy || queueRunning || batchSaving || uris.isEmpty()) return
        settingFolder = true
        scope.launch {
            try {
                var failures = 0
                withContext(Dispatchers.IO) {
                    val remaining = records.history().mapNotNull { saved ->
                        if (saved.uri !in uris) return@mapNotNull saved
                        if (!deleteFiles) return@mapNotNull null
                        val failed = saved.uris.filter { raw ->
                            runCatching {
                                val uri = Uri.parse(raw)
                                require(uri.scheme == "content")
                                if (DocumentsContract.isDocumentUri(application, uri)) DocumentsContract.deleteDocument(application.contentResolver, uri)
                                else application.contentResolver.delete(uri, null, null) > 0
                            }.getOrDefault(false).not()
                        }
                        if (failed.isEmpty()) null else {
                            failures += failed.size
                            saved.copy(uri = failed.first(), uris = failed, coverUri = failed.first())
                        }
                    }
                    records.writeHistory(remaining)
                }
                history = loadHistory()
                updateDuplicateCaches(history.groupBy { it.id })
                message = if (failures == 0) if (deleteFiles) "所选文件与记录已删除" else "所选记录已移除，文件仍保留" else "部分文件无法删除，相关记录已保留"
                refreshDuplicateCaches(generation)
            } catch (error: Exception) { fail(error) }
            finally { settingFolder = false }
        }
    }
    fun serviceInterrupted(reason: String) {
        if (stage !in listOf(TaskStage.DOWNLOADING, TaskStage.SAVING)) return
        cancel()
        trace("background_interrupted reason=$reason")
    }

    companion object {
        @Volatile private var instance: SaverEngine? = null
        fun get(application: Application): SaverEngine = instance ?: synchronized(this) {
            instance ?: SaverEngine(application).also { instance = it }
        }
    }
}
