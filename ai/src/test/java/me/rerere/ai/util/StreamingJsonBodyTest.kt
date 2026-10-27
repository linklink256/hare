package me.rerere.ai.util

import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StreamingJsonBodyTest {
    @Test fun encodingExactlyMatchesOriginalUtf8IncludingEscapes() {
        val value = buildJsonObject { put("text", "中文🙂\n\"\\\t".repeat(20000)); put("null", JsonNull) }
        val body = value.streamingJsonBody()
        val expected = json.encodeToString(JsonElement.serializer(), value).toByteArray(Charsets.UTF_8)
        val sink = Buffer(); body.writeTo(sink)
        assertEquals(expected.size.toLong(), body.contentLength())
        assertArrayEquals(expected, sink.readByteArray())
        assertFalse(body.isOneShot())
    }
    @Test fun bodyReplayAndContentLengthDoNotConsumeValue() {
        val body = buildJsonObject { put("n", 12); put("a", buildJsonArray { add("x"); add(true) }) }.streamingJsonBody()
        val one = Buffer(); val two = Buffer()
        val len = body.contentLength(); body.writeTo(one); body.writeTo(two)
        assertEquals(len, one.size); assertEquals(one.readUtf8(), two.readUtf8())
    }
}
