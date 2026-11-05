package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid

/** 工具操作检查点：证明「这次工具调用到底有没有产生副作用」，只记录不重放 */
enum class ToolOperationState { INTENT, SUCCEEDED, FAILED, UNCERTAIN }

data class ToolOperation(
    val id: Uuid = Uuid.random(),
    val runId: Uuid,
    val conversationId: Uuid,
    val toolCallId: String,
    val toolName: String,
    val operationKey: String,
    val state: ToolOperationState = ToolOperationState.INTENT,
    val inputText: String? = null,
    val outputText: String? = null,
    val errorText: String? = null,
    val startedAt: Instant = Instant.now(),
    val finishedAt: Instant? = null,
)

/** 恢复判定结果：只给结论，调用方决定是否续跑 */
data class RecoveryPlan(
    val uncertain: List<Uuid> = emptyList(),
    val completed: List<Uuid> = emptyList(),
    val failed: List<Uuid> = emptyList(),
) {
    val hasUncertain: Boolean get() = uncertain.isNotEmpty()
}

/** 纯规则：无 Android 依赖，JVM 单测直接覆盖 */
object ToolOperationRules {
    /** 输入/输出正文上限；超限只截断到上限（检查点只回答有没有结果，不是审计原件） */
    const val MAX_INPUT_CHARS = 8192
    const val MAX_OUTPUT_CHARS = 16384

    fun operationKey(runId: Uuid, toolCallId: String): String = "tool:$runId:$toolCallId"

    /** 合法迁移：INTENT → SUCCEEDED/FAILED/UNCERTAIN；后三者终态 */
    fun transition(from: ToolOperationState, to: ToolOperationState): ToolOperationState? =
        if (from == ToolOperationState.INTENT && to != ToolOperationState.INTENT) to else null

    fun isTerminal(state: ToolOperationState): Boolean = state != ToolOperationState.INTENT

    /** 恢复判定：残留 INTENT 一律视为副作用不明 → UNCERTAIN，该 Run 不允许自动续跑 */
    fun planRecovery(rows: List<ToolOperation>): RecoveryPlan = RecoveryPlan(
        uncertain = rows.filter { it.state == ToolOperationState.INTENT }.map { it.id },
        completed = rows.filter { it.state == ToolOperationState.SUCCEEDED }.map { it.id },
        failed = rows.filter {
            it.state == ToolOperationState.FAILED || it.state == ToolOperationState.UNCERTAIN
        }.map { it.id },
    )

    /** 有未决/不明副作用时禁止自动继续 */
    fun blocksAutomaticResume(rows: List<ToolOperation>): Boolean = rows.any {
        it.state == ToolOperationState.INTENT || it.state == ToolOperationState.UNCERTAIN
    }

    fun clampText(raw: String?, limit: Int): String? = when {
        raw == null -> null
        raw.length <= limit -> raw
        else -> raw.take(limit)
    }
}
