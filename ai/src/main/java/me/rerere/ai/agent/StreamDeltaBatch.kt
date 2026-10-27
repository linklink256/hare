package me.rerere.ai.agent

import me.rerere.ai.ui.StreamChunk

/** Batches adjacent compatible deltas only. Never drops text, metadata or structural events. */
internal class StreamDeltaBatch(
    private val intervalNanos: Long = 80_000_000L,
    private val maxChars: Int = 8192,
) {
    private var pending: StreamChunk? = null
    private val text = StringBuilder()
    private var lastFlush = Long.MIN_VALUE
    private var receivedChars = 0L

    fun offer(chunk: StreamChunk, now: Long): List<StreamChunk> {
        val out = ArrayList<StreamChunk>(2)
        if (!compatible(pending, chunk)) drain()?.let(out::add)
        when (chunk) {
            is StreamChunk.TextDelta -> { pending = chunk; text.append(chunk.text) }
            is StreamChunk.ReasoningDelta -> { pending = chunk; text.append(chunk.text) }
            else -> { out.add(chunk); lastFlush = now; return out }
        }
        receivedChars += when (chunk) {
            is StreamChunk.TextDelta -> chunk.text.length
            is StreamChunk.ReasoningDelta -> chunk.text.length
            else -> 0
        }
        if (receivedChars <= 4096 || lastFlush == Long.MIN_VALUE || now - lastFlush >= intervalNanos || text.length >= maxChars) {
            drain()?.let(out::add)
            lastFlush = now
        }
        return out
    }

    fun drain(): StreamChunk? {
        val p = pending ?: return null
        val merged = when (p) {
            is StreamChunk.TextDelta -> p.copy(text = text.toString())
            is StreamChunk.ReasoningDelta -> p.copy(text = text.toString())
            else -> error("not a delta")
        }
        pending = null
        text.setLength(0)
        return merged
    }

    private fun compatible(a: StreamChunk?, b: StreamChunk): Boolean = when {
        a is StreamChunk.TextDelta && b is StreamChunk.TextDelta -> a.id == b.id && a.metadata == b.metadata
        a is StreamChunk.ReasoningDelta && b is StreamChunk.ReasoningDelta ->
            a.id == b.id && a.metadata == b.metadata && a.reasoningType == b.reasoningType
        else -> false
    }
}
