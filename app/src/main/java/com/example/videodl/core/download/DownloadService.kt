package com.example.videodl.core.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.videodl.core.ServiceLocator
import com.example.videodl.core.model.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Hosts the download while the app is backgrounded. It owns no I/O itself:
 * [DownloadEngine] does the work and this service mirrors its state into a
 * foreground notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: DownloadEngine
    private var observeJob: Job? = null
    private var foreground = false
    private val notifiedResults = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        engine = ServiceLocator.engine(this)
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                startInForeground(taskId)
                taskId?.let(engine::launch)
                observeTasks()
            }

            ACTION_CANCEL -> intent?.getStringExtra(EXTRA_TASK_ID)?.let(engine::cancel)
            ACTION_PAUSE -> intent?.getStringExtra(EXTRA_TASK_ID)?.let(engine::pause)
        }
        // Also cover a cancel/pause that woke a service which was not running.
        observeTasks()
        return START_NOT_STICKY
    }

    private fun startInForeground(taskId: String?) {
        if (foreground) return
        val task = taskId?.let { engine.tasks.value[it] }
        ServiceCompat.startForeground(
            this,
            Notifications.FOREGROUND_ID,
            Notifications.progress(this, task),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        foreground = true
    }

    private fun observeTasks() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            engine.tasks.collect { tasks ->
                val current = tasks.values.firstOrNull { it.state == TaskState.RUNNING }
                    ?: tasks.values.firstOrNull { it.state == TaskState.QUEUED }

                if (current != null) {
                    if (!foreground) startInForeground(current.id)
                    val manager = NotificationManagerCompat.from(this@DownloadService)
                    if (manager.areNotificationsEnabled()) {
                        manager.notify(
                            Notifications.FOREGROUND_ID,
                            Notifications.progress(this@DownloadService, current),
                        )
                    }
                }

                tasks.values
                    .filter { it.state == TaskState.COMPLETED || it.state == TaskState.FAILED }
                    .forEach { task ->
                        if (notifiedResults.add(task.id)) {
                            Notifications.finished(this@DownloadService, task)
                        }
                    }

                if (current == null) finishService()
            }
        }
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        observeJob = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.videodl.action.START"
        const val ACTION_CANCEL = "com.example.videodl.action.CANCEL"
        const val ACTION_PAUSE = "com.example.videodl.action.PAUSE"
        const val EXTRA_TASK_ID = "task_id"

        fun start(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK_ID, taskId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TASK_ID, taskId)
            context.startService(intent)
        }
    }
}
