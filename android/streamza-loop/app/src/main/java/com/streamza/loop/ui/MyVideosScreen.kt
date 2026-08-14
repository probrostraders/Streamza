package com.streamza.loop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.streamza.loop.AppViewModel
import com.streamza.loop.data.SavedVideo
import com.streamza.loop.data.formatFileSize
import kotlinx.coroutines.launch

@Composable
fun MyVideosScreen(viewModel: AppViewModel) {
    val repo by viewModel.repo.collectAsState()
    val auth by (repo?.auth ?: return).collectAsState()
    val email = auth?.email
    val scope = rememberCoroutineScope()

    var videos by remember { mutableStateOf<List<SavedVideo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        if (email == null) return
        repo!!.myUploads(email)
            .onSuccess { videos = it.uploads }
            .onFailure { error = it.message }
    }

    LaunchedEffect(email) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Your videos", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            videos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            videos!!.isEmpty() -> Text("Videos you stream get saved here for reuse, up to ${videos?.size ?: 0}.")
            else -> LazyColumn {
                items(videos!!, key = { it.id }) { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(v.name, style = MaterialTheme.typography.bodyLarge)
                            Text(formatFileSize(v.size), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                repo!!.deleteUpload(email!!, v.id)
                                    .onSuccess { reload() }
                                    .onFailure { error = it.message }
                            }
                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }
}
