package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AgentTeamRulesTest {
    // ---------- 名册 ----------

    @Test
    fun `member name must be lowercase and bounded`() {
        assertTrue(AgentTeamRules.isValidMemberName("researcher1"))
        assertTrue(AgentTeamRules.isValidMemberName("reviewer-2"))
        assertTrue(AgentTeamRules.isValidMemberName("a_b"))
        assertFalse(AgentTeamRules.isValidMemberName("Researcher"))   // 大写
        assertFalse(AgentTeamRules.isValidMemberName("1abc"))         // 数字开头
        assertFalse(AgentTeamRules.isValidMemberName(""))             // 空
        assertFalse(AgentTeamRules.isValidMemberName("a".repeat(33))) // 超长
    }

    @Test
    fun `reserved names cannot be taken by teammates`() {
        listOf("lead", "user", "system", "assistant").forEach { reserved ->
            assertFalse("$reserved 不应可用", AgentTeamRules.isValidMemberName(reserved))
        }
    }

    @Test
    fun `normalize lowercases and trims`() {
        assertEquals("reviewer", AgentTeamRules.normalizeMemberName("  ReViewer  "))
    }

    // ---------- 依赖图无环 ----------

    @Test
    fun `self dependency is rejected`() {
        val a = Uuid.random()
        assertTrue(AgentTeamRules.wouldCreateCycle(a, listOf(a)) { emptyList() })
    }

    @Test
    fun `direct cycle is rejected`() {
        val a = Uuid.random()
        val b = Uuid.random()
        // b 依赖 a；现在让 a 依赖 b => 环
        val edges = mapOf(a to emptyList<Uuid>(), b to listOf(a))
        assertTrue(AgentTeamRules.wouldCreateCycle(a, listOf(b)) { edges[it] ?: emptyList() })
    }

    @Test
    fun `indirect cycle through a chain is rejected`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()
        // c 依赖 b，b 依赖 a；现在让 a 依赖 c => a->c->b->a 环
        val edges = mapOf(a to emptyList<Uuid>(), b to listOf(a), c to listOf(b))
        assertTrue(AgentTeamRules.wouldCreateCycle(a, listOf(c)) { edges[it] ?: emptyList() })
    }

    @Test
    fun `diamond dependency is allowed`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val c = Uuid.random()
        val d = Uuid.random()
        // d 依赖 b、c；b、c 都依赖 a => 合法 DAG
        val edges = mapOf(a to emptyList<Uuid>(), b to listOf(a), c to listOf(a))
        assertFalse(AgentTeamRules.wouldCreateCycle(d, listOf(b, c)) { edges[it] ?: emptyList() })
    }

    @Test
    fun `already visited node does not loop forever`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val edges = mapOf(a to listOf(b), b to listOf(a)) // 既有图里已存在环，不应死循环
        assertTrue(AgentTeamRules.wouldCreateCycle(a, listOf(b)) { edges[it] ?: emptyList() })
    }

    // ---------- 派发条件 ----------

    @Test
    fun `task without dependencies is dispatchable`() {
        val team = Uuid.random()
        val t = TeamTask(teamId = team, title = "t")
        assertTrue(AgentTeamRules.isDispatchable(t, listOf(t)))
    }

    @Test
    fun `task waits for all upstream tasks to be done`() {
        val team = Uuid.random()
        val a = TeamTask(teamId = team, title = "a")
        val b = TeamTask(teamId = team, title = "b", blockedBy = listOf(a.id))
        assertFalse(AgentTeamRules.isDispatchable(b, listOf(a, b)))
        assertTrue(AgentTeamRules.isDispatchable(b, listOf(a.copy(status = TeamTaskStatus.DONE), b)))
    }

    @Test
    fun `finished task is never dispatchable`() {
        val team = Uuid.random()
        val t = TeamTask(teamId = team, title = "t", status = TeamTaskStatus.DONE)
        assertFalse(AgentTeamRules.isDispatchable(t, listOf(t)))
    }

    // ---------- 同时运行的名额 ----------

    @Test
    fun `occupied slots count only teammates that are running`() {
        val team = Uuid.random()
        val running = TeamMember(teamId = team, name = "a", conversationId = Uuid.random())
        val idle = TeamMember(teamId = team, name = "b", conversationId = Uuid.random())

        assertEquals(
            1,
            AgentTeamRules.occupiedSlots(listOf(running, idle)) { it == running.conversationId },
        )
    }

    @Test
    fun `failed teammates never occupy a slot`() {
        val team = Uuid.random()
        val failed = TeamMember(
            teamId = team,
            name = "bad",
            conversationId = Uuid.random(),
            status = TeamMemberStatus.FAILED,
        )

        // 即使它的会话看起来还在跑，失败成员也不占名额
        assertEquals(0, AgentTeamRules.occupiedSlots(listOf(failed)) { true })
    }

    @Test
    fun `finished teammates free their slot`() {
        val team = Uuid.random()
        val a = TeamMember(teamId = team, name = "a", conversationId = Uuid.random())
        val b = TeamMember(teamId = team, name = "b", conversationId = Uuid.random())

        assertEquals(0, AgentTeamRules.occupiedSlots(listOf(a, b)) { false })
        assertEquals(1, AgentTeamRules.occupiedSlots(listOf(a, b)) { it == a.conversationId })
    }

    @Test
    fun `empty roster occupies nothing`() {
        assertEquals(0, AgentTeamRules.occupiedSlots(emptyList()) { true })
    }

    // ---------- 状态机 ----------

    @Test
    fun `legal transitions`() {
        assertEquals(TeamTaskStatus.CLAIMED, AgentTeamRules.transition(TeamTaskStatus.PENDING, TeamTaskStatus.CLAIMED))
        assertEquals(TeamTaskStatus.DONE, AgentTeamRules.transition(TeamTaskStatus.CLAIMED, TeamTaskStatus.DONE))
        assertEquals(TeamTaskStatus.FAILED, AgentTeamRules.transition(TeamTaskStatus.CLAIMED, TeamTaskStatus.FAILED))
        assertEquals(TeamTaskStatus.PENDING, AgentTeamRules.transition(TeamTaskStatus.DONE, TeamTaskStatus.PENDING))
    }

    @Test
    fun `illegal transitions are rejected`() {
        // 不能从 pending 直接跳到 done，也不能从终态直接跳回 claimed
        assertNull(AgentTeamRules.transition(TeamTaskStatus.PENDING, TeamTaskStatus.DONE))
        assertNull(AgentTeamRules.transition(TeamTaskStatus.DONE, TeamTaskStatus.CLAIMED))
        assertNull(AgentTeamRules.transition(TeamTaskStatus.FAILED, TeamTaskStatus.DONE))
    }
}
