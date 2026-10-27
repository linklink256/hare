package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import org.junit.Assert.*
import org.junit.Test

class AutoCompressionTest {
    @Test fun `percentage follows model window without absolute token cap`() {
        assertEquals(96000L, automaticCompressionBudget(128000, 75, 4096))
        assertEquals(150000L, automaticCompressionBudget(200000, 75, 4096))
        assertEquals(100000L, automaticCompressionBudget(200000, 50, 4096))
    }
    @Test fun `percentages above seventy five are not capped at seventy five`() {
        assertEquals(90000L, automaticCompressionBudget(100000, 90, 4096))
    }
    @Test fun `output reserve can trigger earlier but not later`() {
        assertEquals(2048L, automaticCompressionBudget(8192, 95, 4096))
        assertEquals(819L, automaticCompressionBudget(8192, 10, 4096))
    }
    @Test fun `percentage is clamped and large windows do not overflow`() {
        assertEquals(10000L, automaticCompressionBudget(100000, Int.MIN_VALUE, 0))
        assertEquals(95000L, automaticCompressionBudget(100000, Int.MAX_VALUE, 0))
        assertEquals(Int.MAX_VALUE.toLong() * 95 / 100, automaticCompressionBudget(Int.MAX_VALUE, 95, 0))
    }
    @Test fun `exact percentage boundary triggers and below it does not`() {
        val messages = List(6) { UIMessage.user("small") }.toMutableList()
        messages[4] = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), lastUsage = TokenUsage(promptTokens = 7489))
        assertNull(end(messages, threshold = 75, request = messages, window = 10000))
        messages[4] = messages[4].copy(lastUsage = TokenUsage(promptTokens = 7490))
        assertNotNull(end(messages, threshold = 75, request = messages, window = 10000))
    }

    private fun history(count: Int = 10) = List(count) { UIMessage.user("x".repeat(900)) }
    private fun end(
        messages: List<UIMessage>,
        threshold: Int = 10,
        keep: Int = 6,
        request: List<UIMessage> = messages.limitContext(0),
        window: Int = 10000,
    ) = automaticCompressionEnd(messages, request, window, threshold, keep, outputReserve = 0)

    @Test fun `short history does not trigger`() {
        assertNull(end(List(10) { UIMessage.user("hello") }))
    }
    @Test fun `threshold triggers retaining configured tail`() {
        assertEquals(4, end(history()))
    }
    @Test fun `large keep count still makes progress`() {
        assertEquals(2, end(history(4), keep = 100))
    }
    @Test fun `single message and active reply are never compressed`() {
        assertNull(end(history(1)))
        assertNull(end(history(2)))
        assertTrue(end(history(4), keep = 0)!! < 4)
    }
    @Test fun `model window limits high user threshold`() {
        assertNotNull(end(history(), threshold = 75, window = 4096))
    }
    @Test fun `provider last usage triggers before local estimate`() {
        val messages = List(10) { UIMessage.user("short") }.toMutableList()
        messages[8] = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), lastUsage = TokenUsage(promptTokens = 2000))
        assertNotNull(end(messages))
    }
    @Test fun `previous checkpoint invalidates old provider usage`() {
        val old = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), lastUsage = TokenUsage(promptTokens = 99999))
        val messages = listOf(old, UIMessage.user("summary").copy(isContextCheckpoint = true)) + List(4) { UIMessage.user("short") }
        assertNull(end(messages))
    }
    @Test fun `checkpoint alone is not recompressed`() {
        val messages = listOf(UIMessage.user("x".repeat(9000)).copy(isContextCheckpoint = true), UIMessage.user("short"), UIMessage.user("short"))
        assertNull(end(messages))
    }
    @Test fun `pending tool blocks unsafe prefix`() {
        val messages = history().toMutableList()
        messages[1] = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("id", "shell", "{}", approvalState = ToolApprovalState.Pending)))
        assertNull(end(messages))
    }
    @Test fun `completed tools remain compressible`() {
        val messages = history().toMutableList()
        messages[1] = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("id", "shell", "{}", output = listOf(UIMessagePart.Text("done")))))
        assertNotNull(end(messages))
    }
    @Test fun `tool outputs and multilingual text count without joining`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("id", "shell", "{}", output = listOf(UIMessagePart.Text("中".repeat(9000))))))
        assertTrue(estimateContextTokens(listOf(message)) >= 9000)
    }
    @Test fun `count limit does not cause endless summary of omitted history`() {
        val messages = history()
        assertNull(end(messages, request = messages.takeLast(2), threshold = 10))
    }
    @Test fun `overlay survives next tool step without altering original history`() {
        val original = history()
        val state = AutoCompressionState()
        assertSame(original, state.apply(original))
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        state.remember(original[3].id, checkpoint)
        val next = original + UIMessage.user("new tool step")
        assertEquals(listOf(checkpoint) + next.drop(4), state.apply(next))
        assertEquals(10, original.size)
        assertFalse(original.any { it.isContextCheckpoint })
    }
    @Test fun `second overlay replaces earlier summary`() {
        val original = history()
        val state = AutoCompressionState()
        state.remember(original[2].id, UIMessage.user("summary1"))
        val latest = UIMessage.user("summary2").copy(isContextCheckpoint = true)
        state.remember(original[6].id, latest)
        assertEquals(listOf(latest) + original.drop(7), state.apply(original))
    }
    @Test fun `invalid settings are clamped without overflow`() {
        assertNotNull(end(history(), threshold = Int.MIN_VALUE, keep = Int.MAX_VALUE, window = Int.MIN_VALUE))
        assertNull(end(history(), threshold = Int.MAX_VALUE, window = Int.MAX_VALUE))
    }
}
