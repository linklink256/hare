package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionPipelineTest {

    @Test
    fun `tool output keeps head and tail with a marker`() {
        val long = "H".repeat(PRUNE_TOOL_OUTPUT_HEAD) + "M".repeat(5000) + "T".repeat(PRUNE_TOOL_OUTPUT_TAIL)
        val tool = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "bash",
            input = "{}",
            output = listOf(UIMessagePart.Text(long)),
        )
        val pruned = prunePartForCompression(tool) as UIMessagePart.Tool
        val text = (pruned.output.single() as UIMessagePart.Text).text
        assertTrue(text.startsWith("H".repeat(PRUNE_TOOL_OUTPUT_HEAD)))
        assertTrue(text.endsWith("T".repeat(PRUNE_TOOL_OUTPUT_TAIL)))
        assertTrue(text.length < long.length)
        assertTrue(text.contains("truncated for compression"))
    }

    @Test
    fun `reasoning keeps only the beginning`() {
        val reasoning = UIMessagePart.Reasoning(reasoning = "R".repeat(PRUNE_REASONING_CHARS + 500))
        val pruned = prunePartForCompression(reasoning) as UIMessagePart.Reasoning
        assertTrue(pruned.reasoning.startsWith("R".repeat(PRUNE_REASONING_CHARS)))
        assertTrue(pruned.reasoning.length < PRUNE_REASONING_CHARS + 100)
    }

    @Test
    fun `attachments become placeholders`() {
        val image = UIMessagePart.Image(url = "file:///a.png")
        assertTrue((prunePartForCompression(image) as UIMessagePart.Text).text.contains("image omitted"))
    }

    @Test
    fun `short parts are untouched`() {
        val text = UIMessagePart.Text("hello")
        assertEquals(text, prunePartForCompression(text))
    }

    @Test
    fun `split keeps order and respects chunk size`() {
        val messages = List(10) { UIMessage.user("x".repeat(3000)) }
        val chunks = splitForCompression(messages, chunkTokens = 3000)
        assertEquals(messages, chunks.flatten())
        assertTrue(chunks.size > 1)
    }

    @Test
    fun `single oversized message still forms one chunk`() {
        val messages = listOf(UIMessage.user("x".repeat(50_000)))
        val chunks = splitForCompression(messages, chunkTokens = 1000)
        assertEquals(1, chunks.size)
    }

    @Test
    fun `fallback text reports range and excerpts without inventing content`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("first question"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))),
        )
        val text = fallbackCompactionText(messages, reason = "window too small")
        assertTrue(text.contains("Context trimmed without a model summary"))
        assertTrue(text.contains("2 messages"))
        assertTrue(text.contains("window too small"))
        assertTrue(text.contains("first question"))
    }
}
