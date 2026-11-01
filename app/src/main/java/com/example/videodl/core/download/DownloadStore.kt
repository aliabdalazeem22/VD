package com.example.videodl.core.download

import android.content.Context
import com.example.videodl.core.model.DownloadRecord
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Tiny JSON-backed history. Room is overkill for a capped 200-row list. */
class DownloadStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<DownloadRecord>> = _history.asStateFlow()

    fun record(record: DownloadRecord) {
        _history.update { current -> (current + record).takeLast(MAX_RECORDS) }
        persist()
    }

    private fun load(): List<DownloadRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                add(
                    DownloadRecord(
                        id = json.getString("id"),
                        title = json.optString("title"),
                        fileName = json.optString("fileName"),
                        uri = json.optString("uri"),
                        quality = json.optString("quality"),
                        completedAt = json.optLong("completedAt"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun persist() = runCatching {
        val array = JSONArray()
        _history.value.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("title", record.title)
                    .put("fileName", record.fileName)
                    .put("uri", record.uri)
                    .put("quality", record.quality)
                    .put("completedAt", record.completedAt)
            )
        }
        file.writeText(array.toString())
    }

    private companion object {
        const val MAX_RECORDS = 200
    }
}
