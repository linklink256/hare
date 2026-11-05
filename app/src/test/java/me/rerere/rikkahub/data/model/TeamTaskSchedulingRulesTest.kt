package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TeamTaskSchedulingRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量真值表；不从产品实现反推期望。
 * 只覆盖 SPEC-R3A §1 的 planDispatch / blockingDependencies；
 * repository/DB 事务、service 门禁、工具层与命名空间见 TEST-R3A.md（未覆盖项）。
 */
class TeamTaskSchedulingRulesTest {

    // ---------- helpers ----------

    private fun task(
        id: Uuid = Uuid.random(),
        teamId: Uuid = TEST_TEAM,
        title: String = "t",
        status: TeamTaskStatus = TeamTaskStatus.PENDING,
        ownerName: String? = null,
        blockedBy: List<Uuid> = emptyList(),
        revision: Long = 1,
    ) = TeamTask(
        id = id,
        teamId = teamId,
        title = title,
        status = status,
        ownerName = ownerName,
        blockedBy = blockedBy,
        revision = revision,
    )

    private fun applied(plan: TaskDispatchPlan): TaskDispatchPlan.Apply {
        assertTrue("期望 Apply，实际 $plan", plan is TaskDispatchPlan.Apply)
        return plan as TaskDispatchPlan.Apply
    }

    private fun rejected(plan: TaskDispatchPlan): TaskDispatchPlan.Rejected {
        assertTrue("期望 Rejected，实际 $plan", plan is TaskDispatchPlan.Rejected)
        return plan as TaskDispatchPlan.Rejected
    }

    // ---------- 1. 无依赖 PENDING → Apply ----------

