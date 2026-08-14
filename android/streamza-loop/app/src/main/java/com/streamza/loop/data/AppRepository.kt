package com.streamza.loop.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** streamza.live is the single backend for both Web Studio and this app — same endpoints, same
 *  session cookie, same slot pool. See server.js for the source of truth on every shape below. */
const val API_HOST = "streamza.live"
const val BASE_URL = "https://streamza.live/"

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

class AppRepository private constructor(
    private val api: StreamzaApi,
    private val cookieJar: SessionCookieJar,
    private val resolver: android.content.ContentResolver,
) {
    private val _auth = MutableStateFlow<AuthMeResponse?>(null)
    val auth: StateFlow<AuthMeResponse?> = _auth.asStateFlow()

    val isSignedIn: Boolean get() = _auth.value?.signedIn == true

    suspend fun refreshAuth() {
        _auth.value = runCatching { api.authMe() }.getOrNull() ?: AuthMeResponse(signedIn = false)
    }

    suspend fun googleClientId(): String? = runCatching { api.authConfig().clientId }.getOrNull()

    suspend fun signIn(idToken: String): Result<AuthMeResponse> = runCatching {
        val res = api.googleSignIn(GoogleSignInRequest(idToken))
        if (res.error != null) throw ApiException(res.error)
        _auth.value = res
        res
    }

    suspend fun signOut() {
        runCatching { api.logout() }
        cookieJar.clear()
        _auth.value = AuthMeResponse(signedIn = false)
    }

    suspend fun fetchSlots(): Result<SlotsResponse> = runCatching { api.slots() }

    // Uploads to R2 in chunks (see R2ChunkedUploader) whenever the free-tier budget allows it; once
    // it's spent, the server says so (r2Unavailable) and this falls back to the same whole-file
    // /pending-upload path Web Studio's browser has always used — bytes go through Oracle in that
    // case, but the upload still succeeds instead of just failing.
    suspend fun uploadVideo(
        video: PickedVideo,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Result<PendingUploadResponse> = runCatching {
        val maxMB = runCatching { api.r2Status().maxUploadMB }.getOrDefault(300)
        if (video.size > maxMB.toLong() * 1024 * 1024) {
            throw ApiException("Videos are limited to $maxMB MB for looping streams — trim it down or use a shorter clip.")
        }
        try {
            uploadVideoToR2(api, resolver, video, onProgress)
        } catch (_: R2UnavailableException) {
            uploadVideoLocal(video, onProgress)
        }
    }

    private suspend fun uploadVideoLocal(
        video: PickedVideo,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): PendingUploadResponse {
        val body = wholeVideoRequestBody(resolver, video)
        val part = MultipartBody.Part.createFormData("video", video.name, body)
        val res = api.pendingUpload(part)
        if (!res.ok) throw ApiException(res.error ?: "Upload failed.")
        onProgress(video.size, video.size) // no incremental progress on this path — report done in one step
        return res
    }

    /** Claims a slot and goes live. No `slot` field is sent — the server auto-assigns the next free
     *  one (see server.js /start's "otherwise take the next free one" fallback); this app deliberately
     *  never exposes a numbered slot picker, unlike Web Studio's slot board. */
    suspend fun goLive(
        email: String,
        pendingUploadId: String,
        dests: List<Destination>,
        loop: Boolean,
        agree: Boolean,
    ): Result<StartResponse> = runCatching {
        fun text(v: String): RequestBody = v.toRequestBody("text/plain".toMediaTypeOrNull())
        val fields = mapOf(
            "email" to text(email),
            "loop" to text(if (loop) "true" else "false"),
            "agree" to text(if (agree) "true" else "false"),
            "pendingUploadId" to text(pendingUploadId),
            "dests" to text(json.encodeToString(dests)),
        )
        val http = api.start(fields)
        val body = http.body()
        if (http.isSuccessful && body?.ok == true) return@runCatching body
        val errBody = http.errorBody()?.string()
        val parsed = errBody?.let { runCatching { json.decodeFromString<StartResponse>(it) }.getOrNull() }
        throw StartException(parsed?.error ?: body?.error ?: "Couldn't go live.", parsed?.trialExhausted == true)
    }

    suspend fun stopStream(token: String): Result<OkResponse> = runCatching { api.stop(StopRequest(token)) }

    suspend fun status(token: String): Result<StatusResponse> = runCatching { api.status(token) }

    suspend fun myUploads(email: String): Result<MyUploadsResponse> = runCatching { api.myUploads(email) }

    suspend fun deleteUpload(email: String, fileId: String): Result<OkResponse> = runCatching {
        val res = api.deleteUpload(DeleteUploadRequest(email, fileId))
        if (!res.ok) throw ApiException(res.error ?: "Couldn't delete that video.")
        res
    }

    suspend fun billingConfig(): Result<BillingConfigResponse> = runCatching { api.billingConfig() }

    suspend fun verifyPurchase(purchaseToken: String): Result<VerifyPurchaseResponse> = runCatching {
        val res = api.verifyPurchase(VerifyPurchaseRequest(purchaseToken))
        if (!res.ok) throw ApiException(res.error ?: "Couldn't verify that purchase.")
        res
    }

    companion object {
        suspend fun create(context: Context): AppRepository {
            val cookieJar = SessionCookieJar.create(context, API_HOST)
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                // Identifies this app to the shared backend (server.js branches on it — see
                // authPayload()/​the /start trial gate) so Web Studio's own free model stays untouched.
                // Host-scoped the same way the cookie jar is: never sent to R2's presigned-URL host.
                .addInterceptor { chain ->
                    val req = chain.request()
                    val tagged = if (req.url.host == API_HOST) req.newBuilder().addHeader("X-Streamza-Client", "loop").build() else req
                    chain.proceed(tagged)
                }
                // BASIC only logs method/URL/status/timing — never headers or bodies (which would
                // include stream keys and the session cookie), so it's safe to leave on unconditionally.
                .addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC })
                .build()
            val contentType = "application/json".toMediaType()
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
            return AppRepository(retrofit.create(StreamzaApi::class.java), cookieJar, context.contentResolver)
        }
    }
}

class ApiException(message: String) : Exception(message)

/** Thrown by [AppRepository.goLive] on any /start failure. [trialExhausted] lets the UI tell "your
 *  free trial is over" apart from an ordinary error (bad codec, all slots full, ...) so it can jump
 *  straight to the Subscription screen instead of just showing inline text. */
class StartException(message: String, val trialExhausted: Boolean = false) : Exception(message)
