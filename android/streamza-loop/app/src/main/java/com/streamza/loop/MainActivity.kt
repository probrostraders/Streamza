package com.streamza.loop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamza.loop.ui.AccountScreen
import com.streamza.loop.ui.MyVideosScreen
import com.streamza.loop.ui.SignInScreen
import com.streamza.loop.ui.StreamScreen
import com.streamza.loop.ui.theme.StreamzaLoopTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamzaLoopTheme {
                StreamzaLoopApp(viewModel)
            }
        }
    }
}

private sealed class Tab(val route: String, val label: String) {
    data object Stream : Tab("stream", "Stream")
    data object Videos : Tab("videos", "Videos")
    data object Account : Tab("account", "Account")
}

@androidx.compose.runtime.Composable
private fun StreamzaLoopApp(viewModel: AppViewModel) {
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

    val navController = rememberNavController()
    val tabs = listOf(Tab.Stream, Tab.Videos, Tab.Account)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(iconFor(tab), contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Stream.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Stream.route) { StreamScreen(viewModel) }
            composable(Tab.Videos.route) { MyVideosScreen(viewModel) }
            composable(Tab.Account.route) { AccountScreen(viewModel) }
        }
    }
}

private fun iconFor(tab: Tab) = when (tab) {
    Tab.Stream -> Icons.Default.PlayArrow
    Tab.Videos -> Icons.Default.VideoLibrary
    Tab.Account -> Icons.Default.AccountCircle
}
