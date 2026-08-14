package com.streamza.loop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamza.loop.data.AppPreferences
import com.streamza.loop.data.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Holds the one AppRepository instance for the process and the sign-in state every screen reads.
 *  Streamza Loop requires sign-in (unlike Web Studio's casual "just type an email" flow) since the
 *  account is what a shared Play Billing subscription (Phase 3) and slot resume both key off. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _repo = MutableStateFlow<AppRepository?>(null)
    val repo: StateFlow<AppRepository?> = _repo.asStateFlow()

    /** Set right after a successful goLive() so the UI can jump straight to the live view without
     *  waiting on a full /auth/me round-trip. Screens should prefer this over auth.slot?.token when
     *  it's non-null, and fall back to the server-reported slot token (auth.value?.slot?.token)
     *  otherwise — that combination is done at the call site, not here, so it stays a plain StateFlow. */
    val justClaimedToken = MutableStateFlow<String?>(null)

    private val prefs = AppPreferences(application)
    val defaultLoop: StateFlow<Boolean> = prefs.defaultLoop.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    val themeMode: StateFlow<String> = prefs.themeMode.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, "system")

    init {
        viewModelScope.launch {
            val r = AppRepository.create(getApplication())
            r.refreshAuth()
            _repo.value = r
        }
    }

    fun onClaimed(token: String) {
        justClaimedToken.value = token
    }

    fun onStopped() {
        justClaimedToken.value = null
        viewModelScope.launch { _repo.value?.refreshAuth() }
    }

    fun refreshAuth() {
        viewModelScope.launch { _repo.value?.refreshAuth() }
    }

    fun setDefaultLoop(value: Boolean) {
        viewModelScope.launch { prefs.setDefaultLoop(value) }
    }

    fun setThemeMode(value: String) {
        viewModelScope.launch { prefs.setThemeMode(value) }
    }
}
