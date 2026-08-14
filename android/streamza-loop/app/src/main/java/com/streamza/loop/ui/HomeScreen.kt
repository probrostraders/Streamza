package com.streamza.loop.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import com.streamza.loop.data.StatusResponse
import com.streamza.loop.ui.theme.StreamzaRed

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    liveToken: String?,
    onGoToStream: () -> Unit,
    onGoToLive: () -> Unit,
    onGoToVideos: () -> Unit,
    onGoToSettings: () -> Unit,
    onGoToSubscription: () -> Unit,
) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()
    var status by remember { mutableStateOf<StatusResponse?>(null) }

    LaunchedEffect(liveToken) {
        status = if (liveToken != null) repo?.status(liveToken)?.getOrNull() else null
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppLogo(size = 40.dp)
                Text("Streamza Loop", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "Welcome back${auth?.name?.let { ", $it" } ?: ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val subscribed = auth?.subscribed == true
        val trialAvailable = auth?.trialAvailable == true
        val canStartFree = subscribed || trialAvailable

        if (liveToken != null) {
            LiveStatusCard(status = status, onClick = onGoToLive)
        } else if (canStartFree) {
            Card(
                onClick = onGoToStream,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StreamzaRed),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ready to go live?", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        if (subscribed) "Pick a video, choose where it streams, and go — Streamza keeps it running even after you close the app."
                        else "Your first stream is free for 15 minutes, no card needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                    )
                    Button(
                        onClick = onGoToStream,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White, contentColor = StreamzaRed),
                    ) { Text("Start streaming") }
                }
            }
        } else {
            Card(
                onClick = onGoToSubscription,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StreamzaRed),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your free trial is over", style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Subscribe to keep streaming — pick how many platforms you need and how often you're billed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                    )
                    Button(
                        onClick = onGoToSubscription,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White, contentColor = StreamzaRed),
                    ) { Text("See plans") }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction(Icons.Default.CloudQueue, "My Videos", Modifier.weight(1f)) { onGoToVideos() }
            QuickAction(
                if (subscribed) Icons.Default.Hub else Icons.Default.FlashOn,
                when {
                    subscribed -> "Plan: ${auth?.maxDestinations ?: 1} slot${if ((auth?.maxDestinations ?: 1) == 1) "" else "s"}"
                    trialAvailable -> "Free trial"
                    else -> "Subscribe"
                },
                Modifier.weight(1f),
            ) { onGoToSettings() }
        }

        Text("What you get", style = MaterialTheme.typography.titleMedium)

        FeatureCard(
            icon = Icons.Default.AllInclusive,
            title = "24/7 looping",
            body = "Upload once and let it play on repeat around the clock — no laptop, no OBS, nothing left running on your end.",
        )
        FeatureCard(
            icon = Icons.Default.Hub,
            title = if ((auth?.maxDestinations ?: 1) > 1) "Multistream, unlocked" else "Multistream to several platforms",
            body = if ((auth?.maxDestinations ?: 1) > 1)
                "Your plan streams to ${auth?.maxDestinations} platforms at the same time from one upload."
            else
                "Buy more slots to send one stream to YouTube, Facebook, and Twitch at once.",
        )
        FeatureCard(
            icon = Icons.Default.CloudQueue,
            title = "Cloud video library",
            body = "Videos you stream are saved for reuse — go live again without re-uploading.",
        )
    }
}

@Composable
private fun LiveStatusCard(status: StatusResponse?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = StreamzaRed, modifier = Modifier.size(12.dp)) {}
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You're live", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    status?.file ?: "Streaming now — tap to view the dashboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = StreamzaRed)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
