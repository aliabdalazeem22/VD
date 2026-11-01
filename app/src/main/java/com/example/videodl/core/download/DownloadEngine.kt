package com.example.videodl.core.download

import com.example.videodl.core.extract.ExtractorRegistry
import com.example.videodl.core.model.Delivery
import com.example.videodl.core.model.DownloadRecord
import com.example.videodl.core.model.DownloadTask
import com.example.videodl.core.model.MediaInfo
import com.example.videodl.core.model.MediaStream
import com.example.videodl.core.model.TaskState
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth for downloads. The service only renders this state and
 * keeps the process alive; all I/O runs on [scope].
 */
class DownloadEngine(
    private val registry: ExtractorRegistry,
    private val chunked: ChunkedDownloader,
    private val hls: HlsDownloader,
    private val sink: MediaSink,
    private val store: DownloadStore,
    private val workDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()
    val history: StateFlow<List<DownloadRecord>> = store.history

    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelRequested = ConcurrentHashMap<String, Boolean>()

    suspend fun analyze(url: String): MediaInfo = registry.extract(url)

    fun enqueue(media: MediaInfo, stream: MediaStream, fileName: String): String {
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(
            id = id,
            media = media,
            stream = stream,
            fileName = fileName,
            totalBytes = stream.contentLength,
        )
        _tasks.update { it + (id to task) }
        return id
    }

    fun launch(id: String) {
        val snapshot = _tasks.value[id] ?: return
        if (snapshot.state == TaskState.COMPLETED) return
        if (jobs[id]?.isActive == true) return

        jobs[id] = scope.launch {
            val self = coroutineContext[Job]
            mark(id) { it.copy(state = TaskState.RUNNING, error = null) }
            val target = File(workDir, "$id.download")

            try {
                val output = when (snapshot.stream.delivery) {
                    Delivery.HLS -> hls.download(
                        playlistUrl = snapshot.stream.url,
                        headers = snapshot.stream.headers,
                        target = target,
                    ) { progress ->
                        mark(id) {
                            it.copy(
                                segmentsDone = progress.segmentsDone,
                                segmentsTotal = progress.segmentsTotal,
                            )
                        }
                    }

                    Delivery.PROGRESSIVE -> chunked.download(
                        url = snapshot.stream.url,
                        headers = snapshot.stream.headers,
                        sizeHint = snapshot.stream.contentLength,
                        target = target,
                    ) { progress ->
                        mark(id) {
                            it.copy(
                                bytesDownloaded = progress.downloaded,
                                totalBytes = progress.total ?: it.totalBytes,
                                bytesPerSecond = progress.bytesPerSecond,
                            )
                        }
                    }
                }

                val uri = sink.publish(output, snapshot.fileName, snapshot.stream.mimeType)
                output.delete()

                mark(id) {
                    it.copy(
                        state = TaskState.COMPLETED,
                        bytesDownloaded = it.totalBytes ?: it.bytesDownloaded,
                        bytesPerSecond = 0,
                        outputUri = uri.toString(),
                    )
                }
                store.record(
                    DownloadRecord(
                        id = id,
                        title = snapshot.media.title,
                        fileName = snapshot.fileName,
                        uri = uri.toString(),
                        quality = snapshot.stream.qualityLabel,
                        completedAt = System.currentTimeMillis(),
                    )
                )
            } catch (cancel: CancellationException) {
                val state = if (cancelRequested[id] == true) TaskState.CANCELED else TaskState.PAUSED
                // A retry may already have moved this id back to QUEUED/RUNNING.
                mark(id) { task ->
                    if (task.state == TaskState.RUNNING) {
                        task.copy(state = state, bytesPerSecond = 0)
                    } else {
                        task
                    }
                }
                if (state == TaskState.CANCELED) cleanWorkFiles(id)
                throw cancel
            } catch (t: Throwable) {
                mark(id) {
                    it.copy(
                        state = TaskState.FAILED,
                        bytesPerSecond = 0,
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
            } finally {
                jobs.remove(id, self)
            }
        }
    }

    fun pause(id: String) {
        cancelRequested[id] = false
        jobs[id]?.cancel()
    }

    fun cancel(id: String) {
        cancelRequested[id] = true
        jobs[id]?.cancel()
    }

    fun retry(id: String) {
        cancelRequested[id] = false
        mark(id) { it.copy(state = TaskState.QUEUED, error = null) }
        launch(id)
    }

    fun dismiss(id: String) {
        jobs[id]?.cancel()
        cleanWorkFiles(id)
        _tasks.update { it - id }
    }

    private fun mark(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { map -> map[id]?.let { map + (id to transform(it)) } ?: map }
    }

    private fun cleanWorkFiles(id: String) {
        val target = File(workDir, "$id.download")
        target.delete()
        target.parentFile?.listFiles { file -> file.name.startsWith(target.name) }
            ?.forEach { it.deleteRecursively() }
    }
}
