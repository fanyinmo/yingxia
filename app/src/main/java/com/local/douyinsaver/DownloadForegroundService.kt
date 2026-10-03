package com.local.douyinsaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Only user-started work; never starts on boot or silently restarts interrupted transfers. */
class DownloadForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        instance = this
        manager().createNotificationChannel(NotificationChannel(CHANNEL, "下载任务", NotificationManager.IMPORTANCE_LOW))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:download").apply { acquire(60 * 60 * 1000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            SaverEngine.get(application).cancel()
            return START_NOT_STICKY
        }
        show("准备下载…", 0, -1, intent?.getBooleanExtra("processing", false) == true)
        return START_NOT_STICKY
    }
    private fun manager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun notification(text: String, done: Long, total: Long, finished: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, if (finished) 2 else 0, Intent(this, MainActivity::class.java)
            .putExtra("open_history", finished).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(if (finished) "保存完成" else getString(R.string.app_name))
            .setContentText(text.take(140)).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(!finished).setAutoCancel(finished).apply {
                if (!finished) {
                    if (total > 0) setProgress(100, ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100), false)
                    else setProgress(0, 0, true)
                    val cancel = PendingIntent.getService(this@DownloadForegroundService, 1,
                        Intent(this@DownloadForegroundService, DownloadForegroundService::class.java).setAction(CANCEL),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null, "取消", cancel).build())
                }
            }.build()
    }
    private fun show(text: String, done: Long, total: Long, processing: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= 35 && processing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(ONGOING, notification(text, done, total), type)
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        SaverEngine.get(application).serviceInterrupted("后台任务达到系统时间限制，请重试")
        finish()
    }
    private fun finish() {
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (!stopping) SaverEngine.get(application).serviceInterrupted("后台任务已停止，请重试")
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "download_tasks"
        private const val ONGOING = 3001
        private const val COMPLETE = 3002
        private const val CANCEL = "com.local.douyinsaver.CANCEL_DOWNLOAD"
        @Volatile private var instance: DownloadForegroundService? = null
        fun start(context: Context, processing: Boolean) {
            val current = instance
            if (current != null) current.show("准备下载…", 0, -1, processing)
            else context.startForegroundService(Intent(context, DownloadForegroundService::class.java).putExtra("processing", processing))
        }
        fun progress(text: String, done: Long, total: Long, processing: Boolean = false) {
            instance?.show(text, done, total, processing)
        }
        fun complete(context: Context, saved: SavedVideo) {
            val service = instance
            if (service != null) runCatching { service.manager().notify(COMPLETE, service.notification(saved.title, 0, -1, true)) }
            else {
                // A very short transfer may finish before the service's first callback.
                context.stopService(Intent(context, DownloadForegroundService::class.java))
            }
        }
        fun stop(context: Context) {
            instance?.finish() ?: context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
