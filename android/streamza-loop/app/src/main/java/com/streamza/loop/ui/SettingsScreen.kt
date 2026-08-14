package com.streamza.loop.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import kotlinx.coroutines.launch

private const val SUPPORT_EMAIL = "probrostraders@gmail.com"

@Composable
fun SettingsScreen(viewModel: AppViewModel, activity: Activity, onOpenSubscription: () -> Unit) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val themeMode by viewModel.themeMode.collectAsState()
    val defaultLoop by viewModel.defaultLoop.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader("Settings")

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(auth?.email ?: "-", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        auth?.subscribed == true -> "Plan: ${auth?.maxDestinations ?: 1} slot${if ((auth?.maxDestinations ?: 1) == 1) "" else "s"}"
                        auth?.trialAvailable == true -> "Free trial available"
                        else -> "Free trial used"
                    }
                )
            }
        }

        SettingsSection(title = "Appearance") {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = themeMode == value,
                        onClick = { viewModel.setThemeMode(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    ) { Text(label) }
                }
            }
        }

        SettingsSection(title = "Streaming preferences") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Loop by default", style = MaterialTheme.typography.bodyMedium)
                    Text("New streams start with looping switched on", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = defaultLoop, onCheckedChange = { viewModel.setDefaultLoop(it) })
            }
        }

        SettingsSection(title = "Subscription") {
            Text(
                if (auth?.subscribed == true) "You're on the ${auth?.maxDestinations ?: 1}-slot plan."
                else "Buy slots to stream to more platforms at once, with no free-trial limit.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenSubscription, modifier = Modifier.fillMaxWidth()) {
                Text(if (auth?.subscribed == true) "Manage subscription" else "See plans")
            }
        }

        SettingsSection(title = "Support") {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Contact support") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://streamza.live/privacy.html")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Privacy policy") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://streamza.live/terms.html")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Terms of service") }
        }

        Text("Streamza Loop · Version 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedButton(onClick = { scope.launch { repo?.signOut() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}
