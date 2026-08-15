package com.chochocho.homephotoclient.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class BackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = BackupEngine.get(applicationContext)
        createChannel()

        // 포그라운드 승격 — 화면이 꺼져도 장시간 백업 유지.
        // (앱이 백그라운드일 때 승격이 거부될 수 있는데, 그 경우에도 일반 워커로 계속 진행)
        try {
            setForeground(foregroundInfo("백업 준비 중"))
        } catch (_: Exception) {
        }

        return coroutineScope {
            val notifier = launch {
                engine.state.collect { s ->
                    if (s is BackupState.Working && s.total > 0) {
                        val speed = s.speedBps?.let { " · ${formatSpeed(it)}" } ?: ""
                        notify("${s.phase} ${s.done}/${s.total}$speed")
                    }
                }
            }
            try {
                when (val result = engine.runBackupOnce()) {
                    is BackupState.Done -> {
                        if (result.failed > 0 && runAttemptCount < 2) Result.retry() else Result.success()
                    }
                    is BackupState.Error -> if (runAttemptCount < 2) Result.retry() else Result.failure()
                    else -> Result.success()
                }
            } finally {
                notifier.cancel()
            }
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification = buildNotification(text)
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("사진 백업")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun notify(text: String) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "사진 백업", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_ID = "backup"
        const val NOTIFICATION_ID = 1001
    }
}
