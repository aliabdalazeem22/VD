package com.example.videodl.core.extract

import java.net.URLDecoder

internal fun titleFromUrl(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val name = path.substringAfterLast('/').ifEmpty { "video" }
    val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
    return decoded
        .substringBeforeLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifEmpty { "video" }
}

internal fun mimeForExtension(ext: String, audioOnly: Boolean): String = when (ext.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> if (audioOnly) "audio/webm" else "video/webm"
    "mkv" -> "video/x-matroska"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "ogg", "opus" -> "audio/ogg"
    "ts" -> "video/mp2t"
    else -> if (audioOnly) "audio/mp4" else "video/mp4"
}