    @Test
    fun `pending task without dependencies is applied`() {
        val t = task(title = "write spec")
        val plan = applied(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals(t, plan.task)
        assertEquals("alice", plan.ownerName)
    }

    @Test
    fun `pending task ignores other tasks in the board`() {
        val other = task(title = "other", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val t = task(title = "free")
        val plan = applied(TeamTaskSchedulingRules.planDispatch(t, listOf(other, t), "alice"))
        assertEquals(t.id, plan.task.id)
        assertEquals("alice", plan.ownerName)
    }

    // ---------- 2. 单依赖：DONE → Apply；其它 → DEPENDENCIES_UNMET ----------

    @Test
    fun `single dependency that is done allows dispatch`() {
        val dep = task(title = "dep", status = TeamTaskStatus.DONE)
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        val plan = applied(TeamTaskSchedulingRules.planDispatch(t, listOf(dep, t), "alice"))
        assertEquals("alice", plan.ownerName)
    }

    @Test
    fun `single pending dependency blocks dispatch`() {
        val dep = task(title = "dep", status = TeamTaskStatus.PENDING)
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(dep, t), "alice"))
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(dep.id), r.blocking)
    }

    @Test
    fun `single claimed dependency blocks dispatch`() {
        val dep = task(title = "dep", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(dep, t), "alice"))
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(dep.id), r.blocking)
    }

    @Test
    fun `single failed dependency blocks dispatch`() {
        val dep = task(title = "dep", status = TeamTaskStatus.FAILED)
        val t = task(title = "downstream", blockedBy = listOf(dep.id))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(dep, t), "alice"))
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(dep.id), r.blocking)
    }

    // ---------- 3. 多依赖里只有一个未完成 ----------

    @Test
    fun `only the unfinished dependency is reported`() {
        val a = task(title = "a", status = TeamTaskStatus.DONE)
        val b = task(title = "b", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val c = task(title = "c", status = TeamTaskStatus.DONE)
        val t = task(title = "downstream", blockedBy = listOf(a.id, b.id, c.id))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(a, b, c, t), "alice"))
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(b.id), r.blocking)
    }

    @Test
    fun `all dependencies done applies`() {
        val a = task(title = "a", status = TeamTaskStatus.DONE)
        val b = task(title = "b", status = TeamTaskStatus.DONE)
        val t = task(title = "downstream", blockedBy = listOf(a.id, b.id))
        val plan = applied(TeamTaskSchedulingRules.planDispatch(t, listOf(a, b, t), "alice"))
        assertEquals(t, plan.task)
    }

    // ---------- 4. 未知依赖 id ----------

    @Test
    fun `dependency missing from the board counts as unmet`() {
        val ghost = Uuid.random()
        val t = task(title = "downstream", blockedBy = listOf(ghost))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(ghost), r.blocking)
    }

    @Test
    fun `unknown dependency blocks even when the known one is done`() {
        val done = task(title = "done", status = TeamTaskStatus.DONE)
        val ghost = Uuid.random()
        val t = task(title = "downstream", blockedBy = listOf(done.id, ghost))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(done, t), "alice"))
        assertEquals(listOf(ghost), r.blocking)
    }

    // ---------- 5. 终态 ----------

    @Test
    fun `done task is terminal`() {
        val t = task(title = "finished", status = TeamTaskStatus.DONE)
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals(TaskDispatchRejection.TERMINAL, r.reason)
        assertEquals(emptyList<Uuid>(), r.blocking)
    }

    @Test
    fun `failed task is terminal`() {
        val t = task(title = "broken", status = TeamTaskStatus.FAILED)
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals(TaskDispatchRejection.TERMINAL, r.reason)
    }

    @Test
    fun `terminal wins over unmet dependencies`() {
        val dep = task(title = "dep", status = TeamTaskStatus.PENDING)
        val t = task(title = "done", status = TeamTaskStatus.DONE, blockedBy = listOf(dep.id))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(dep, t), "alice"))
        assertEquals(TaskDispatchRejection.TERMINAL, r.reason)
    }

    // ---------- 6. CLAIMED 归属 ----------

    @Test
    fun `task claimed by another member is rejected`() {
        val t = task(title = "busy", status = TeamTaskStatus.CLAIMED, ownerName = "bob")
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals(TaskDispatchRejection.CLAIMED_BY_OTHER, r.reason)
        assertEquals(emptyList<Uuid>(), r.blocking)
    }

    @Test
    fun `task claimed by the same member is applied`() {
        val t = task(title = "mine", status = TeamTaskStatus.CLAIMED, ownerName = "alice")
        val plan = applied(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals(t, plan.task)
        assertEquals("alice", plan.ownerName)
    }

    @Test
    fun `claimed without an owner is treated as free`() {
        val t = task(title = "orphan", status = TeamTaskStatus.CLAIMED, ownerName = null)
        val plan = applied(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals("alice", plan.ownerName)
    }

    @Test
    fun `unmet dependency wins over another owner`() {
        val dep = task(title = "dep", status = TeamTaskStatus.PENDING)
        val t = task(
            title = "busy downstream",
            status = TeamTaskStatus.CLAIMED,
            ownerName = "bob",
            blockedBy = listOf(dep.id),
        )
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(dep, t), "alice"))
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(dep.id), r.blocking)
    }

    // ---------- 7. blockingDependencies：去重 + 原顺序 ----------

    @Test
    fun `blocking dependencies dedupe and keep blockedBy order`() {
        val a = Uuid.random()   // 未完成
        val b = Uuid.random()   // DONE
        val c = Uuid.random()   // 未完成
        val unknown = Uuid.random()
        val taskList = listOf(
            task(id = a, title = "a", status = TeamTaskStatus.PENDING),
            task(id = b, title = "b", status = TeamTaskStatus.DONE),
            task(id = c, title = "c", status = TeamTaskStatus.FAILED),
        )
        // 手写真值：c 先出现、a 再次、b 完成不算、unknown 找不到 -> [c, a, unknown]
        val t = task(blockedBy = listOf(c, a, b, c, unknown, a))
        assertEquals(
            listOf(c, a, unknown),
            TeamTaskSchedulingRules.blockingDependencies(t, taskList),
        )
    }

    @Test
    fun `blocking dependencies is empty when every dependency is done`() {
        val a = task(title = "a", status = TeamTaskStatus.DONE)
        val b = task(title = "b", status = TeamTaskStatus.DONE)
        val t = task(blockedBy = listOf(a.id, b.id))
        assertEquals(
            emptyList<Uuid>(),
            TeamTaskSchedulingRules.blockingDependencies(t, listOf(a, b, t)),
        )
    }

    @Test
    fun `blocking dependencies of a task without edges is empty`() {
        val t = task(title = "free")
        assertEquals(
            emptyList<Uuid>(),
            TeamTaskSchedulingRules.blockingDependencies(t, listOf(t)),
        )
    }

    // ---------- 8. 自依赖 ----------

    @Test
    fun `task blocked by itself is unmet without throwing`() {
        val t = task(id = SELF_ID, title = "loop", blockedBy = listOf(SELF_ID))
        val r = rejected(TeamTaskSchedulingRules.planDispatch(t, listOf(t), "alice"))
        assertEquals(TaskDispatchRejection.DEPENDENCIES_UNMET, r.reason)
        assertEquals(listOf(SELF_ID), r.blocking)
        assertEquals(listOf(SELF_ID), TeamTaskSchedulingRules.blockingDependencies(t, listOf(t)))
    }
}

/** 固定 team / self id：让「同一团队」「自依赖」是字面事实而不是随机巧合 */
private val TEST_TEAM: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
private val SELF_ID: Uuid = Uuid.parse("22222222-2222-2222-2222-222222222222")
