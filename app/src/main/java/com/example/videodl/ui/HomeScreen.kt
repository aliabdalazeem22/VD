package com.example.videodl.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.videodl.core.model.DownloadRecord
import com.example.videodl.core.model.DownloadTask
import com.example.videodl.core.model.MediaInfo
import com.example.videodl.core.model.MediaStream
import com.example.videodl.core.model.TaskState
import com.example.videodl.core.model.readableSize
import com.example.videodl.core.model.readableSpeed
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: DownloadViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    RuntimePermissionsEffect()

    val active = tasks.values.filter {
        it.state == TaskState.RUNNING || it.state == TaskState.QUEUED ||
            it.state == TaskState.PAUSED || it.state == TaskState.FAILED
    }

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header() }

            item {
                UrlInput(
                    url = ui.url,
                    analyzing = ui.analyzing,
                    onUrlChange = vm::onUrlChange,
                    onAnalyze = vm::analyze,
                )
            }

            ui.error?.let { message ->
                item { ErrorBanner(message) }
            }

            ui.media?.let { media ->
                item {
                    MediaCard(
                        media = media,
                        selectedStreamId = ui.selectedStreamId,
                        onSelect = vm::select,
                    )
                }
                item {
                    Button(
                        onClick = vm::download,
                        enabled = ui.selectedStream != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download selected quality")
                    }
                }
            }

            if (active.isNotEmpty()) {
                item { SectionTitle("Active") }
                items(active, key = { it.id }) { task ->
                    ActiveTaskCard(
                        task = task,
                        onPause = { vm.pause(task.id) },
                        onCancel = { vm.cancel(task.id) },
                        onRetry = { vm.retry(task.id) },
                        onDismiss = { vm.dismiss(task.id) },
                    )
                }
            }

            if (history.isNotEmpty()) {
                item { SectionTitle("Completed") }
                items(history.reversed(), key = { it.id }) { record ->
                    HistoryCard(record)
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "VideoDL",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Paste a link or share one from another app",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UrlInput(
    url: String,
    analyzing: Boolean,
    onUrlChange: (String) -> Unit,
    onAnalyze: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video link") },
            placeholder = { Text("https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    onAnalyze()
                }
            ),
            trailingIcon = if (url.isNotEmpty()) {
                {
                    TextButton(onClick = { onUrlChange("") }) { Text("Clear") }
                }
            } else {
                null
            },
        )

        Button(
            onClick = {
                focusManager.clearFocus()
                onAnalyze()
            },
            enabled = url.isNotBlank() && !analyzing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (analyzing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (analyzing) "Analyzing…" else "Analyze")
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MediaCard(
    media: MediaInfo,
    selectedStreamId: String?,
    onSelect: (MediaStream) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = media.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${media.streams.size} streams available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            media.streams.forEach { stream ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = stream.id == selectedStreamId,
                            role = Role.RadioButton,
                            onClick = { onSelect(stream) },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = stream.id == selectedStreamId,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stream.qualityLabel, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = listOfNotNull(
                                stream.container.uppercase(),
                                stream.contentLength?.readableSize(),
                                if (stream.audioOnly) "audio only" else null,
                                if (!stream.audioOnly && !stream.hasAudio) "video only" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = task.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine(task),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { task.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (task.state) {
                    TaskState.RUNNING -> {
                        TextButton(onClick = onPause) { Text("Pause") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    TaskState.PAUSED -> {
                        TextButton(onClick = onRetry) { Text("Resume") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    TaskState.FAILED -> {
                        TextButton(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                    TaskState.QUEUED, TaskState.CANCELED -> {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                    else -> Unit
                }
            }
            task.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(record: DownloadRecord) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = record.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${record.quality} · ${DateFormat.getDateTimeInstance().format(Date(record.completedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun statusLine(task: DownloadTask): String {
    val percent = (task.fraction * 100).toInt()
    val segments = task.segmentsTotal
    return if (segments != null) {
        "$percent% · ${task.segmentsDone ?: 0}/$segments segments"
    } else {
        buildString {
            append(percent)
            append("% · ")
            append(task.bytesDownloaded.readableSize())
            task.totalBytes?.let { append(" / ${it.readableSize()}") }
            if (task.bytesPerSecond > 0) append(" · ${task.bytesPerSecond.readableSpeed()}")
        }
    }
}

@Composable
private fun RuntimePermissionsEffect() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        // API 26–28 writes into public Downloads; later versions use MediaStore.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val storageGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!storageGranted) {
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return@LaunchedEffect
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect

        val notificationsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!notificationsGranted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
