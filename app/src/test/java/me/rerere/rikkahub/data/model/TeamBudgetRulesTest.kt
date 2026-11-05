package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TeamBudgetRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * 契约：CONTRACT-R5D §1
 *   normalizeLimit(raw)          = raw coerceIn(64, 100_000)
 *   remaining(limit, used)       = (limit - used) coerceAtLeast 0
 *   planRootBudget(limit, used, taskRemaining, requested)
 *       remaining == 0 -> Rejected(used, limit)
 *       否则           -> Granted(min(requested, remaining, taskRemaining ?: MAX))
 *
 * 期望值全部是本文件手写的字面量真值表；不从产品实现反推，也不用产品常量拼期望，
 * 常量值只做单点核对。本轮不运行测试（executed: false），未覆盖项见 TEST-R5D.md。
 */
class TeamBudgetRulesTest {

    // ---------- 1. 常量（手写核对，不参与其它用例的期望计算） ----------

    @Test
    fun `documented root budget constants`() {
        assertEquals(1024, TeamBudgetRules.DEFAULT_ROOT_STEP_LIMIT)
        assertEquals(64, TeamBudgetRules.MIN_ROOT_STEP_LIMIT)
        assertEquals(100_000, TeamBudgetRules.MAX_ROOT_STEP_LIMIT)
    }

    // ---------- 2. normalizeLimit 真值表 ----------

    /**
     * 手写真值表：低于 64 一律抬到 64，高于 100000 一律压到 100000，区间内原样返回。
     */
    @Test
    fun `normalize limit truth table`() {
        assertEquals(64, TeamBudgetRules.normalizeLimit(0))
        assertEquals(64, TeamBudgetRules.normalizeLimit(-5))
        assertEquals(64, TeamBudgetRules.normalizeLimit(1))
        assertEquals(64, TeamBudgetRules.normalizeLimit(63))
        assertEquals(64, TeamBudgetRules.normalizeLimit(64))
        assertEquals(65, TeamBudgetRules.normalizeLimit(65))
        assertEquals(1024, TeamBudgetRules.normalizeLimit(1024))
        assertEquals(100_000, TeamBudgetRules.normalizeLimit(100_000))
        assertEquals(100_000, TeamBudgetRules.normalizeLimit(100_001))
        assertEquals(100_000, TeamBudgetRules.normalizeLimit(123_456))
        assertEquals(100_000, TeamBudgetRules.normalizeLimit(Int.MAX_VALUE))
        assertEquals(64, TeamBudgetRules.normalizeLimit(Int.MIN_VALUE))
    }

    // ---------- 3. remaining 真值表 ----------

    @Test
    fun `remaining is positive while the budget is not exhausted`() {
        // 手写真值：1024 - 0 = 1024
        assertEquals(1024, TeamBudgetRules.remaining(limit = 1024, used = 0))
        // 手写真值：100 - 30 = 70
        assertEquals(70, TeamBudgetRules.remaining(limit = 100, used = 30))
        // 手写真值：仅差一步 1024 - 1023 = 1
        assertEquals(1, TeamBudgetRules.remaining(limit = 1024, used = 1023))
    }

    @Test
    fun `remaining is zero exactly when the budget is exhausted`() {
        // 手写真值：1024 - 1024 = 0
        assertEquals(0, TeamBudgetRules.remaining(limit = 1024, used = 1024))
        // 手写真值：零额度账本 0 - 0 = 0
        assertEquals(0, TeamBudgetRules.remaining(limit = 0, used = 0))
    }

    @Test
    fun `remaining never goes below zero when over used`() {
        // 手写真值：1024 - 1500 = -476 -> 夹到 0
        assertEquals(0, TeamBudgetRules.remaining(limit = 1024, used = 1500))
        // 手写真值：100 - 101 = -1 -> 夹到 0
        assertEquals(0, TeamBudgetRules.remaining(limit = 100, used = 101))
    }

    // ---------- 4. planRootBudget：Rejected 分支（根账本耗尽，fail-closed） ----------

