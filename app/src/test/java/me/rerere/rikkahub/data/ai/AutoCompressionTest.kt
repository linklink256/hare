package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import org.junit.Assert.*
import org.junit.Test

/**
 * 通用压缩回归（与压缩策略无关的部分）：阈值/软预算/usage 基线/overlay。
 *
 * 旧「保留最近 N 条」模式已停用并从产品移除，原依赖尾部保留条数的用例已删除或改写为
 * 完整回合（PostTurn）断言；纯预算算术与 overlay 用例保持不变。
 * 状态：仅编写，未运行 Gradle/JUnit。
 */
class AutoCompressionTest {
    // ---------- 软预算 ----------
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

    // ---------- 阈值 ----------
    private fun reply(usage: Int = 0) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("done")),
        lastUsage = if (usage > 0) TokenUsage(promptTokens = usage) else null,
    )

    private fun history(count: Int = 10) = List(count) { UIMessage.user("x".repeat(900)) }
    private fun completed(messages: List<UIMessage>) = messages + reply(usage = 9999)

    private fun reached(
        messages: List<UIMessage>,
        threshold: Int = 10,
        request: List<UIMessage> = messages.limitContext(0),
        window: Int = 10000,
        reserve: Int = 0,
    ) = compressionThresholdReached(messages, request, window, threshold, reserve)

    private fun end(
        messages: List<UIMessage>,
        threshold: Int = 10,
        request: List<UIMessage> = messages.limitContext(0),
        window: Int = 10000,
        reserve: Int = 0,
    ) = completedTurnCompressionEnd(messages, request, window, threshold, reserve)

    @Test fun `exact percentage boundary triggers and below it does not`() {
        // window 10000 / 75% / reserve 0 → 触发线 7500；观测取 max(估算, provider usage)
        val messages = List(6) { UIMessage.user("small") }.toMutableList()
        messages[5] = reply(usage = 7499)
        assertFalse(reached(messages.toList(), threshold = 75))
        messages[5] = reply(usage = 7500)
        assertTrue(reached(messages.toList(), threshold = 75))
    }

    @Test fun `below threshold does not plan a completed turn`() {
        val messages = listOf(UIMessage.user("hi"), reply(usage = 1))
        assertNull(end(messages, window = 200_000))
    }

    @Test fun `completed turn plans to the final reply`() {
        val messages = completed(history())
        assertEquals(messages.size, end(messages))
    }

    @Test fun `completed turn requires the assistant reply last`() {
        assertNull(end(listOf(UIMessage.user("a"), reply(usage = 9999), UIMessage.user("c"))))
    }

    @Test fun `two message pair is compressible in complete turn mode`() {
        assertEquals(2, end(listOf(UIMessage.user("问"), reply(usage = 9999))))
    }

    @Test fun `model window limits high user threshold`() {
        assertTrue(reached(completed(history()), threshold = 75, window = 4096))
    }

    @Test fun `provider last usage triggers before local estimate`() {
        val messages = List(10) { UIMessage.user("short") }
        assertTrue(reached(messages + reply(usage = 2000)))
    }

    @Test fun `previous checkpoint invalidates old provider usage`() {
        val old = reply(usage = 99999)
        val messages = listOf(old, UIMessage.user("summary").copy(isContextCheckpoint = true)) +
            List(4) { UIMessage.user("short") }
        assertFalse(reached(messages))
    }

    @Test fun `a checkpoint as the last message is not recompressed`() {
        val checkpoint = UIMessage.user("x".repeat(9000)).copy(isContextCheckpoint = true)
        assertNull(end(listOf(UIMessage.user("old"), checkpoint)))
    }

    @Test fun `pending tool blocks the completed turn`() {
        val messages = listOf(UIMessage.user("x"), assemblyPendingTool(), reply(usage = 9999))
        assertNull(end(messages))
    }

    @Test fun `completed tools remain compressible`() {
        val messages = completed(listOf(UIMessage.user("x"), executedTool()))
        assertEquals(messages.size, end(messages))
    }

    private fun executedTool() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("id", "shell", "{}", output = listOf(UIMessagePart.Text("done")))),
    )

    private fun assemblyPendingTool() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("id", "shell", "{}", approvalState = ToolApprovalState.Pending)),
    )

    @Test fun `tool outputs and multilingual text count without joining`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("id", "shell", "{}", output = listOf(UIMessagePart.Text("中".repeat(9000))))),
        )
        assertTrue(estimateContextTokens(listOf(message)) >= 9000)
    }

    @Test fun `invalid settings are clamped without overflow`() {
        assertTrue(reached(completed(history()), threshold = Int.MIN_VALUE, window = Int.MIN_VALUE))
        assertFalse(reached(completed(history()), threshold = Int.MAX_VALUE, window = Int.MAX_VALUE))
    }

    // ---------- 生成期 overlay（与策略无关，保留） ----------
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
}
