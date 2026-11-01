package com.example.videodl.core

import android.content.Context
import com.example.videodl.BuildConfig
import com.example.videodl.core.download.ChunkedDownloader
import com.example.videodl.core.download.DownloadEngine
import com.example.videodl.core.download.DownloadStore
import com.example.videodl.core.download.HlsDownloader
import com.example.videodl.core.download.MediaSink
import com.example.videodl.core.extract.DirectExtractor
import com.example.videodl.core.extract.ExtractorRegistry
import com.example.videodl.core.extract.HlsExtractor
import com.example.videodl.core.extract.YtDlpRemoteResolver
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Manual DI: one engine per process, no reflection cost, no Hilt. */
object ServiceLocator {

    @Volatile
    private var engineRef: DownloadEngine? = null

    fun engine(context: Context): DownloadEngine =
        engineRef ?: synchronized(this) {
            engineRef ?: create(context.applicationContext).also { engineRef = it }
        }

    private fun create(context: Context): DownloadEngine {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val hlsExtractor = HlsExtractor(client)
        val directExtractor = DirectExtractor(client, hlsExtractor)
        val remoteResolver = BuildConfig.YTDLP_BASE_URL
            .takeIf { it.isNotBlank() }
            ?.let { baseUrl -> YtDlpRemoteResolver(client, baseUrl) }

        return DownloadEngine(
            registry = ExtractorRegistry(remoteResolver, hlsExtractor, directExtractor),
            chunked = ChunkedDownloader(client, parallelism = 4),
            hls = HlsDownloader(client, parallelism = 4),
            sink = MediaSink(context),
            store = DownloadStore(context),
            workDir = File(context.cacheDir, "downloads"),
        )
    }
}
