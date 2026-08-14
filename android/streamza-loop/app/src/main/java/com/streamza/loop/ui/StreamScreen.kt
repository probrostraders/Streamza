package com.streamza.loop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import com.streamza.loop.data.AppRepository
import com.streamza.loop.data.AuthMeResponse
import com.streamza.loop.data.Destination
import com.streamza.loop.data.PickedVideo
import com.streamza.loop.data.resolvePickedVideo
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close

@Composable
fun StreamScreen(viewModel: AppViewModel, liveToken: String?, onGoToLive: () -> Unit) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()

    val defaultLoop by viewModel.defaultLoop.collectAsState()

    if (liveToken != null) {
        AlreadyLiveCard(onGoToLive)
    } else {
        NewStreamForm(repo = repo!!, auth = auth, defaultLoop = defaultLoop, onClaimed = { token ->
            viewModel.onClaimed(token)
            onGoToLive()
        })
    }
}

@Composable
private fun AlreadyLiveCard(onGoToLive: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("New stream", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("You're already live", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Stop your current stream from the Live tab before starting a new one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGoToLive) { Text("Go to Live dashboard") }
            }
        }
    }
}

@Composable
private fun NewStreamForm(repo: AppRepository, auth: AuthMeResponse?, defaultLoop: Boolean, onClaimed: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf<PickedVideo?>(null) }
    var dests by remember { mutableStateOf(listOf(DestinationDraft())) }
    var loop by remember { mutableStateOf(defaultLoop) }
    var agree by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked = resolvePickedVideo(context.contentResolver, uri)
    }

    val canMultistream = auth?.multi == true
    val email = auth?.email.orEmpty()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("New stream", style = MaterialTheme.typography.headlineSmall)

        Section(title = "1. Video") {
            OutlinedButton(onClick = {
                pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }, modifier = Modifier.fillMaxWidth()) {
                Text(picked?.name ?: "Choose a video")
            }
            picked?.let { Text("${it.size / 1024 / 1024} MB", style = MaterialTheme.typography.bodySmall) }
        }

        Section(title = if (dests.size > 1) "2. Destinations" else "2. Destination") {
            dests.forEachIndexed { i, d ->
                DestinationEditor(
                    draft = d,
                    canRemove = dests.size > 1,
                    onChange = { updated -> dests = dests.toMutableList().also { it[i] = updated } },
                    onRemove = { dests = dests.toMutableList().also { it.removeAt(i) } },
                )
            }
            if (canMultistream && dests.size < 3) {
                OutlinedButton(onClick = { dests = dests + DestinationDraft() }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Add another platform")
                }
            } else if (!canMultistream) {
                Text(
                    "Upgrade your plan to stream to multiple platforms at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(title = "3. Options") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = loop, onCheckedChange = { loop = it })
                Text("Loop forever (24/7 channel)", modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agree, onCheckedChange = { agree = it })
                Text(
                    "I own or have the rights to stream this content.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        if (uploading) {
            LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            enabled = !uploading && picked != null && agree && dests.all { it.isValid },
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val video = picked ?: return@Button
                if (email.isBlank()) { error = "Sign in again — your account email is missing."; return@Button }
                uploading = true
                error = null
                scope.launch {
                    val uploadResult = repo.uploadVideo(video) { sent, total ->
                        if (total > 0) uploadProgress = sent.toFloat() / total.toFloat()
                    }
                    uploadResult.onFailure {
                        error = it.message ?: "Upload failed."
                        uploading = false
                        return@launch
                    }
                    val uploadId = uploadResult.getOrNull()?.uploadId
                    if (uploadId == null) {
                        error = "Upload finished but no upload id was returned."
                        uploading = false
                        return@launch
                    }
                    val finalDests = dests.map { Destination(it.resolvedUrl, it.key) }
                    repo.goLive(email, uploadId, finalDests, loop, agree)
                        .onSuccess { start -> onClaimed(start.token!!) }
                        .onFailure { error = it.message ?: "Couldn't go live." }
                    uploading = false
                }
            },
        ) {
            Text("Go Live")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun DestinationEditor(
    draft: DestinationDraft,
    canRemove: Boolean,
    onChange: (DestinationDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(StreamPlatform.entries.toList()) { platform ->
                        FilterChip(
                            selected = draft.platform == platform,
                            onClick = { onChange(draft.copy(platform = platform)) },
                            label = { Text(platform.label) },
                        )
                    }
                }
                if (canRemove) {
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                }
            }
            if (draft.platform == StreamPlatform.Custom) {
                OutlinedTextField(
                    value = draft.customUrl,
                    onValueChange = { onChange(draft.copy(customUrl = it)) },
                    label = { Text("RTMP server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            } else {
                Text(
                    draft.platform.defaultUrl.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = draft.key,
                onValueChange = { onChange(draft.copy(key = it)) },
                label = { Text("Stream key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}
