package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.tools.canUseTeamTools
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TeamToolAccess.canUseTeamTools 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 契约：CONTRACT-R5D §3
 *   canUseTeamTools(enabled, isMember, canNest = false) = enabled && (!isMember || canNest)
 *
 * 期望值是本文件手写的 8 行真值表；不从产品实现反推。
 * 本轮不运行测试（executed: false），未覆盖项见 TEST-R5D.md。
 */
class TeamToolAccessTest {

    /**
     * 手写真值表（enabled, isMember, canNest -> 期望）：
     *   true,  true,  true  -> true   成员且允许嵌套：拿领队工具
     *   true,  true,  false -> false  成员且不许嵌套：仍然没有
     *   true,  false, true  -> true   根 lead：canNest 不影响
     *   true,  false, false -> true   根 lead：默认情形
     *   false, true,  true  -> false  功能关闭
     *   false, true,  false -> false  功能关闭
     *   false, false, true  -> false  功能关闭
     *   false, false, false -> false  功能关闭
     */
    @Test
    fun `enabled member with nesting gets the team tools`() {
        assertEquals(true, canUseTeamTools(enabled = true, isMember = true, canNest = true))
    }

    @Test
    fun `enabled member without nesting gets no team tools`() {
        assertEquals(false, canUseTeamTools(enabled = true, isMember = true, canNest = false))
    }

    @Test
    fun `root lead gets the team tools when nesting is allowed`() {
        assertEquals(true, canUseTeamTools(enabled = true, isMember = false, canNest = true))
    }

    @Test
    fun `root lead gets the team tools when nesting is not allowed`() {
        assertEquals(true, canUseTeamTools(enabled = true, isMember = false, canNest = false))
    }

    @Test
    fun `disabled team tools stay disabled for a nesting member`() {
        assertEquals(false, canUseTeamTools(enabled = false, isMember = true, canNest = true))
    }

    @Test
    fun `disabled team tools stay disabled for a plain member`() {
        assertEquals(false, canUseTeamTools(enabled = false, isMember = true, canNest = false))
    }

    @Test
    fun `disabled team tools stay disabled for a root lead with nesting`() {
        assertEquals(false, canUseTeamTools(enabled = false, isMember = false, canNest = true))
    }

    @Test
    fun `disabled team tools stay disabled for a root lead`() {
        assertEquals(false, canUseTeamTools(enabled = false, isMember = false, canNest = false))
    }

    /**
     * 契约默认值 canNest = false：省略时等价于「成员不许嵌套」，
     * 也是 agentTeamMaxDepth = 1 时与旧行为一致的那一路。
     */
    @Test
    fun `omitted canNest defaults to no nesting for members`() {
        assertEquals(true, canUseTeamTools(enabled = true, isMember = false))
        assertEquals(false, canUseTeamTools(enabled = true, isMember = true))
        assertEquals(false, canUseTeamTools(enabled = false, isMember = false))
    }
}
