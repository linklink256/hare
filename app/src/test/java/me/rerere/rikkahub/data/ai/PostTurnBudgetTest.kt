package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩预算相关的纯 helper 契约（工具固定成本 / 摘要请求可行性 / 片段长度反推）。
 *
 * 只证明纯函数判定，**不**证明"装不下就不发请求"的整链行为（那在 ChatRequestFactory 内联）。
 * 状态：仅编写，未运行 Gradle/JUnit。
 */
class PostTurnBudgetTest {

    private fun tool(
        name: String = "shell",
        description: String = "run a command",
        schema: (() -> InputSchema?)? = null,
    ) = Tool(
        name = name,
        description = description,
        parameters = schema ?: { null },
        execute = { emptyList() },
    )

    // ---------- 工具固定成本 ----------
    @Test
    fun `tool name description and overhead are counted`() {
        val minimal = estimateToolTokens(tool(name = "t", description = ""))
        assertTrue(minimal >= 32L)                       // 基础包装开销
        val described = estimateToolTokens(tool(name = "t", description = "中".repeat(1_000)))
        assertTrue(described >= minimal + 1_000L)         // 描述按同口径计入
    }

    @Test
    fun `tool schema parameters are counted`() {
        val withoutSchema = estimateToolTokens(tool())
        val withSchema = estimateToolTokens(
            tool(
                schema = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("cmd", buildJsonObject { put("type", "string") })
                            put("cwd", buildJsonObject { put("type", "string") })
                        },
                        required = listOf("cmd"),
                    )
                }
            )
        )
        assertTrue(withSchema > withoutSchema)
    }

    @Test
    fun `tool list cost is the sum of individual tools`() {
        val a = tool(name = "a", description = "alpha")
        val b = tool(name = "b", description = "beta")
        assertEquals(estimateToolTokens(a) + estimateToolTokens(b), estimateToolTokens(listOf(a, b)))
        assertEquals(0L, estimateToolTokens(emptyList<Tool>()))
    }

    @Test
    fun `throwing schema lambda degrades to no schema cost instead of crashing`() {
        val cost = estimateToolTokens(tool(schema = { throw IllegalStateException("boom") }))
        assertTrue(cost >= 32L)
    }

    // ---------- 摘要请求可行性 ----------
    @Test
    fun `summary budget refuses when fixed cost leaves less than minimum room`() {
        assertNull(compressionSummaryBudget(1024, fixedTokens = 800, coveredTokens = 0, instructionTokens = 0))
        assertNull(compressionSummaryBudget(1024, fixedTokens = 257, coveredTokens = 0, instructionTokens = 0))
        assertNotNull(compressionSummaryBudget(1024, fixedTokens = 256, coveredTokens = 0, instructionTokens = 0))
    }

    @Test
    fun `summary budget refuses when covered history alone fills the window`() {
        assertNull(compressionSummaryBudget(10_000, fixedTokens = 1_000, coveredTokens = 9_000, instructionTokens = 100))
    }

    @Test
    fun `summary budget never exceeds its own output scale`() {
        assertEquals(
            8_192L,
            compressionSummaryBudget(200_000, fixedTokens = 1_000, coveredTokens = 1_000, instructionTokens = 100),
        )
        assertEquals(
            compressionSummaryMaxTokens(200_000).toLong(),
            compressionSummaryBudget(200_000, fixedTokens = 0, coveredTokens = 0, instructionTokens = 0),
        )
    }

    @Test
    fun `every fixed cost reduces the summary room`() {
        val roomy = compressionSummaryBudget(100_000, 0, 0, 0)!!
        val tight = compressionSummaryBudget(100_000, 95_000, 0, 0)!!
        assertTrue(tight < roomy)
        val tighter = compressionSummaryBudget(100_000, 95_000, 2_000, 500)!!
        assertTrue(tighter < tight)
    }

    @Test
    fun `reserved output room is respected`() {
        // window 2000、最小余量 1000 → 只剩 1000（小于默认 512 之外的额外保留）
        assertEquals(1_000L, compressionSummaryBudget(2_000, 0, 0, 0, windowMinRoomTokens = 1_000))
        assertNull(compressionSummaryBudget(2_000, 0, 0, 0, windowMinRoomTokens = 1_800))
    }

    // ---------- 片段长度反推 ----------
    @Test
    fun `max prefix chars follows the same estimator for cjk`() {
        val text = "中".repeat(100)
        val chars = maxPrefixCharsWithinTokens(text, 10)
        assertEquals(10, chars)
        assertTrue(estimateTextTokens(text.take(chars)) <= 10)
        assertTrue(estimateTextTokens(text.take(chars + 1)) > 10)
    }

    @Test
    fun `max prefix chars is not tokens times a fixed factor for ascii`() {
        val text = "a".repeat(100)
        val chars = maxPrefixCharsWithinTokens(text, 10)
        assertTrue(chars in 28..33)                      // 与中文口径不同，不能乘 3
        assertTrue(estimateTextTokens(text.take(chars)) <= 10)
        assertTrue(estimateTextTokens(text.take(chars + 1)) > 10)
    }

    @Test
    fun `max prefix chars handles zero budget and fully fitting text`() {
        assertEquals(0, maxPrefixCharsWithinTokens("abc", 0))
        assertEquals(0, maxPrefixCharsWithinTokens("abc", -5))
        assertEquals(3, maxPrefixCharsWithinTokens("abc", 100))
        assertEquals(0, maxPrefixCharsWithinTokens("", 10))
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return true
                i += 2
            } else if (c.isLowSurrogate()) {
                return true
            } else {
                i += 1
            }
        }
        return false
    }

    @Test
    fun `max prefix chars never splits a surrogate pair`() {
        // 非 BMP 字符在 UTF-16 里占两个 char（代理对）；预算只够“半个”时应停在 0，而不是切出孤立高代理项。
        // 这是原话裁剪的正确性边界（partial 文本必须是合法 Unicode），与 tokenizer 精度无关。
        // 现实现按 code unit 计数、无代理对保护：本断言预计在修复前为红。
        val cases = listOf(
            "😀" to 1L,
            "a😀" to 2L,
            "😀😀" to 3L,
            "中😀" to 2L,
        )
        for ((text, tokens) in cases) {
            val chars = maxPrefixCharsWithinTokens(text, tokens)
            val prefix = text.take(chars)
            assertTrue("prefix must stay a prefix of the source", text.startsWith(prefix))
            assertFalse(
                "prefix ended with a half surrogate pair: text=$text tokens=$tokens chars=$chars",
                hasUnpairedSurrogate(prefix),
            )
            assertTrue(
                "prefix cost ${estimateTextTokens(prefix)} must stay within $tokens",
                estimateTextTokens(prefix) <= tokens,
            )
        }
    }

    @Test
    fun `max prefix chars keeps whole code points when budget allows them`() {
        // 不能为了安全一律退到 0：预算够时仍应保留完整的 emoji。
        assertEquals(2, maxPrefixCharsWithinTokens("😀", 2))
        assertEquals(2, maxPrefixCharsWithinTokens("😀😀", 2))
        assertEquals(3, maxPrefixCharsWithinTokens("a😀", 3))
    }

    // ---------- 工具固定成本参与阈值判定 ----------
    // 对接：有 usage 时 `observedContextTokens = usage + 其后消息的估算`，没有 usage 才用
    // `estimateContextTokens(request) + toolTokens`；usage 已包含工具定义，不能再累加一次。

    private fun noUsageMessage(tokenTarget: Int) =
        UIMessage.user("中".repeat((tokenTarget - 8).coerceAtLeast(0)))   // estimateContextTokens = 8 + 字数

    @Test
    fun `tools alone can cross the threshold when no usage is available`() {
        val messages = listOf(noUsageMessage(800))
        assertEquals(800L, estimateContextTokens(messages))
        // W=16000 / 75% / reserve=0 → 触发线 12000
        assertFalse(
            compressionThresholdReached(messages, messages, 16_000, 75, outputReserve = 0),
        )
        assertTrue(
            compressionThresholdReached(messages, messages, 16_000, 75, outputReserve = 0, toolTokens = 12_000),
        )
    }

    @Test
    fun `usage that already includes tools is not counted twice`() {
        // provider 的 promptTokens 已包含工具定义；只能与「消息估算 + tools」取较大者，不能再加一次
        val usageIncludesTools = 12_800
        val messages = listOf(
            UIMessage(
                role = me.rerere.ai.core.MessageRole.ASSISTANT,
                parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("final")),
                lastUsage = me.rerere.ai.core.TokenUsage(promptTokens = usageIncludesTools),
            )
        )
        // W=17332 → 75% = 12999：正确值 12800 不触发；若重复累加（12800+12000）会误触发
        assertFalse(
            compressionThresholdReached(messages, messages, 17_332, 75, outputReserve = 0, toolTokens = 12_000),
        )
        // 70% = 12132 → 12800 触发，说明 usage 仍被计入
        assertTrue(
            compressionThresholdReached(messages, messages, 17_332, 70, outputReserve = 0, toolTokens = 12_000),
        )
    }
}
