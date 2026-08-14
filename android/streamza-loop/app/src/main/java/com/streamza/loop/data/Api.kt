package com.streamza.loop.data

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Query
import retrofit2.http.Url

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
    // Real entitlement (server.js authPayload()'s app branch): how many destinations this account may
    // stream to at once — 1 by default (covers the free-trial case), the purchased slot count once
    // subscribed. trialAvailable is whether the one free 15-minute stream is still unclaimed.
    val maxDestinations: Int = 1,
    val trialAvailable: Boolean = false,
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
    val trial: Boolean = false,
    val error: String? = null,
    val trialExhausted: Boolean = false,
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
    val trial: Boolean = false,
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

// ---- Cloudflare R2 chunked upload (see server.js /r2/multipart/*) — the app's actual upload path.
// Bytes never go through streamza.live: create/part-url/complete only exchange small JSON control
// messages; the multipart PUTs below go straight to the presigned R2 URL via @Url, overriding the
// Retrofit base URL entirely for that one call.

@Serializable
data class R2CreateRequest(val name: String, val contentType: String, val size: Long)

@Serializable
data class R2CreateResponse(val ok: Boolean = false, val r2Key: String? = null, val r2UploadId: String? = null, val name: String? = null, val r2Unavailable: Boolean = false, val error: String? = null)

@Serializable
data class R2StatusResponse(val available: Boolean = false, val maxUploadMB: Int = 300)

@Serializable
data class R2PartUrlRequest(val r2Key: String, val r2UploadId: String, val partNumber: Int)

@Serializable
data class R2PartUrlResponse(val ok: Boolean = false, val url: String? = null, val error: String? = null)

@Serializable
data class R2CompletedPart(val partNumber: Int, val etag: String)

@Serializable
data class R2CompleteRequest(val r2Key: String, val r2UploadId: String, val parts: List<R2CompletedPart>, val name: String, val size: Long)

@Serializable
data class R2AbortRequest(val r2Key: String, val r2UploadId: String)

// ---- Google Play Billing verification (see server.js /billing/*) — product ids are fetched from
// the server rather than hardcoded here so Play Console is the one place tier ids live. One product
// per destination-slot count ("1"/"2"/"3" -> product id); weekly/monthly/yearly are base plans within
// each product, read straight from Play's own ProductDetails (see BillingManager.periodOffers()).

@Serializable
data class BillingConfigResponse(val enabled: Boolean = false, val products: Map<String, String> = emptyMap())

@Serializable
data class VerifyPurchaseRequest(val purchaseToken: String)

@Serializable
data class VerifyPurchaseResponse(val ok: Boolean = false, val slots: Int? = null, val error: String? = null)

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

    // Fallback upload path for when R2 has no free-tier budget left (see R2CreateResponse.r2Unavailable
    // below) — the same whole-file endpoint Web Studio's browser has always used. Bytes go through the
    // Oracle VM here, unlike the chunked-to-R2 path this app uses the rest of the time.
    @Multipart
    @POST("pending-upload")
    suspend fun pendingUpload(@Part video: MultipartBody.Part): PendingUploadResponse

    // Server expects multipart/form-data for /start regardless of whether a fresh file is attached
    // (it's parsed by multer's upload.single("video") middleware either way) — fields arrive as a
    // map so the caller only includes whichever of pendingUploadId/fileId/slot actually apply.
    // Returns the raw Response (not the unwrapped body) because /start reports every failure — full
    // slots, bad codec, trial exhausted — via a non-2xx status with a real JSON error body; unwrapping
    // would make Retrofit throw a generic HttpException and lose that message (see AppRepository.goLive).
    @Multipart
    @POST("start")
    suspend fun start(@PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>): Response<StartResponse>

    @POST("stop")
    suspend fun stop(@Body body: StopRequest): OkResponse

    @GET("status")
    suspend fun status(@Query("token") token: String): StatusResponse

    @GET("myuploads")
    suspend fun myUploads(@Query("email") email: String): MyUploadsResponse

    @POST("deleteupload")
    suspend fun deleteUpload(@Body body: DeleteUploadRequest): OkResponse

    @POST("r2/multipart/create")
    suspend fun r2Create(@Body body: R2CreateRequest): R2CreateResponse

    @POST("r2/multipart/part-url")
    suspend fun r2PartUrl(@Body body: R2PartUrlRequest): R2PartUrlResponse

    @POST("r2/multipart/complete")
    suspend fun r2Complete(@Body body: R2CompleteRequest): PendingUploadResponse

    @POST("r2/multipart/abort")
    suspend fun r2Abort(@Body body: R2AbortRequest): OkResponse

    // Cheap pre-check, not authoritative — see the comment on the server-side route.
    @GET("r2/status")
    suspend fun r2Status(): R2StatusResponse

    // Goes straight to the presigned R2 URL (a full https://<bucket>.<account>.r2.cloudflarestorage.com/...
    // URL), not to streamza.live — @Url with a fully-qualified URL overrides the Retrofit base URL for
    // just this call. No auth needed here: the presigned URL itself is the credential.
    @PUT
    suspend fun r2PutPart(@Url url: String, @Body body: RequestBody): Response<ResponseBody>

    @GET("billing/config")
    suspend fun billingConfig(): BillingConfigResponse

    @POST("billing/verify-purchase")
    suspend fun verifyPurchase(@Body body: VerifyPurchaseRequest): VerifyPurchaseResponse
}
