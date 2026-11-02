package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PostTurn 压缩：只锁定"与模式无关"的不变量（完整模式上线后仍必须成立）。
 * 依赖新边界（覆盖到最终答复、两条一问一答可压、摘要预算/截断语义）的断言在
 * [PostTurnContractPendingTest]，尚未与产品 helper 对接。
 *
 * 状态：本文件仅编写，未在平板/任何机器上运行 Gradle 或 JUnit（见 TEST-PLAN.md）。
 */
class PostTurnCompressionTest {

    private fun history(count: Int = 10) = List(count) { UIMessage.user("x".repeat(900)) }

    @Test
    fun `threshold observation is idempotent and never runs maintenance`() = runTest {
        val schedule = DeferredTaskCompression()
        var compressions = 0
        repeat(100) { schedule.observeThreshold(true) }
        assertTrue(schedule.pending)
        // 观察期只能留下标记：未完成的任务不得借标记触发摘要
        schedule.afterTask(false, { fail("unfinished task must not recheck"); false }) { compressions++ }
        assertEquals(0, compressions)
        assertTrue(schedule.pending)
    }

    @Test
    fun `approval pause step limit failure and cancellation never compress`() = runTest {
        // 四种中止语义在调用方都折算为 completed=false；这里逐个确认它们都不能进入摘要路径
        listOf("awaiting approval", "step limit", "failed", "cancelled").forEach { label ->
            val schedule = DeferredTaskCompression()
            schedule.observeThreshold(true)
            var compressions = 0
            schedule.afterTask(false, { fail("$label must not recheck threshold"); true }) {
                compressions++
            }
            assertEquals("$label must not compress", 0, compressions)
        }
    }

    @Test
    fun `stale pending estimate cannot force compression when final check is negative`() = runTest {
        val schedule = DeferredTaskCompression()
        schedule.observeThreshold(true)
        var compressions = 0
        schedule.afterTask(true, { false }) { compressions++ }
        assertEquals(0, compressions)
    }

    @Test
    fun `failed maintenance propagates and is not retried for the same task`() = runTest {
        val schedule = DeferredTaskCompression()
        var attempts = 0
        try {
            schedule.afterTask(true, { true }) {
                attempts++
                // 摘要生成失败：调用方此时还没有提交检查点（提交回调不在本层）
                throw IllegalStateException("summary failed")
            }
            fail("must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals(1, attempts)
        }
        schedule.afterTask(true, { fail("already attempted"); true }) { attempts++ }
        assertEquals(1, attempts)
    }

    @Test
    fun `summary budget is independent from chat output reserve`() {
        // 聊天输出上限被抬到超过窗口时，摘要预算只能收紧到下限，不能跟着变大
        assertEquals(256L, automaticCompressionBudget(1024, 75, 4096))
        assertEquals(2048L, automaticCompressionBudget(8192, 95, 60000))
        assertTrue(automaticCompressionBudget(200000, 75, 8192) < 200000L)
    }

    @Test
    fun `estimate stays bounded for huge unicode and tool payloads`() {
        val huge = UIMessage.user("中".repeat(120_000))
        val tool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("id", "shell", "{}", output = listOf(UIMessagePart.Text("x".repeat(200_000))))),
        )
        val estimate = estimateContextTokens(listOf(huge, tool))
        assertTrue(estimate in 1..Long.MAX_VALUE)
        assertTrue(estimate > 150_000L)   // 120k CJK + 200k ascii 的近似读数，无溢出且不丢量
    }

    @Test
    fun `tool calls are rejected while blank text stays empty for the caller to reject`() {
        fun reply(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()).compressionSummaryOrNull()

        // 既有契约：解析层对空文本返回 ""（见 CompressionSummaryTest`reply without text gives a blank summary`），
        // 拒绝发生在调用方 ChatRequestFactory 的 ifBlank（L344）；本用例只断言解析结果，不覆盖整个 Factory。
        assertEquals("", reply())                                                       // 空文本 → ""，交调用者拒绝
        assertNull(reply(UIMessagePart.Text("先看文件"), UIMessagePart.Tool("c1", "shell", "{}"))) // 带工具调用 → null（解析层即拒）
        assertEquals("", reply(UIMessagePart.Reasoning(reasoning = "thinking")))          // 仅思考 → 无文本 → ""，交调用者拒绝
        assertEquals("摘要", reply(UIMessagePart.Text("<think>x</think>摘要")))             // 正常摘要；think 被剥离
    }

    @Test
    fun `checkpoint always precedes the retained tail in the request view`() {
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        val tail = List(4) { UIMessage.user("t$it") }
        val view = (listOf(UIMessage.user("old"), checkpoint) + tail).limitContext(2)
        assertEquals(checkpoint, view.first())
        assertTrue(view.none { it.parts == listOf(UIMessagePart.Text("old")) })
    }
}
