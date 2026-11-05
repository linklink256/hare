package me.rerere.rikkahub.data.model

/** 根预算决策：Granted 给出本次 Run 的步数上限，Rejected 表示根账本已耗尽 */
sealed interface RootBudgetPlan {
    data class Granted(val stepLimit: Int) : RootBudgetPlan

    /** 拒绝只回数值（已用/额度），给人看的文案由调用方按契约固定文案拼 */
    data class Rejected(val used: Int, val limit: Int) : RootBudgetPlan
}

/**
 * 根团队步数总账的纯规则（无 Android 依赖，可 JVM 单测）。
 *
 * 根账本额度由同一根团队下的所有层共享；任务级额度是它之下的第二层约束。
 */
object TeamBudgetRules {
    const val DEFAULT_ROOT_STEP_LIMIT = 1024
    const val MIN_ROOT_STEP_LIMIT = 64
    const val MAX_ROOT_STEP_LIMIT = 100_000

    /** 设置值夹到合法区间，0/负数/超大值都不原样进入账本 */
    fun normalizeLimit(raw: Int): Int = raw.coerceIn(MIN_ROOT_STEP_LIMIT, MAX_ROOT_STEP_LIMIT)

    /** 根账本剩余额度，永不为负 */
    fun remaining(limit: Int, used: Int): Int = (limit - used).coerceAtLeast(0)

    /**
     * 派活前算本次 Run 的 stepLimit。
     * 根账本耗尽即 fail-closed；否则取 requested / 根剩余 / 任务剩余 三者的最小值。
     */
    fun planRootBudget(
        limit: Int,
        used: Int,
        taskRemaining: Int?,
        requested: Int = AgentRunRules.DEFAULT_STEP_LIMIT,
    ): RootBudgetPlan {
        val left = remaining(limit, used)
        if (left == 0) return RootBudgetPlan.Rejected(used = used, limit = limit)
        // 没有任务级额度时只受 requested 与根账本约束
        val cap = taskRemaining ?: Int.MAX_VALUE
        return RootBudgetPlan.Granted(stepLimit = minOf(requested, left, cap).coerceAtLeast(0))
    }
}
