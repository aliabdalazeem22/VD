package com.example.videodl.core.download

import com.example.videodl.core.extract.UnsupportedSourceException
import com.example.videodl.core.net.defaultHeaders
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads an HLS media playlist: segments are fetched concurrently into a
 * side directory (so a retry resumes) and concatenated in playlist order.
 *
 * Output is the raw transport stream (`.ts`) or fragmented MP4 (`.mp4`) and is
 * NOT remuxed. Container-level remux/transcode would need FFmpeg (see README).
 */
class HlsDownloader(
    private val client: OkHttpClient,
    private val parallelism: Int = 4,
) {
    data class Progress(val segmentsDone: Int, val segmentsTotal: Int)

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        var (text, finalUrl) = fetchText(playlistUrl, headers)

        // A master playlist can reach us through the yt-dlp resolver. Pick the
        // highest-bandwidth variant instead of failing.
        if (text.contains("#EXT-X-STREAM-INF")) {
            val masterBase = finalUrl.toHttpUrlOrNull() ?: throw IOException("Bad playlist URL")
            val variant = pickVariant(text, masterBase)
                ?: throw IOException("Master playlist has no variants")
            val media = fetchText(variant, headers)
            text = media.first
            finalUrl = media.second
        }

        if (Regex("#EXT-X-KEY:METHOD=(?!NONE)").containsMatchIn(text)) {
            throw UnsupportedSourceException("Encrypted HLS is not supported")
        }

        val base = finalUrl.toHttpUrlOrNull() ?: throw IOException("Bad playlist URL")
        val segments = parseSegments(text, base)
        if (segments.isEmpty()) throw IOException("Playlist contains no segments")

        val segmentDir = File(target.parentFile, "${target.name}.segments")
        segmentDir.mkdirs()
        val done = AtomicInteger(0)
        val gate = Semaphore(parallelism)

        coroutineScope {
            segments.mapIndexed { index, segment ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val part = File(segmentDir, "%05d.bin".format(index))
                        if (!part.exists() || part.length() == 0L) {
                            currentCoroutineContext().ensureActive()
                            part.writeBytes(fetchBytes(segment.url, headers, segment.range))
                        }
                        onProgress(Progress(done.incrementAndGet(), segments.size))
                    }
                }
            }.awaitAll()
        }

        val initUri = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)

        FileOutputStream(target, false).use { out ->
            initUri?.let { uri -> base.resolve(uri)?.let { out.write(fetchBytes(it, headers, null)) } }
            segments.indices.forEach { index ->
                val part = File(segmentDir, "%05d.bin".format(index))
                part.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
        }

        segmentDir.deleteRecursively()
        target
    }

    private data class Segment(val url: HttpUrl, val range: String?)

    private fun pickVariant(text: String, base: HttpUrl): String? {
        val attribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        var best: String? = null
        var bestBandwidth = -1L
        var attrs: Map<String, String>? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> attrs = attribute
                    .findAll(line.substringAfter(':'))
                    .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val values = attrs ?: continue
                    val bandwidth = values["BANDWIDTH"]?.toLongOrNull() ?: 0L
                    if (bandwidth > bestBandwidth) {
                        bestBandwidth = bandwidth
                        best = base.resolve(line)?.toString()
                    }
                    attrs = null
                }
            }
        }
        return best
    }

    private fun parseSegments(text: String, base: HttpUrl): List<Segment> {
        val out = mutableListOf<Segment>()
        var pendingLength: Long? = null
        var pendingOffset: Long? = null
        var lastEnd = 0L

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val spec = line.substringAfter(':')
                    pendingLength = spec.substringBefore('@').toLongOrNull()
                    pendingOffset = spec.substringAfter('@', "").toLongOrNull()
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val url = base.resolve(line) ?: continue
                    val range = pendingLength?.let { length ->
                        val offset = pendingOffset ?: lastEnd
                        lastEnd = offset + length
                        "bytes=$offset-${offset + length - 1}"
                    }
                    out += Segment(url, range)
                    pendingLength = null
                    pendingOffset = null
                }
            }
        }
        return out
    }

    private fun fetchText(url: String, headers: Map<String, String>): Pair<String, String> {
        val request = Request.Builder().url(url).defaultHeaders(headers).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for playlist")
            val body = response.body?.string() ?: throw IOException("Empty playlist")
            return body to response.request.url.toString()
        }
    }

    private fun fetchBytes(
        url: HttpUrl,
        headers: Map<String, String>,
        range: String?,
    ): ByteArray {
        val request = Request.Builder()
            .url(url)
            .defaultHeaders(headers)
            .apply { range?.let { header("Range", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for segment $url")
            }
            return response.body?.bytes() ?: throw IOException("Empty segment $url")
        }
    }
}
