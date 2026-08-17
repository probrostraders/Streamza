package com.streamza.loop.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import com.streamza.loop.billing.BillingManager
import com.streamza.loop.billing.PeriodOffer
import com.streamza.loop.billing.SlotPlan
import com.streamza.loop.ui.theme.StreamzaRed
import kotlinx.coroutines.launch

@Composable
fun SubscriptionScreen(viewModel: AppViewModel, billingManager: BillingManager, activity: Activity, onBack: () -> Unit) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val plans by billingManager.plans.collectAsState()
    val connected by billingManager.connected.collectAsState()
    var billingEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(connected) {
        val cfg = repo?.billingConfig()?.getOrNull()
        billingEnabled = cfg?.enabled ?: false
        val ids = cfg?.products?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }?.toMap().orEmpty()
        if (connected && ids.isNotEmpty()) billingManager.queryProducts(ids)
    }

    var selectedSlots by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(plans) {
        if (selectedSlots == null && plans.isNotEmpty()) selectedSlots = plans.first().slots
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.width(8.dp))
            Text("Subscription", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (auth?.subscribed == true) {
                ActiveSubscriptionCard(slots = auth?.maxDestinations ?: 1, portal = auth?.portal)
            } else if (auth?.trialAvailable == true) {
                Surface(
                    color = StreamzaRed,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Streaming is free right now", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Every stream is free for 20 minutes on one platform, no card needed. Subscribe for longer sessions and more platforms at once.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }

            if (!billingEnabled) {
                SubscriptionPreview()
            } else if (plans.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text("Streaming slots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Number of platforms you can stream to at the same time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    plans.forEach { plan ->
                        FilterChip(
                            selected = selectedSlots == plan.slots,
                            onClick = { selectedSlots = plan.slots },
                            label = { Text("${plan.slots}") },
                        )
                    }
                }

                val activePlan = plans.firstOrNull { it.slots == selectedSlots }
                activePlan?.offers?.forEachIndexed { i, offer ->
                    PeriodCard(
                        plan = activePlan,
                        offer = offer,
                        highlighted = offer.period == "P1Y",
                        onChoose = { billingManager.launchPurchase(activity, activePlan, offer) },
                    )
                }
            }

            OutlinedButton(
                onClick = { scope.launch { billingManager.restorePurchases() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore purchases") }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActiveSubscriptionCard(slots: Int, portal: String?) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = StreamzaRed)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Active subscription", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
            Text(
                "$slots streaming ${if (slots == 1) "slot" else "slots"}",
                style = MaterialTheme.typography.headlineSmall,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Bold,
            )
            if (!portal.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(portal)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "Couldn't open the subscription page.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Manage subscription", color = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

/** Shown while Play Billing isn't configured yet (no real prices to query) — explains the plan
 *  structure itself instead of leaving the screen looking broken/empty. No prices are shown here;
 *  those come from Play Console once billing is live, never hardcoded in the app. */
@Composable
private fun SubscriptionPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subscriptions are coming soon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Streaming is free for now. Here's how paid plans will work once they're switched on:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        listOf(
            1 to "Stream to 1 platform at once",
            2 to "Stream to 2 platforms at once, at the same time",
            3 to "Stream to 3 platforms at once, at the same time",
        ).forEach { (slots, desc) ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = RoundedCornerShape(50), color = StreamzaRed.copy(alpha = 0.15f)) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Text("$slots", color = StreamzaRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Column {
                        Text("$slots ${if (slots == 1) "Slot" else "Slots"}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("How billing works", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FeatureLine("Pick weekly, monthly, or yearly billing for any plan")
                FeatureLine("Managed entirely through Google Play — cancel anytime")
                FeatureLine("One subscription unlocks the app on any device you sign into")
            }
        }
    }
}

@Composable
private fun PeriodCard(plan: SlotPlan, offer: PeriodOffer, highlighted: Boolean, onChoose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (highlighted) androidx.compose.foundation.BorderStroke(2.dp, StreamzaRed) else null,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "${plan.slots} ${if (plan.slots == 1) "Slot" else "Slots"} · ${offer.periodLabel.replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(offer.priceText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = StreamzaRed)
                }
                if (highlighted) {
                    Surface(color = StreamzaRed, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "BEST VALUE",
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            offer.trialText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FeatureLine("Stream to ${plan.slots} platform${if (plan.slots == 1) "" else "s"} at once")
            FeatureLine("24-hour continuous streaming per session")
            FeatureLine("Loop your video 24/7")
            Button(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
                Text("Choose ${plan.slots} ${if (plan.slots == 1) "Slot" else "Slots"} ${offer.periodLabel.replaceFirstChar { it.uppercase() }}")
            }
        }
    }
}

@Composable
private fun FeatureLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(StreamzaRed.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = StreamzaRed, modifier = Modifier.size(11.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
