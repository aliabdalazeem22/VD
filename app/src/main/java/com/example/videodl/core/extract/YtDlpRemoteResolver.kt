package com.example.videodl.core.extract

import com.example.videodl.core.model.Delivery
import com.example.videodl.core.model.MediaInfo
import com.example.videodl.core.model.MediaStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client for a self-hosted yt-dlp resolver. Keeping yt-dlp off-device avoids
 * shipping a ~50 MB Python runtime and keeps extractor updates server-side.
 *
 * Contract: POST {base}/resolve  {"url": "..."}
 * Response: { title, thumbnail, duration,
 *             formats: [{ format_id, url, ext, height, vcodec, acodec,
 *                         filesize, filesize_approx, tbr, format_note,
 *                         http_headers: {...} }] }
 */
class YtDlpRemoteResolver(
    private val client: OkHttpClient,
    baseUrl: String,
) : MediaExtractor {

    private val endpoint: String = baseUrl.trimEnd('/') + "/resolve"

    override fun canHandle(url: String): Boolean = KnownPlatforms.matches(url)

    override suspend fun extract(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .post(JSONObject().put("url", url).toString().toRequestBody("application/json".toMediaType()))
            .build()

        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Resolver returned HTTP ${response.code}")
            }
            JSONObject(response.body?.string() ?: throw IOException("Empty resolver response"))
        }

        val formats = json.optJSONArray("formats") ?: JSONArray()
        val streams = buildList {
            for (index in 0 until formats.length()) {
                val format = formats.getJSONObject(index)
                val vcodec = format.optString("vcodec", "")
                val acodec = format.optString("acodec", "")
                if (vcodec == "none" && acodec == "none") continue

                val audioOnly = vcodec == "none" || vcodec.isEmpty()
                val hasAudio = acodec != "none" && acodec.isNotEmpty()
                val height = format.optInt("height", 0).takeIf { it > 0 }
                val ext = format.optString("ext", "mp4")
                val protocol = format.optString("protocol", "https")

                // Segmented DASH/ISM manifests are not plain files; skip them rather
                // than downloading a manifest and calling it a video.
                if (protocol.contains("dash_segments") || protocol.contains("ism")) continue

                val isHls = protocol.contains("m3u8") || ext.equals("m3u8", ignoreCase = true)
                val headers = buildMap {
                    format.optJSONObject("http_headers")?.let { h ->
                        h.keys().forEach { key -> put(key, h.optString(key)) }
                    }
                }

                add(
                    MediaStream(
                        id = format.optString("format_id", "fmt$index"),
                        url = format.getString("url"),
                        mimeType = if (isHls) "application/vnd.apple.mpegurl" else mimeForExtension(ext, audioOnly),
                        qualityLabel = when {
                            audioOnly -> "${format.optString("format_note").ifEmpty { "audio" }} · audio"
                            height != null && hasAudio -> "${height}p"
                            height != null -> "${height}p · no audio"
                            else -> format.optString("format_note", "video")
                        },
                        height = height,
                        bandwidth = format.optLong("tbr", 0L).takeIf { it > 0 }?.times(1000),
                        contentLength = format.optLong("filesize", 0L).takeIf { it > 0 }
                            ?: format.optLong("filesize_approx", 0L).takeIf { it > 0 },
                        audioOnly = audioOnly,
                        hasAudio = hasAudio || audioOnly,
                        container = ext,
                        delivery = if (isHls) Delivery.HLS else Delivery.PROGRESSIVE,
                        headers = headers,
                    )
                )
            }
        }

        if (streams.isEmpty()) {
            throw UnsupportedSourceException("Resolver returned no downloadable formats")
        }

        MediaInfo(
            sourceUrl = url,
            title = json.optString("title").ifEmpty { titleFromUrl(url) },
            durationSeconds = json.optLong("duration", 0L).takeIf { it > 0 },
            thumbnailUrl = json.optString("thumbnail").ifEmpty { null },
            streams = streams.sortedWith(
                compareBy<MediaStream> { it.audioOnly }
                    .thenByDescending { it.height ?: 0 }
            ),
        )
    }
}
