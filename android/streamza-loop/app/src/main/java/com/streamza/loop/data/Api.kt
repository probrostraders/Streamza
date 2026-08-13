package com.streamza.loop.data

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Query

// DTOs mirror server.js's response shapes field-for-field (see server.js /auth/me, /slots, /start,
// /status, /myuploads) so there's no translation layer to keep in sync by hand.

@Serializable
data class AuthConfigResponse(val enabled: Boolean, val clientId: String? = null)

@Serializable
data class MySlot(
    val id: Int,
    val token: String,
    val live: Boolean,
    val file: String? = null,
    val secondsLeft: Long = 0,
)

@Serializable
data class AuthMeResponse(
    val signedIn: Boolean,
    val email: String? = null,
    val name: String? = null,
    val subscribed: Boolean = false,
    val tier: String? = null,
    val multi: Boolean = false,
    val portal: String? = null,
    val slot: MySlot? = null,
    val error: String? = null,
)

@Serializable
data class GoogleSignInRequest(val credential: String)

@Serializable
data class OkResponse(val ok: Boolean = false, val error: String? = null)

@Serializable
data class SlotSummary(val id: Int, val busy: Boolean, val secondsLeft: Long = 0)

@Serializable
data class SlotsResponse(val total: Int, val free: Int, val slots: List<SlotSummary> = emptyList())

@Serializable
data class PendingUploadResponse(
    val ok: Boolean = false,
    val uploadId: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val error: String? = null,
)

@Serializable
data class StartResponse(
    val ok: Boolean = false,
    val slot: Int? = null,
    val token: String? = null,
    val expiresAt: Long? = null,
    val destinations: Int? = null,
    val multistreamLimited: Boolean = false,
    val error: String? = null,
)

@Serializable
data class StopRequest(val token: String)

@Serializable
data class StatusResponse(
    val running: Boolean,
    val slot: Int? = null,
    val file: String? = null,
    val dest: String? = null,
    val dests: List<String>? = null,
    val loop: Boolean = false,
    val email: String? = null,
    val uptime: Long? = null,
    val secondsLeft: Long? = null,
    val log: List<String>? = null,
    val endReason: String? = null,
    val endMessage: String? = null,
)

@Serializable
data class SavedVideo(val id: String, val name: String, val size: Long, val uploadedAt: Long, val expiresInMinutes: Int)

@Serializable
data class MyUploadsResponse(val subscribed: Boolean, val signedIn: Boolean, val retentionDays: Int, val uploads: List<SavedVideo> = emptyList())

@Serializable
data class DeleteUploadRequest(val email: String, val fileId: String)

/** One RTMP destination — a platform's server URL + stream key. Mirrors Web Studio's `dests` shape. */
@Serializable
data class Destination(val url: String, val key: String)

interface StreamzaApi {
    @GET("auth/config")
    suspend fun authConfig(): AuthConfigResponse

    @GET("auth/me")
    suspend fun authMe(): AuthMeResponse

    @POST("auth/google")
    suspend fun googleSignIn(@Body body: GoogleSignInRequest): AuthMeResponse

    @POST("auth/logout")
    suspend fun logout(): OkResponse

    @GET("slots")
    suspend fun slots(): SlotsResponse

    @Multipart
    @POST("pending-upload")
    suspend fun pendingUpload(@Part video: MultipartBody.Part): PendingUploadResponse

    // Server expects multipart/form-data for /start regardless of whether a fresh file is attached
    // (it's parsed by multer's upload.single("video") middleware either way) — fields arrive as a
    // map so the caller only includes whichever of pendingUploadId/fileId/slot actually apply.
    @Multipart
    @POST("start")
    suspend fun start(@PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>): StartResponse

    @POST("stop")
    suspend fun stop(@Body body: StopRequest): OkResponse

    @GET("status")
    suspend fun status(@Query("token") token: String): StatusResponse

    @GET("myuploads")
    suspend fun myUploads(@Query("email") email: String): MyUploadsResponse

    @POST("deleteupload")
    suspend fun deleteUpload(@Body body: DeleteUploadRequest): OkResponse
}
