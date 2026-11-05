package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

/** 一次任务派发的判定结果：能不能派、以及派给谁 */
sealed interface TaskDispatchPlan {
    /** 可以派发：把任务置 CLAIMED、owner 记为执行者 */
    data class Apply(val task: TeamTask, val ownerName: String) : TaskDispatchPlan

    data class Rejected(val reason: TaskDispatchRejection, val blocking: List<Uuid> = emptyList()) : TaskDispatchPlan
}

enum class TaskDispatchRejection {
    /** 任务已 DONE/FAILED（终态不再派发） */
    TERMINAL,

    /** 依赖未满足；[TaskDispatchPlan.Rejected.blocking] 给出未 DONE 的依赖 id */
    DEPENDENCIES_UNMET,

    /** 已被别的队友认领 */
    CLAIMED_BY_OTHER,
}

/** 派活门禁的纯规则：只判「能不能派」，真正写状态由 repository 在事务里按 revision CAS 提交 */
object TeamTaskSchedulingRules {
    /** 只判「能不能派」；真正的 claim 由 repository 在事务里按 revision CAS 提交 */
    fun planDispatch(task: TeamTask, allTasks: List<TeamTask>, memberName: String): TaskDispatchPlan {
        if (task.status == TeamTaskStatus.DONE || task.status == TeamTaskStatus.FAILED) {
            return TaskDispatchPlan.Rejected(TaskDispatchRejection.TERMINAL)
        }
        val blocking = blockingDependencies(task, allTasks)
        if (blocking.isNotEmpty()) {
            return TaskDispatchPlan.Rejected(TaskDispatchRejection.DEPENDENCIES_UNMET, blocking)
        }
        if (task.status == TeamTaskStatus.CLAIMED && task.ownerName != null && task.ownerName != memberName) {
            return TaskDispatchPlan.Rejected(TaskDispatchRejection.CLAIMED_BY_OTHER)
        }
        return TaskDispatchPlan.Apply(task, memberName)
    }

    /** 该任务当前是否卡在未完成依赖上（给错误文案/UI 用） */
    fun blockingDependencies(task: TeamTask, allTasks: List<TeamTask>): List<Uuid> {
        val byId = allTasks.associateBy { it.id }
        // 找不到的依赖视为未满足（fail-closed）；去重但保持 blocked_by 原顺序
        return task.blockedBy.filter { id -> byId[id]?.status != TeamTaskStatus.DONE }.distinct()
    }

    /** 依赖满足 = 上游任务 DONE 且当前 revision 有接受凭据；未知依赖一律算未满足 */
    fun blockingDependenciesWithAcceptance(
        task: TeamTask,
        allTasks: List<TeamTask>,
        acceptedTaskIds: Set<Uuid>,
    ): List<Uuid> {
        val byId = allTasks.associateBy { it.id }
        // DONE 但没有接受凭据也算未满足（fail-closed：自报完成不能解锁下游）
        return task.blockedBy.filter { id ->
            val upstream = byId[id]
            upstream == null || upstream.status != TeamTaskStatus.DONE || id !in acceptedTaskIds
        }.distinct()
    }

    /** 与 planDispatch 同序，但依赖门禁换成 accepted 版本 */
    fun planDispatchWithAcceptance(
        task: TeamTask,
        allTasks: List<TeamTask>,
        acceptedTaskIds: Set<Uuid>,
        memberName: String,
    ): TaskDispatchPlan {
        if (task.status == TeamTaskStatus.DONE || task.status == TeamTaskStatus.FAILED) {
            return TaskDispatchPlan.Rejected(TaskDispatchRejection.TERMINAL)
        }
        val blocking = blockingDependenciesWithAcceptance(task, allTasks, acceptedTaskIds)
        if (blocking.isNotEmpty()) {
            return TaskDispatchPlan.Rejected(TaskDispatchRejection.DEPENDENCIES_UNMET, blocking)
        }
        if (task.status == TeamTaskStatus.CLAIMED && task.ownerName != null && task.ownerName != memberName) {
            return TaskDispatchPlan.Rejected(TaskDispatchRejection.CLAIMED_BY_OTHER)
        }
        return TaskDispatchPlan.Apply(task, memberName)
    }
}

/** 把拒绝原因转成直接回给模型的一句话；仓库与 service 共用同一份文案 */
internal fun describeDispatchRejection(task: TeamTask, rejection: TaskDispatchPlan.Rejected): String =
    when (rejection.reason) {
        TaskDispatchRejection.DEPENDENCIES_UNMET ->
            "task ${task.title} is blocked by unfinished dependencies: " +
                rejection.blocking.joinToString(", ")

        TaskDispatchRejection.TERMINAL ->
            "task ${task.title} is already ${task.status.name}; create a new task instead"

        TaskDispatchRejection.CLAIMED_BY_OTHER ->
            "task ${task.title} is claimed by ${task.ownerName}; ask the lead to reassign it"
    }
