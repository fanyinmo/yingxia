package com.local.douyinsaver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppPage(val label: String) { HOME("首页"), HISTORY("下载记录"), SETTINGS("设置") }

class MainActivity : ComponentActivity() {
    private val model: SaverViewModel by viewModels()
    private lateinit var parserHost: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveIntent(intent)
        parserHost = FrameLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false; isClickable = false
        }
        val layout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            addView(parserHost, FrameLayout.LayoutParams(-1, -1))
            addView(ComposeView(this@MainActivity).apply {
                setBackgroundColor(android.graphics.Color.WHITE)
                setContent {
                    val appearance = remember { AppearanceStore(applicationContext) }
                    SaverAppearance(appearance) { SaverScreen(model, appearance) }
                }
            }, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(layout)
        model.attachParserHost(parserHost)
    }
    override fun onDestroy() { model.detachParserHost(parserHost); super.onDestroy() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveIntent(intent) }
    private fun receiveIntent(source: Intent?) {
        if (source?.hasExtra("open_history") == true) model.selectedPage = if (source.getBooleanExtra("open_history", false)) AppPage.HISTORY else AppPage.HOME
        if (source?.action == Intent.ACTION_SEND && source.type == "text/plain") source.getStringExtra(Intent.EXTRA_TEXT)?.let {
            if (model.busy) Toast.makeText(this, "任务进行中，可在首页使用批量队列添加链接", Toast.LENGTH_LONG).show()
            else { model.acceptShare(it); model.selectedPage = AppPage.HOME }
        }
    }
}

@Composable
private fun SaverScreen(model: SaverViewModel, appearance: AppearanceStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = LocalActivity.current
    var confirmDuplicate by remember(model.generation) { mutableStateOf(false) }
    DisposableEffect(activity, model.busy) {
        if (model.busy) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) model.selectDownloadFolder(uri) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "通知未开启，仍可在 App 内查看进度", Toast.LENGTH_LONG).show()
    }
    val requestNotification: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Toast.makeText(context, "可在系统应用设置中管理通知", Toast.LENGTH_SHORT).show()
    }
    fun launchDownload(force: Boolean) {
        model.download(force = force)
        val prefs = context.getSharedPreferences("notification_prompt", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply(); notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val download: (Boolean) -> Unit = { force -> if (!force && model.duplicate != null) confirmDuplicate = true else launchDownload(force) }
    val chooseFolder = { if (!model.busy) folderPicker.launch(model.downloadFolder?.treeUri?.toUri()) }
    Box(Modifier.fillMaxSize()) {
        AppearanceBackdrop(appearance, Modifier.fillMaxSize())
        Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                NavigationBar(containerColor = appearancePanelColor(), tonalElevation = 0.dp) {
                    AppPage.entries.forEach { page -> NavigationBarItem(selected = model.selectedPage == page,
                        onClick = { model.selectedPage = page }, icon = { Glyph(when (page) {
                            AppPage.HOME -> GlyphKind.HOME; AppPage.HISTORY -> GlyphKind.HISTORY; AppPage.SETTINGS -> GlyphKind.SETTINGS
                        }) }, label = { Text(page.label) }) }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (model.selectedPage) {
                    AppPage.HOME -> HomeScreen(model, download)
                    AppPage.HISTORY -> HistoryScreen(model)
                    AppPage.SETTINGS -> SettingsScreen(model, appearance, chooseFolder, requestNotification)
                }
            }
        }
    }
    if (confirmDuplicate) AlertDialog(onDismissRequest = { confirmDuplicate = false }, title = { Text("这个作品已经保存过") },
        text = { Text("可以打开已有文件，也可以再保存一份。新文件不会覆盖已有文件。") },
        confirmButton = { TextButton(onClick = { confirmDuplicate = false; launchDownload(true) }) { Text("再保存一份") } },
        dismissButton = { Row {
            TextButton(onClick = { model.duplicate?.let { openSaved(context, it) }; confirmDuplicate = false }) { Text("打开已有文件") }
            TextButton(onClick = { confirmDuplicate = false }) { Text("取消") }
        } })
}

