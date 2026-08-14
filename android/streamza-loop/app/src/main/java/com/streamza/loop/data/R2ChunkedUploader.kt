package com.streamza.loop.data

import android.content.ContentResolver
import kotlinx.coroutines.delay
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FileInputStream
import java.io.IOException

private const val CHUNK_SIZE = 8 * 1024 * 1024 // 8MB — R2/S3 multipart parts must be >=5MB except the last;
// smaller than the old 16MB so a single slow mobile-network request has less to time out on.
private const val PROGRESS_STEP = 64 * 1024 // report progress every 64KB written, not just once per whole part
private const val MAX_ATTEMPTS_PER_PART = 3

/** A part's bytes, written in small steps so [onDelta] gives real-time progress within the part's own
 *  upload instead of just one jump when the whole part finishes. */
private fun progressRequestBody(bytes: ByteArray, mediaType: MediaType?, onDelta: (Long) -> Unit): RequestBody =
    object : RequestBody() {
        override fun contentType() = mediaType
        override fun contentLength() = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(PROGRESS_STEP, bytes.size - offset)
                sink.write(bytes, offset, len)
                offset += len
                onDelta(len.toLong())
            }
        }
    }

/** Reads a picked video in fixed-size chunks via its real file descriptor (ParcelFileDescriptor lets us
 *  seek precisely instead of re-reading from the start for every chunk, unlike a plain InputStream). */
private class VideoChunkReader(resolver: ContentResolver, video: PickedVideo) : AutoCloseable {
    private val pfd = resolver.openFileDescriptor(video.uri, "r") ?: throw IOException("Could not open the selected video.")
    private val stream = FileInputStream(pfd.fileDescriptor)

    fun readChunk(offset: Long, length: Int): ByteArray {
        stream.channel.position(offset)
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = stream.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) buf else buf.copyOf(read)
    }

    override fun close() {
        stream.close()
        pfd.close()
    }
}

/** Thrown when the server reports R2 is at its free-tier budget (see r2Unavailable in server.js) — a
 *  distinct, expected outcome the caller should react to by falling back to the local-disk upload path,
 *  not by treating it as a real failure the way any other exception from this function should be. */
class R2UnavailableException(message: String) : Exception(message)

/** Uploads a picked video to R2 in chunks, phone -> R2 directly via presigned per-part URLs (see the
 *  server-side r2/multipart endpoints in server.js) — streamza.live only ever sees the small JSON
 *  control calls, never the video bytes. Each part gets up to 3 attempts before the whole upload
 *  fails; on any unrecoverable failure the in-progress R2 multipart upload is aborted so it doesn't
 *  linger as a partial object. */
suspend fun uploadVideoToR2(
    api: StreamzaApi,
    resolver: ContentResolver,
    video: PickedVideo,
    onProgress: (sent: Long, total: Long) -> Unit,
): PendingUploadResponse {
    val create = api.r2Create(R2CreateRequest(video.name, video.mimeType, video.size))
    if (create.r2Unavailable) throw R2UnavailableException(create.error ?: "Cloud upload is at its limit.")
    if (!create.ok || create.r2Key == null || create.r2UploadId == null) {
        throw ApiException(create.error ?: "Couldn't start the upload.")
    }
    val r2Key = create.r2Key
    val r2UploadId = create.r2UploadId

    try {
        val totalParts = ((video.size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)
        val parts = mutableListOf<R2CompletedPart>()
        var confirmedSent = 0L // only advances once a part's PUT actually succeeds (real ETag back)

        VideoChunkReader(resolver, video).use { reader ->
            for (partNumber in 1..totalParts) {
                val offset = (partNumber - 1).toLong() * CHUNK_SIZE
                val thisChunkSize = minOf(CHUNK_SIZE.toLong(), video.size - offset).toInt()
                val bytes = reader.readChunk(offset, thisChunkSize)

                var etag: String? = null
                var lastError: Exception? = null
                for (attempt in 1..MAX_ATTEMPTS_PER_PART) {
                    try {
                        val partUrlRes = api.r2PartUrl(R2PartUrlRequest(r2Key, r2UploadId, partNumber))
                        if (!partUrlRes.ok || partUrlRes.url == null) throw ApiException(partUrlRes.error ?: "Couldn't get an upload URL.")
                        var partProgress = 0L
                        val body = progressRequestBody(bytes, "application/octet-stream".toMediaTypeOrNull()) { delta ->
                            partProgress += delta
                            onProgress((confirmedSent + partProgress).coerceAtMost(video.size), video.size)
                        }
                        val putRes = api.r2PutPart(partUrlRes.url, body)
                        if (!putRes.isSuccessful) throw ApiException("Upload failed (HTTP ${putRes.code()}).")
                        etag = putRes.headers()["ETag"]?.trim('"') ?: throw ApiException("Upload succeeded but no ETag was returned.")
                        break
                    } catch (e: Exception) {
                        lastError = e
                        if (attempt < MAX_ATTEMPTS_PER_PART) delay(1000L * attempt)
                    }
                }
                if (etag == null) throw (lastError ?: ApiException("Upload failed."))

                parts.add(R2CompletedPart(partNumber, etag))
                confirmedSent += thisChunkSize
                onProgress(confirmedSent, video.size)
            }
        }

        val complete = api.r2Complete(R2CompleteRequest(r2Key, r2UploadId, parts, video.name, video.size))
        if (!complete.ok) throw ApiException(complete.error ?: "Couldn't finish the upload.")
        return complete
    } catch (e: Exception) {
        runCatching { api.r2Abort(R2AbortRequest(r2Key, r2UploadId)) }
        throw e
    }
}
