package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TeamMemberToolScope 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量真值表，只使用 SPEC-R5A §1 声明的 API；
 * 不从产品实现反推期望，也不引用产品常量拼期望文案。
 * 本轮不运行测试（executed: false），未覆盖项见 TEST-R5A.md。
 */
class TeamMemberToolScopeTest {

    // ---------- 1. normalize ----------

    @Test
    fun `normalize of an empty list stays empty`() {
        assertEquals(emptyList<String>(), TeamMemberToolScope.normalize(emptyList()))
    }

    @Test
    fun `normalize trims surrounding whitespace`() {
        assertEquals(
            listOf("shell", "read_file"),
            TeamMemberToolScope.normalize(listOf("  shell  ", " read_file")),
        )
    }

    @Test
    fun `normalize drops empty and blank entries`() {
        assertEquals(
            listOf("a", "b"),
            TeamMemberToolScope.normalize(listOf("a", "", "   ", "b", "\t")),
        )
    }

    @Test
    fun `normalize removes duplicates keeping the first occurrence`() {
        assertEquals(
            listOf("shell", "read_file"),
            TeamMemberToolScope.normalize(listOf("shell", "read_file", "shell")),
        )
        assertEquals(
            listOf("shell"),
            TeamMemberToolScope.normalize(listOf(" shell ", "shell", "  shell  ")),
        )
    }

    @Test
    fun `normalize preserves the input order of distinct names`() {
        assertEquals(
            listOf("zeta", "alpha", "mid"),
            TeamMemberToolScope.normalize(listOf("zeta", "alpha", "mid")),
        )
    }

    // ---------- 2. validate ----------

    @Test
    fun `validate accepts an empty list`() {
        assertNull(TeamMemberToolScope.validate(emptyList()))
    }

    @Test
    fun `validate accepts a normal list`() {
        assertNull(TeamMemberToolScope.validate(listOf("shell", "read_file", "report_back")))
    }

    @Test
    fun `validate rejects thirty three entries with the counted message`() {
        val raw = (1..33).map { "tool_$it" }
        assertEquals("too many allowed tools (33 > 32)", TeamMemberToolScope.validate(raw))
    }

    @Test
    fun `validate accepts exactly the maximum number of entries`() {
        val raw = (1..32).map { "tool_$it" }
        assertNull(TeamMemberToolScope.validate(raw))
    }

    @Test
    fun `validate rejects a whitespace only entry as blank`() {
        assertEquals(
            "tool name must not be blank",
            TeamMemberToolScope.validate(listOf("shell", "   ")),
        )
    }

    @Test
    fun `validate rejects an empty string entry as blank`() {
        assertEquals("tool name must not be blank", TeamMemberToolScope.validate(listOf("")))
    }

    @Test
    fun `validate rejects a sixty five character name as too long`() {
        assertEquals(
            "tool name too long (65 > 64)",
            TeamMemberToolScope.validate(listOf("a".repeat(65))),
        )
    }

    @Test
    fun `validate accepts a sixty four character name`() {
        assertNull(TeamMemberToolScope.validate(listOf("a".repeat(64))))
    }

    // ---------- 3. allows ----------

    @Test
    fun `allows with an empty allowlist is unrestricted`() {
        assertTrue(TeamMemberToolScope.allows(emptyList(), "shell"))
        assertTrue(TeamMemberToolScope.allows(emptyList(), "some_future_tool"))
    }

    @Test
    fun `allows returns true for an exact hit`() {
        assertTrue(TeamMemberToolScope.allows(listOf("shell", "read_file"), "read_file"))
    }

    @Test
    fun `allows returns false for a miss`() {
        assertFalse(TeamMemberToolScope.allows(listOf("shell", "read_file"), "write_file"))
    }

    @Test
    fun `allows matches the name exactly and case sensitively`() {
        assertFalse(TeamMemberToolScope.allows(listOf("shell"), "Shell"))
        assertFalse(TeamMemberToolScope.allows(listOf("shell"), "shellx"))
    }

    @Test
    fun `allows always keeps report_back even when it is not listed`() {
        assertTrue(TeamMemberToolScope.allows(listOf("shell"), "report_back"))
        assertTrue(TeamMemberToolScope.allows(emptyList(), "report_back"))
    }

    @Test
    fun `allows always keeps ask_lead even when it is not listed`() {
        assertTrue(TeamMemberToolScope.allows(listOf("shell"), "ask_lead"))
        assertTrue(TeamMemberToolScope.allows(emptyList(), "ask_lead"))
    }

    // ---------- 4. filter ----------

    @Test
    fun `filter with an empty allowlist keeps every tool in order`() {
        assertEquals(
            listOf("a", "b", "c"),
            TeamMemberToolScope.filter(emptyList(), listOf("a", "b", "c")),
        )
    }

    @Test
    fun `filter keeps only the hits in the original order`() {
        assertEquals(
            listOf("a", "b"),
            TeamMemberToolScope.filter(listOf("b", "a"), listOf("a", "b", "c", "d")),
        )
    }

    @Test
    fun `filter keeps the always allowed tools that are present in the input`() {
        assertEquals(
            listOf("report_back", "shell", "ask_lead"),
            TeamMemberToolScope.filter(
                listOf("shell"),
                listOf("report_back", "shell", "ask_lead", "read_file"),
            ),
        )
    }

    @Test
    fun `filter does not inject always allowed tools missing from the input`() {
        assertEquals(
            listOf("shell"),
            TeamMemberToolScope.filter(listOf("shell"), listOf("shell")),
        )
    }

    @Test
    fun `filter of an empty tool list stays empty`() {
        assertEquals(
            emptyList<String>(),
            TeamMemberToolScope.filter(listOf("shell"), emptyList()),
        )
    }
}
