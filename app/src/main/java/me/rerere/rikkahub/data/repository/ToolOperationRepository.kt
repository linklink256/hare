package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.ToolOperationDAO
import me.rerere.rikkahub.data.db.entity.ToolOperationEntity
import me.rerere.rikkahub.data.model.ToolOperation
import me.rerere.rikkahub.data.model.ToolOperationState
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 工具操作检查点的只读接口：start/finish 已收口到 `RuntimeOperationRepository`，这里只剩读取与启动恢复标记。
 * 接线层只依赖本接口；异常由调用方吞（检查点失败绝不能打断工具执行）。
 */
interface ToolOperationStore {
    suspend fun listForRun(runId: Uuid): List<ToolOperation>

    /** 启动恢复用：把所有残留 INTENT 标 UNCERTAIN，返回条数 */
    suspend fun markInterruptedAsUncertain(): Int
}

/** Room 实现：只保留读取与启动恢复标记；start/finish 已收口到 RuntimeOperationRepository */
class ToolOperationRepository(private val dao: ToolOperationDAO) : ToolOperationStore {

    override suspend fun listForRun(runId: Uuid): List<ToolOperation> =
        dao.listForRun(runId.toString()).map { it.toModel() }

    override suspend fun markInterruptedAsUncertain(): Int =
        dao.markAllIntentUncertain(Instant.now().toEpochMilli())
}

internal fun ToolOperationEntity.toModel(): ToolOperation = ToolOperation(
    id = Uuid.parse(id),
    runId = Uuid.parse(runId),
    conversationId = Uuid.parse(conversationId),
    toolCallId = toolCallId,
    toolName = toolName,
    operationKey = operationKey,
    // 不认识的 state 按最保守的 UNCERTAIN 处理，绝不当成「已完成」
    state = runCatching { ToolOperationState.valueOf(state) }
        .getOrDefault(ToolOperationState.UNCERTAIN),
    inputText = inputText,
    outputText = outputText,
    errorText = errorText,
    startedAt = Instant.ofEpochMilli(startedAt),
    finishedAt = finishedAt?.let(Instant::ofEpochMilli),
)
