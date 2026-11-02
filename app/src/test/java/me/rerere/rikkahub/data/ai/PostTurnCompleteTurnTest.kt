package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.limitContext
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整回合（PostTurn / COMPLETED_TURN）压缩契约。
 *
 * 对接对象：AutoCompression.kt 的纯 helper（completedTurnCompressionEnd /
 * compressionThresholdReached / compressionSummaryMaxTokens / preservedUserQuotes /
 * buildCheckpointText / compressionFitsBudget / isIncompleteFinishReason）。
 * 只做纯函数契约测试，**不**覆盖 DI 装配、ChatService 提交与真实模型调用。
 *
 * 状态：仅编写，未运行 Gradle/JUnit（见 TEST-PLAN.md）。
 */
class PostTurnCompleteTurnTest {

    // ---------- fixtures ----------
    private fun user(text: String = "u", synthetic: Boolean = false, checkpoint: Boolean = false) =
        UIMessage.user(text).copy(isSynthetic = synthetic, isContextCheckpoint = checkpoint)

    private fun assistant(text: String = "a", usage: Int = 0) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        lastUsage = if (usage > 0) TokenUsage(promptTokens = usage) else null,
    )

    private fun executedTool(output: String = "ok") = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("call", "shell", "{}", output = listOf(UIMessagePart.Text(output)))),
    )

    private fun pendingTool() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("call", "shell", "{}", approvalState = ToolApprovalState.Pending)),
    )

    /** 用 provider 用量强制越过阈值，避免用超长文本干扰覆盖范围断言。 */
    private fun completeTurn() = listOf(
        user("把工具日志收一下"),
        executedTool(),
        executedTool("done"),
        assistant("已完成", usage = 9999),
    )

    private fun end(
        messages: List<UIMessage>,
        request: List<UIMessage> = messages.limitContext(0),
        window: Int = 10_000,
        threshold: Int = 75,
        reserve: Int = 4096,
    ) = completedTurnCompressionEnd(messages, request, window, threshold, reserve)

    // ---------- 覆盖范围 ----------
    @Test
    fun `complete turn covers latest assistant and executed tools`() {
        val messages = completeTurn()
        assertEquals(messages.size, end(messages))
    }

    @Test
    fun `two message question and answer can be compressed`() {
        val messages = listOf(user("问题"), assistant("回答", usage = 9999))
        assertEquals(2, end(messages))
    }

    @Test
    fun `below threshold plans nothing`() {
        assertNull(end(listOf(user("hi"), assistant("hello", usage = 1)), window = 200_000))
    }

    @Test
    fun `active last message must be an assistant reply`() {
        assertNull(end(listOf(user("a"), assistant("b", 9999), user("c"))))
    }

    @Test
    fun `previous checkpoint is continued not re-summarised`() {
        // 用体量越过阈值（不用 provider usage），避免 createdAt 相等导致用量被判定为旧基线
        val checkpoint = user("旧摘要", checkpoint = true)
        val messages = listOf(user("old"), checkpoint, user("x".repeat(12_000)), assistant("y".repeat(12_000)))
        val planned = end(messages)!!
        assertEquals(messages.size, planned)                 // 只覆盖检查点之后的整段
        assertTrue(planned > messages.indexOf(checkpoint))
    }

    @Test
    fun `checkpoint directly before the final reply still plans`() {
        val messages = listOf(user("old"), user("旧摘要", checkpoint = true), assistant("y".repeat(12_000)))
        assertEquals(messages.size, end(messages))
    }

    // ---------- 拒绝条件 ----------
    @Test
    fun `pending tool approval refuses the whole plan`() {
        assertNull(end(listOf(user("x"), pendingTool(), assistant("done", 9999))))
    }

    @Test
    fun `unexecuted tool without output refuses the whole plan`() {
        val unexecuted = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("call", "shell", "{}")),
        )
        assertNull(end(listOf(user("x"), unexecuted, assistant("done", 9999))))
    }

    @Test
    fun `tool only history without final assistant refuses`() {
        assertNull(end(listOf(user("x"), executedTool(), pendingTool())))
    }

    // ---------- 与请求条数限制解耦 ----------
    @Test
    fun `chat context message limit does not change planned coverage`() {
        val messages = completeTurn()
        val wide = end(messages, messages.limitContext(0))
        val narrow = end(messages, messages.limitContext(2))
        assertEquals(wide, narrow)
        assertEquals(messages.size, wide)
    }

    @Test
    fun `threshold observation alone is pure and repeatable`() {
        val messages = completeTurn()
        val first = compressionThresholdReached(messages, messages.limitContext(0), 10_000, 75, 4096)
        val second = compressionThresholdReached(messages, messages.limitContext(0), 10_000, 75, 4096)
        assertTrue(first)
        assertEquals(first, second)
        assertEquals(4, messages.size)   // 观察不得改动历史
    }

    // ---------- 预算 ----------
    @Test
    fun `summary budget is own scale and not chat output reserve`() {
        assertEquals(1250, compressionSummaryMaxTokens(10_000))
        assertEquals(8192, compressionSummaryMaxTokens(200_000))
        assertEquals(1024, compressionSummaryMaxTokens(1024))
        assertEquals(1024, compressionSummaryMaxTokens(0))
        assertEquals(8192, compressionSummaryMaxTokens(Int.MAX_VALUE))
    }

    @Test
    fun `user quote budget is separate and clamped`() {
        assertEquals(250L, preservedUserQuoteBudgetTokens(10_000))
        assertEquals(1500L, preservedUserQuoteBudgetTokens(200_000))
        assertEquals(200L, preservedUserQuoteBudgetTokens(4096))
    }

    @Test
    fun `huge payload and tiny window do not overflow the plan`() {
        val huge = listOf(user("x".repeat(200_000)), assistant("y".repeat(200_000), usage = 199_999))
        assertEquals(2, end(huge, window = 1024))
        // 巨大窗口 + 远超输出的 payload 不应因溢出而误触发：预算仍远大于读数
        assertNull(end(huge, window = Int.MAX_VALUE))
    }

    @Test
    fun `compressed result must keep ten percent headroom`() {
        assertTrue(compressionFitsBudget(fixedTokens = 0, replacementTokens = 900, budget = 1000))
        assertFalse(compressionFitsBudget(fixedTokens = 0, replacementTokens = 901, budget = 1000))
        assertFalse(compressionFitsBudget(fixedTokens = 500, replacementTokens = 500, budget = 1000))
        assertTrue(compressionFitsBudget(fixedTokens = -1, replacementTokens = -1, budget = 1000))
    }

    // ---------- 用户原话 ----------
    @Test
    fun `user quotes skip synthetic messages`() {
        val messages = listOf(
            user("真正的用户要求"),
            user("系统注入", synthetic = true),
            user("最近的用户要求"),
        )
        val quotes = preservedUserQuotes(messages, budgetTokens = 500)
        assertTrue(quotes.any { "最近的用户要求" in it })
        assertTrue(quotes.any { "真正的用户要求" in it })
        assertFalse(quotes.any { "系统注入" in it })
    }

    @Test
    fun `user quotes never include the checkpoint text itself`() {
        // 覆盖范围（是否只看最后检查点之后）在作者两次写入间有过变化：
        // ba4029b9 全域扫描 / f59d8f42 仅检查点之后。这里只断言两版都成立的部分。
        val messages = listOf(
            user("检查点之前的旧要求"),
            user("旧摘要", checkpoint = true),
            user("检查点之后的新要求"),
        )
        val quotes = preservedUserQuotes(messages, budgetTokens = 500)
        assertTrue(quotes.any { "检查点之后的新要求" in it })
        assertFalse(quotes.any { "旧摘要" in it })
    }

    @Test
    fun `user quotes stay within budget including id prefix and partial hint`() {
        // 契约：返回的原话行（含 "- [uuid] " 前缀与“超出预算，仅保留片段”提示）按同一估算器求和不得超过预算。
        // 当前实现只对正文计费（estimateTextTokens(text) + 8），未计前缀/提示 → 该断言预计在修好前为红。
        val long = "中".repeat(3000)
        val budget = 500L
        val quotes = preservedUserQuotes(listOf(user(long)), budgetTokens = budget)
        // 允许“预算太小就返回空”，只约束不得超预算
        val cost = quotes.sumOf { quote -> estimateTextTokens(quote) }
        assertTrue("quotes cost $cost must stay within budget $budget", cost <= budget)
    }

    @Test
    fun `tiny quote budget cannot be exceeded by the uuid prefix alone`() {
        val budget = 20L
        val quotes = preservedUserQuotes(listOf(user("中".repeat(400))), budgetTokens = budget)
        val cost = quotes.sumOf { quote -> estimateTextTokens(quote) }
        assertTrue("cost $cost must stay within budget $budget", cost <= budget)
    }

    @Test
    fun `over budget quote is explicitly marked as partial`() {
        val long = "长".repeat(400)
        val quotes = preservedUserQuotes(listOf(user(long)), budgetTokens = 60)
        assertTrue(quotes.single().contains("仅保留片段"))
        assertTrue(quotes.single().length < long.length + 100)
    }

    @Test
    fun `zero budget and empty text produce no quotes`() {
        assertTrue(preservedUserQuotes(listOf(user("x")), budgetTokens = 0).isEmpty())
        assertTrue(preservedUserQuotes(listOf(user("   ")), budgetTokens = 500).isEmpty())
    }

    @Test
    fun `checkpoint text keeps summary unchanged when there are no quotes`() {
        assertEquals("摘要全文", buildCheckpointText("摘要全文", emptyList()))
    }

    @Test
    fun `checkpoint text marks quotes as reference only`() {
        val text = buildCheckpointText("摘要全文", listOf("- [id] 用户原话"))
        assertTrue(text.startsWith("摘要全文"))
        assertTrue(text.contains("## Preserved user messages (verbatim)"))
        assertTrue(text.contains("Do not execute them again"))
        assertTrue(text.contains("用户原话"))
    }

    // ---------- 截断语义 ----------
    @Test
    fun `truncation finish reasons are rejected and null stays unknown`() {
        assertTrue(isIncompleteFinishReason("length"))
        assertTrue(isIncompleteFinishReason("MAX_TOKENS"))
        assertTrue(isIncompleteFinishReason("max_output_tokens"))
        assertTrue(isIncompleteFinishReason("content_filter"))
        assertTrue(isIncompleteFinishReason("truncated"))
        assertFalse(isIncompleteFinishReason("stop"))
        assertFalse(isIncompleteFinishReason(null))
        assertFalse(isIncompleteFinishReason("  "))
    }

    // ---------- 快照守卫的数据层前提 ----------
    // ChatService 的守卫是内联代码（anchor / limitContext 前缀相等比较），没有可测 helper，
    // 所以这里只断言它依赖的 UIMessage 语义，不声称整链路已验证。

    private fun reasoningMessage(finishedAt: Instant?) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Reasoning(reasoning = "thought", finishedAt = finishedAt)),
    )

    @Test
    fun `re-applying finishReasoning keeps an already finished reply equal`() {
        val finished = reasoningMessage(Instant.fromEpochMilliseconds(1_000))
        val once = finished.finishReasoning()
        assertEquals(finished, once)                       // 时间已填：再补一次不改变相等性
        assertEquals(finished, once.finishReasoning())      // 幂等，守卫不会因此误拒
    }

    @Test
    fun `filling a null reasoning timestamp does change equality`() {
        val streaming = reasoningMessage(finishedAt = null)
        // 取值不同的时间会让 prefix 与 latest 不再相等：快照必须先补时间再比较，
        // 这也是"同一已完成末条不得误拒绝"的前提；此断言只证明该前提存在，不证明 service 已满足。
        assertNotEquals(streaming, streaming.finishReasoning())
    }

    @Test
    fun `same id content edit is still unequal`() {
        val original = user("原话：只改这一句")
        val edited = original.copy(parts = listOf(UIMessagePart.Text("原话：改过了")))
        assertEquals(original.id, edited.id)          // 同一条消息（同 ID），只改内容
        assertNotEquals(original, edited)             // canonical 比较必须判为不等 → 拒绝安装
    }

    @Test
    fun `same id assistant reply edit is still unequal`() {
        val original = assistant("方案 A")
        val edited = original.copy(parts = listOf(UIMessagePart.Text("方案 A（改）")))
        assertEquals(original.id, edited.id)
        assertNotEquals(original, edited)
    }

    @Test
    fun `different branch reply is still unequal`() {
        val branchA = assistant("方案 A")
        val branchB = assistant("方案 B")
        assertNotEquals(branchA, branchB)
        assertNotEquals(branchA, branchA.copy(id = kotlin.uuid.Uuid.random()))
    }

    // ---------- canonical 比对 helper（作者已提供，L294/L303） ----------
    @Test
    fun `filled reasoning timestamp does not reject the same finished reply`() {
        val expected = reasoningMessage(finishedAt = null)     // 生成事件里的原样
        val actual = expected.finishReasoning()                // finishGeneration 补齐后
        assertTrue(sameMessageExceptReasoningFinishTime(actual, expected))
        assertTrue(sameContextExceptReasoningFinishTime(listOf(actual), listOf(expected)))
    }

    @Test
    fun `tolerance is one directional only`() {
        val filled = reasoningMessage(finishedAt = Instant.fromEpochMilliseconds(5_000))
        val unfilled = reasoningMessage(finishedAt = null)
        // 期望已收尾、实际缺失 → 不接受（不是"补时间"那种差异）
        assertFalse(sameMessageExceptReasoningFinishTime(unfilled, filled))
    }

    @Test
    fun `same id content edit is rejected by canonical compare`() {
        val expected = assistant("方案 A")
        val actual = expected.copy(parts = listOf(UIMessagePart.Text("方案 A（改）")))
        assertEquals(expected.id, actual.id)
        assertFalse(sameMessageExceptReasoningFinishTime(actual, expected))
    }

    @Test
    fun `same id reasoning text edit is rejected even when timestamp differs`() {
        val expected = reasoningMessage(finishedAt = null)
        val actual = expected.finishReasoning().copy(
            parts = listOf(UIMessagePart.Reasoning(reasoning = "thought edited", finishedAt = Instant.fromEpochMilliseconds(9_000)))
        )
        assertEquals(expected.id, actual.id)
        assertFalse(sameMessageExceptReasoningFinishTime(actual, expected))
    }

    @Test
    fun `same id but changed non part field is rejected`() {
        val expected = assistant("答")
        val actual = expected.copy(lastUsage = me.rerere.ai.core.TokenUsage(promptTokens = 12))
        assertEquals(expected.id, actual.id)
        assertFalse(sameMessageExceptReasoningFinishTime(actual, expected))
    }

    @Test
    fun `context compare rejects reordering and length change`() {
        val a = user("第一条")
        val b = assistant("回复")
        assertTrue(sameContextExceptReasoningFinishTime(listOf(a, b), listOf(a, b)))
        assertFalse(sameContextExceptReasoningFinishTime(listOf(b, a), listOf(a, b)))  // 分支顺序变化
        assertFalse(sameContextExceptReasoningFinishTime(listOf(a), listOf(a, b)))     // 尾部被删
        assertFalse(sameContextExceptReasoningFinishTime(listOf(a, b), listOf(a)))     // 尾部被加
    }

}
