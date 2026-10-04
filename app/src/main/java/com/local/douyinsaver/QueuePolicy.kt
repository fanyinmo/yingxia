package com.local.douyinsaver

object QueuePolicy {
    fun recover(task: QueueTask): QueueTask = when (task.status) {
        QueueStatus.READY -> task.copy(status = QueueStatus.FAILED, message = "解析地址已失效，点击重试重新解析")
        QueueStatus.PARSING, QueueStatus.RUNNING -> task.copy(status = QueueStatus.FAILED,
            message = "上次任务被中断，点击重试重新解析")
        else -> if (listOf("无水印", "有水印", "原始版本", "切换版本", "使用可用版本").any(task.message::contains)) {
            task.copy(message = when (task.status) {
                QueueStatus.DONE -> "已保存，可在下载记录中查看"
                QueueStatus.CANCELLED -> "已取消，可重新解析"
                QueueStatus.QUEUED -> "等待解析"
                else -> "暂未读取到可下载地址，请重新解析"
            })
        } else task
    }
    fun move(tasks: List<QueueTask>, key: String, delta: Int): List<QueueTask> {
        val position = tasks.indexOfFirst { it.key == key }
        if (position < 0) return tasks
        return moveTo(tasks, key, position + delta.coerceIn(-1, 1))
    }
    fun moveTo(tasks: List<QueueTask>, key: String, targetIndex: Int): List<QueueTask> {
        val position = tasks.indexOfFirst { it.key == key }
        if (position < 0 || tasks[position].status != QueueStatus.QUEUED) return tasks
        val target = targetIndex.coerceIn(tasks.indices)
        if (target == position || (minOf(position, target)..maxOf(position, target)).any { tasks[it].status != QueueStatus.QUEUED }) return tasks
        return tasks.toMutableList().apply { add(target, removeAt(position)) }
    }

    fun nextToParse(tasks: List<QueueTask>): QueueTask? = tasks.firstOrNull { it.status == QueueStatus.QUEUED }

    fun keysToSave(tasks: List<QueueTask>, parsedKeys: Set<String>, selection: Set<String> = emptySet()): List<String> =
        tasks.filter { it.key in parsedKeys && it.status !in listOf(QueueStatus.RUNNING, QueueStatus.PARSING) &&
            (selection.isEmpty() || it.key in selection) }.map { it.key }.distinct()
}
