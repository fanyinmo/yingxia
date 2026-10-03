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
import java.net.SocketTimeoutException
import java.util.UUID
import android.provider.DocumentsContract

class SaverEngine private constructor(private val application: Application, private val namespace: String = "") {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private val records = DownloadRecords(application, namespace)
    private val optionsPreferences = application.getSharedPreferences("${namespace}download_options", 0)
    var albumMode by mutableStateOf(AlbumMode.IMAGES)
    var imageSeconds by mutableIntStateOf(optionsPreferences.getInt("image_seconds", 3).coerceIn(2, 10))
    var fileName by mutableStateOf("")
    var namingRule by mutableStateOf(runCatching { NamingRule.valueOf(optionsPreferences.getString("naming", "DATE_TITLE")!!) }
        .getOrDefault(NamingRule.DATE_TITLE)); private set
    var queue by mutableStateOf(records.queue()); private set
    private var activeTaskKey: String? = null
    private var queueRunning = false
    private var duplicateCandidates by mutableStateOf<List<SavedVideo>>(emptyList())
    val duplicate: SavedVideo? get() = duplicateCandidates.firstOrNull {
        if (video?.isAlbum == true && albumMode == AlbumMode.IMAGES) it.mimeType.startsWith("image/")
        else it.mimeType == "video/mp4"
    }
    private val parsePreferences = application.getSharedPreferences("${namespace}parse_diagnostics", 0)
    var input by mutableStateOf(parsePreferences.getString("last_url", "").orEmpty()); private set
    var selectedPage by mutableStateOf(AppPage.HOME)
    var stage by mutableStateOf(TaskStage.IDLE); private set
    var message by mutableStateOf("粘贴一条抖音分享文案，开始解析"); private set
    var browsingId by mutableStateOf<String?>(null); private set
    var generation by mutableIntStateOf(0); private set
    var video by mutableStateOf<ParsedVideo?>(null); private set
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
        if (!busy) { updateInput(text); selectedPage = AppPage.HOME }
    }

    fun updateInput(text: String) {
        if (busy) return
        val value = text.take(16_384)
        if (value == input) return
        input = value
        invalidateInputResult("输入已修改，请重新解析")
        persistInput()
    }

    fun clearInput() {
        if (busy) return
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
        if (queue.any { it.key == activeTaskKey && it.status == QueueStatus.READY }) {
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
        retireReadyTask(detail)
        activeTaskKey = null
        queueRunning = false
        stopCurrentParsing()
        resetResult()
        stage = TaskStage.IDLE
        message = "粘贴一条抖音分享文案，开始解析"
        stopForegroundService()
    }

    fun resolve() {
        if (busy) return
        queueRunning = false
        val source = runCatching { ShareLinks.extract(input) }.getOrElse {
            invalidateInputResult("已重新解析其他输入")
            fail(IllegalArgumentException(it.message))
            return
        }
        beginResolve(source)
    }

    private fun beginResolve(source: String, taskKey: String? = null) {
        if (activeTaskKey != taskKey) retireReadyTask("已切换作品，请重新解析此任务")
        stopCurrentParsing()
        activeTaskKey = taskKey
        // A queue task supplies its own source; this assignment must not retire that task.
        if (taskKey != null) input = source
        val current = generation
        diagnostics = ""
        trace("version=${BuildConfig.VERSION_NAME} android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
        resetResult()
        stage = TaskStage.RESOLVING
        updateTask(QueueStatus.PARSING)
        message = "正在识别分享链接…"
        job = scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { ShareLinks.resolveVideoId(source) }
                if (current != generation) return@launch
                parsePreferences.edit().putString("last_url", ShareLinks.extract(source)).apply()
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
                video = verified
                duplicateCandidates = duplicates
                trace("media_probe_passed")
                stage = TaskStage.READY
                updateTask(QueueStatus.READY, title = verified.title)
                message = if (verified.isAlbum) "已解析 ${verified.images.size} 张图片，请选择保存方式" else "解析完成，可以下载视频"
                if (queueRunning && !verified.isAlbum) {
                    if (duplicate != null) {
                        updateTask(QueueStatus.DONE, "已有可读取的文件，已跳过重复下载")
                        stage = TaskStage.DONE
                        advanceQueueLater()
                    } else download()
                } else if (verified.isAlbum) {
                    queueRunning = false
                    stopForegroundService()
                }
            } catch (_: CancellationException) {
                if (current == generation) { stage = TaskStage.CANCELLED; message = "任务已取消" }
            } catch (error: Exception) { if (current == generation) { fail(error); advanceQueueLater() } }
            finally { if (probe === currentProbe) probe = null }
        }
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
        if (busy) return
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
        if (busy) return
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
        if (busy) return
        if (!force && duplicate != null) { message = "这个作品已经保存，可以打开已有文件，或选择再次下载"; return }
        val options = DownloadOptions(albumMode, imageSeconds.coerceIn(2, 10), fileName, namingRule)
        if (selected.isAlbum && options.albumMode == AlbumMode.VIDEO && selected.bgmUrl.isBlank()) {
            message = "作品没有提供可读取的 BGM，可以选择保存图片"; return
        }
        try {
            DownloadForegroundService.start(application, false)
        } catch (error: Exception) {
            message = "无法启动后台下载，请回到首页点击下载后重试"
            trace("service_start_failed type=${error.javaClass.simpleName}")
            return
        }
        optionsPreferences.edit().putInt("image_seconds", options.imageSeconds).apply()
        val folder = downloadFolder
        stage = TaskStage.DOWNLOADING
        updateTask(QueueStatus.RUNNING, title = selected.title)
        trace("download_started id=${selected.id}")
        message = if (selected.isAlbum) "正在下载图集素材…" else "正在下载，可切换到其他应用"
        downloaded = 0
        total = -1
        job = scope.launch {
            var committed: SavedVideo? = null
            try {
                var lastUpdate = 0L
                val saved = withContext(Dispatchers.IO) {
                    downloader.download(selected, folder, options, onProgress = { read, size ->
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
                    }, onSaved = { saveHistory(it); committed = it })
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    history = loadHistory()
                    stage = TaskStage.DONE
                    message = "已保存 · " + saved.locationLabel
                    trace("download_saved id=${saved.id} bytes=${saved.bytes} location=${saved.locationLabel}")
                    updateTask(QueueStatus.DONE, message)
                    duplicateCandidates = listOf(saved)
                    DownloadForegroundService.complete(application, saved)
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    history = loadHistory()
                    if (committed != null) {
                        stage = TaskStage.DONE
                        message = "文件已保存完成"
                        updateTask(QueueStatus.DONE, message)
                        duplicateCandidates = listOf(committed!!)
                        DownloadForegroundService.complete(application, committed!!)
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
                } else fail(error)
            } finally {
                if (!queueRunning || parserHost == null) stopForegroundService()
                advanceQueueLater()
            }
        }
    }

    fun cancel() {
        if (!busy || settingFolder || stage == TaskStage.CANCELLING) return
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
        stopForegroundService()
    }

    private fun fail(error: Exception) {
        trace("failure type=${error.javaClass.simpleName} message=${error.message}")
        stage = TaskStage.FAILED
        message = when (error) {
            is SocketTimeoutException -> "连接超时，请检查网络后重试"
            is IllegalArgumentException, is IllegalStateException -> error.message ?: "处理失败，请重试"
            else -> "网络或文件处理失败，请检查网络、目录权限和手机可用空间后重试"
        }
        updateTask(QueueStatus.FAILED, message)
    }

    private fun loadHistory(): List<SavedVideo> = records.history()
    private fun saveHistory(saved: SavedVideo) = records.save(saved)

    fun updateNamingRule(value: NamingRule) {
        namingRule = value
        optionsPreferences.edit().putString("naming", value.name).apply()
    }

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
            if (!busy) message = "已加入 ${new.size} 个任务，点击开始队列"
        } catch (error: Exception) { if (!busy) message = error.message ?: "未能添加任务" }
    }
    fun startQueue() {
        if (busy) return
        if (stage == TaskStage.READY && video != null && activeTaskKey == null) {
            queueRunning = false
            message = "请先下载或清空当前作品，再开始队列"
            return
        }
        queueRunning = true
        if (stage == TaskStage.READY && video != null) {
            if (video?.isAlbum == true || duplicate != null) { queueRunning = false; message = "请先处理当前作品，再开始队列"; return }
            download()
        } else advanceQueue()
    }
    private fun advanceQueueLater() { if (queueRunning) scope.launch { kotlinx.coroutines.delay(300); advanceQueue() } }
    private fun advanceQueue() {
        if (busy || !queueRunning) return
        val next = queue.firstOrNull { it.status == QueueStatus.QUEUED }
        if (next == null) { queueRunning = false; stopForegroundService(); return }
        if (parserHost == null) {
            message = "队列已暂停，打开 App 后可继续解析下一项"
            stopForegroundService()
            return
        }
        DownloadForegroundService.progress("正在解析队列中的下一项…", 0, -1)
        beginResolve(next.source, next.key)
    }
    fun retryTask(key: String) {
        if (busy) return
        val task = queue.firstOrNull { it.key == key } ?: return
        queueRunning = false
        selectedPage = AppPage.HOME
        beginResolve(task.source, task.key)
    }
    fun removeTask(key: String) {
        if (busy && key == activeTaskKey) return
        if (key == activeTaskKey) invalidateInputResult("当前任务已移除")
        queue = queue.filterNot { it.key == key }
        persistQueue()
    }
    fun moveTask(key: String, delta: Int) { queue = QueuePolicy.move(queue, key, delta); persistQueue() }
    private fun accessibleDuplicates(id: String): List<SavedVideo> = records.history().filter { it.id == id }.filter { saved ->
        saved.uris.all { uri -> runCatching { application.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false }.getOrDefault(false) }
    }
    fun refreshHistory() {
        val current = generation
        val id = video?.id
        scope.launch {
            history = withContext(Dispatchers.IO) { records.history() }
            val duplicates = if (id == null) emptyList() else withContext(Dispatchers.IO) { accessibleDuplicates(id) }
            if (current == generation && video?.id == id) duplicateCandidates = duplicates
        }
    }
    fun manageHistory(uris: Set<String>, deleteFiles: Boolean) {
        if (busy || uris.isEmpty()) return
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
                message = if (failures == 0) if (deleteFiles) "所选文件与记录已删除" else "所选记录已移除，文件仍保留" else "部分文件无法删除，相关记录已保留"
                refreshHistory()
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
