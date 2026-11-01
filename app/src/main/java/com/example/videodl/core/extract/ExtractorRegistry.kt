package com.example.videodl.core.extract

import com.example.videodl.core.model.MediaInfo

class ExtractorRegistry(
    private val remote: YtDlpRemoteResolver?,
    private val hls: HlsExtractor,
    private val direct: DirectExtractor,
) {
    suspend fun extract(raw: String): MediaInfo {
        val url = normalize(raw)
        if (KnownPlatforms.matches(url)) {
            return remote?.extract(url) ?: throw UnsupportedSourceException(
                "${hostOf(url)} links need the yt-dlp resolver. " +
                    "Set YTDLP_BASE_URL in app/build.gradle.kts (see README). " +
                    "Direct media URLs and .m3u8 playlists work without it."
            )
        }
        return when {
            hls.canHandle(url) -> hls.extract(url)
            direct.canHandle(url) -> direct.extract(url)
            else -> throw UnsupportedSourceException("Unsupported link: $url")
        }
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: "This platform"
}
