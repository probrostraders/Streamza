package com.streamza.loop.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

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
