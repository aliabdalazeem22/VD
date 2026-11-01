package com.example.videodl.core.extract

/** Hosts whose pages need a server-side extractor; a raw GET returns HTML. */
object KnownPlatforms {
    private val hosts = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com",
        "fb.watch", "twitter.com", "x.com", "vimeo.com", "dailymotion.com",
        "reddit.com", "twitch.tv", "soundcloud.com",
    )

    fun matches(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }
}
