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
    private val albumTimingPreferences = AlbumTimingPreferences(application, namespace)
    var staticImageSeconds by mutableStateOf(albumTimingPreferences.readStaticSeconds()); private set
    fun updateStaticImageSeconds(value: Double) {
        staticImageSeconds = albumTimingPreferences.updateStaticSeconds(value)
    }
    var itemDurationSeconds by mutableStateOf<Double?>(null)
    private var queueDurations by mutableStateOf<Map<String, Double?>>(emptyMap())
    fun itemDurationForTask(key: String): Double? = if (selectedTaskKey == key) itemDurationSeconds else queueDurations[key]
    fun setItemDurationForTask(key: String, value: Double?) {
        queueDurations = queueDurations + (key to value)
        if (selectedTaskKey == key) itemDurationSeconds = value
    }
    fun setSelectedItemDuration(value: Double?) {
        itemDurationSeconds = value
        selectedTaskKey?.let { queueDurations = queueDurations + (it to value) }
    }
    var durationAdjustments by mutableStateOf<List<DurationAdjustment>>(emptyList()); private set
    private var durationAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    fun confirmDurationAdjustment() { durationAnswer?.complete(true); durationAdjustments = emptyList() }
    fun returnToDurationEditing() { durationAnswer?.complete(false); durationAdjustments = emptyList() }
    var gifStartSeconds by mutableStateOf(0f)
    var gifDurationSeconds by mutableStateOf(6f)
    var gifExportQuality by mutableStateOf(GifExportQuality.SHARE)
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
        val stage: TaskStage, val albumMode: AlbumMode, val fileName: String, val downloaded: Long, val total: Long,
        val gifStartSeconds: Float, val gifDurationSeconds: Float, val itemDurationSeconds: Double?)
    private var singleSelection: SingleSelection? = null
    /** Deterministic fixture dispatch is available only to isolated instrumentation engines. */
    internal var queueParseOverride: ((QueueTask) -> Unit)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换解析来源" }; field = value }
    internal var queueSaveOverride: (suspend (ParsedVideo, DownloadOptions) -> SavedVideo)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换保存方式" }; field = value }
    internal var albumMotionResolveOverride: ((String, (DesktopAlbumResult) -> Unit) -> Unit)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换动态来源" }; field = value }
    internal var publicApiReadOverride: (suspend (String) -> ParsedVideo?)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换作品接口" }; field = value }
    internal var desktopShareResolveOverride: ((String, (DesktopAlbumResult) -> Unit) -> Unit)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换分享备用来源" }; field = value }
    internal var mediaProbeFactoryOverride: (() -> MediaProbe)? = null
        set(value) { check(namespace.isNotEmpty()) { "正式引擎不能替换媒体校验连接" }; field = value }
    private var duplicateCandidates by mutableStateOf<List<SavedVideo>>(emptyList())
    val duplicate: SavedVideo? get() {
        val content = video ?: return null
        val matchingKind = duplicateCandidates.filter { AlbumRecordPolicy.matches(it, content, albumMode,
            gifStartSeconds, gifDurationSeconds, imageSeconds, itemDurationSeconds, staticImageSeconds, gifExportQuality) }
        return matchingKind.firstOrNull { it.watermarkMode == WatermarkMode.CLEAN }
    }
    private val parsePreferences = application.getSharedPreferences("${namespace}parse_diagnostics", 0)
    var input by mutableStateOf(parsePreferences.getString("last_url", "").orEmpty()); private set
    var selectedPage by mutableStateOf(AppPage.HOME)
    var stage by mutableStateOf(TaskStage.IDLE); private set
    var message by mutableStateOf("粘贴一条抖音分享文案，开始解析"); private set
    var browsingId by mutableStateOf<String?>(null); private set
    private var browsingUrl: String? = null
    var generation by mutableIntStateOf(0); private set
    var video by mutableStateOf<ParsedVideo?>(null); private set
    val selectedContent: ParsedVideo? get() = video?.let {
        runCatching { WatermarkSources.selectForDownload(it, WatermarkMode.CLEAN) }.getOrNull()
    }
    var downloaded by mutableLongStateOf(0); private set
    var total by mutableLongStateOf(-1); private set
    var history by mutableStateOf(loadHistory()); private set
    var diagnostics by mutableStateOf(parsePreferences.getString("summary", "").orEmpty()); private set
    private val storage = DownloadStorage(application)
    var downloadFolder by mutableStateOf(storage.loadFolder()); private set
    val downloadLocationLabel get() = downloadFolder?.label ?: if (albumMode == AlbumMode.GIF || (video?.isAlbum == true && albumMode in listOf(AlbumMode.IMAGES, AlbumMode.LIVE_PHOTOS, AlbumMode.COVERS)))
        DownloadStorage.DEFAULT_IMAGE_LOCATION else DownloadStorage.DEFAULT_LOCATION
    private var settingFolder by mutableStateOf(false)
    private var job: Job? = null
    private var parserHost: FrameLayout? = null
    private var parser: BrowserParser? = null
    private var probe: MediaProbe? = null
    private var publicApi: PublicVideoApi? = null
    private var apiAttempted = false
    private var desktopVideoAttempted = false
    private var desktopVideoResolver: DesktopAlbumResolver? = null
    private var desktopVideoBrowser: WebView? = null
    private var desktopShareAttempted = false
    private var desktopShareToken: Any? = null
    private var desktopShareResolver: DesktopAlbumResolver? = null
    private var desktopShareBrowser: WebView? = null
    private var desktopShareCandidate: ParsedVideo? = null
    private var desktopShareStateKey: String? = null
    private val downloader = ContentDownloader(application)
    private data class MotionRequest(val nonce: Long, val taskKey: String?, val base: ParsedVideo,
        val generation: Int, val queueEpoch: Int, val force: Boolean, val mode: AlbumMode,
        val failedShare: Boolean = false, val selectedImageIndices: List<Int> = emptyList(), val saveOnReady: Boolean = true,
        val durationOverride: Double? = null, val automatic: Boolean = false) {
        val stateKey get() = taskKey?.let { "queue:$it" } ?: "single:${base.id}"
    }
    private var motionNonce = 0L
    private var motionRequest by mutableStateOf<MotionRequest?>(null)
    private var motionResolver: DesktopAlbumResolver? = null
    private var motionBrowser: WebView? = null
    private var motionVerificationHost: FrameLayout? = null
    private var motionStates by mutableStateOf<Map<String, AlbumMotionReadState>>(emptyMap())
    var albumMotionVerificationVisible by mutableStateOf(false); private set
    private val coreBusy get() = settingFolder || stage in listOf(TaskStage.RESOLVING, TaskStage.BROWSING,
        TaskStage.VERIFYING, TaskStage.DOWNLOADING, TaskStage.CANCELLING, TaskStage.SAVING)
    val busy get() = coreBusy || motionRequest != null

    fun albumMotionState(taskKey: String? = selectedTaskKey): AlbumMotionReadState {
        val content = if (taskKey == null) video else queueResults[taskKey]
        val key = taskKey?.let { "queue:$it" } ?: (content?.id ?: browsingId)?.let { "single:$it" }
        return motionStates[key] ?: AlbumMotionReadState()
    }

    /** Reads desktop sources on demand; IMAGES preserves the paired cover and original motion bytes. */
    fun saveAlbumMotion(taskKey: String? = selectedTaskKey, force: Boolean = false, mode: AlbumMode = AlbumMode.IMAGES,
        selectedImageIndices: List<Int> = emptyList()) {
        if (busy || queueRunning || batchSaving) return
        if (!acceptMotionMode(mode)) return
        val content = (if (taskKey == null) video else queueResults[taskKey]) ?: return
        if (!content.isAlbum) return
        val selected = try { AlbumSelection.select(content, selectedImageIndices) }
            catch (error: IllegalArgumentException) { message = error.message.orEmpty(); return }
        if (WatermarkSources.requiresEmbeddedLiveVerification(selected, WatermarkMode.CLEAN) &&
            WatermarkSources.availableForDownload(selected, WatermarkMode.CLEAN)) {
            // Download qualification is not proof of motion: the complete JPEG and its
            // embedded video must pass AlbumDownloader before any URI or record is kept.
            if (taskKey != null && !activateQueueResult(taskKey)) return
            itemDurationSeconds = if (taskKey == null) itemDurationSeconds else itemDurationForTask(taskKey)
            albumMode = mode
            downloadContent(content, force, mode, selectedImageIndices)
            if (!busy && taskKey != null) restoreSingleSelection()
            return
        }
        val request = MotionRequest(++motionNonce, taskKey, content, generation, queueEpoch, force, mode,
            selectedImageIndices = selectedImageIndices,
            durationOverride = if (taskKey == null) itemDurationSeconds else itemDurationForTask(taskKey))
        startAlbumMotionRead(request)
    }

    fun checkAlbumMotion(taskKey: String? = selectedTaskKey) {
        if (busy || queueRunning || batchSaving) return
        val content = (if (taskKey == null) video else queueResults[taskKey]) ?: return
        if (!content.isAlbum) return
        startAlbumMotionRead(MotionRequest(++motionNonce, taskKey, content, generation, queueEpoch, false,
            AlbumMode.IMAGES, saveOnReady = false))
    }

    /** Manual recovery for a confirmed share id whose mobile page returned no work data. */
    fun saveFailedShareAlbumMotion(mode: AlbumMode = AlbumMode.IMAGES, saveOnReady: Boolean = true) {
        if (busy || queueRunning || batchSaving || selectedTaskKey != null || video != null || stage != TaskStage.FAILED) return
        if (!acceptMotionMode(mode)) return
        val id = browsingId ?: return
        val placeholder = ParsedVideo(id, "", "", 0.0, 0, 0)
        startAlbumMotionRead(MotionRequest(++motionNonce, null, placeholder, generation, queueEpoch, false, mode,
            failedShare = true, saveOnReady = saveOnReady))
    }

    private fun acceptMotionMode(mode: AlbumMode): Boolean {
        if (mode in listOf(AlbumMode.IMAGES, AlbumMode.GIF, AlbumMode.VIDEO, AlbumMode.MOTION_VIDEOS)) return true
        message = "动态资源可保存原始片段，或选择 GIF 兼容版"
        return false
    }

    private fun startAlbumMotionRead(request: MotionRequest) {
        val content = request.base
        val taskKey = request.taskKey
        val force = request.force
        motionRequest = request
        setMotionState(request, AlbumMotionPhase.READING, "正在读取这套图集的动态资源…")
        if (content.images.any { it.kind == AlbumAssetKind.ANIMATED } &&
            content.images.none { it.kind == AlbumAssetKind.LIVE || it.motion != null } &&
            (!request.automatic || content.images.all { it.kind == AlbumAssetKind.ANIMATED }) &&
            WatermarkSources.available(content, WatermarkMode.CLEAN)) {
            motionRequest = null
            setMotionState(request, AlbumMotionPhase.AVAILABLE, "已取得原始动图，保存时保留原格式")
            if (!request.saveOnReady) { finishAutomaticMotionRead(request); return }
            if (taskKey != null && !activateQueueResult(taskKey)) return
            itemDurationSeconds = request.durationOverride
            albumMode = request.mode
            downloadContent(content, force, request.mode, request.selectedImageIndices)
            if (!busy && taskKey != null) restoreSingleSelection()
            return
        }
        if (content.images.any { it.motion != null } && WatermarkSources.available(content, WatermarkMode.CLEAN) &&
            (!request.automatic || content.images.all { it.motion != null || it.kind == AlbumAssetKind.ANIMATED })) {
            acceptAlbumMotionResult(request, DesktopAlbumResult(DesktopAlbumStatus.CANDIDATE, content))
            return
        }
        try {
            val fixture = albumMotionResolveOverride
            if (fixture != null) { fixture(content.id) { acceptAlbumMotionResult(request, it) }; return }
            val host = parserHost ?: error("请回到首页后重试保存动图")
            val browser = WebView(host.context)
            motionBrowser = browser
            host.addView(browser, FrameLayout.LayoutParams(1280, -1))
            motionResolver = DesktopAlbumResolver(browser, content.id,
                { acceptAlbumMotionResult(request, it) }, { if (isCurrentMotion(request)) trace(it) },
                // Cold desktop hydration can take over 15s even when its own motion is
                // available. Give automatic reading the same bounded window as a retry.
                timeoutMs = 30_000L)
        } catch (error: Exception) {
            if (isCurrentMotion(request)) endAlbumMotion(request, "读取动图失败：${FailureMessages.describe(error)}")
        }
    }

    private fun isCurrentMotion(request: MotionRequest): Boolean = motionRequest === request &&
        request.nonce == motionNonce && request.generation == generation && request.queueEpoch == queueEpoch &&
        if (request.taskKey == null) selectedTaskKey == null &&
            (if (request.failedShare) video == null && browsingId == request.base.id else video === request.base)
        else queue.any { it.key == request.taskKey } && queueResults[request.taskKey] === request.base

    private fun setMotionState(request: MotionRequest, phase: AlbumMotionPhase, detail: String) {
        motionStates = motionStates + (request.stateKey to AlbumMotionReadState(phase, detail))
    }

    private fun acceptAlbumMotionResult(request: MotionRequest, result: DesktopAlbumResult) {
        if (!isCurrentMotion(request)) return
        when (result.status) {
            DesktopAlbumStatus.NEEDS_VERIFICATION -> {
                if (request.automatic) endAlbumMotion(request, "动态检查需要抖音网页验证；可重新检查并手动验证，现有图片仍可保存")
                else setMotionState(request, AlbumMotionPhase.NEEDS_VERIFICATION,
                    "抖音网页需要验证，完成后可继续读取；当前图片仍保留")
            }
            DesktopAlbumStatus.CANDIDATE -> {
                val resolved = result.album
                // A complete same-work array is used intact, never merged by a mobile-page index.
                if (resolved == null || resolved.id != request.base.id || !resolved.isAlbum ||
                    resolved.images.size < request.base.images.size ||
                    resolved.images.none { it.motion != null } || !WatermarkSources.available(resolved, WatermarkMode.CLEAN)) {
                    endAlbumMotion(request, "未取得完整的逐图动态资源，仍可保存现有图片")
                    return
                }
                val candidate = AlbumKindPreservation.preserveKnownKinds(request.base, resolved)
                releaseAlbumMotionReader()
                motionRequest = null
                if (!request.saveOnReady) {
                    if (request.taskKey != null) queueResults = queueResults + (request.taskKey to candidate)
                    if (request.taskKey == selectedTaskKey) { video = candidate; stage = TaskStage.READY }
                    if (request.failedShare) duplicateCandidates = history.filter { it.id == candidate.id }.filter(::isSavedAccessible)
                    message = "已取得动态资源，请选择保存方式"
                    setMotionState(request, AlbumMotionPhase.AVAILABLE, "已取得动态资源，请选择保存方式")
                    finishAutomaticMotionRead(request)
                    return
                }
                if (request.taskKey != null && !activateQueueResult(request.taskKey)) return
                if (!request.failedShare) {
                    if (request.taskKey != null) queueResults = queueResults + (request.taskKey to candidate)
                    video = candidate
                }
                if (request.failedShare) {
                    video = candidate
                    stage = TaskStage.READY
                    // Existing accessible records still control duplicate downloads in this recovery path.
                    duplicateCandidates = history.filter { it.id == candidate.id }.filter(::isSavedAccessible)
                }
                setMotionState(request, AlbumMotionPhase.AVAILABLE, if (request.mode == AlbumMode.GIF)
                    "已取得动态资源，将转换为 GIF 兼容版" else "已取得原始动态片段，保存时保留来源画质与帧率")
                albumMode = request.mode
                itemDurationSeconds = request.durationOverride
                val corresponding = try { AlbumSelection.correspondingIndices(request.base, candidate, request.selectedImageIndices) }
                    catch (error: Exception) { message = error.message.orEmpty(); return }
                downloadContent(candidate, request.force, request.mode, corresponding)
                if (!busy && request.taskKey != null) restoreSingleSelection()
            }
            else -> endAlbumMotion(request, result.reason.ifBlank { "未取得动态资源，仍可保存现有图片" })
        }
    }

    private fun endAlbumMotion(request: MotionRequest, detail: String) {
        setMotionState(request, AlbumMotionPhase.UNAVAILABLE, detail)
        motionRequest = null
        releaseAlbumMotionReader()
        finishAutomaticMotionRead(request)
        // Source acquisition has not changed the original result, task status or history.
    }

    private fun finishAutomaticMotionRead(request: MotionRequest) {
        if (!request.automatic) return
        if (!queueRunning && request.taskKey != null) restoreSingleSelection()
        advanceQueueLater()
    }

    private fun releaseAlbumMotionReader() {
        albumMotionVerificationVisible = false
        motionVerificationHost = null
        val resolver = motionResolver
        motionResolver = null
        val browser = motionBrowser
        motionBrowser = null
        if (resolver != null) resolver.dispose() else browser?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading(); it.destroy()
        }
    }

    fun cancelAlbumMotion() {
        val request = motionRequest ?: return
        motionNonce++
        endAlbumMotion(request, "已取消读取动图，现有图片仍可保存")
    }

    fun openAlbumMotionVerification() {
        val request = motionRequest ?: return
        if (!isCurrentMotion(request) || albumMotionState(request.taskKey).phase != AlbumMotionPhase.NEEDS_VERIFICATION) return
        albumMotionVerificationVisible = true
    }

    fun attachAlbumMotionVerificationHost(host: FrameLayout) {
        if (!albumMotionVerificationVisible || motionRequest == null) return
        val browser = motionBrowser ?: return
        motionVerificationHost = host
        host.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        (browser.parent as? ViewGroup)?.removeView(browser)
        host.addView(browser, FrameLayout.LayoutParams(-1, -1))
        motionResolver?.enterVerification()
    }

    fun detachAlbumMotionVerificationHost(host: FrameLayout) {
        if (motionVerificationHost !== host) return
        motionVerificationHost = null
        if (albumMotionVerificationVisible) cancelAlbumMotion()
    }

    fun continueAlbumMotionVerification() {
        val request = motionRequest ?: return
        if (!isCurrentMotion(request) || !albumMotionVerificationVisible) return
        val browser = motionBrowser
        val host = parserHost
        if (browser != null && host == null) { endAlbumMotion(request, "页面已关闭，请重试保存动图"); return }
        albumMotionVerificationVisible = false
        motionVerificationHost = null
        if (browser != null && host != null) {
            (browser.parent as? ViewGroup)?.removeView(browser)
            host.addView(browser, FrameLayout.LayoutParams(1280, -1))
        }
        setMotionState(request, AlbumMotionPhase.READING, "正在重新检查这套图集的动态资源…")
        motionResolver?.resumeAfterVerification()
    }

    fun attachParserHost(host: FrameLayout) {
        if (parserHost !== host) { cancelAlbumMotion(); stopParser() }
        parserHost = host
        host.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        startParserIfReady()
        if (queueRunning && !busy) advanceQueue()
    }

    fun detachParserHost(host: FrameLayout) {
        if (parserHost !== host) return
        cancelAlbumMotion()
        if (desktopVideoResolver != null || desktopVideoBrowser != null) {
            releaseDesktopVideoReader()
            stage = TaskStage.BROWSING
        }
        if (desktopShareToken != null) {
            releaseDesktopShareReader("页面已关闭，返回后可继续检查备用来源")
            // Returning after an interrupted read restarts the normal bounded route.
            desktopShareAttempted = false
            apiAttempted = false
            stage = TaskStage.BROWSING
        }
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
                { if (current == generation) trace(it) }, initialUrl = browsingUrl)
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
        if (coreBusy || queueRunning || batchSaving) return
        val value = text.take(16_384)
        if (value == input) return
        input = value
        invalidateInputResult("输入已修改，请重新解析")
        persistInput()
    }

    fun clearInput() {
        if (coreBusy || queueRunning || batchSaving) return
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
        cancelAlbumMotion()
        releaseDesktopVideoReader()
        releaseDesktopShareReader("备用来源检查已停止")
        generation++
        job?.cancel()
        job = null
        probe?.cancel()
        probe = null
        publicApi?.cancel()
        publicApi = null
        stopParser()
        browsingId = null
        browsingUrl = null
        apiAttempted = false
        desktopVideoAttempted = false
        desktopShareAttempted = false
        desktopShareCandidate = null
    }

    private fun resetResult() {
        video = null
        itemDurationSeconds = null
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
        motionStates = motionStates.filterKeys { it.startsWith("queue:") }
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
                val resolved = withContext(Dispatchers.IO) { ShareLinks.resolveShare(source) }
                val id = resolved.id
                if (current != generation) return@launch
                if (taskKey == null) parsePreferences.edit().putString("last_url", ShareLinks.extract(source)).apply()
                browsingId = id
                browsingUrl = resolved.url
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
        motionStates = if (taskKey == null) motionStates.filterKeys { it.startsWith("queue:") }
            else motionStates - "queue:$taskKey"
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
        gifStartSeconds = 0f
        gifDurationSeconds = 6f
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
        val currentProbe = mediaProbeFactoryOverride?.invoke() ?: MediaProbe()
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
            } catch (error: Exception) {
                if (current == generation && !startDesktopVideoFallback(current, result, error)) {
                    fail(error); advanceQueueLater()
                }
            }
            finally { if (probe === currentProbe) probe = null }
        }
    }

    /** A CDN rejection may affect a mobile play route while the same work has a fresh
     * desktop source. This is a single bounded retry, never a different work/rendition. */
    private fun startDesktopVideoFallback(current: Int, base: ParsedVideo, error: Exception): Boolean {
        if (base.isAlbum || desktopVideoAttempted || current != generation || stage != TaskStage.VERIFYING) return false
        val rejected = generateSequence<Throwable>(error) { it.cause }.take(8).any {
            it.message.orEmpty().contains("HTTP 403")
        }
        if (!rejected) return false
        val host = parserHost ?: return false
        desktopVideoAttempted = true
        message = "正在检查这个作品的备用视频来源…"
        trace("desktop_video_fallback_started cause=http403 id=${base.id}")
        return try {
            val browser = WebView(host.context)
            desktopVideoBrowser = browser
            host.addView(browser, FrameLayout.LayoutParams(1280, -1))
            desktopVideoResolver = DesktopAlbumResolver(browser, base.id, { result ->
                if (current != generation || stage != TaskStage.VERIFYING) return@DesktopAlbumResolver
                releaseDesktopVideoReader()
                val candidate = result.album
                if (result.status == DesktopAlbumStatus.CANDIDATE && candidate != null &&
                    candidate.id == base.id && !candidate.isAlbum && WatermarkSources.available(candidate, WatermarkMode.CLEAN)) {
                    trace("desktop_video_candidate_confirmed id=${candidate.id}")
                    stage = TaskStage.BROWSING
                    parsed(current, candidate)
                } else {
                    trace("desktop_video_fallback_unavailable status=${result.status}")
                    fail(IllegalStateException(if (result.status == DesktopAlbumStatus.NEEDS_VERIFICATION)
                        "备用视频网页需要手动验证，当前无法取得可下载来源"
                        else "视频来源返回 HTTP 403，备用页面也未提供可用来源；${result.reason}"))
                    advanceQueueLater()
                }
            }, { if (current == generation) trace(it) }, timeoutMs = 20_000L, allowVideo = true)
            true
        } catch (fallbackError: Exception) {
            releaseDesktopVideoReader()
            trace("desktop_video_fallback_error type=${fallbackError.javaClass.simpleName}")
            false
        }
    }

    private fun releaseDesktopVideoReader() {
        val resolver = desktopVideoResolver
        val browser = desktopVideoBrowser
        desktopVideoResolver = null; desktopVideoBrowser = null
        if (resolver != null) resolver.dispose() else browser?.let {
            (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy()
        }
    }

    private fun acceptVerifiedResult(current: Int, verified: ParsedVideo, duplicates: List<SavedVideo>) {
        if (current != generation || stage != TaskStage.VERIFYING) return
        if (verified.isAlbum) {
            val cleanImages = verified.images.count { image -> image.mediaSources.any { it.mode == WatermarkMode.CLEAN } }
            val markedImages = verified.images.count { image -> image.mediaSources.any { it.mode == WatermarkMode.WATERMARKED } }
            trace("album_sources images=${verified.images.size} clean_images=$cleanImages marked_images=$markedImages " +
                "bgm=${verified.bgmUrl.isNotBlank()} live=${verified.images.count { it.kind == AlbumAssetKind.LIVE || it.motion != null }} " +
                "animated=${verified.images.count { it.kind == AlbumAssetKind.ANIMATED }}")
        }
        // The mobile renderer can declare a Live Photo while omitting its clip.
        // Its verified, same-image cover is enough to start the bounded desktop read,
        // but never enough to download it as a completed live photograph. Retain the
        // original LIVE hint; only this temporary availability check ignores that
        // missing clip, and all other image/motion sources must remain valid.
        val canReadMissingLiveMotion = verified.isAlbum &&
            verified.images.any { it.kind == AlbumAssetKind.LIVE && it.motion == null } &&
            WatermarkSources.available(verified.copy(images = verified.images.map { image ->
                if (image.kind == AlbumAssetKind.LIVE && image.motion == null)
                    image.copy(kind = AlbumAssetKind.STATIC) else image
            }), WatermarkMode.CLEAN)
        if (!WatermarkSources.available(verified, WatermarkMode.CLEAN) && !canReadMissingLiveMotion) {
            fail(IllegalArgumentException(if (verified.isAlbum) WatermarkSources.unavailableAlbumReason(verified, WatermarkMode.CLEAN)
                ?: "这套图集暂时无法获取完整图片，请重新解析后重试"
                else "这个视频暂时无法获取下载来源，请重新解析后重试"))
            advanceQueueLater()
            return
        }
        video = verified
        if (verified.hasDynamicAlbumAssets) albumMode = AlbumMode.IMAGES
        duplicateCandidates = duplicates
        activeTaskKey?.let { key ->
            queueResults = queueResults + (key to verified)
            queueDuplicates = queueDuplicates + (key to duplicates)
        }
        trace("media_probe_passed")
        stage = TaskStage.READY
        updateTask(QueueStatus.READY, title = verified.title)
        message = if (verified.isAlbum) "已解析 ${verified.images.size} 张图片，请选择保存方式" else "解析完成，可以下载视频"
        if (verified.isAlbum) {
            if (desktopShareCandidate === verified) {
                desktopShareCandidate = null
                val key = activeTaskKey?.let { "queue:$it" } ?: "single:${verified.id}"
                val hasDynamicSource = verified.images.any { it.motion != null || it.kind == AlbumAssetKind.ANIMATED }
                motionStates = motionStates + (key to AlbumMotionReadState(
                    if (hasDynamicSource) AlbumMotionPhase.AVAILABLE else AlbumMotionPhase.UNAVAILABLE,
                    if (hasDynamicSource) "已读取这套图集的动态资源，请选择保存方式"
                    else "已取得图片，网页未提供逐图动态片段"))
                // A complete stable desktop array needs no second automatic page read,
                // including an array that mixes static covers with paired motion.
                if (!queueRunning && activeTaskKey != null) restoreSingleSelection()
                advanceQueueLater()
                return
            }
            // Mobile pages often expose only covers. Resolve the same work once before
            // presenting static-only actions; a bounded failure never discards the covers.
            val request = MotionRequest(++motionNonce, activeTaskKey, verified, generation, queueEpoch, false,
                AlbumMode.IMAGES, saveOnReady = false, automatic = true)
            startAlbumMotionRead(request)
            return
        }
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
                val fixture = publicApiReadOverride
                val result = if (fixture != null) fixture(id) else source.read(id, userAgent) { event ->
                    mainHandler.post { if (current == generation) trace(event) }
                }
                if (current != generation) return@launch
                if (result != null) {
                    stage = TaskStage.BROWSING
                    parsed(current, result)
                } else {
                    val unavailableReason = PageNetworkFailures.unavailableMessage(detail, source.lastNetworkFailure)
                    if (startDesktopShareFallback(current, id, unavailableReason)) return@launch
                    stage = TaskStage.FAILED
                    message = unavailableReason
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

    /** One complete, dynamic same-work album check after both mobile sources
     * return no work data. This never assumes a failed video is an album. */
    private fun startDesktopShareFallback(current: Int, id: String, pageReason: String): Boolean {
        if (current != generation || stage != TaskStage.RESOLVING || browsingId != id || video != null ||
            desktopShareAttempted || !id.matches(Regex("[0-9]{15,22}"))) return false
        val fixture = desktopShareResolveOverride
        val host = parserHost
        if (fixture == null && host == null) return false
        val token = Any()
        val taskKey = activeTaskKey
        val epoch = queueEpoch
        desktopShareAttempted = true
        desktopShareToken = token
        stage = TaskStage.VERIFYING
        val stateKey = taskKey?.let { "queue:$it" } ?: "single:$id"
        desktopShareStateKey = stateKey
        motionStates = motionStates + (stateKey to AlbumMotionReadState(AlbumMotionPhase.READING,
            "正在检查同一作品的备用来源…"))
        message = "正在检查同一作品的备用来源…"
        trace("desktop_share_fallback_started id=$id cause=no_mobile_data")
        val complete: (DesktopAlbumResult) -> Unit = callback@{ result ->
            if (desktopShareToken !== token || current != generation || epoch != queueEpoch ||
                stage != TaskStage.VERIFYING || browsingId != id || activeTaskKey != taskKey || video != null ||
                taskKey != null && queue.none { it.key == taskKey }) return@callback
            val candidate = result.album
            if (result.status == DesktopAlbumStatus.CANDIDATE && candidate != null && candidate.id == id &&
                (candidate.isAlbum || candidate.width in 1..16384 && candidate.height in 1..16384 &&
                    candidate.durationSeconds.isFinite() && candidate.durationSeconds > 0) &&
                WatermarkSources.available(candidate, WatermarkMode.CLEAN)) {
                releaseDesktopShareReader()
                if (candidate.isAlbum) {
                    desktopShareCandidate = candidate
                    trace("desktop_share_album_confirmed id=$id images=${candidate.images.size} motions=${candidate.images.count { it.motion != null }}")
                } else {
                    // This exact work's desktop page has already been read. Its
                    // source must pass the ordinary byte probe; do not re-read the
                    // same desktop route again if that probe rejects it.
                    desktopVideoAttempted = true
                    motionStates = motionStates - stateKey
                    trace("desktop_share_video_confirmed id=$id")
                }
                stage = TaskStage.BROWSING
                parsed(current, candidate)
            } else {
                val requiresVerification = result.status == DesktopAlbumStatus.NEEDS_VERIFICATION
                releaseDesktopShareReader()
                motionStates = motionStates + (stateKey to AlbumMotionReadState(
                    if (requiresVerification) AlbumMotionPhase.NEEDS_VERIFICATION else AlbumMotionPhase.UNAVAILABLE,
                    if (requiresVerification) "抖音网页需要手动验证，可单独检查此链接的动态资源后打开官方网页验证"
                    else "同一作品的备用页面未提供完整可下载资源"))
                trace("desktop_share_fallback_unavailable status=${result.status} page_reason=$pageReason")
                stage = TaskStage.FAILED
                message = if (requiresVerification) "抖音网页需要手动验证；可检查动态资源并打开官方网页验证"
                    else PageNetworkFailures.unavailableMessage(pageReason, desktopReason = result.reason)
                updateTask(QueueStatus.FAILED, message)
                if (!queueRunning) restoreSingleSelection()
                advanceQueueLater()
            }
        }
        return try {
            if (fixture != null) fixture(id, complete) else {
                val nativeHost = checkNotNull(host)
                val browser = WebView(nativeHost.context)
                desktopShareBrowser = browser
                nativeHost.addView(browser, FrameLayout.LayoutParams(1280, -1))
                desktopShareResolver = DesktopAlbumResolver(browser, id, complete,
                    { if (desktopShareToken === token && current == generation) trace(it) },
                    timeoutMs = 20_000L, allowVideo = true, allowStaticAlbums = true)
            }
            true
        } catch (error: Exception) {
            trace("desktop_share_fallback_error type=${error.javaClass.simpleName}")
            complete(DesktopAlbumResult(DesktopAlbumStatus.FAILED))
            true
        }
    }

    private fun releaseDesktopShareReader(cancelDetail: String? = null) {
        if (cancelDetail != null) desktopShareStateKey?.let { key ->
            motionStates = motionStates + (key to AlbumMotionReadState(AlbumMotionPhase.UNAVAILABLE, cancelDetail))
        }
        desktopShareStateKey = null
        desktopShareToken = null
        val resolver = desktopShareResolver
        val browser = desktopShareBrowser
        desktopShareResolver = null; desktopShareBrowser = null
        if (resolver != null) resolver.dispose() else browser?.let {
            (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy()
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

    fun downloadGif(force: Boolean = false) {
        if (busy || queueRunning || batchSaving) return
        albumMode = AlbumMode.GIF
        download(force)
    }

    fun download(force: Boolean = false, selectedImageIndices: List<Int> = emptyList()) {
        val selected = video ?: return
        downloadContent(selected, force, selectedImageIndices = selectedImageIndices)
    }

    private fun downloadContent(selected: ParsedVideo, force: Boolean, mode: AlbumMode? = null,
        selectedImageIndices: List<Int> = emptyList()) {
        if (busy || queueRunning) return
        if (!(mode ?: albumMode).isSupportedExport()) { message = "此保存格式已移除"; return }
        val options = if (batchSaving) checkNotNull(batchOptions).copy(watermarkMode = WatermarkMode.CLEAN,
            albumMode = if (selected.images.any { albumPreviewKind(it) == AlbumAssetKind.DYNAMIC })
                checkNotNull(batchOptions).albumMode else AlbumMode.IMAGES)
            else DownloadOptions(mode ?: albumMode, imageSeconds.coerceIn(2, 10), fileName, namingRule, WatermarkMode.CLEAN,
                gifStartSeconds, gifDurationSeconds, itemDurationSeconds, selectedImageIndices, staticImageSeconds, gifExportQuality)
        val effective = try { AlbumSelection.select(selected, options.selectedImageIndices).let { content ->
            if (options.albumMode == AlbumMode.COVERS) content.copy(images = content.images.map { it.copy(motion = null, kind = AlbumAssetKind.STATIC) }) else content
        } }
            catch (error: Exception) { message = error.message.orEmpty(); return }
        if (options.albumMode == AlbumMode.IMAGES && effective.images.any { it.kind == AlbumAssetKind.DYNAMIC }) {
            message = "网页未标注动态素材类型，请选择保存为无声动图或 GIF"; return
        }
        if (options.albumMode == AlbumMode.GIF) {
            if (!selected.isAlbum && (!options.gifStartSeconds.isFinite() || options.gifStartSeconds < 0 ||
                    !options.gifDurationSeconds.isFinite() || options.gifDurationSeconds < 0.1f)) {
                message = "请设置有效的 GIF 起始时间和时长，最短 0.1 秒"; return
            }
        }
        if (!WatermarkSources.availableForDownload(effective, options.watermarkMode)) {
            val detail = if (selected.isAlbum) WatermarkSources.unavailableAlbumReason(selected, options.watermarkMode)
                ?: "这套图集暂时无法获取完整图片，请重新解析后重试"
                else "这个视频暂时无法获取下载来源，请重新解析后重试"
            message = detail
            return
        }
        if (!force && options.selectedImageIndices.isEmpty() && options.itemDurationSeconds == null && duplicateCandidates.any { it.id == selected.id && it.watermarkMode == WatermarkMode.CLEAN &&
                AlbumRecordPolicy.matches(it, selected, options.albumMode, options.gifStartSeconds, options.gifDurationSeconds,
                    options.imageSeconds, options.itemDurationSeconds, options.staticImageSeconds, options.gifExportQuality) }) {
            message = "已有可读取的文件，已跳过重复保存"
            updateTask(QueueStatus.DONE, message)
            return
        }
        if (selected.isAlbum && options.albumMode == AlbumMode.VIDEO && selected.bgmUrl.isBlank()) {
            message = "作品没有提供可读取的 BGM，可以选择保存图片"; return
        }
        try {
            if (namespace.isEmpty() && queueSaveOverride == null) DownloadForegroundService.start(application, false)
        } catch (error: Exception) {
            message = "无法启动后台下载，请回到首页点击下载后重试"
            trace("service_start_failed type=${error.javaClass.simpleName}")
            return
        }
        val folder = downloadFolder
        stage = TaskStage.DOWNLOADING
        updateTask(QueueStatus.RUNNING, title = selected.title)
        trace("download_started id=${selected.id} watermark=${options.watermarkMode}")
        message = if (WatermarkSources.requiresEmbeddedLiveVerification(effective, options.watermarkMode))
            "正在读取图片中的动态片段…"
            else if (selected.isAlbum) "正在下载图集素材…" else "正在下载，可切换到其他应用"
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
                                if (namespace.isEmpty()) DownloadForegroundService.progress(message, read, size,
                                    stage == TaskStage.SAVING && (options.albumMode == AlbumMode.GIF || (selected.isAlbum && options.albumMode == AlbumMode.VIDEO)))
                            }
                        }
                    }, onSaving = {
                        withContext(Dispatchers.Main) {
                            stage = TaskStage.SAVING
                            message = when {
                                options.albumMode == AlbumMode.GIF -> "正在生成 GIF 并保存…"
                                selected.isAlbum && options.albumMode == AlbumMode.VIDEO -> "正在合成视频并保存…"
                                else -> "正在检查文件并保存…"
                            }
                            if (namespace.isEmpty()) DownloadForegroundService.progress(message, 0, -1,
                                options.albumMode == AlbumMode.GIF || (selected.isAlbum && options.albumMode == AlbumMode.VIDEO))
                        }
                    }, onSaved = { saveHistory(it); committed = it }, onDiagnostic = { event ->
                        Handler(Looper.getMainLooper()).post { if (current == generation) trace(event) }
                    }, onDurationMismatch = { adjustments ->
                        withContext(Dispatchers.Main.immediate) {
                            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
                            durationAnswer = answer
                            durationAdjustments = adjustments
                            try { answer.await() } finally {
                                durationAnswer = null; durationAdjustments = emptyList()
                            }
                        }
                    })
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    history = loadHistory()
                    stage = TaskStage.DONE
                    message = "已保存 · " + saved.locationLabel + if (saved.mimeType == "image/gif")
                        "\n" + GifConversionPolicy.sharingSummary(saved.bytes) else ""
                    trace("download_saved id=${saved.id} requested=${options.watermarkMode.name} actual=${saved.watermarkMode?.name ?: "UNKNOWN"} bytes=${saved.bytes} location=${saved.locationLabel}")
                    updateTask(QueueStatus.DONE, message)
                    if (batchSaving) batchSaved++
                    rememberSavedDuplicate(saved)
                    if (namespace.isEmpty()) DownloadForegroundService.complete(application, saved)
                }
            } catch (cancelled: CancellationException) {
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
                        message = if (cancelled.message == "返回修改素材时长") "已返回修改素材时长" else "下载已取消"
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
        if (motionRequest != null) { cancelAlbumMotion(); return }
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
        browsingUrl = null
        stage = TaskStage.CANCELLED
        releaseDesktopVideoReader()
        releaseDesktopShareReader("备用来源检查已取消")
        desktopShareCandidate = null
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
        // The album may already be READY while its automatic motion read is still busy.
        // Release that read before invalidating its epoch, preserving the parsed cover result.
        if (motionRequest != null) cancelAlbumMotion()
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
        if (coreBusy || queueRunning || batchSaving) return
        // Commit the empty task list first; a storage error must not pretend that
        // the queue is gone when it would reappear after the process restarts.
        try { records.writeQueue(emptyList()) } catch (error: Exception) {
            trace("queue_clear_failed type=${error.javaClass.simpleName}")
            message = FailureMessages.describe(error)
            return
        }
        cancelAlbumMotion()
        motionStates = motionStates.filterKeys { !it.startsWith("queue:") }
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
        queueDurations = emptyMap()
        queueDuplicates = emptyMap()
        message = "已清空全部任务，单链接内容和已保存记录已保留"
    }
    fun removeTask(key: String) {
        if (motionRequest?.taskKey == key) cancelAlbumMotion()
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
          queueDurations = queueDurations - key
        queueDuplicates = queueDuplicates - key
        motionStates = motionStates - "queue:$key"
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
        itemDurationSeconds = queueDurations[key]
        duplicateCandidates = queueDuplicates[key].orEmpty()
        stage = TaskStage.READY
        message = "已查看此作品，可选择保存方式"
        selectedPage = AppPage.HOME
        return true
    }
    fun downloadTask(key: String, force: Boolean = false, mode: AlbumMode = AlbumMode.IMAGES,
        selectedImageIndices: List<Int> = emptyList()) {
        if (busy || queueRunning || batchSaving) return
        val durationOverride = itemDurationForTask(key)
        if (!activateQueueResult(key)) return
        itemDurationSeconds = durationOverride
        albumMode = mode
        download(force, selectedImageIndices)
        if (!busy) restoreSingleSelection()
    }
    fun downloadParsedQueue(keys: Set<String> = emptySet(), mode: AlbumMode = AlbumMode.IMAGES) {
        if (busy || queueRunning || batchSaving) return
        require(mode in listOf(AlbumMode.IMAGES, AlbumMode.MOTION_VIDEOS))
        pendingBatchKeys = QueuePolicy.keysToSave(queue, queueResults.keys, keys)
        if (pendingBatchKeys.isEmpty()) { message = "没有可保存的新解析结果"; return }
        batchOptions = DownloadOptions(mode, imageSeconds.coerceIn(2, 10), "", namingRule, WatermarkMode.CLEAN,
            staticImageSeconds = staticImageSeconds)
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
            val result = video ?: continue
            albumMode = if (result.images.any { albumPreviewKind(it) == AlbumAssetKind.DYNAMIC })
                checkNotNull(batchOptions).albumMode else AlbumMode.IMAGES
            val requested = checkNotNull(batchOptions).watermarkMode
            if (!WatermarkSources.availableForDownload(result, requested)) {
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
        video?.let { singleSelection = SingleSelection(it, duplicateCandidates, stage, albumMode, fileName, downloaded, total, gifStartSeconds, gifDurationSeconds, itemDurationSeconds) }
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
        gifStartSeconds = saved.gifStartSeconds
        gifDurationSeconds = saved.gifDurationSeconds
        itemDurationSeconds = saved.itemDurationSeconds
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
                            AlbumRecordPolicy.retainFiles(saved, failed)
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
