package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 本轮 debug 修复 B（验收自校验大小写）的回归测试。
 *
 * 契约见 `/root/results/hare-debug-fix-20261104-1545/CONTRACT.md` B 段：
 * 同一 owner/verifier 只改字母大小写不得通过 SELF_ACCEPT；合法不同验收者返回结构不变。
 *
 * 期望值全部是手写的字面量真值表，不引用产品常量拼期望，也不从实现反推。
 * 只测纯规则 `TaskAcceptanceRules.planAccept`；repository/DB 落库见 TESTS.md 的未覆盖清单。
 */
class TaskAcceptanceCaseFixTest {

    private val teamId = Uuid.parse("00000000-0000-0000-0000-0000000000aa")

    private fun task(
        status: TeamTaskStatus = TeamTaskStatus.CLAIMED,
        ownerName: String? = "bob",
        title: String = "write spec",
        revision: Long = 3,
    ) = TeamTask(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000bb"),
        teamId = teamId,
        title = title,
        status = status,
        ownerName = ownerName,
        revision = revision,
    )

    private fun rejected(verifier: String?, owner: String? = "bob"): AcceptancePlan.Rejected {
        val plan = TaskAcceptanceRules.planAccept(task(ownerName = owner), verifier)
        assertTrue("expected Rejected, got $plan", plan is AcceptancePlan.Rejected)
        return plan as AcceptancePlan.Rejected
    }

    private fun applied(verifier: String?, owner: String? = "bob"): AcceptancePlan.Apply {
        val plan = TaskAcceptanceRules.planAccept(task(ownerName = owner), verifier)
        assertTrue("expected Apply, got $plan", plan is AcceptancePlan.Apply)
        return plan as AcceptancePlan.Apply
    }

    // ---------- 1. 同一 owner，仅大小写不同：一律 SELF_ACCEPT ----------

    @Test
    fun `same owner and verifier in the same case is a self accept`() {
        assertEquals(AcceptanceRejection.SELF_ACCEPT, rejected("bob").reason)
    }

    @Test
    fun `uppercase verifier is still a self accept`() {
        assertEquals(AcceptanceRejection.SELF_ACCEPT, rejected("BOB").reason)
    }

    @Test
    fun `capitalized verifier is still a self accept`() {
        assertEquals(AcceptanceRejection.SELF_ACCEPT, rejected("Bob").reason)
    }

    @Test
    fun `mixed case verifier is still a self accept`() {
        assertEquals(AcceptanceRejection.SELF_ACCEPT, rejected("bOb").reason)
    }

    @Test
    fun `lowercase verifier against a capitalized owner is a self accept`() {
        val plan = rejected("bob", owner = "Bob")
        assertEquals(AcceptanceRejection.SELF_ACCEPT, plan.reason)
        // 拒绝消息用存储的 owner 原文，而不是验收者传进来的大小写
        assertEquals("verifier must differ from the executor (Bob)", plan.message)
    }

    @Test
    fun `lowercase verifier against an uppercase owner is a self accept`() {
        assertEquals(AcceptanceRejection.SELF_ACCEPT, rejected("bob", owner = "BOB").reason)
    }

    @Test
    fun `padded mixed case verifier is still a self accept`() {
        assertEquals(AcceptanceRejection.SELF_ACCEPT, rejected("  BOB  ").reason)
    }

    @Test
    fun `non ascii case difference is still a self accept`() {
        assertEquals(AcceptanceRejection.SELF_ACCEPT, rejected("ÄNNE", owner = "änne").reason)
    }

    // ---------- 2. 合法不同验收者：结构不变、大小写原样保留 ----------

    @Test
    fun `a different name is a valid independent verifier`() {
        val plan = applied("alice")
        assertEquals("alice", plan.verifier)
    }

    @Test
    fun `a longer name sharing the prefix is not the owner`() {
        assertEquals("bobb", applied("bobb").verifier)
    }

    @Test
    fun `a shorter name sharing the prefix is not the owner`() {
        assertEquals("bo", applied("bo").verifier)
    }

    @Test
    fun `a name with a digit suffix is not the owner`() {
        assertEquals("bob2", applied("bob2").verifier)
    }

    @Test
    fun `verifier case is preserved for a valid independent verifier`() {
        assertEquals("ALICE", applied("ALICE").verifier)
    }

    @Test
    fun `a valid verifier is only trimmed`() {
        assertEquals("Alice", applied(" Alice ").verifier)
    }

    @Test
    fun `apply keeps the task untouched`() {
        val original = task()
        val plan = TaskAcceptanceRules.planAccept(original, "alice")
        assertTrue("expected Apply, got $plan", plan is AcceptancePlan.Apply)
        val accepted = plan as AcceptancePlan.Apply
        assertSame(original, accepted.task)
        assertEquals(TeamTaskStatus.CLAIMED, accepted.task.status)
        assertEquals(3L, accepted.task.revision)
        assertEquals("write spec", accepted.task.title)
    }

    // ---------- 3. 状态/执行者优先级高于自验收 ----------

    @Test
    fun `not claimed wins over a case insensitive self accept`() {
        val plan = TaskAcceptanceRules.planAccept(task(status = TeamTaskStatus.PENDING), "BOB")
        assertTrue("expected Rejected, got $plan", plan is AcceptancePlan.Rejected)
        assertEquals(AcceptanceRejection.NOT_CLAIMED, (plan as AcceptancePlan.Rejected).reason)
    }

    @Test
    fun `missing owner wins over a case insensitive self accept`() {
        val plan = TaskAcceptanceRules.planAccept(task(ownerName = null), "BOB")
        assertTrue("expected Rejected, got $plan", plan is AcceptancePlan.Rejected)
        assertEquals(AcceptanceRejection.NO_EXECUTOR, (plan as AcceptancePlan.Rejected).reason)
    }
}
