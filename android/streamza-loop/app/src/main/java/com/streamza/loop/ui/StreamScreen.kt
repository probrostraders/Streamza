package com.streamza.loop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamza.loop.AppViewModel
import com.streamza.loop.UploadState
import com.streamza.loop.data.AppRepository
import com.streamza.loop.data.AuthMeResponse
import com.streamza.loop.data.Destination
import com.streamza.loop.data.StartException
import com.streamza.loop.data.StreamKeyStore
import com.streamza.loop.data.formatFileSize
import com.streamza.loop.data.resolvePickedVideo
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close

@Composable
fun StreamScreen(viewModel: AppViewModel, liveToken: String?, onGoToLive: () -> Unit, onGoToSubscription: () -> Unit) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()

    val defaultLoop by viewModel.defaultLoop.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()

    if (liveToken != null) {
        AlreadyLiveCard(onGoToLive)
    } else {
        NewStreamForm(
            repo = repo!!, auth = auth, defaultLoop = defaultLoop, uploadState = uploadState,
            onPickVideo = viewModel::pickVideo,
            onClearVideo = viewModel::clearPickedVideo,
            onClaimed = { token -> viewModel.onGoneLive(); viewModel.onClaimed(token); onGoToLive() },
            onGoToSubscription = onGoToSubscription,
        )
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
private fun NewStreamForm(
    repo: AppRepository,
    auth: AuthMeResponse?,
    defaultLoop: Boolean,
    uploadState: UploadState,
    onPickVideo: (com.streamza.loop.data.PickedVideo) -> Unit,
    onClearVideo: () -> Unit,
    onClaimed: (String) -> Unit,
    onGoToSubscription: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dests by remember { mutableStateOf(listOf(DestinationDraft())) }
    var loop by remember { mutableStateOf(defaultLoop) }
    var agree by remember { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPickVideo(resolvePickedVideo(context.contentResolver, uri))
    }

    val maxDests = auth?.maxDestinations ?: 1
    val canMultistream = maxDests > 1
    val email = auth?.email.orEmpty()

    // Trim any extra destination rows if the plan's cap just shrank (e.g. auth refreshed after signup).
    LaunchedEffect(maxDests) { if (dests.size > maxDests) dests = dests.take(maxDests.coerceAtLeast(1)) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("New stream", style = MaterialTheme.typography.headlineSmall)

        if (auth?.trialAvailable == true) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("This stream is free", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "20 minutes, one platform, no card needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Section(title = "1. Video") {
            val picked = uploadState.picked
            if (picked == null) {
                OutlinedButton(onClick = {
                    pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose a video")
                }
            } else {
                Card(
                    onClick = { pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                model = picked.uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (uploadState.uploading) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(picked.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                when {
                                    uploadState.error != null -> uploadState.error
                                    uploadState.uploading -> "Uploading… ${(uploadState.progress * 100).toInt()}%"
                                    uploadState.uploadId != null -> "Uploaded, ready to go · ${formatFileSize(picked.size)}"
                                    else -> formatFileSize(picked.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uploadState.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onClearVideo) { Icon(Icons.Default.Close, contentDescription = "Remove video") }
                    }
                }
                if (uploadState.uploading) {
                    LinearProgressIndicator(progress = { uploadState.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
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
            if (dests.size < maxDests) {
                OutlinedButton(onClick = { dests = dests + DestinationDraft() }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Add another platform")
                }
            } else if (!canMultistream) {
                Text(
                    "Buy more slots to stream to multiple platforms at once.",
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

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            enabled = uploadState.ready && !claiming && agree && dests.all { it.isValid },
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val uploadId = uploadState.uploadId ?: return@Button
                if (email.isBlank()) { error = "Sign in again — your account email is missing."; return@Button }
                claiming = true
                error = null
                scope.launch {
                    val finalDests = dests.map { Destination(it.resolvedUrl, it.key) }
                    repo.goLive(email, uploadId, finalDests, loop, agree)
                        .onSuccess { start -> onClaimed(start.token!!) }
                        .onFailure {
                            if (it is StartException && it.trialExhausted) onGoToSubscription()
                            else error = it.message ?: "Couldn't go live."
                        }
                    claiming = false
                }
            },
        ) {
            Text(if (claiming) "Going live…" else "Go Live")
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyStore = remember { StreamKeyStore(context) }

    // Auto-fill this platform's remembered key (and URL, for Custom) the moment it's selected — the
    // platform-switch handler below always clears key/customUrl first, so this never leaks one
    // platform's saved key into another's field.
    LaunchedEffect(draft.platform) {
        if (draft.key.isBlank()) {
            val savedKey = keyStore.savedKey(draft.platform.name)
            if (savedKey.isNotBlank()) onChange(draft.copy(key = savedKey))
        }
        if (draft.platform == StreamPlatform.Custom && draft.customUrl.isBlank()) {
            val savedUrl = keyStore.savedUrl(draft.platform.name)
            if (savedUrl.isNotBlank()) onChange(draft.copy(customUrl = savedUrl))
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(StreamPlatform.entries.toList()) { platform ->
                        FilterChip(
                            selected = draft.platform == platform,
                            onClick = { onChange(draft.copy(platform = platform, key = "", customUrl = "")) },
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
                    onValueChange = { v ->
                        onChange(draft.copy(customUrl = v))
                        scope.launch { keyStore.save(draft.platform.name, draft.key, v) }
                    },
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
                onValueChange = { v ->
                    onChange(draft.copy(key = v))
                    scope.launch { keyStore.save(draft.platform.name, v) }
                },
                label = { Text("Stream key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}
