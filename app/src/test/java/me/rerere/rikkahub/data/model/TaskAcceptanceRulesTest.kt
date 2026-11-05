package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TaskAcceptanceRules 与「接受凭据版依赖门禁」的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量真值表，只使用 SPEC-R3B §3/§4 声明的 API；
 * 不从产品实现反推期望，也不引用产品常量拼期望文案。
 * 本轮不运行测试（executed: false），未覆盖项见 TEST-R3B.md。
 */
class TaskAcceptanceRulesTest {

    // ---------- helpers ----------

    private val hex64Upper = "ABCDEF0123456789".repeat(4)
    private val hex64Lower = "abcdef0123456789".repeat(4)

    private fun task(
        id: Uuid = Uuid.random(),
        title: String = "t",
        status: TeamTaskStatus = TeamTaskStatus.PENDING,
        ownerName: String? = null,
        blockedBy: List<Uuid> = emptyList(),
        revision: Long = 1,
    ) = TeamTask(
        id = id,
        teamId = TEST_TEAM,
        title = title,
        status = status,
        ownerName = ownerName,
        blockedBy = blockedBy,
        revision = revision,
    )

    private fun appliedPlan(plan: TaskDispatchPlan): TaskDispatchPlan.Apply {
        assertTrue("期望 Apply，实际 $plan", plan is TaskDispatchPlan.Apply)
        return plan as TaskDispatchPlan.Apply
    }

    private fun rejectedPlan(plan: TaskDispatchPlan): TaskDispatchPlan.Rejected {
        assertTrue("期望 Rejected，实际 $plan", plan is TaskDispatchPlan.Rejected)
        return plan as TaskDispatchPlan.Rejected
    }

    private fun appliedAccept(plan: AcceptancePlan): AcceptancePlan.Apply {
        assertTrue("期望 AcceptancePlan.Apply，实际 $plan", plan is AcceptancePlan.Apply)
        return plan as AcceptancePlan.Apply
    }

    private fun rejectedAccept(plan: AcceptancePlan): AcceptancePlan.Rejected {
        assertTrue("期望 AcceptancePlan.Rejected，实际 $plan", plan is AcceptancePlan.Rejected)
        return plan as AcceptancePlan.Rejected
    }

    // ---------- 1. normalizeRef ----------

    @Test
    fun `normalize ref trims surrounding whitespace`() {
        assertEquals("ref-1", TaskAcceptanceRules.normalizeRef("  ref-1\t"))
    }

    @Test
    fun `normalize ref keeps inner characters untouched`() {
        assertEquals("a b/c", TaskAcceptanceRules.normalizeRef(" a b/c "))
    }

    @Test
    fun `normalize ref keeps a 512 char ref`() {
        val ref = "x".repeat(512)
        assertEquals(ref, TaskAcceptanceRules.normalizeRef(ref))
    }

    @Test
    fun `normalize ref rejects blank and null`() {
        assertNull(TaskAcceptanceRules.normalizeRef(null))
        assertNull(TaskAcceptanceRules.normalizeRef(""))
        assertNull(TaskAcceptanceRules.normalizeRef("   "))
    }

    @Test
    fun `normalize ref rejects 513 chars`() {
        assertNull(TaskAcceptanceRules.normalizeRef("x".repeat(513)))
    }

    // ---------- 2. normalizeSha256 ----------

    @Test
    fun `normalize sha256 lowercases 64 hex`() {
        assertEquals(hex64Lower, TaskAcceptanceRules.normalizeSha256(hex64Upper))
        assertEquals(hex64Lower, TaskAcceptanceRules.normalizeSha256(hex64Lower))
    }

    @Test
    fun `normalize sha256 rejects 63 and 65 chars`() {
        assertNull(TaskAcceptanceRules.normalizeSha256("a".repeat(63)))
        assertNull(TaskAcceptanceRules.normalizeSha256("a".repeat(65)))
    }

    @Test
    fun `normalize sha256 rejects non hex`() {
        assertNull(TaskAcceptanceRules.normalizeSha256("g".repeat(64)))
        assertNull(TaskAcceptanceRules.normalizeSha256(hex64Upper.dropLast(1) + "z"))
    }

    @Test
    fun `normalize sha256 maps null and empty to null`() {
        assertNull(TaskAcceptanceRules.normalizeSha256(null))
        assertNull(TaskAcceptanceRules.normalizeSha256(""))
    }

    // ---------- 3. validate：通过 ----------

    @Test
    fun `validate passes with refs and verifier`() {
        assertNull(
            TaskAcceptanceRules.validate("ref-1", hex64Upper, "ev-1", "lead"),
        )
    }

    @Test
    fun `validate passes when optional sha256 is null or empty`() {
        assertNull(TaskAcceptanceRules.validate("ref-1", null, "ev-1", "lead"))
        assertNull(TaskAcceptanceRules.validate("ref-1", "", "ev-1", "lead"))
    }

    // ---------- 4. validate：六到七条错误文案（只断言前缀字面量） ----------

