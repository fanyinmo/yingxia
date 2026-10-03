package com.local.douyinsaver

object QueuePolicy {
    fun recover(task: QueueTask): QueueTask = when (task.status) {
        QueueStatus.PARSING, QueueStatus.RUNNING, QueueStatus.READY -> task.copy(status = QueueStatus.FAILED,
            message = "上次任务被中断，点击重试重新解析")
        else -> task
    }
    fun move(tasks: List<QueueTask>, key: String, delta: Int): List<QueueTask> {
        val position = tasks.indexOfFirst { it.key == key }
        if (position < 0 || tasks[position].status != QueueStatus.QUEUED) return tasks
        val target = (position + delta.coerceIn(-1, 1)).coerceIn(tasks.indices)
        if (target == position || tasks[target].status != QueueStatus.QUEUED) return tasks
        return tasks.toMutableList().apply { add(target, removeAt(position)) }
    }
}