    @Test
    fun `exhausted root budget is rejected even when the task still has budget`() {
        assertEquals(
            RootBudgetPlan.Rejected(used = 1024, limit = 1024),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 1024,
                taskRemaining = 500,
                requested = 256,
            ),
        )
    }

    @Test
    fun `over used root budget is rejected and reports the raw numbers`() {
        assertEquals(
            RootBudgetPlan.Rejected(used = 1500, limit = 1024),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 1500,
                taskRemaining = null,
                requested = 256,
            ),
        )
    }

    @Test
    fun `zero limit root budget is rejected`() {
        // 手写真值：0 - 0 = 0 -> Rejected
        assertEquals(
            RootBudgetPlan.Rejected(used = 0, limit = 0),
            TeamBudgetRules.planRootBudget(
                limit = 0,
                used = 0,
                taskRemaining = 64,
                requested = 32,
            ),
        )
    }

    @Test
    fun `rejection is a Rejected instance not a granted zero`() {
        val plan = TeamBudgetRules.planRootBudget(limit = 1024, used = 1024, taskRemaining = 500, requested = 256)
        assertTrue(plan is RootBudgetPlan.Rejected)
    }

    // ---------- 5. planRootBudget：Granted 分支取最小值 ----------

    @Test
    fun `task remaining is tighter than requested and root remaining`() {
        // 手写真值：min(requested 256, remaining 1000, task 10) = 10
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 10),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 24,
                taskRemaining = 10,
                requested = 256,
            ),
        )
    }

    @Test
    fun `requested is tighter than task and root remaining`() {
        // 手写真值：min(requested 100, remaining 1000, task 500) = 100
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 100),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 24,
                taskRemaining = 500,
                requested = 100,
            ),
        )
    }

    @Test
    fun `root remaining is tighter than requested and task`() {
        // 手写真值：min(requested 256, remaining 24, task 500) = 24
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 24),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 1000,
                taskRemaining = 500,
                requested = 256,
            ),
        )
    }

    @Test
    fun `three way minimum picks the smallest of all three`() {
        // 手写真值：min(requested 256, remaining 24, task 10) = 10
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 10),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 1000,
                taskRemaining = 10,
                requested = 256,
            ),
        )
        // 手写真值：min(requested 100, remaining 24, task 5) = 5
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 5),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 1000,
                taskRemaining = 5,
                requested = 100,
            ),
        )
    }

    @Test
    fun `absent task budget falls back to requested and root remaining`() {
        // 手写真值：taskRemaining = null -> min(requested 256, remaining 124) = 124
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 124),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 900,
                taskRemaining = null,
                requested = 256,
            ),
        )
        // 手写真值：taskRemaining = null -> min(requested 128, remaining 2048) = 128
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 128),
            TeamBudgetRules.planRootBudget(
                limit = 2048,
                used = 0,
                taskRemaining = null,
                requested = 128,
            ),
        )
    }

    /**
     * 默认 requested 取 AgentRunRules.DEFAULT_STEP_LIMIT（契约 §1，手写真值 256）。
     * 这里刻意省略 requested 实参，只验证「省略时的默认值」，不参与其它用例的期望推导。
     */
    @Test
    fun `omitted requested uses the documented default step limit`() {
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 256),
            TeamBudgetRules.planRootBudget(
                limit = 100_000,
                used = 0,
                taskRemaining = null,
            ),
        )
    }

    /**
     * 契约字面：只有 remaining == 0 才 Rejected，taskRemaining = 0 走 Granted 分支并取到 0。
     * 记录为真值表条目，避免调用方误以为规则会替它拒绝任务级零额度。
     */
    @Test
    fun `zero task remaining is granted zero per the reported formula`() {
        assertEquals(
            RootBudgetPlan.Granted(stepLimit = 0),
            TeamBudgetRules.planRootBudget(
                limit = 1024,
                used = 24,
                taskRemaining = 0,
                requested = 256,
            ),
        )
    }
}
