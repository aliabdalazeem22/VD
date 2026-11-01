package com.example.videodl.core.net

import okhttp3.Request

object HttpDefaults {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}

/** Applies a browser-like User-Agent plus any per-stream headers (Referer, Cookie…). */
fun Request.Builder.defaultHeaders(headers: Map<String, String>): Request.Builder = apply {
    header("User-Agent", headers["User-Agent"] ?: HttpDefaults.USER_AGENT)
    header("Accept", headers["Accept"] ?: "*/*")
    headers.forEach { (key, value) ->
        if (!key.equals("User-Agent", ignoreCase = true) &&
            !key.equals("Accept", ignoreCase = true)
        ) {
            header(key, value)
        }
    }
}
