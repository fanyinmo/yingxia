package com.local.douyinsaver

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException

/** Refreshes the clean source so preview and saving use the same validated media path. */
@Composable
fun VerifiedVideoPreviewDialog(content: ParsedVideo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val previewOptions = remember(context) { PreviewPreferences(context).read() }
    var attempt by rememberSaveable(content) { mutableIntStateOf(0) }
    var preparedSource by rememberSaveable(content, attempt) { mutableStateOf<String?>(null) }
    var failure by remember(content, attempt) { mutableStateOf<String?>(null) }
    val probe = remember(content, attempt) { MediaProbe() }
    DisposableEffect(probe) { onDispose { probe.cancel() } }
    LaunchedEffect(probe) {
        if (preparedSource != null) return@LaunchedEffect
        try {
            preparedSource = probe.verifySelected(content, WatermarkMode.CLEAN).mediaUrl
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = FailureMessages.describe(error)
        }
    }
    preparedSource?.let { source ->
        VideoPreviewDialog(source, content.title, content.width, content.height,
            backwardSeconds = previewOptions.backwardSeconds, forwardSeconds = previewOptions.forwardSeconds,
            onDismiss = onDismiss)
    } ?: Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(content.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (failure == null) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("正在准备预览…", style = MaterialTheme.typography.bodyMedium)
                } else Text(failure.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    if (failure != null) TextButton(onClick = { attempt++ }) { Text("重新检查") }
                }
            }
        }
    }
}
