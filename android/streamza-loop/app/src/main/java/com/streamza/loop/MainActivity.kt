package com.streamza.loop

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamza.loop.billing.BillingManager
import com.streamza.loop.ui.HomeScreen
import com.streamza.loop.ui.LiveScreen
import com.streamza.loop.ui.MyVideosScreen
import com.streamza.loop.ui.SettingsScreen
import com.streamza.loop.ui.SignInScreen
import com.streamza.loop.ui.StreamScreen
import com.streamza.loop.ui.theme.StreamzaLoopTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    // Purchase tokens flow straight to the server for verification (see /billing/verify-purchase) —
    // acknowledgement happens there too, so this callback's only job is to hand the token off and
    // refresh auth state once verified.
    private val billingManager: BillingManager by lazy {
        BillingManager(this) { _, purchaseToken ->
            lifecycleScope.launch {
                viewModel.repo.value?.verifyPurchase(purchaseToken)
                viewModel.refreshAuth()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billingManager.startConnection()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            StreamzaLoopTheme(themeMode) {
                StreamzaLoopApp(viewModel, billingManager, this)
            }
        }
    }

    override fun onDestroy() {
        billingManager.endConnection()
        super.onDestroy()
    }
}

private sealed class Tab(val route: String, val label: String) {
    data object Home : Tab("home", "Home")
    data object Stream : Tab("stream", "Stream")
    data object Live : Tab("live", "Live")
    data object Videos : Tab("videos", "Videos")
    data object Settings : Tab("settings", "Settings")
}

@androidx.compose.runtime.Composable
private fun StreamzaLoopApp(viewModel: AppViewModel, billingManager: BillingManager, activity: Activity) {
    val repo by viewModel.repo.collectAsState()

    val r = repo
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val auth by r.auth.collectAsState()
    if (auth?.signedIn != true) {
        SignInScreen(repo = r, onSignedIn = { viewModel.refreshAuth() })
        return
    }

    val justClaimed by viewModel.justClaimedToken.collectAsState()
    val liveToken = justClaimed ?: auth?.slot?.token

    val navController = rememberNavController()
    fun goTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val tabs = listOf(Tab.Home, Tab.Stream, Tab.Live, Tab.Videos, Tab.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { goTo(tab.route) },
                        icon = {
                            if (tab == Tab.Live && liveToken != null) {
                                BadgedBox(badge = { Badge() }) { Icon(iconFor(tab), contentDescription = tab.label) }
                            } else {
                                Icon(iconFor(tab), contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Home.route) {
                HomeScreen(
                    viewModel, liveToken,
                    onGoToStream = { goTo(Tab.Stream.route) },
                    onGoToLive = { goTo(Tab.Live.route) },
                    onGoToVideos = { goTo(Tab.Videos.route) },
                    onGoToSettings = { goTo(Tab.Settings.route) },
                )
            }
            composable(Tab.Stream.route) {
                StreamScreen(viewModel, liveToken, onGoToLive = { goTo(Tab.Live.route) })
            }
            composable(Tab.Live.route) {
                LiveScreen(viewModel, liveToken, onGoToStream = { goTo(Tab.Stream.route) })
            }
            composable(Tab.Videos.route) { MyVideosScreen(viewModel) }
            composable(Tab.Settings.route) { SettingsScreen(viewModel, billingManager, activity) }
        }
    }
}

private fun iconFor(tab: Tab) = when (tab) {
    Tab.Home -> Icons.Default.Home
    Tab.Stream -> Icons.Default.Videocam
    Tab.Live -> Icons.Default.FiberManualRecord
    Tab.Videos -> Icons.Default.VideoLibrary
    Tab.Settings -> Icons.Default.Settings
}
