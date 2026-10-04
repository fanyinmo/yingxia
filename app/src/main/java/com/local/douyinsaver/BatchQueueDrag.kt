package com.local.douyinsaver

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Dragging is confined to the contiguous waiting segment; result cards never change their order. */
internal class BatchQueueDragState(
    private val list: LazyListState,
    private val tasks: () -> List<QueueTask>,
    private val enabled: () -> Boolean,
    private val move: (String, Int) -> Unit,
    private val edgeSize: Float,
) {
    var key by mutableStateOf<String?>(null); private set
    private var desiredTop by mutableFloatStateOf(0f)
    private var itemHeight by mutableIntStateOf(0)

    fun start(taskKey: String) {
        if (!enabled() || tasks().none { it.key == taskKey && it.status == QueueStatus.QUEUED }) return
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == taskKey } ?: return
        desiredTop = item.offset.toFloat(); itemHeight = item.size; key = taskKey
    }

    fun drag(delta: Float) {
        if (!enabled()) { stop(); return }
        if (key == null) return
        desiredTop += delta
        reorder()
    }

    private fun reorder() {
        val dragging = key ?: return
        val current = tasks()
        val index = current.indexOfFirst { it.key == dragging }
        if (index < 0 || current[index].status != QueueStatus.QUEUED) { stop(); return }
        var start = index; var end = index
        while (start > 0 && current[start - 1].status == QueueStatus.QUEUED) start--
        while (end < current.lastIndex && current[end + 1].status == QueueStatus.QUEUED) end++
        val center = desiredTop + itemHeight / 2f
        val nearest = list.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val target = current.indexOfFirst { it.key == item.key }
            if (target in start..end) target to abs(item.offset + item.size / 2f - center) else null
        }.minByOrNull { it.second }?.first ?: return
        if (nearest != index) move(dragging, nearest)
    }

    fun translation(taskKey: String): Int = if (key == taskKey) {
        val top = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == taskKey }?.offset ?: desiredTop.roundToInt()
        (desiredTop - top).roundToInt()
    } else 0

    suspend fun scrollAtEdge() {
        if (key == null || !enabled()) return
        val center = desiredTop + itemHeight / 2f
        val info = list.layoutInfo
        val top = info.viewportStartOffset + edgeSize
        val bottom = info.viewportEndOffset - edgeSize
        val delta = when { center < top -> ((center - top) / edgeSize).coerceIn(-1f, 0f) * 18f
            center > bottom -> ((center - bottom) / edgeSize).coerceIn(0f, 1f) * 18f
            else -> 0f }
        if (delta != 0f) { list.scrollBy(delta); reorder() }
    }

    fun stop() { key = null }
}

@Composable
internal fun rememberBatchQueueDragState(list: LazyListState, tasks: List<QueueTask>, enabled: Boolean,
                                        edgeSize: Float, onMove: (String, Int) -> Unit): BatchQueueDragState {
    val latestTasks by rememberUpdatedState(tasks)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestMove by rememberUpdatedState(onMove)
    val drag = remember(list, edgeSize) { BatchQueueDragState(list, { latestTasks }, { latestEnabled },
        { key, index -> latestMove(key, index) }, edgeSize) }
    LaunchedEffect(enabled) { if (!enabled) drag.stop() }
    LaunchedEffect(drag.key) {
        while (drag.key != null) { drag.scrollAtEdge(); delay(16) }
    }
    return drag
}

internal fun Modifier.batchQueueDragHandle(state: BatchQueueDragState, key: String, enabled: Boolean): Modifier =
    if (!enabled) this else pointerInput(state, key) {
        detectDragGesturesAfterLongPress(onDragStart = { state.start(key) }, onDragEnd = state::stop,
            onDragCancel = state::stop, onDrag = { change, amount -> change.consume(); state.drag(amount.y) })
    }

internal fun Modifier.batchQueueDragPlacement(state: BatchQueueDragState, key: String): Modifier =
    offset { IntOffset(0, state.translation(key)) }.zIndex(if (state.key == key) 1f else 0f)
