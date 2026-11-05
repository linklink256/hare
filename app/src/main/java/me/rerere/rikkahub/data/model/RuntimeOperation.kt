package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid

/** operation 的类型；R2 只有工具调用一种。 */
enum class OperationKind { TOOL }

/** 后台 operation 的生命周期。R2 只产生 RUNNING/SUCCEEDED；其余态由 R3/R4 写入。SUCCEEDED 仅表示「本 wire 内返回了输出」，不是业务动作成功。 */
enum class OperationState { ACCEPTED, RUNNING, SUCCEEDED, FAILED, UNKNOWN, CANCELLED }

/**
 * 一次持久 operation 的行事实。
 *
 * R2 只写 start/finish 必需的字段：`acceptedAt/deliveryState/late` 仍为空，
 * 因为工具 hook 没有受理/投递/迟到判定（归 R3/R4）；step 身份记在 `AgentRunEvent` 上，不在本模型。
 */
data class RuntimeOperation(
    val operationId: Uuid = Uuid.random(),
    val runId: Uuid,
    val attemptNo: Int,
    val conversationId: Uuid,
    val originCallId: String,
    val operationKey: String,
    val kind: OperationKind,
    val state: OperationState,
    val inputText: String? = null,
    val outputText: String? = null,
    val errorText: String? = null,
    val generation: Long = 0L,
    val revision: Long = 1L,
    val createdAt: Instant = Instant.now(),
    val startedAt: Instant? = null,
    val settledAt: Instant? = null,
    val acceptedAt: Instant? = null,
    val deliveryState: String? = null,
    /** null = 未判定；R2 恒不写，R4 迟到结算才写。 */
    val late: Boolean? = null,
)

object RuntimeOperationRules {
    /** 幂等键：同 Run 同 attempt 同 callId 只允许一条 operation。 */
    fun operationKey(runId: Uuid, attemptNo: Int, originCallId: String): String =
        "op:$runId:$attemptNo:$originCallId"

    /** 唯一调用点：finish 的 AlreadySettled 分类（transition 已删，ACCEPTED 出边随 R3 建）。 */
    fun isTerminal(state: OperationState): Boolean =
        state == OperationState.SUCCEEDED || state == OperationState.FAILED ||
            state == OperationState.UNKNOWN || state == OperationState.CANCELLED

    /** 仍需后续动作的状态：RUNNING（未结算）与 ACCEPTED（已受理、等消费侧结算）。 */
    fun isOpen(state: OperationState): Boolean =
        state == OperationState.RUNNING || state == OperationState.ACCEPTED
}
