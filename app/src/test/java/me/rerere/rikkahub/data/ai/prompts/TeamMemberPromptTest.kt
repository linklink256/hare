package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.TeamMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 子代理（团队成员）系统提示词回归测试。
 *
 * 只跑真实入口 [resolveChatSystemPrompt]：不 mock、不构造 Android 环境、不重复实现选择逻辑。
 * 断言针对「可观察行为」（继承了什么、没继承什么、身份与规则是否在场），而不是把产品文案
 * 整段抄回来做等值比较——文案改动不应该让测试变成自我证明。
 */
class TeamMemberPromptTest {
    private val leadSentinel = "LEAD-ONLY-SENTINEL"
    private val customSentinel = "CONVERSATION-ONLY-SENTINEL"

    private fun assistant(
        systemPrompt: String = leadSentinel,
        allowCustom: Boolean = false,
        enableAgentTeam: Boolean = true,
    ) = Assistant(
        systemPrompt = systemPrompt,
        allowConversationSystemPrompt = allowCustom,
        enableAgentTeam = enableAgentTeam,
    )

    private fun member(
        name: String = "tester",
        role: String = "负责跑回归并给出证据",
    ) = TeamMember(
        teamId = Uuid.random(),
        name = name,
        conversationId = Uuid.random(),
        role = role,
    )

    /** 提示词里的「- 」条目；身份与规则判断只看这些条目，避免误命中标题或措辞外的段落。 */
    private fun String.rules(): List<String> =
        lines().map { it.trim() }.filter { it.startsWith("- ") }

    @Test
    fun `normal assistant keeps its own system prompt`() {
        val prompt = resolveChatSystemPrompt(assistant(), null, null)

        assertEquals(leadSentinel, prompt)
    }

    @Test
    fun `conversation prompt overrides only when the assistant allows it`() {
        val allowed = resolveChatSystemPrompt(
            assistant(allowCustom = true),
            customSentinel,
            null,
        )
        val notAllowed = resolveChatSystemPrompt(
            assistant(allowCustom = false),
            customSentinel,
            null,
        )

        assertEquals(customSentinel, allowed)
        assertEquals(leadSentinel, notAllowed)
    }

    @Test
    fun `blank conversation prompt falls back to the assistant prompt`() {
        listOf(null, "", "   ", "\n\t ").forEach { blank ->
            val prompt = resolveChatSystemPrompt(assistant(allowCustom = true), blank, null)

            assertEquals("blank custom prompt: <$blank>", leadSentinel, prompt)
        }
    }

    @Test
    fun `member prompt never carries the lead prompt or the conversation prompt`() {
        listOf(false, true).forEach { allowCustom ->
            listOf(false, true).forEach { enableTeam ->
                listOf(null, customSentinel, "  ").forEach { custom ->
                    val prompt = resolveChatSystemPrompt(
                        assistant(allowCustom = allowCustom, enableAgentTeam = enableTeam),
                        custom,
                        member(),
                    )

                    assertTrue(prompt.isNotBlank())
                    assertFalse("lead prompt leaked", prompt.contains(leadSentinel))
                    assertFalse("conversation prompt leaked", prompt.contains(customSentinel))
                }
            }
        }
    }

    @Test
    fun `member prompt states the member identity with its name and role`() {
        val target = member(name = "scout", role = "只负责抓取原始日志")
        val prompt = resolveChatSystemPrompt(assistant(), null, target)

        assertTrue(prompt.contains("scout"))
        assertTrue(prompt.contains("只负责抓取原始日志"))
        assertTrue(
            "成员必须在规则里被明确称为成员/子代理",
            prompt.rules().any { it.contains("成员") || it.contains("子代理") },
        )
        assertTrue(
            "必须说明领队身份不属于自己",
            prompt.rules().any { it.contains("领队") && (it.contains("不是") || it.contains("不属于")) },
        )
    }

    @Test
    fun `member prompt still works without a role and trims the given one`() {
        val noRole = resolveChatSystemPrompt(assistant(), customSentinel, member(role = ""))
        val spacedRole = resolveChatSystemPrompt(
            assistant(),
            customSentinel,
            member(role = "  负责跑单测  "),
        )

        assertTrue(noRole.contains("tester"))
        assertTrue(noRole.rules().isNotEmpty())
        assertTrue(spacedRole.contains("负责跑单测"))
        assertFalse(spacedRole.contains("  负责跑单测  "))
    }

    @Test
    fun `member prompt requires direct execution and forbids nesting agents`() {
        val prompt = resolveChatSystemPrompt(assistant(), null, member())
        val rules = prompt.rules()

        assertTrue(
            "必须要求成员自己动手执行",
            rules.any { it.contains("自己") && (it.contains("执行") || it.contains("动手")) },
        )
        assertTrue(
            "必须禁止创建/派生或向其他代理派活",
            rules.any { it.contains("子代理") && it.contains("不要") },
        )
        assertTrue(
            "必须禁止把任务再转派出去",
            rules.any { it.contains("子代理") && it.contains("不要") && it.contains("派") },
        )
        assertTrue(
            "必须禁止虚构执行结果",
            rules.any { it.contains("虚构") },
        )
    }

    @Test
    fun `member prompt is independent of the assistant team switch`() {
        val target = member()
        val on = resolveChatSystemPrompt(assistant(enableAgentTeam = true), null, target)
        val off = resolveChatSystemPrompt(assistant(enableAgentTeam = false), null, target)

        assertEquals(on, off)
        assertTrue(off.isNotBlank())
        assertFalse(off.contains(leadSentinel))
    }

    @Test
    fun `re-dispatching to the same member keeps the same independent prompt`() {
        val target = member()
        val first = resolveChatSystemPrompt(
            assistant(allowCustom = true),
            customSentinel,
            target,
        )
        // 换一轮：助手提示词、是否允许会话级提示词、团队开关都变了，成员身份不变
        val second = resolveChatSystemPrompt(
            assistant(systemPrompt = "", allowCustom = false, enableAgentTeam = false),
            null,
            target,
        )

        assertEquals(first, second)
        assertFalse(second.contains(leadSentinel))
        assertFalse(second.contains(customSentinel))
        assertNotEquals(assistant().systemPrompt, second)
    }

    @Test
    fun `a different member gets its own name and role`() {
        val first = resolveChatSystemPrompt(assistant(), null, member(name = "scout", role = "抓日志"))
        val second = resolveChatSystemPrompt(assistant(), null, member(name = "builder", role = "编包"))

        assertTrue(first.contains("scout"))
        assertFalse(first.contains("builder"))
        assertTrue(second.contains("builder"))
        assertFalse(second.contains("抓日志"))
    }
}
