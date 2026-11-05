package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TeamNestingRules 的纯规则单测（JUnit4，无 Android/Room 依赖）。
 *
 * R5-D（开放嵌套）后边界公式为 `allowed = !callerIsMember || currentDepth + 1 < maxDepth`：
 * 根 lead 恒可建队；成员只有在「再建一层仍小于 maxDepth」时才允许。
 *
 * 期望值全部是本文件手写的字面量真值表，只使用 CONTRACT-R5D §1 声明的 API；
 * 不从产品实现反推期望，也不引用产品常量拼期望文案（常量只做单点核对）。
 * 本轮不运行测试（executed: false），未覆盖项见 TEST-R5D.md。
 */
class TeamNestingRulesTest {

    // ---------- 1. 常量 ----------

    @Suppress("DEPRECATION")
    @Test
    fun `max depth is one`() {
        // 手写真值：DEFAULT_MAX_DEPTH = 1；MAX_DEPTH 为兼容旧引用的别名，同样 = 1
        assertEquals(1, TeamNestingRules.DEFAULT_MAX_DEPTH)
        assertEquals(1, TeamNestingRules.MAX_DEPTH)
        assertEquals(3, TeamNestingRules.MAX_SUPPORTED_DEPTH)
    }

    // ---------- 2. depthOf ----------

    @Test
    fun `non member caller is the root whatever the parent depth says`() {
        assertEquals(0, TeamNestingRules.depthOf(callerIsMember = false))
        assertEquals(0, TeamNestingRules.depthOf(callerIsMember = false, parentDepth = 0))
        // 手写真值：非成员不属于任何团队，parentDepth 再大也仍是 depth 0
        assertEquals(0, TeamNestingRules.depthOf(callerIsMember = false, parentDepth = 7))
    }

    @Test
    fun `member of a root team sits one level deeper`() {
        assertEquals(1, TeamNestingRules.depthOf(callerIsMember = true))
        assertEquals(1, TeamNestingRules.depthOf(callerIsMember = true, parentDepth = 0))
    }

    @Test
    fun `member of a nested team sits two levels deep`() {
        // 手写真值：成员 -> parentDepth + 1 = 1 + 1 = 2
        assertEquals(2, TeamNestingRules.depthOf(callerIsMember = true, parentDepth = 1))
    }

    // ---------- 2b. normalizeMaxDepth：手写真值表（契约区间 1..3） ----------

    /**
     * 手写真值表：低于 1 抬到 1，高于 3 压到 3，区间内原样返回。
     *   0 -> 1、1 -> 1、2 -> 2、3 -> 3、4 -> 3、-5 -> 1
     */
    @Test
    fun `normalize max depth truth table`() {
        assertEquals(1, TeamNestingRules.normalizeMaxDepth(0))
        assertEquals(1, TeamNestingRules.normalizeMaxDepth(1))
        assertEquals(2, TeamNestingRules.normalizeMaxDepth(2))
        assertEquals(3, TeamNestingRules.normalizeMaxDepth(3))
        assertEquals(3, TeamNestingRules.normalizeMaxDepth(4))
        assertEquals(1, TeamNestingRules.normalizeMaxDepth(-5))
    }

    // ---------- 2c. canNest：手写真值表（parentDepth + 1 < maxDepth） ----------

    /**
     * 手写真值表：
     *   (0,1) -> false（0+1<1 否）
     *   (0,2) -> true （0+1<2 是）
     *   (1,2) -> false（1+1<2 否）
     *   (1,3) -> true （1+1<3 是）
     *   (2,3) -> false（2+1<3 否）
     */
    @Test
    fun `can nest truth table`() {
        assertEquals(false, TeamNestingRules.canNest(parentDepth = 0, maxDepth = 1))
        assertEquals(true, TeamNestingRules.canNest(parentDepth = 0, maxDepth = 2))
        assertEquals(false, TeamNestingRules.canNest(parentDepth = 1, maxDepth = 2))
        assertEquals(true, TeamNestingRules.canNest(parentDepth = 1, maxDepth = 3))
        assertEquals(false, TeamNestingRules.canNest(parentDepth = 2, maxDepth = 3))
    }

    // ---------- 3. planSpawn：默认 maxDepth = DEFAULT_MAX_DEPTH = 1 ----------

