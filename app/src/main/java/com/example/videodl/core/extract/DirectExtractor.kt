package com.example.videodl.core.extract

import com.example.videodl.core.model.Delivery
import com.example.videodl.core.model.MediaInfo
import com.example.videodl.core.model.MediaStream
import com.example.videodl.core.model.readableSize
import com.example.videodl.core.net.defaultHeaders
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Handles a URL that is already a media resource (mp4/webm/m4a/mp3…) by probing
 * Content-Type and Content-Length, and delegating playlists to [HlsExtractor].
 */
class DirectExtractor(
    private val client: OkHttpClient,
    private val hls: HlsExtractor,
) : MediaExtractor {

    override fun canHandle(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    override suspend fun extract(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val probe = probe(url)
        if (probe.isHls) return@withContext hls.extract(probe.finalUrl)

        val mime = probe.contentType.substringBefore(';').trim().lowercase()
        val playable = mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime == "application/octet-stream" ||
            mime == "binary/octet-stream"

        if (!playable) {
            throw UnsupportedSourceException(
                "That link returns ${mime.ifEmpty { "unknown content" }}, not a media stream. " +
                    "Web pages need the yt-dlp resolver; direct files and .m3u8 work without it."
            )
        }

        val audioOnly = mime.startsWith("audio/")
        val container = when {
            mime.contains("mp4") -> if (audioOnly) "m4a" else "mp4"
            mime.contains("webm") -> "webm"
            audioOnly && mime.contains("mpeg") -> "mp3"
            mime.contains("matroska") -> "mkv"
            mime.contains("ogg") -> "ogg"
            mime.contains("mp2t") -> "ts"
            else -> "bin"
        }

        val stream = MediaStream(
            id = "direct",
            url = probe.finalUrl,
            mimeType = mime.ifEmpty { "application/octet-stream" },
            qualityLabel = if (audioOnly) {
                "audio"
            } else {
                probe.length?.let { "source · ${it.readableSize()}" } ?: "source"
            },
            contentLength = probe.length,
            audioOnly = audioOnly,
            container = container,
            delivery = Delivery.PROGRESSIVE,
            headers = mapOf("Referer" to url),
        )

        MediaInfo(
            sourceUrl = url,
            title = titleFromUrl(probe.finalUrl),
            streams = listOf(stream),
        )
    }

    private data class Probe(
        val finalUrl: String,
        val contentType: String,
        val length: Long?,
        val isHls: Boolean,
    )

    private fun probe(url: String): Probe {
        runCatching {
            client.newCall(
                Request.Builder().url(url).head().defaultHeaders(emptyMap()).build()
            ).execute()
        }.getOrNull()?.use { response ->
            val type = response.header("Content-Type").orEmpty()
            val length = response.header("Content-Length")?.toLongOrNull()
            val finalUrl = response.request.url.toString()
            val isHls = type.contains("mpegurl", ignoreCase = true) ||
                finalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            if (response.isSuccessful && (type.isNotEmpty() || length != null)) {
                return Probe(finalUrl, type, length, isHls)
            }
        }

        // HEAD blocked by some CDNs: ask for a single byte instead.
        client.newCall(
            Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .defaultHeaders(emptyMap())
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while probing the link")
            }
            val type = response.header("Content-Type").orEmpty()
            val total = response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
            val finalUrl = response.request.url.toString()
            return Probe(
                finalUrl = finalUrl,
                contentType = type,
                length = total,
                isHls = type.contains("mpegurl", ignoreCase = true) ||
                    finalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true),
            )
        }
    }
}
