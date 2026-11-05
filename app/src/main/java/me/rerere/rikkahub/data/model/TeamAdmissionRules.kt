package me.rerere.rikkahub.data.model

/** 派活前的名额判定：同一份规则同时服务 checkCapacity 与执行期 gate */
sealed interface AdmissionDecision {
    data object Admit : AdmissionDecision

    /** 目标会话已经有一个占位 Job（排队/惰性/取消中）：不再重复占用名额，也不再开新轮 */
    data object AlreadyQueued : AdmissionDecision

    data class Reject(val occupied: Int, val limit: Int) : AdmissionDecision
}

/** 任务级预算判定：同一 task 的多次尝试共享一份步数额度 */
sealed interface AttemptBudgetPlan {
    data class Granted(val stepLimit: Int) : AttemptBudgetPlan

    data class Exhausted(val used: Int, val budget: Int) : AttemptBudgetPlan
}

object TeamAdmissionRules {
    /** 单个任务（跨 attempt）的步数总账上限；与 Run 默认 stepLimit 一致 */
    const val DEFAULT_TASK_STEP_BUDGET = 256

    /** limit <= 0 按 1 处理（与既有 gate 的 coerceAtLeast(1) 一致） */
    fun normalizeLimit(limit: Int): Int = limit.coerceAtLeast(1)

    /**
     * 派活前判定。[occupied] 是当前已有占位 Job 的队友数，[targetHasActiveJob] 表示目标会话
     * 自己已经有一个 Job。目标自己已排队时优先返回 [AdmissionDecision.AlreadyQueued]。
     */
    fun planDispatch(occupied: Int, limit: Int, targetHasActiveJob: Boolean): AdmissionDecision {
        val normalized = normalizeLimit(limit)
        return when {
            targetHasActiveJob -> AdmissionDecision.AlreadyQueued
            occupied >= normalized -> AdmissionDecision.Reject(occupied, normalized)
            else -> AdmissionDecision.Admit
        }
    }

    /** 执行期 gate 的准入：当前活跃 permit 数 < 规范化后的上限 */
    fun canAdmit(active: Int, limit: Int): Boolean = active < normalizeLimit(limit)

    /** 任务剩余预算（不小于 0）；[usedByPreviousAttempts] 是该任务历史 Run 的 stepsUsed 之和 */
    fun remainingTaskBudget(usedByPreviousAttempts: Int, budget: Int = DEFAULT_TASK_STEP_BUDGET): Int =
        (budget - usedByPreviousAttempts).coerceAtLeast(0)

    /** 下一次尝试能不能派、能拿多少步：remaining<=0 → Exhausted，否则 Granted(remaining) */
    fun planAttempt(
        usedByPreviousAttempts: Int,
        budget: Int = DEFAULT_TASK_STEP_BUDGET,
    ): AttemptBudgetPlan {
        val remaining = remainingTaskBudget(usedByPreviousAttempts, budget)
        return if (remaining <= 0) {
            AttemptBudgetPlan.Exhausted(usedByPreviousAttempts, budget)
        } else {
            AttemptBudgetPlan.Granted(remaining)
        }
    }
}
