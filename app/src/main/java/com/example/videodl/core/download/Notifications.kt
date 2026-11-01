package com.example.videodl.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.videodl.MainActivity
import com.example.videodl.core.model.DownloadTask
import com.example.videodl.core.model.TaskState
import com.example.videodl.core.model.readableSize
import com.example.videodl.core.model.readableSpeed

object Notifications {

    const val FOREGROUND_ID = 1001
    private const val CHANNEL_PROGRESS = "dl_progress"
    private const val CHANNEL_RESULT = "dl_result"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "Download progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                "Download results",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    fun progress(context: Context, task: DownloadTask?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))

        if (task == null) {
            builder.setContentTitle("Preparing download…")
                .setProgress(0, 0, true)
            return builder.build()
        }

        val detail = buildString {
            val segments = task.segmentsTotal
            if (segments != null) {
                append("${task.segmentsDone ?: 0}/$segments segments")
            } else {
                append(task.bytesDownloaded.readableSize())
                task.totalBytes?.let { append(" / ${it.readableSize()}") }
            }
            if (task.bytesPerSecond > 0) append(" · ${task.bytesPerSecond.readableSpeed()}")
        }

        return builder
            .setContentTitle(task.fileName)
            .setContentText(detail)
            .setProgress(
                100,
                (task.fraction * 100).toInt(),
                task.totalBytes == null && task.segmentsTotal == null,
            )
            .addAction(0, "Cancel", cancelAction(context, task.id))
            .build()
    }

    fun finished(context: Context, task: DownloadTask) {
        val success = task.state == TaskState.COMPLETED
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText(if (success) task.fileName else (task.error ?: task.fileName))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification)
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAction(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_CANCEL)
            .putExtra(DownloadService.EXTRA_TASK_ID, taskId)
        return PendingIntent.getService(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
