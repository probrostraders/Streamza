package com.streamza.loop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import com.streamza.loop.data.AppRepository
import com.streamza.loop.data.StatusResponse
import com.streamza.loop.ui.theme.StreamzaRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 3000L

@Composable
fun LiveScreen(viewModel: AppViewModel, liveToken: String?, onGoToStream: () -> Unit, onGoToSubscription: () -> Unit) {
    val repo by viewModel.repo.collectAsState()
    (repo ?: return)

    if (liveToken == null) {
        EmptyLiveState(onGoToStream)
    } else {
        LiveDashboard(repo = repo!!, token = liveToken, onStopped = viewModel::onStopped, onGoToSubscription = onGoToSubscription)
    }
}

@Composable
private fun EmptyLiveState(onGoToStream: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("You're not live right now", style = MaterialTheme.typography.titleMedium)
            Text(
                "Start a stream and its live dashboard — uptime, destinations, and diagnostics — shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGoToStream) { Text("Start a stream") }
        }
    }
}

@Composable
private fun LiveDashboard(repo: AppRepository, token: String, onStopped: () -> Unit, onGoToSubscription: () -> Unit) {
    var status by remember { mutableStateOf<StatusResponse?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var wasTrial by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token) {
        while (true) {
            val res = repo.status(token).getOrNull()
            status = res
            if (res != null && res.running) wasTrial = res.trial
            if (res == null || !res.running) {
                onStopped()
                // The 15-minute trial just ran out — take them straight to Subscribe instead of
                // leaving them staring at an empty dashboard wondering why it stopped.
                if (wasTrial) onGoToSubscription()
                break
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    val s = status
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = StreamzaRed, modifier = Modifier.size(10.dp)) {}
            Text("Live", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (s.trial) {
                Surface(color = StreamzaRed.copy(alpha = 0.15f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                    Text(
                        "FREE TRIAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = StreamzaRed,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(s.file ?: "video.mp4", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Uptime", fmt(s.uptime ?: 0), Modifier.weight(1f))
            StatCard("Remaining", fmt(s.secondsLeft ?: 0), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Destinations", "${s.dests?.size ?: 1}", Modifier.weight(1f))
            StatCard("Loop", if (s.loop) "On" else "Off", Modifier.weight(1f))
        }

        val dests = s.dests
        if (!dests.isNullOrEmpty()) {
            Text("Streaming to", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dests.forEachIndexed { i, d ->
                        Text("• $d", style = MaterialTheme.typography.bodySmall)
                        if (i != dests.lastIndex) Divider()
                    }
                }
            }
        }

        val log = s.log
        if (!log.isNullOrEmpty()) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    log.takeLast(8).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Button(
            enabled = !stopping,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            onClick = {
                stopping = true
                scope.launch {
                    repo.stopStream(token)
                    stopping = false
                    onStopped()
                }
            },
        ) { Text(if (stopping) "Stopping…" else "Go offline") }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmt(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
