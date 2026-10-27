package me.rerere.ai.agent

import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.ReasoningType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class StreamDeltaBatchTest {
    @Test fun thousandsOfDeltasKeepAllTextWithFarFewerSnapshots() {
        val batch = StreamDeltaBatch(); val out = mutableListOf<StreamChunk>()
        repeat(10000) { out += batch.offer(StreamChunk.TextDelta("a", "🙂x"), it * 1000L) }
        batch.drain()?.let(out::add)
        assertEquals("🙂x".repeat(10000), out.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertTrue(out.size < 1500)
    }
    @Test fun structuralEventsKeepOriginalOrderAndDrainTail() {
        val batch = StreamDeltaBatch(); val out = mutableListOf<StreamChunk>()
        val input = listOf(StreamChunk.TextStart("x"), StreamChunk.TextDelta("x", "a"), StreamChunk.TextDelta("x", "b"), StreamChunk.TextEnd("x"), StreamChunk.Finish())
        input.forEach { out += batch.offer(it, 0) }; batch.drain()?.let(out::add)
        assertEquals(input.first(),out.first());assertEquals(StreamChunk.TextDelta("x", "a"),out[1]);assertEquals(input.takeLast(2),out.takeLast(2));assertEquals(StreamChunk.TextDelta("x", "b"),out[2])
    }
    @Test fun idMetadataAndReasoningTypeNeverMergedAcrossBoundaries() {
        val b=StreamDeltaBatch();val out=mutableListOf<StreamChunk>();val meta=buildJsonObject{put("k",1)}
        val input=listOf(StreamChunk.ReasoningDelta("r","a"),StreamChunk.ReasoningDelta("r","b",reasoningType=ReasoningType.SUMMARY_TEXT),StreamChunk.TextDelta("t","c"),StreamChunk.TextDelta("t","d",meta),StreamChunk.TextDelta("z","e"))
        input.forEach{out+=b.offer(it,0)};b.drain()?.let(out::add);assertEquals(input,out)
    }
    @Test fun elapsedIntervalFlushesAndExplicitDrainIsIdempotent() {
        val b=StreamDeltaBatch()
        assertEquals(1,b.offer(StreamChunk.TextDelta("t","a".repeat(4096)),0).size)
        assertTrue(b.offer(StreamChunk.TextDelta("t","b"),1).isEmpty())
        assertEquals(listOf(StreamChunk.TextDelta("t","bc")),b.offer(StreamChunk.TextDelta("t","c"),80_000_000))
        assertNull(b.drain());assertNull(b.drain())
    }
}
