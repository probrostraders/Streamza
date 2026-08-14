package com.streamza.loop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamza.loop.data.AppPreferences
import com.streamza.loop.data.AppRepository
import com.streamza.loop.data.PickedVideo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Picking a video and uploading it are two separate moments in the new flow — upload starts the
 *  instant a video is picked (not when Go Live is tapped), so by the time destinations/options are
 *  filled in the upload is usually already done. Lives in the ViewModel (not screen-local `remember`
 *  state) so switching tabs mid-upload doesn't cancel it or lose the pick. */
data class UploadState(
    val picked: PickedVideo? = null,
    val uploading: Boolean = false,
    val progress: Float = 0f,
    val uploadId: String? = null,
    val error: String? = null,
) {
    val ready: Boolean get() = picked != null && !uploading && uploadId != null && error == null
}

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

    private val _uploadState = MutableStateFlow(UploadState())
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()
    private var uploadJob: Job? = null

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

    /** Kicks off the upload immediately — replaces whatever was picked/uploading before, cancelling
     *  that upload if it was still in flight. */
    fun pickVideo(video: PickedVideo) {
        uploadJob?.cancel()
        _uploadState.value = UploadState(picked = video, uploading = true)
        uploadJob = viewModelScope.launch {
            val result = _repo.value?.uploadVideo(video) { sent, total ->
                if (total > 0) _uploadState.value = _uploadState.value.copy(progress = sent.toFloat() / total.toFloat())
            }
            result?.onSuccess { res ->
                _uploadState.value = _uploadState.value.copy(uploading = false, uploadId = res.uploadId, progress = 1f)
            }?.onFailure {
                _uploadState.value = _uploadState.value.copy(uploading = false, error = it.message ?: "Upload failed.")
            }
        }
    }

    fun clearPickedVideo() {
        uploadJob?.cancel()
        _uploadState.value = UploadState()
    }

    /** Called once the claimed stream's upload has done its job — clears the draft so the form is
     *  empty next time the user starts a new stream. */
    fun onGoneLive() {
        _uploadState.value = UploadState()
    }
}