    @Test
    fun `validate reports blank result_ref`() {
        val msg = TaskAcceptanceRules.validate("   ", null, "ev-1", "lead")
        assertTrue("实际 $msg", msg != null && msg.startsWith("result_ref must not be blank"))
    }

    @Test
    fun `validate reports null result_ref`() {
        val msg = TaskAcceptanceRules.validate(null, null, "ev-1", "lead")
        assertTrue("实际 $msg", msg != null && msg.startsWith("result_ref must not be blank"))
    }

    @Test
    fun `validate reports too long result_ref`() {
        val msg = TaskAcceptanceRules.validate("x".repeat(513), null, "ev-1", "lead")
        assertTrue("实际 $msg", msg != null && msg.startsWith("result_ref too long"))
    }

    @Test
    fun `validate reports sha256 that is not 64 hex`() {
        val msg = TaskAcceptanceRules.validate("ref-1", "abc", "ev-1", "lead")
        assertTrue("实际 $msg", msg != null && msg.startsWith("result_sha256 must be 64 hex chars"))
    }

    @Test
    fun `validate reports blank evidence_ref`() {
        val msg = TaskAcceptanceRules.validate("ref-1", null, "  ", "lead")
        assertTrue("实际 $msg", msg != null && msg.startsWith("evidence_ref must not be blank"))
    }

    @Test
    fun `validate reports too long evidence_ref`() {
        val msg = TaskAcceptanceRules.validate("ref-1", null, "e".repeat(513), "lead")
        assertTrue("实际 $msg", msg != null && msg.startsWith("evidence_ref too long"))
    }

    @Test
    fun `validate reports blank verifier`() {
        val msg = TaskAcceptanceRules.validate("ref-1", null, "ev-1", "  ")
        assertTrue("实际 $msg", msg != null && msg.startsWith("verifier must not be blank"))
    }

    @Test
    fun `validate reports too long verifier`() {
        // 手写字面量：33 个字符 > 32
        val msg = TaskAcceptanceRules.validate("ref-1", null, "ev-1", "v".repeat(33))
        assertTrue("实际 $msg", msg != null && msg.startsWith("verifier too long"))
    }

    // ---------- 5. planAccept：Apply ----------

