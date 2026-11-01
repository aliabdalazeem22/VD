package com.example.videodl.core.model

/** How the bytes must be fetched. */
enum class Delivery {
    /** One HTTP resource, resumable with Range requests. */
    PROGRESSIVE,

    /** An HLS media playlist that must be fetched segment-by-segment. */
    HLS,
}

data class MediaStream(
    val id: String,
    val url: String,
    val mimeType: String,
    val qualityLabel: String,
    val height: Int? = null,
    val bandwidth: Long? = null,
    val contentLength: Long? = null,
    val audioOnly: Boolean = false,
    /** False for DASH video-only renditions that need muxing with an audio track. */
    val hasAudio: Boolean = true,
    val container: String = "mp4",
    val delivery: Delivery = Delivery.PROGRESSIVE,
    val headers: Map<String, String> = emptyMap(),
)

data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val streams: List<MediaStream> = emptyList(),
)

enum class TaskState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED }

data class DownloadTask(
    val id: String,
    val media: MediaInfo,
    val stream: MediaStream,
    val fileName: String,
    val state: TaskState = TaskState.QUEUED,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = stream.contentLength,
    val bytesPerSecond: Long = 0,
    val segmentsDone: Int? = null,
    val segmentsTotal: Int? = null,
    val error: String? = null,
    val outputUri: String? = null,
) {
    val isActive: Boolean get() = state == TaskState.QUEUED || state == TaskState.RUNNING

    val fraction: Float
        get() {
            val sDone = segmentsDone
            val sTotal = segmentsTotal
            if (sDone != null && sTotal != null && sTotal > 0) {
                return (sDone.toFloat() / sTotal).coerceIn(0f, 1f)
            }
            val total = totalBytes
            return if (total != null && total > 0) {
                (bytesDownloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
        }
}

data class DownloadRecord(
    val id: String,
    val title: String,
    val fileName: String,
    val uri: String,
    val quality: String,
    val completedAt: Long,
)

fun Long.readableSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${toInt()} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
}

fun Long.readableSpeed(): String = "${readableSize()}/s"
