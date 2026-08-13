package com.streamza.loop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.streamza.loop.data.AppRepository
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(repo: AppRepository, onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { clientId = repo.googleClientId() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Streamza Loop", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in to claim a slot and go live.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        if (loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = {
                val id = clientId
                if (id == null) {
                    error = "Sign-in isn't available right now — try again shortly."
                    return@Button
                }
                loading = true
                error = null
                scope.launch {
                    try {
                        val credentialManager = CredentialManager.create(context)
                        val option = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId(id)
                            .build()
                        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                        val result = credentialManager.getCredential(context, request)
                        val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
                        repo.signIn(googleCred.idToken)
                            .onSuccess { onSignedIn() }
                            .onFailure { error = it.message ?: "Sign-in failed." }
                    } catch (e: GetCredentialException) {
                        error = e.message ?: "Sign-in was cancelled."
                    } finally {
                        loading = false
                    }
                }
            }) {
                Text("Sign in with Google")
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
