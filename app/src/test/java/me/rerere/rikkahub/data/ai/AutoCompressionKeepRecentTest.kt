package me.rerere.rikkahub.data.ai

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.putSettings
import me.rerere.rikkahub.data.datastore.toSettings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动压缩「保留最近 N 条」契约（keep recent）。
 *
 * 对接对象：
 * - AutoCompression.kt 的纯 helper：completedTurnCompressionEnd(keepRecentMessages) / compressionFitsBudget；
 * - Settings / Preferences 的键映射：autoCompressionKeepRecentMessages ↔ `auto_compression_keep_recent_messages`。
 *
 * 阈值全部用**估算**触发（不带 provider usage），避免依赖消息创建时间；fixture 用 1024 窗口 / 75%，
 * 触发线 = min(768, max(1024-2048, 256)) = 256 token（见下方 premise 用例）。每条长消息约 129 token，
 * 三条即越过触发线。
 *
 * 未覆盖（纯 JVM 无法构造，故不写用例）：
 * 1. ChatRequestFactory.compressAfterTask 里 covered 逐步加大直到 size 的循环（需要 ChatTurnContext /
 *    DeferredTaskCompression / buildStep，没有纯 JVM fixture）；本文件只覆盖它依赖的 compressionFitsBudget 算术。
 * 2. 安装检查点前 fixedTokens + replacement + retained 的运行时汇总路径（同上，需要真实摘要请求）。
 * 3. SettingAutoCompressionPage 的输入控件与展示（需要 Compose 运行时）。
 * 4. SettingsStore 的真实 DataStore 读写（需要 Context / 文件 IO）；本文件只覆盖 Preferences 键映射。
 *
 * 状态：仅编写，未运行 Gradle/JUnit（executed:false）。
 */
class AutoCompressionKeepRecentTest {

    // ---------- fixtures ----------
    private val windowSize = 1024
    private val thresholdPercent = 75

    /** 约 129 token：3 条即可越过 256 的触发线。 */
    private fun longUser(tag: String = "u") = UIMessage.user(tag + "x".repeat(360))

    private fun user(text: String = "hi") = UIMessage.user(text)

    private fun assistant(text: String = "答复") = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun checkpoint(text: String = "摘要") =
        UIMessage.user(text).copy(isContextCheckpoint = true)

