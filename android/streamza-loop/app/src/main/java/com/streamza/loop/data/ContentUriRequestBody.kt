package com.streamza.loop.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** A video file the user picked, resolved once so its name/size are known upfront (needed for the
 *  multipart request and for showing "12.3 MB" in the UI before upload starts). */
data class PickedVideo(val uri: Uri, val name: String, val size: Long, val mimeType: String)

fun resolvePickedVideo(resolver: ContentResolver, uri: Uri): PickedVideo {
    var name = "video.mp4"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
            if (sizeIdx >= 0) size = c.getLong(sizeIdx)
        }
    }
    val mimeType = resolver.getType(uri) ?: "video/mp4"
    return PickedVideo(uri, name, size, mimeType)
}

/** Streams a content:// Uri's bytes directly as a RequestBody — content URIs under scoped storage
 *  don't reliably expose a real filesystem File, so this reads via ContentResolver instead of File I/O. */
class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val video: PickedVideo,
) : RequestBody() {
    override fun contentType() = video.mimeType.toMediaTypeOrNull()
    override fun contentLength() = video.size

    override fun writeTo(sink: BufferedSink) {
        resolver.openInputStream(video.uri)?.use { input ->
            sink.writeAll(input.source())
        } ?: throw java.io.IOException("Could not open the selected video.")
    }
}
