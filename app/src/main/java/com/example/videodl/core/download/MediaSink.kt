package com.example.videodl.core.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** Publishes a finished file into the public Downloads collection. */
class MediaSink(private val context: Context) {

    fun publish(source: File, displayName: String, mimeType: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishScoped(source, displayName, mimeType)
        } else {
            publishLegacy(source, displayName, mimeType)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishScoped(source: File, displayName: String, mimeType: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VideoDL")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore rejected the insert")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            } ?: throw IOException("Cannot open MediaStore output")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(source: File, displayName: String, mimeType: String): Uri {
        // API 26–28: public Downloads needs WRITE_EXTERNAL_STORAGE. If the user
        // denied it (or never saw the prompt), fall back to the app's own folder
        // instead of failing the whole download.
        val canWritePublic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

        val baseDir = if (canWritePublic) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        }

        val dir = File(baseDir, "VideoDL").apply { mkdirs() }

        var target = File(dir, displayName)
        var suffix = 1
        while (target.exists()) {
            val base = displayName.substringBeforeLast('.', displayName)
            val ext = displayName.substringAfterLast('.', "")
            target = File(dir, if (ext.isEmpty()) "$base ($suffix)" else "$base ($suffix).$ext")
            suffix++
        }

        source.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return Uri.fromFile(target)
    }
}