    private fun executedTool() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("call", "shell", "{}", output = listOf(UIMessagePart.Text("ok")))),
    )

    private fun pendingTool() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("call", "shell", "{}", approvalState = ToolApprovalState.Pending)),
    )

    private fun end(
        messages: List<UIMessage>,
        keep: Int,
        window: Int = windowSize,
        threshold: Int = thresholdPercent,
    ): Int? = completedTurnCompressionEnd(
        messages = messages,
        requestMessages = messages,
        modelContext = window,
        thresholdPercent = threshold,
        outputReserve = 0,
        toolTokens = 0,
        keepRecentMessages = keep,
    )

    // ---------- 前提：触发线 ----------
    @Test
    fun `fixture window and threshold resolve to a 256 token trigger`() {
        assertEquals(256L, automaticCompressionBudget(windowSize, thresholdPercent, outputReserve = 0))
    }

    // ---------- 覆盖终点 ----------
    @Test
    fun `keep zero plans the whole completed turn to the final reply`() {
        val messages = listOf(longUser("a"), longUser("b"), executedTool(), assistant("最终答复"))
        // 已执行的工具不影响覆盖
        assertEquals(4, end(messages, keep = 0))
        // 默认参数也是 0：省略 keepRecentMessages 时仍然覆盖整段
        assertEquals(
            4,
            completedTurnCompressionEnd(
                messages = messages,
                requestMessages = messages,
                modelContext = windowSize,
                thresholdPercent = thresholdPercent,
                outputReserve = 0,
            ),
        )
    }

    @Test
    fun `keep zero still requires the final message to be an assistant reply`() {
        val messages = listOf(longUser("a"), longUser("b"), longUser("c"), user("又问"))
        assertEquals(MessageRole.USER, messages.last().role)
        assertNull(end(messages, keep = 0))
    }

    @Test
    fun `keep two leaves the newest two messages out of the covered range`() {
        val messages = listOf(
            longUser("a"),
            longUser("b"),
            assistant("中间答复"),
            user("最近的问题"),
            user("最近的补充"),
        )
        // 终点落在倒数第二条之后，且不要求 end 边界上是 assistant（最后一条是用户消息）
        assertEquals(3, end(messages, keep = 2))
        assertEquals(MessageRole.USER, messages.last().role)
    }

    @Test
    fun `keep at or above the message count plans nothing`() {
        val messages = listOf(longUser("a"), longUser("b"), longUser("c"), assistant("答复"))
        assertNull(end(messages, keep = 4))
        assertNull(end(messages, keep = 5))
        assertNull(end(messages, keep = 64))
    }

    @Test
    fun `boundary landing exactly on the fresh start plans nothing`() {
        val messages = listOf(
            longUser("a"),
            checkpoint("旧摘要"),
            longUser("b"),
            longUser("c"),
            assistant("答复"),
        )
        // keep=3 → end=2；最后一个检查点在下标 1，freshStart=2 → 覆盖段为空
        assertNull(end(messages, keep = 3))
        // keep=2 → end=3，覆盖段恰好一条，仍可规划
        assertEquals(3, end(messages, keep = 2))
    }

    // ---------- 未执行工具 ----------
    @Test
    fun `unexecuted tool inside the covered range blocks planning`() {
        val messages = listOf(longUser("a"), pendingTool(), longUser("b"), assistant("答复"))
        assertNull(end(messages, keep = 0))
    }

    @Test
    fun `unexecuted tool left in the retained tail does not block planning`() {
        val messages = listOf(
            longUser("a"),
            longUser("b"),
            longUser("c"),
            assistant("中间答复"),
            pendingTool(),
            assistant("等待批准"),
        )
        val pendingIndex = messages.indexOfFirst { message -> message.getTools().any { !it.isExecuted } }
        assertEquals(4, pendingIndex)
        val planned = end(messages, keep = 3)
        assertEquals(3, planned)
        assertTrue(planned!! < pendingIndex)
    }

    // ---------- 阈值 ----------
    @Test
    fun `below the trigger line plans nothing for any keep value`() {
        val messages = listOf(user("hi"), assistant("ok"))
        assertNull(end(messages, keep = 0, window = 200_000))
        assertNull(end(messages, keep = 1, window = 200_000))
        assertNull(end(messages, keep = 2, window = 200_000))
        assertNull(end(messages, keep = 64, window = 200_000))
    }

    // ---------- keep 的取值边界 ----------
    @Test
    fun `negative keep is treated as zero`() {
        val messages = listOf(longUser("a"), longUser("b"), longUser("c"), assistant("答复"))
        assertEquals(4, end(messages, keep = -1))
        assertEquals(4, end(messages, keep = -5))
        assertEquals(4, end(messages, keep = Int.MIN_VALUE))
    }

    @Test
    fun `keep above the cap is clamped to sixty four`() {
        val messages = List(69) { index -> longUser("m$index") } + assistant("答复")
        assertEquals(70, messages.size)
        // 65 若不被 clamp，终点会是 5；clamp 到 64 后是 6；65 以上（含 70）都按 64 处理
        assertEquals(6, end(messages, keep = 65))
        assertEquals(6, end(messages, keep = 64))
        assertEquals(6, end(messages, keep = 70))
    }

    // ---------- 压缩后仍要低于触发线 ----------
    @Test
    fun `compression budget counts the retained tail against the trigger line`() {
        // 预算 10000 的触发线，压缩后必须 ≤ 9000（90%）
        assertTrue(compressionFitsBudget(fixedTokens = 4000, replacementTokens = 3000, budget = 10_000))
        assertTrue(compressionFitsBudget(fixedTokens = 4000, replacementTokens = 5000, budget = 10_000))
        assertFalse(compressionFitsBudget(fixedTokens = 4000, replacementTokens = 5001, budget = 10_000))
        // 调用方把保留区加进 replacement：同一组 fixed+replacement 会被保留区推过线
        assertTrue(compressionFitsBudget(fixedTokens = 4000, replacementTokens = 3000 + 2000, budget = 10_000))
        assertFalse(compressionFitsBudget(fixedTokens = 4000, replacementTokens = 3000 + 2001, budget = 10_000))
    }

    // ---------- 设置 ----------
    @Test
    fun `settings default keep recent is zero and reads the new datastore key`() {
        val keepKey = intPreferencesKey("auto_compression_keep_recent_messages")
        // 默认值
        assertEquals(0, emptyPreferences().toSettings().autoCompressionKeepRecentMessages)
        // 读新的 int key
        assertEquals(7, mutablePreferencesOf(keepKey to 7).toSettings().autoCompressionKeepRecentMessages)
        // 写回同一个 key
        val written = mutablePreferencesOf().apply {
            putSettings(Settings(autoCompressionKeepRecentMessages = 12))
        }
        val stored = written[keepKey]
        assertEquals(12L, stored!!.toLong())
        assertEquals(12, written.toSettings().autoCompressionKeepRecentMessages)
    }

    @Test
    fun `legacy keep recent keys are still ignored`() {
        // 旧 DataStore key 只被容忍，不映射到新字段
        val legacyPrefs = mutablePreferencesOf(intPreferencesKey("auto_compression_keep_recent") to 9)
        assertEquals(0, legacyPrefs.toSettings().autoCompressionKeepRecentMessages)
        // 旧 JSON 字段同样被忽略
        val legacyJson = JsonInstant.decodeFromString<Settings>("""{"autoCompressionKeepRecent":9}""")
        assertEquals(0, legacyJson.autoCompressionKeepRecentMessages)
        // 新旧同时出现时只认新字段
        val both = JsonInstant.decodeFromString<Settings>(
            """{"autoCompressionKeepRecent":9,"autoCompressionKeepRecentMessages":3}"""
        )
        assertEquals(3, both.autoCompressionKeepRecentMessages)
    }
}