@Composable
internal fun OngoingTask(model: SaverViewModel) {
    Card(onClick = { model.selectedPage = AppPage.HOME }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(model.message, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
            Text("查看", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun PageHeading(title: String, subtitle: String) {
    val background = LocalAppearance.current.backgroundRevision > 0
    Surface(color = if (background) appearancePanelColor() else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(if (background) 14.dp else 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun cardColor(): Color = appearancePanelColor()

@Composable
internal fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor())) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

internal fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.take(32_768)
    if (text.isNullOrBlank()) Toast.makeText(context, "剪贴板中没有文本", Toast.LENGTH_SHORT).show()
    return text?.takeIf { it.isNotBlank() }
}

internal fun openSaved(context: Context, saved: SavedVideo) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri.toUri(), saved.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        .onFailure { Toast.makeText(context, "无法打开文件，可能已移动或删除，请刷新记录后重试", Toast.LENGTH_LONG).show() }
}

internal enum class GlyphKind { HOME, HISTORY, SETTINGS, FOLDER, LINK, DOWNLOAD, PLAY, CLOSE }

@Composable
internal fun Glyph(kind: GlyphKind, modifier: Modifier = Modifier, size: Dp = 24.dp,
                  color: Color = LocalContentColor.current) {
    Canvas(modifier.size(size)) {
        val unit = this.size.width / 24f
        val stroke = Stroke(width = 1.8f * unit, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke.width, StrokeCap.Round)
        fun outline(points: List<Offset>, closed: Boolean = false) {
            val path = Path().apply {
                moveTo(points.first().x * unit, points.first().y * unit)
                points.drop(1).forEach { lineTo(it.x * unit, it.y * unit) }
                if (closed) close()
            }
            drawPath(path, color, style = stroke)
        }
        when (kind) {
            GlyphKind.HOME -> {
                outline(listOf(Offset(3f, 10f), Offset(12f, 3f), Offset(21f, 10f)))
                outline(listOf(Offset(5f, 9f), Offset(5f, 21f), Offset(10f, 21f), Offset(10f, 14f),
                    Offset(14f, 14f), Offset(14f, 21f), Offset(19f, 21f), Offset(19f, 9f)))
            }
            GlyphKind.HISTORY -> {
                drawRoundRect(color, Offset(5f * unit, 3f * unit), Size(14f * unit, 18f * unit),
                    CornerRadius(2f * unit), style = stroke)
                line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f)
            }
            GlyphKind.SETTINGS -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                listOf(Offset(8f, 6f), Offset(16f, 12f), Offset(10f, 18f)).forEach { drawCircle(color, 2.4f * unit, it * unit) }
            }
            GlyphKind.FOLDER -> outline(listOf(Offset(3f, 6f), Offset(10f, 6f), Offset(12f, 9f),
                Offset(21f, 9f), Offset(21f, 20f), Offset(3f, 20f)), closed = true)
            GlyphKind.LINK -> {
                outline(listOf(Offset(10f, 8f), Offset(13f, 5f), Offset(18f, 5f), Offset(21f, 8f),
                    Offset(21f, 11f), Offset(17f, 15f)))
                outline(listOf(Offset(14f, 16f), Offset(11f, 19f), Offset(6f, 19f), Offset(3f, 16f),
                    Offset(3f, 13f), Offset(7f, 9f)))
                line(8f, 15f, 16f, 9f)
            }
            GlyphKind.DOWNLOAD -> {
                line(12f, 3f, 12f, 15f)
                outline(listOf(Offset(7f, 10f), Offset(12f, 15f), Offset(17f, 10f)))
                outline(listOf(Offset(4f, 16f), Offset(4f, 21f), Offset(20f, 21f), Offset(20f, 16f)))
            }
            GlyphKind.PLAY -> outline(listOf(Offset(8f, 5f), Offset(19f, 12f), Offset(8f, 19f)), closed = true)
            GlyphKind.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        }
    }
}

internal fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0)
internal fun savedTime(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
internal fun duration(seconds: Double): String {
    val value = seconds.toLong().coerceAtLeast(0)
    return if (value >= 3600) "%d:%02d:%02d".format(value / 3600, value / 60 % 60, value % 60)
    else "%02d:%02d".format(value / 60, value % 60)
}
