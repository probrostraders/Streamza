package com.streamza.loop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(viewModel: AppViewModel) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineSmall)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(auth?.email ?: "-", style = MaterialTheme.typography.titleMedium)
                Text(if (auth?.subscribed == true) "Plan: ${auth?.tier ?: "subscribed"}" else "Plan: Free")
            }
        }

        // Placeholder until Phase 3 (Google Play Billing) — Web Studio is free right now anyway
        // (see server.js tierOf(), hardcoded during this beta), so there's nothing to sell yet.
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subscription", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Everything is free right now, on the app and the website.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        OutlinedButton(onClick = { scope.launch { repo?.signOut() } }) {
            Text("Sign out")
        }
    }
}