    @Test
    fun `root team may create its first teammate`() {
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = false, currentDepth = 0),
        )
    }

    /**
     * R5-D 边界改动：旧期望是 Allowed（旧公式 0 + 1 <= 1）。
     * 新公式 0 + 1 < 1 不成立 -> Rejected，maxDepth=1 时成员一层都建不了。
     */
    @Test
    fun `member at depth zero is rejected when max depth is one`() {
        val plan = TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 0, maxDepth = 1)
        assertTrue(plan is SpawnPlan.Rejected)
        assertEquals(
            "nested teams are disabled (max depth 1); ask the lead to create the teammate",
            (plan as SpawnPlan.Rejected).message,
        )
    }

    @Test
    fun `member at depth one cannot spawn a teammate`() {
        // 默认 maxDepth 即 1：1 + 1 < 1 不成立 -> Rejected
        val plan = TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 1)
        assertTrue(plan is SpawnPlan.Rejected)
        val message = (plan as SpawnPlan.Rejected).message
        assertTrue(message.contains("nested teams are disabled"))
        assertTrue(message.contains("max depth 1"))
    }

    @Test
    fun `rejection message is the documented one`() {
        assertEquals(
            SpawnPlan.Rejected("nested teams are disabled (max depth 1); ask the lead to create the teammate"),
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 1),
        )
    }

    @Test
    fun `member deeper than the limit is rejected as well`() {
        val plan = TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 2)
        assertTrue(plan is SpawnPlan.Rejected)
        assertTrue((plan as SpawnPlan.Rejected).message.contains("max depth 1"))
    }

    // ---------- 4. planSpawn：显式 maxDepth 的真值表（R5-D 新边界） ----------

    /**
     * 手写真值表，列为 maxDepth、行为成员所在 parentDepth：
     *   maxDepth=1: depth 0 -> Rejected（0+1<1 否）、depth 1 -> Rejected、depth 2 -> Rejected
     *   maxDepth=2: depth 0 -> Allowed（0+1<2 是）、depth 1 -> Rejected（1+1<2 否）、depth 2 -> Rejected
     *   maxDepth=3: depth 0 -> Allowed、depth 1 -> Allowed（1+1<3 是）、depth 2 -> Rejected（2+1<3 否）
     */
    @Test
    fun `member spawn truth table for max depth one`() {
        assertEquals(
            SpawnPlan.Rejected("nested teams are disabled (max depth 1); ask the lead to create the teammate"),
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 0, maxDepth = 1),
        )
        assertTrue(TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 1, maxDepth = 1) is SpawnPlan.Rejected)
        assertTrue(TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 2, maxDepth = 1) is SpawnPlan.Rejected)
    }

    @Test
    fun `member spawn truth table for max depth two`() {
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 0, maxDepth = 2),
        )
        assertEquals(
            SpawnPlan.Rejected("nested teams are disabled (max depth 2); ask the lead to create the teammate"),
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 1, maxDepth = 2),
        )
        assertTrue(TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 2, maxDepth = 2) is SpawnPlan.Rejected)
    }

    @Test
    fun `member spawn truth table for max depth three`() {
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 0, maxDepth = 3),
        )
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 1, maxDepth = 3),
        )
        assertEquals(
            SpawnPlan.Rejected("nested teams are disabled (max depth 3); ask the lead to create the teammate"),
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 2, maxDepth = 3),
        )
    }

    @Test
    fun `explicit max depth of two rejects the boundary level`() {
        // R5-D 边界改动：旧期望是 Allowed（旧公式 1 + 1 <= 2）；新公式 1 + 1 < 2 不成立 -> Rejected
        assertEquals(
            SpawnPlan.Rejected("nested teams are disabled (max depth 2); ask the lead to create the teammate"),
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 1, maxDepth = 2),
        )
    }

    @Test
    fun `explicit max depth of two still rejects the third level`() {
        assertEquals(
            SpawnPlan.Rejected("nested teams are disabled (max depth 2); ask the lead to create the teammate"),
            TeamNestingRules.planSpawn(callerIsMember = true, currentDepth = 2, maxDepth = 2),
        )
    }

    // ---------- 5. planSpawn：非成员（根 lead）恒 Allowed ----------

    /**
     * R5-D 边界改动：旧期望是 Rejected（旧公式 1 + 1 <= 1 不成立）。
     * 新公式首项 `!callerIsMember` 短路 -> 无论 depth 与 maxDepth 如何都是 Allowed。
     */
    @Test
    fun `non member caller is always allowed even past the depth limit`() {
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = false, currentDepth = 1),
        )
        // 手写真值：非成员在任意 depth / maxDepth 组合下都不受深度门禁影响
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = false, currentDepth = 0, maxDepth = 1),
        )
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = false, currentDepth = 2, maxDepth = 2),
        )
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = false, currentDepth = 3, maxDepth = 3),
        )
        assertEquals(
            SpawnPlan.Allowed,
            TeamNestingRules.planSpawn(callerIsMember = false, currentDepth = 9, maxDepth = 1),
        )
    }
}
