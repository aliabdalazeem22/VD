package com.example.videodl.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videodl.core.ServiceLocator
import com.example.videodl.core.download.DownloadService
import com.example.videodl.core.model.MediaInfo
import com.example.videodl.core.model.MediaStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val url: String = "",
    val analyzing: Boolean = false,
    val media: MediaInfo? = null,
    val selectedStreamId: String? = null,
    val error: String? = null,
) {
    val selectedStream: MediaStream?
        get() = media?.streams?.firstOrNull { it.id == selectedStreamId }
}

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = ServiceLocator.engine(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val tasks = engine.tasks
    val history = engine.history

    fun onUrlChange(value: String) = _ui.update { it.copy(url = value, error = null) }

    fun select(stream: MediaStream) = _ui.update { it.copy(selectedStreamId = stream.id) }

    fun analyze() {
        val url = _ui.value.url.trim()
        if (url.isEmpty() || _ui.value.analyzing) return

        viewModelScope.launch {
            _ui.update {
                it.copy(analyzing = true, error = null, media = null, selectedStreamId = null)
            }
            try {
                val info = engine.analyze(url)
                _ui.update {
                    it.copy(
                        analyzing = false,
                        media = info,
                        selectedStreamId = info.streams
                            .firstOrNull { stream -> !stream.audioOnly }
                            ?.id
                            ?: info.streams.firstOrNull()?.id,
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        analyzing = false,
                        error = t.message ?: "Could not analyze this link",
                    )
                }
            }
        }
    }

    fun download() {
        val state = _ui.value
        val media = state.media ?: return
        val stream = state.selectedStream ?: return

        val extension = stream.container.ifBlank { if (stream.audioOnly) "m4a" else "mp4" }
        val fileName = "${sanitize(media.title)}.$extension"
        val taskId = engine.enqueue(media, stream, fileName)
        DownloadService.start(getApplication<Application>(), taskId)
    }

    /** Pulls the first URL out of a shared post ("Watch this: https://…"). */
    fun onSharedText(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value
            ?.trimEnd('.', ',', ')', ']', '"', '\'')
            ?: return
        _ui.update { it.copy(url = url) }
        analyze()
    }

    fun cancel(id: String) = engine.cancel(id)
    fun pause(id: String) = engine.pause(id)
    fun retry(id: String) = engine.retry(id)
    fun dismiss(id: String) = engine.dismiss(id)

    private fun sanitize(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifEmpty { "video" }
}
