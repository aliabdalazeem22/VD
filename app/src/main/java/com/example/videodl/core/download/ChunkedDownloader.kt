package com.example.videodl.core.download

import com.example.videodl.core.net.defaultHeaders
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Parallel HTTP range downloader with per-chunk resume.
 *
 * Layout: `target.part0`, `target.part1`, … Each part is an independent byte
 * range, so an interrupted download restarts only the bytes it is missing.
 * Parts are merged into `target` on success.
 */
class ChunkedDownloader(
    private val client: OkHttpClient,
    private val parallelism: Int = 4,
    private val minChunkBytes: Long = 1L shl 20,
) {
    data class Progress(val downloaded: Long, val total: Long?, val bytesPerSecond: Long)

    private class RangeUnsupported : IOException("Server ignored the Range request")

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        sizeHint: Long?,
        target: File,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val total = sizeHint?.takeIf { it > 0 } ?: probeSize(url, headers)
        try {
            if (total != null && total > minChunkBytes && parallelism > 1) {
                downloadChunked(url, headers, total, target, onProgress)
            } else {
                downloadStream(url, headers, total, target, onProgress)
            }
        } catch (unsupported: RangeUnsupported) {
            deleteParts(target)
            target.delete()
            downloadStream(url, headers, total, target, onProgress)
        }
        target
    }

    private suspend fun downloadChunked(
        url: String,
        headers: Map<String, String>,
        total: Long,
        target: File,
        onProgress: (Progress) -> Unit,
    ) = coroutineScope {
        val chunkSize = ceil(total.toDouble() / parallelism).toLong().coerceAtLeast(1)
        val parts = buildList {
            var start = 0L
            var index = 0
            while (start < total) {
                val end = minOf(total - 1, start + chunkSize - 1)
                add(Part(index, start, end, File("${target.path}.part$index")))
                start = end + 1
                index++
            }
        }

        val counter = AtomicLong(parts.sumOf { it.completedBytes() })
        val reporter = ProgressReporter(counter.get(), total, onProgress)

        parts.map { part ->
            async(Dispatchers.IO) { fetchPart(part, url, headers, counter, reporter) }
        }.awaitAll()

        merge(parts, target, total)
        parts.forEach { part -> part.file.delete() }
        reporter.finish()
    }

    private fun merge(parts: List<Part>, target: File, total: Long) {
        if (target.exists()) target.delete()
        FileOutputStream(target, true).use { out ->
            parts.forEach { part ->
                part.file.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
            }
        }
        if (target.length() != total) {
            throw IOException("Merged file is ${target.length()} bytes, expected $total")
        }
    }

    private suspend fun fetchPart(
        part: Part,
        url: String,
        headers: Map<String, String>,
        counter: AtomicLong,
        reporter: ProgressReporter,
    ) {
        var have = part.file.length().coerceAtMost(part.length)
        var attempts = 0

        while (have < part.length) {
            if (attempts++ >= 5) throw IOException("Chunk ${part.index} stalled")
            val from = part.start + have
            val request = Request.Builder()
                .url(url)
                .defaultHeaders(headers)
                .header("Range", "bytes=$from-${part.end}")
                .build()

            client.newCall(request).execute().use { response ->
                // A 200 here means the server sent the whole file and ignored Range.
                if (response.code == 200) throw RangeUnsupported()
                if (response.code != 206) {
                    throw IOException("HTTP ${response.code} for bytes $from-${part.end}")
                }
                val body = response.body ?: throw IOException("Empty body for chunk ${part.index}")
                val before = have

                RandomAccessFile(part.file, "rw").use { raf ->
                    raf.seek(have)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            raf.write(buffer, 0, read)
                            counter.addAndGet(read.toLong())
                            reporter.add(read.toLong())
                        }
                    }
                }

                have = part.file.length()
                if (have > part.length) throw RangeUnsupported()
                if (have == before) throw IOException("Chunk ${part.index} made no progress")
            }
        }
    }

    private suspend fun downloadStream(
        url: String,
        headers: Map<String, String>,
        totalHint: Long?,
        target: File,
        onProgress: (Progress) -> Unit,
    ) {
        val existing = if (target.exists()) target.length() else 0L
        val request = Request.Builder()
            .url(url)
            .defaultHeaders(headers)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response body")
            val appending = existing > 0 && response.code == 206
            val startAt = if (appending) existing else 0L
            if (!appending) target.delete()

            val total = when {
                response.code == 206 -> response.header("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull() ?: totalHint
                else -> response.header("Content-Length")?.toLongOrNull() ?: totalHint
            }
            val reporter = ProgressReporter(startAt, total, onProgress)

            FileOutputStream(target, appending).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        reporter.add(read.toLong())
                    }
                }
            }
            reporter.finish()
        }
    }

    private fun probeSize(url: String, headers: Map<String, String>): Long? = runCatching {
        val request = Request.Builder().url(url).head().defaultHeaders(headers).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() else null
        }
    }.getOrNull()

    private fun deleteParts(target: File) {
        val parent = target.parentFile ?: return
        parent.listFiles { file -> file.name.startsWith("${target.name}.part") }
            ?.forEach { it.delete() }
    }

    private data class Part(
        val index: Int,
        val start: Long,
        val end: Long,
        val file: File,
    ) {
        val length: Long get() = end - start + 1
        fun completedBytes(): Long = file.length().coerceIn(0, length)
    }

    /** Throttles UI updates to ~4/s and derives a smoothed transfer rate. */
    private class ProgressReporter(
        initial: Long,
        private val total: Long?,
        private val callback: (Progress) -> Unit,
    ) {
        private val downloaded = AtomicLong(initial)
        private val lock = Any()
        private var lastEmitNanos = 0L
        private var lastBytes = initial
        private var speed = 0.0

        fun add(count: Long) {
            val now = downloaded.addAndGet(count)
            synchronized(lock) {
                val nanos = System.nanoTime()
                if (lastEmitNanos == 0L) {
                    lastEmitNanos = nanos
                    lastBytes = now
                    return
                }
                val elapsed = (nanos - lastEmitNanos) / 1e9
                if (elapsed < 0.25) return
                val instant = (now - lastBytes) / elapsed
                speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                lastEmitNanos = nanos
                lastBytes = now
                callback(Progress(now, total, speed.toLong()))
            }
        }

        fun finish() {
            synchronized(lock) {
                callback(Progress(downloaded.get(), total, speed.toLong()))
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
