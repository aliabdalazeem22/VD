package com.example.videodl.core.extract

import com.example.videodl.core.model.MediaInfo

interface MediaExtractor {
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): MediaInfo
}

/** Raised when a link is valid but this build cannot turn it into streams. */
class UnsupportedSourceException(message: String) : Exception(message)
