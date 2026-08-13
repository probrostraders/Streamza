package com.streamza.loop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import com.streamza.loop.data.AuthMeResponse
import com.streamza.loop.data.Destination
import com.streamza.loop.data.PickedVideo
import com.streamza.loop.data.StatusResponse
import com.streamza.loop.data.resolvePickedVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 3000L

@Composable
fun StreamScreen(viewModel: AppViewModel) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()
    val justClaimed by viewModel.justClaimedToken.collectAsState()
    val liveToken = justClaimed ?: auth?.slot?.token

    if (liveToken != null) {
        LiveView(repo = repo!!, token = liveToken, onStopped = viewModel::onStopped)
    } else {
        NewStreamForm(repo = repo!!, auth = auth, onClaimed = viewModel::onClaimed)
    }
}

@Composable
private fun NewStreamForm(repo: com.streamza.loop.data.AppRepository, auth: AuthMeResponse?, onClaimed: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf<PickedVideo?>(null) }
    var dests by remember { mutableStateOf(listOf(Destination("", ""))) }
    var loop by remember { mutableStateOf(true) }
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("New stream", style = MaterialTheme.typography.headlineSmall)

        OutlinedButton(onClick = {
            pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }) {
            Text(picked?.name ?: "Choose a video")
        }
        picked?.let { Text("${it.size / 1024 / 1024} MB", style = MaterialTheme.typography.bodySmall) }

        Text("Destination${if (dests.size > 1) "s" else ""}", style = MaterialTheme.typography.titleMedium)
        dests.forEachIndexed { i, d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = d.url,
                    onValueChange = { v -> dests = dests.toMutableList().also { it[i] = it[i].copy(url = v) } },
                    label = { Text("RTMP server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = d.key,
                    onValueChange = { v -> dests = dests.toMutableList().also { it[i] = it[i].copy(key = v) } },
                    label = { Text("Stream key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
        if (canMultistream && dests.size < 3) {
            OutlinedButton(onClick = { dests = dests + Destination("", "") }) {
                Text("Add another platform")
            }
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = loop, onCheckedChange = { loop = it })
            Text("Loop forever (24/7 channel)", modifier = Modifier.padding(start = 8.dp))
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = agree, onCheckedChange = { agree = it })
            Text(
                "I own or have the rights to stream this content.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (uploading) {
            LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            enabled = !uploading && picked != null && agree && dests.all { it.url.isNotBlank() },
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
                    repo.goLive(email, uploadId, dests.filter { it.url.isNotBlank() }, loop, agree)
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
private fun LiveView(repo: com.streamza.loop.data.AppRepository, token: String, onStopped: () -> Unit) {
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token) {
        while (true) {
            val res = repo.status(token).getOrNull()
            status = res
            if (res == null || !res.running) {
                onStopped()
                break
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("You're live", style = MaterialTheme.typography.headlineSmall)
        val s = status
        if (s == null) {
            CircularProgressIndicator()
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.file ?: "video.mp4", style = MaterialTheme.typography.titleMedium)
                    Text("Slot ${s.slot ?: "-"} · ${(s.dests?.size ?: 1)} platform(s)")
                    Text("Live for ${fmt(s.uptime ?: 0)}")
                    Text("Remaining: ${fmt(s.secondsLeft ?: 0)}")
                }
            }
            Button(
                enabled = !stopping,
                onClick = {
                    stopping = true
                    scope.launch {
                        repo.stopStream(token)
                        stopping = false
                        onStopped()
                    }
                },
            ) { Text(if (stopping) "Stopping…" else "Stop stream") }
        }
    }
}

private fun fmt(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
