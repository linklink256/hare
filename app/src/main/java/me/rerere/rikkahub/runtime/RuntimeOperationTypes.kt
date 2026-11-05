package me.rerere.rikkahub.runtime

import me.rerere.rikkahub.data.model.RuntimeOperation
import kotlin.uuid.Uuid

data class OperationStart(
    val runId: Uuid,
    val attemptNo: Int,
    val conversationId: Uuid,
    val originCallId: String,
    val toolName: String,
    val input: String?,
    val generation: Long,
)

data class OperationFinish(
    val runId: Uuid,
    val attemptNo: Int,
    val originCallId: String,
    val output: String?,
    val error: String?,
)

sealed interface OperationStartResult {
    data class Started(val operation: RuntimeOperation) : OperationStartResult

    data class AlreadyStarted(val operation: RuntimeOperation) : OperationStartResult

    data class Rejected(val reason: String, val cause: Throwable?) : OperationStartResult
}

/** finish 的五类结果：重复 finish 必须显式冲突，不得静默成功。 */
sealed interface OperationFinishResult {
    data class Settled(val operation: RuntimeOperation) : OperationFinishResult

    /** 已终态、本次零写入；`operation.state` 可能是 UNKNOWN —— 只表示「不再写」，不代表成功。 */
    data class AlreadySettled(val operation: RuntimeOperation) : OperationFinishResult

    data class Conflict(val operation: RuntimeOperation?) : OperationFinishResult

    /** 事务中途失败已整体回滚：行仍是 RUNNING/INTENT，交下次启动恢复。 */
    data class Unsettled(val reason: String, val cause: Throwable?) : OperationFinishResult

    data object NotFound : OperationFinishResult
}
