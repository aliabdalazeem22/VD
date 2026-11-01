package com.example.videodl.core.extract

import com.example.videodl.core.model.Delivery
import com.example.videodl.core.model.MediaInfo
import com.example.videodl.core.model.MediaStream
import com.example.videodl.core.net.defaultHeaders
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads an HLS master playlist and exposes one selectable stream per variant.
 * Encrypted playlists (#EXT-X-KEY with a real METHOD) are rejected on purpose:
 * that path is DRM-adjacent and not implemented here.
 */
class HlsExtractor(private val client: OkHttpClient) : MediaExtractor {

    override fun canHandle(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    override suspend fun extract(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val (text, finalUrl) = fetchPlaylist(url)
        val base = finalUrl.toHttpUrlOrNull()
            ?: throw IOException("Playlist URL is not a valid HTTP URL")

        if (text.contains("#EXT-X-STREAM-INF")) {
            val variants = parseVariants(text, base)
            if (variants.isEmpty()) {
                throw UnsupportedSourceException("Master playlist advertised no variants")
            }
            MediaInfo(
                sourceUrl = finalUrl,
                title = titleFromUrl(finalUrl),
                streams = variants,
            )
        } else {
            val segments = text.lineSequence().count { it.startsWith("#EXTINF") }
            val isFmp4 = text.contains("#EXT-X-MAP")
            MediaInfo(
                sourceUrl = finalUrl,
                title = titleFromUrl(finalUrl),
                streams = listOf(
                    MediaStream(
                        id = "hls-auto",
                        url = finalUrl,
                        mimeType = if (isFmp4) "video/mp4" else "video/mp2t",
                        qualityLabel = "auto · $segments segments",
                        container = if (isFmp4) "mp4" else "ts",
                        delivery = Delivery.HLS,
                    )
                ),
            )
        }
    }

    fun fetchPlaylist(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).defaultHeaders(emptyMap()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while fetching playlist")
            }
            val body = response.body?.string() ?: throw IOException("Empty playlist")
            if (!body.contains("#EXTM3U")) {
                throw UnsupportedSourceException("Response is not an HLS playlist")
            }
            if (Regex("#EXT-X-KEY:METHOD=(?!NONE)").containsMatchIn(body)) {
                throw UnsupportedSourceException(
                    "This playlist is encrypted (#EXT-X-KEY). Encrypted/DRM streams are not supported."
                )
            }
            return body to response.request.url.toString()
        }
    }

    private fun parseVariants(text: String, base: HttpUrl): List<MediaStream> {
        val out = mutableListOf<MediaStream>()
        var attributes: Map<String, String>? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    attributes = parseAttributes(line.substringAfter(':'))
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val attrs = attributes ?: continue
                    val uri = base.resolve(line) ?: continue
                    val height = attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()
                    val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
                    val codecs = attrs["CODECS"].orEmpty()
                    val mp4 = codecs.contains("avc1") || codecs.contains("hvc1") ||
                        codecs.contains("hev1") || codecs.contains("mp4a") ||
                        codecs.contains("av01")
                    // No CODECS attribute means "assume video"; a codec list with
                    // only audio entries describes an audio rendition.
                    val videoCodecs = listOf("avc1", "hvc1", "hev1", "av01", "vp09", "vp8", "vp9")
                    val hasVideo = codecs.isEmpty() || videoCodecs.any { codecs.contains(it) }
                    val audioOnly = !hasVideo

                    out += MediaStream(
                        id = uri.toString(),
                        url = uri.toString(),
                        mimeType = when {
                            audioOnly && mp4 -> "audio/mp4"
                            mp4 -> "video/mp4"
                            else -> "video/mp2t"
                        },
                        qualityLabel = when {
                            audioOnly -> "audio · ${bandwidth?.div(1000) ?: "?"} kbps"
                            height != null -> "${height}p"
                            bandwidth != null -> "${bandwidth / 1000} kbps"
                            else -> "variant"
                        },
                        height = height,
                        bandwidth = bandwidth,
                        audioOnly = audioOnly,
                        container = if (mp4) "mp4" else "ts",
                        delivery = Delivery.HLS,
                    )
                    attributes = null
                }
            }
        }
        return out.distinctBy { it.url }.sortedByDescending { it.height ?: 0 }
    }

    private fun parseAttributes(input: String): Map<String, String> =
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
            .findAll(input)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2].trim('"')
            }
}
