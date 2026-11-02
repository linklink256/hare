package me.rerere.rikkahub.data.ai

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.toSettings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置层：旧模式（保留最近 N 条 / strategy 切换）已停用，只验证遗留键被忽略、不激活旧模式、不影响完整回合压缩。
 *
 * 刻意不引用 `autoCompressionKeepRecent` / `autoCompressionStrategy` 属性，产品删字段后本文件仍应编译。
 * 状态：仅编写，未运行 Gradle/JUnit。
 */
class PostTurnSettingsTest {

    private fun decode(json: String): Settings = JsonInstant.decodeFromString(json)

    private fun endOfCompleteTurn(messages: List<UIMessage>): Int? = completedTurnCompressionEnd(
        messages = messages,
        requestMessages = messages,
        modelContext = messages.size * 1_000 + 1_000,
        thresholdPercent = 75,
        outputReserve = 0,
    )

    private fun fixture() = listOf(
        UIMessage.user("历史一"),
        UIMessage.user("历史二"),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("最终答复")),
            lastUsage = TokenUsage(promptTokens = 99_000),
        ),
    )

    @Test
    fun `legacy json keys are ignored without throwing`() {
        val settings = decode(
            """{"autoCompressionEnabled":false,"autoCompressionThresholdPercent":80,
               "autoCompressionKeepRecent":5,"autoCompressionStrategy":"LEGACY_KEEP_RECENT",
               "autoCompressionMode":"post_turn"}"""
        )
        // 遗留键只被容忍，核心字段按 JSON 读取，旧模式不被激活
        assertFalse(settings.autoCompressionEnabled)
        assertEquals(80, settings.autoCompressionThresholdPercent)
    }

    @Test
    fun `legacy keep recent cannot disable compression`() {
        val settings = decode("""{"autoCompressionKeepRecent":0}""")
        assertTrue(settings.autoCompressionEnabled)
        assertEquals(75, settings.autoCompressionThresholdPercent)
    }

    @Test
    fun `legacy prefs keys are ignored`() {
        val settings = mutablePreferencesOf(
            intPreferencesKey("auto_compression_keep_recent") to 99,
            stringPreferencesKey("auto_compression_strategy") to "LEGACY_KEEP_RECENT",
        ).toSettings()
        assertTrue(settings.autoCompressionEnabled)
        assertEquals(75, settings.autoCompressionThresholdPercent)
    }

    @Test
    fun `old n no longer leaves a tail in complete turn compression`() {
        // 旧模式 N=5 会在检查点后保留 5 条原文；完整回合必须覆盖到最后一条答复
        val legacy = decode("""{"autoCompressionKeepRecent":5}""")
        assertTrue(legacy.autoCompressionEnabled)
        val messages = fixture()
        assertEquals(messages.size, endOfCompleteTurn(messages))
    }

    @Test
    fun `round trip keeps only the remaining compression fields`() {
        val original = decode("""{"autoCompressionEnabled":true,"autoCompressionThresholdPercent":90}""")
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToJsonElement(original).toString())
        assertEquals(original.autoCompressionEnabled, restored.autoCompressionEnabled)
        assertEquals(original.autoCompressionThresholdPercent, restored.autoCompressionThresholdPercent)
    }
}
