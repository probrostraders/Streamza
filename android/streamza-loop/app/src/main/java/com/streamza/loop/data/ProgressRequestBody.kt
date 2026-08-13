package com.streamza.loop.data

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer

/** Wraps a RequestBody so multipart video uploads can drive a progress bar (OkHttp has no built-in hook for this). */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesSent: Long, totalBytes: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val countingSink = CountingSink(sink)
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }

    private inner class CountingSink(sink: Sink) : ForwardingSink(sink) {
        private var bytesWritten = 0L
        private val total = contentLength()

        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            onProgress(bytesWritten, total)
        }
    }
}
