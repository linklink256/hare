package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TeamAdmissionRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 期望值全部是本文件手写的字面量真值表，只使用 SPEC-R3C §1 声明的 API；
 * 不从产品实现反推期望，也不引用产品常量拼期望文案（DEFAULT_TASK_STEP_BUDGET 只做单点核对）。
 * 本轮不运行测试（executed: false），未覆盖项见 TEST-R3C.md。
 */
class TeamAdmissionRulesTest {

    // ---------- 1. normalizeLimit ----------

    @Test
    fun `normalize limit clamps non positive input to one`() {
        assertEquals(1, TeamAdmissionRules.normalizeLimit(0))
        assertEquals(1, TeamAdmissionRules.normalizeLimit(-3))
    }

    @Test
    fun `normalize limit keeps positive input as is`() {
        assertEquals(8, TeamAdmissionRules.normalizeLimit(8))
        assertEquals(1, TeamAdmissionRules.normalizeLimit(1))
    }

    // ---------- 2. planDispatch：未排队 + 未满 → Admit ----------

    @Test
    fun `dispatch with empty slots is admitted`() {
        assertEquals(
            AdmissionDecision.Admit,
            TeamAdmissionRules.planDispatch(occupied = 0, limit = 8, targetHasActiveJob = false),
        )
    }

    @Test
    fun `dispatch below the limit is admitted`() {
        assertEquals(
            AdmissionDecision.Admit,
            TeamAdmissionRules.planDispatch(occupied = 7, limit = 8, targetHasActiveJob = false),
        )
    }

    // ---------- 3. planDispatch：满额 → Reject(occupied, limit) ----------

    @Test
    fun `dispatch at the limit is rejected with the counted slots`() {
        assertEquals(
            AdmissionDecision.Reject(occupied = 8, limit = 8),
            TeamAdmissionRules.planDispatch(occupied = 8, limit = 8, targetHasActiveJob = false),
        )
    }

    @Test
    fun `dispatch above the limit is rejected and reports the raw occupancy`() {
        assertEquals(
            AdmissionDecision.Reject(occupied = 9, limit = 8),
            TeamAdmissionRules.planDispatch(occupied = 9, limit = 8, targetHasActiveJob = false),
        )
    }

    // ---------- 4. planDispatch：limit <= 0 按 1 处理 ----------

    @Test
    fun `dispatch with zero limit admits only the first slot`() {
        // 手写真值：limit=0 归一到 1 -> 0 个占用 Admit，1 个占用 Reject(1, 1)
        assertEquals(
            AdmissionDecision.Admit,
            TeamAdmissionRules.planDispatch(occupied = 0, limit = 0, targetHasActiveJob = false),
        )
        assertEquals(
            AdmissionDecision.Reject(occupied = 1, limit = 1),
            TeamAdmissionRules.planDispatch(occupied = 1, limit = 0, targetHasActiveJob = false),
        )
    }

    @Test
    fun `dispatch with negative limit behaves like a limit of one`() {
        assertEquals(
            AdmissionDecision.Reject(occupied = 2, limit = 1),
            TeamAdmissionRules.planDispatch(occupied = 2, limit = -1, targetHasActiveJob = false),
        )
    }

    // ---------- 5. planDispatch：目标自己已排队优先于 Reject ----------

    @Test
    fun `already queued target wins over a full team`() {
        assertEquals(
            AdmissionDecision.AlreadyQueued,
            TeamAdmissionRules.planDispatch(occupied = 8, limit = 8, targetHasActiveJob = true),
        )
    }

    @Test
    fun `already queued target is reported even with free slots`() {
        assertEquals(
            AdmissionDecision.AlreadyQueued,
            TeamAdmissionRules.planDispatch(occupied = 0, limit = 8, targetHasActiveJob = true),
        )
    }

    // ---------- 6. canAdmit ----------

    @Test
    fun `gate admits while active permits stay below the limit`() {
        assertTrue(TeamAdmissionRules.canAdmit(active = 0, limit = 8))
        assertTrue(TeamAdmissionRules.canAdmit(active = 7, limit = 8))
    }

    @Test
    fun `gate refuses once the limit is reached`() {
        assertFalse(TeamAdmissionRules.canAdmit(active = 8, limit = 8))
    }

    @Test
    fun `gate normalizes non positive limits to one`() {
        assertTrue(TeamAdmissionRules.canAdmit(active = 0, limit = 0))
        assertFalse(TeamAdmissionRules.canAdmit(active = 3, limit = -1))
        assertTrue(TeamAdmissionRules.canAdmit(active = 0, limit = -1))
    }

    // ---------- 7. remainingTaskBudget（默认 256 步总账）----------

    @Test
    fun `default task step budget is 256`() {
        assertEquals(256, TeamAdmissionRules.DEFAULT_TASK_STEP_BUDGET)
        assertEquals(256, TeamAdmissionRules.remainingTaskBudget(usedByPreviousAttempts = 0))
    }

    @Test
    fun `remaining budget subtracts the steps used by earlier attempts`() {
        assertEquals(56, TeamAdmissionRules.remainingTaskBudget(usedByPreviousAttempts = 200))
    }

    @Test
    fun `remaining budget hits zero exactly when the budget is used up`() {
        assertEquals(0, TeamAdmissionRules.remainingTaskBudget(usedByPreviousAttempts = 256))
    }

    @Test
    fun `remaining budget never goes negative`() {
        assertEquals(0, TeamAdmissionRules.remainingTaskBudget(usedByPreviousAttempts = 300))
    }

    @Test
    fun `remaining budget honours an explicit budget argument`() {
        // 手写真值：budget 300 - used 200 = 100
        assertEquals(100, TeamAdmissionRules.remainingTaskBudget(usedByPreviousAttempts = 200, budget = 300))
    }

    // ---------- 8. planAttempt ----------

    @Test
    fun `first attempt is granted the whole task budget`() {
        assertEquals(
            AttemptBudgetPlan.Granted(stepLimit = 256),
            TeamAdmissionRules.planAttempt(usedByPreviousAttempts = 0),
        )
    }

    @Test
    fun `next attempt is granted only the remaining steps`() {
        assertEquals(
            AttemptBudgetPlan.Granted(stepLimit = 1),
            TeamAdmissionRules.planAttempt(usedByPreviousAttempts = 255),
        )
    }

    @Test
    fun `attempt is exhausted exactly when the budget is used up`() {
        assertEquals(
            AttemptBudgetPlan.Exhausted(used = 256, budget = 256),
            TeamAdmissionRules.planAttempt(usedByPreviousAttempts = 256),
        )
    }

    @Test
    fun `attempt stays exhausted past the budget and reports the raw usage`() {
        assertEquals(
            AttemptBudgetPlan.Exhausted(used = 300, budget = 256),
            TeamAdmissionRules.planAttempt(usedByPreviousAttempts = 300),
        )
    }

    @Test
    fun `attempt with an explicit budget is exhausted once it is overrun`() {
        // 手写真值：budget 4、已用 10 -> Exhausted(10, 4)，不会变成 Granted(0)
        assertEquals(
            AttemptBudgetPlan.Exhausted(used = 10, budget = 4),
            TeamAdmissionRules.planAttempt(usedByPreviousAttempts = 10, budget = 4),
        )
        assertEquals(
            AttemptBudgetPlan.Granted(stepLimit = 4),
            TeamAdmissionRules.planAttempt(usedByPreviousAttempts = 0, budget = 4),
        )
    }
}