    @Test
    fun `claimed task with a different verifier is applied with trimmed verifier`() {
        val t = task(title = "write spec", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val plan = appliedAccept(TaskAcceptanceRules.planAccept(t, "  alice  "))
        assertEquals(t, plan.task)
        assertEquals("alice", plan.verifier)
    }

    // ---------- 6. planAccept：NOT_CLAIMED ----------

    @Test
    fun `pending task cannot be accepted`() {
        val t = task(title = "write spec", status = TeamTaskStatus.PENDING)
        val r = rejectedAccept(TaskAcceptanceRules.planAccept(t, "lead"))
        assertEquals(AcceptanceRejection.NOT_CLAIMED, r.reason)
        assertEquals("task write spec is PENDING; only a claimed task can be accepted", r.message)
    }

    @Test
    fun `done task cannot be accepted again`() {
        val t = task(
            title = "write spec",
            status = TeamTaskStatus.DONE,
            ownerName = "bob",
            revision = 2,
        )
        val r = rejectedAccept(TaskAcceptanceRules.planAccept(t, "lead"))
        assertEquals(AcceptanceRejection.NOT_CLAIMED, r.reason)
        assertEquals("task write spec is DONE; only a claimed task can be accepted", r.message)
    }

    @Test
    fun `failed task cannot be accepted`() {
        val t = task(title = "write spec", status = TeamTaskStatus.FAILED, ownerName = "bob")
        val r = rejectedAccept(TaskAcceptanceRules.planAccept(t, "lead"))
        assertEquals(AcceptanceRejection.NOT_CLAIMED, r.reason)
        assertEquals("task write spec is FAILED; only a claimed task can be accepted", r.message)
    }

    // ---------- 7. planAccept：NO_EXECUTOR / SELF_ACCEPT ----------

    @Test
    fun `claimed task without an owner cannot be verified`() {
        val t = task(title = "write spec", status = TeamTaskStatus.CLAIMED, ownerName = null)
        val r = rejectedAccept(TaskAcceptanceRules.planAccept(t, "lead"))
        assertEquals(AcceptanceRejection.NO_EXECUTOR, r.reason)
        assertEquals("task write spec has no executor; cannot verify independence", r.message)
    }

    @Test
    fun `verifier equal to the owner is a self accept`() {
        val t = task(title = "write spec", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val r = rejectedAccept(TaskAcceptanceRules.planAccept(t, "bob"))
        assertEquals(AcceptanceRejection.SELF_ACCEPT, r.reason)
        assertEquals("verifier must differ from the executor (bob)", r.message)
    }

    @Test
    fun `self accept is detected after trimming the verifier`() {
        val t = task(title = "write spec", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val r = rejectedAccept(TaskAcceptanceRules.planAccept(t, "  bob  "))
        assertEquals(AcceptanceRejection.SELF_ACCEPT, r.reason)
    }

    // ---------- 8. blockingDependenciesWithAcceptance ----------

    @Test
    fun `done dependency without acceptance still blocks`() {
        val dep = task(title = "dep", status = TeamTaskStatus.DONE, revision = 2)
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        assertEquals(
            listOf(dep.id),
            TeamTaskSchedulingRules.blockingDependenciesWithAcceptance(t, listOf(dep, t), emptySet()),
        )
    }

    @Test
    fun `done dependency with acceptance does not block`() {
        val dep = task(title = "dep", status = TeamTaskStatus.DONE, revision = 2)
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        assertEquals(
            emptyList<Uuid>(),
            TeamTaskSchedulingRules.blockingDependenciesWithAcceptance(
                t,
                listOf(dep, t),
                setOf(dep.id),
            ),
        )
    }

    @Test
    fun `unknown dependency blocks even when accepted ids are supplied`() {
        val ghost = Uuid.random()
        val other = task(title = "other", status = TeamTaskStatus.DONE, revision = 2)
        val t = task(title = "downstream", blockedBy = listOf(ghost))
        assertEquals(
            listOf(ghost),
            TeamTaskSchedulingRules.blockingDependenciesWithAcceptance(
                t,
                listOf(other, t),
                setOf(other.id),
            ),
        )
    }

    @Test
    fun `acceptance does not rescue a dependency that is not done`() {
        val dep = task(title = "dep", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        assertEquals(
            listOf(dep.id),
            TeamTaskSchedulingRules.blockingDependenciesWithAcceptance(
                t,
                listOf(dep, t),
                setOf(dep.id),
            ),
        )
    }

    @Test
    fun `blocking dependencies with acceptance dedupe and keep order`() {
        val a = Uuid.random()   // DONE 但无凭据 -> 阻塞
        val b = Uuid.random()   // DONE 且有凭据 -> 不阻塞
        val c = Uuid.random()   // FAILED -> 阻塞
        val unknown = Uuid.random()
        val board = listOf(
            task(id = a, title = "a", status = TeamTaskStatus.DONE, revision = 2),
            task(id = b, title = "b", status = TeamTaskStatus.DONE, revision = 2),
            task(id = c, title = "c", status = TeamTaskStatus.FAILED),
        )
        // 手写真值：c 先出现、a 再次、b 已接受不算、unknown 找不到 -> [c, a, unknown]
        val t = task(blockedBy = listOf(c, a, b, c, unknown, a))
        assertEquals(
            listOf(c, a, unknown),
            TeamTaskSchedulingRules.blockingDependenciesWithAcceptance(t, board, setOf(b)),
        )
    }

    // ---------- 9. planDispatchWithAcceptance ----------

    @Test
    fun `dispatch is rejected when a dependency is done but not accepted`() {
        val dep = task(title = "dep", status = TeamTaskStatus.DONE, revision = 2)
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        val r = rejectedPlan(
            TeamTaskSchedulingRules.planDispatchWithAcceptance(t, listOf(dep, t), emptySet(), "alice"),
        )
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(dep.id), r.blocking)
    }

    @Test
    fun `dispatch is applied when every dependency is done and accepted`() {
        val a = task(title = "a", status = TeamTaskStatus.DONE, revision = 2)
        val b = task(title = "b", status = TeamTaskStatus.DONE, revision = 3)
        val t = task(title = "downstream", blockedBy = listOf(a.id, b.id))
        val plan = appliedPlan(
            TeamTaskSchedulingRules.planDispatchWithAcceptance(
                t,
                listOf(a, b, t),
                setOf(a.id, b.id),
                "alice",
            ),
        )
        assertEquals(t, plan.task)
        assertEquals("alice", plan.ownerName)
    }

    @Test
    fun `dispatch of a terminal task is rejected`() {
        val t = task(title = "finished", status = TeamTaskStatus.DONE, revision = 2)
        val r = rejectedPlan(
            TeamTaskSchedulingRules.planDispatchWithAcceptance(t, listOf(t), setOf(t.id), "alice"),
        )
        assertEquals(TaskDispatchRejection.TERMINAL, r.reason)
        assertEquals(emptyList<Uuid>(), r.blocking)
    }

    @Test
    fun `dispatch of a task claimed by another member is rejected`() {
        val t = task(title = "busy", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val r = rejectedPlan(
            TeamTaskSchedulingRules.planDispatchWithAcceptance(t, listOf(t), emptySet(), "alice"),
        )
        assertEquals(TaskDispatchRejection.CLAIMED_BY_OTHER, r.reason)
        assertEquals(emptyList<Uuid>(), r.blocking)
    }

    @Test
    fun `dispatch without dependencies ignores the accepted set`() {
        val other = task(title = "other", status = TeamTaskStatus.PENDING)
        val t = task(title = "free")
        val plan = appliedPlan(
            TeamTaskSchedulingRules.planDispatchWithAcceptance(
                t,
                listOf(other, t),
                setOf(other.id),
                "alice",
            ),
        )
        assertEquals(t.id, plan.task.id)
        assertEquals("alice", plan.ownerName)
    }
}

/** 固定 team id：让「同一团队」是字面事实而不是随机巧合 */
private val TEST_TEAM: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
