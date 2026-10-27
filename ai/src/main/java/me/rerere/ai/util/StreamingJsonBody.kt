package me.rerere.ai.util

import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Replayable exact-length JSON with no full UTF-16 JSON string / UTF-8 byte-array copy. */
@OptIn(ExperimentalSerializationApi::class)
internal fun JsonElement.streamingJsonBody(): RequestBody = object : RequestBody() {
    override fun contentType() = "application/json".toMediaType()
    private val length: Long by lazy {
        val counter = object : OutputStream() {
            var count = 0L
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
        json.encodeToStream(JsonElement.serializer(), this@streamingJsonBody, counter)
        counter.count
    }
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        // encodeToStream flushes, but does not close the caller's stream.
        json.encodeToStream(JsonElement.serializer(), this@streamingJsonBody, sink.outputStream())
    }
}
