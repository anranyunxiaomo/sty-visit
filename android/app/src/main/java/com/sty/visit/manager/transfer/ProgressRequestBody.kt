package com.sty.visit.manager.transfer

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.IOException

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (progress: Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long {
        try {
            return delegate.contentLength()
        } catch (e: IOException) {
            return -1
        }
    }

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val forwardingSink = object : ForwardingSink(sink) {
            private var bytesWritten = 0L
            private val contentLength = contentLength()

            @Throws(IOException::class)
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                if (contentLength > 0) {
                    val progress = ((bytesWritten * 100f) / contentLength).toInt()
                    onProgress(progress)
                }
            }
        }
        val bufferedSink = forwardingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}
