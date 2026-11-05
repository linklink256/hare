package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.RuntimeOperationDAO
import me.rerere.rikkahub.data.db.dao.ToolOperationDAO
import me.rerere.rikkahub.data.db.entity.RuntimeOperationEntity
import me.rerere.rikkahub.data.db.entity.ToolOperationEntity
import me.rerere.rikkahub.data.model.OperationKind
import me.rerere.rikkahub.data.model.OperationState
import me.rerere.rikkahub.data.model.RuntimeOperation
import me.rerere.rikkahub.data.model.RuntimeOperationRules
import me.rerere.rikkahub.data.model.ToolOperationRules
import me.rerere.rikkahub.data.model.ToolOperationState
import me.rerere.rikkahub.runtime.OperationAccept
import me.rerere.rikkahub.runtime.OperationAcceptResult
import me.rerere.rikkahub.runtime.OperationFinish
import me.rerere.rikkahub.runtime.OperationFinishResult
import me.rerere.rikkahub.runtime.OperationStart
import me.rerere.rikkahub.runtime.OperationStartResult
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * operation 的唯一写入口：`start` 在同一个短事务里写 operation 行与副作用检查点，
 * `finish` 先按三段键读行、再用读到的那版 revision 结算，重复 finish 返回显式结果而不是静默成功。
 */
interface OperationStore {
    suspend fun start(spec: OperationStart): OperationStartResult

    suspend fun finish(spec: OperationFinish): OperationFinishResult

    /** 受理：RUNNING→ACCEPTED，并在同一事务里把该 call 的检查点推成 SUCCEEDED。 */
    suspend fun accept(spec: OperationAccept): OperationAcceptResult

    /** 消费侧结算：只对仍为 ACCEPTED 且 revision 未变的行生效。 */
    suspend fun settleAccepted(operationId: Uuid, expectedRevision: Long, outputText: String?): Boolean

    /** 受理后确认不会再有结果：同样只对 ACCEPTED 行生效。 */
    suspend fun settleFailed(operationId: Uuid, expectedRevision: Long, errorText: String): Boolean

    /** 投递键上的 ACCEPTED 行（含 revision）：结算的唯一取行入口。 */
    suspend fun listAcceptedByDelivery(deliveryKey: String): List<RuntimeOperation>

    /** 投递键上的行数（任意状态）：只给 purge 豁免判断用。 */
    suspend fun countByDelivery(deliveryKey: String): Int

    /** 唯一调用点：OperationRecorder 的 Unsettled 诊断（Conflict 已带行）。 */
    suspend fun listForRun(runId: Uuid): List<RuntimeOperation>

    /** 启动恢复：只把残留 RUNNING 降为 UNKNOWN；ACCEPTED 属于「等消费」，不降级。 */
    suspend fun markInterruptedAsUnknown(): Int
}

class RuntimeOperationRepository(
    private val database: AppDatabase,
    private val opDao: RuntimeOperationDAO = database.runtimeOperationDao(),
    private val toolOpDao: ToolOperationDAO = database.toolOperationDao(),
) : OperationStore {

    override suspend fun start(spec: OperationStart): OperationStartResult {
        val key = RuntimeOperationRules.operationKey(spec.runId, spec.attemptNo, spec.originCallId)
        return try {
            database.withTransaction {
                val now = Instant.now().toEpochMilli()
                val operationId = Uuid.random().toString()
                val input = ToolOperationRules.clampText(spec.input, ToolOperationRules.MAX_INPUT_CHARS)
                opDao.insert(
                    RuntimeOperationEntity(
                        operationId = operationId,
                        runId = spec.runId.toString(),
                        attemptNo = spec.attemptNo,
                        conversationId = spec.conversationId.toString(),
                        originCallId = spec.originCallId,
                        operationKey = key,
                        kind = OperationKind.TOOL.name,
                        state = OperationState.RUNNING.name,
                        inputText = input,
                        outputText = null,
                        errorText = null,
                        generation = spec.generation,
                        revision = 1L,
                        createdAt = now,
                        startedAt = now,
                        settledAt = null,
                        acceptedAt = null,
                        deliveryState = null,
                        late = null,
                    )
                )
                toolOpDao.insert(
                    ToolOperationEntity(
                        id = Uuid.random().toString(),
                        runId = spec.runId.toString(),
                        conversationId = spec.conversationId.toString(),
                        toolCallId = spec.originCallId,
                        toolName = spec.toolName,
                        operationKey = ToolOperationRules.operationKey(spec.runId, spec.originCallId),
                        operationId = operationId,
                        state = ToolOperationState.INTENT.name,
                        inputText = input,
                        outputText = null,
                        errorText = null,
                        startedAt = now,
                        finishedAt = null,
                    )
                )
                val row = opDao.getByKey(spec.runId.toString(), spec.attemptNo, spec.originCallId)
                    ?: error("operation row missing right after insert")
                OperationStartResult.Started(row.toModel())
            }
        } catch (e: SQLiteConstraintException) {
            // 只有约束冲突才可能是重复受理；BUSY/IO 等异常不重读，避免误报。
            val existing = runCatching {
                opDao.getByKey(spec.runId.toString(), spec.attemptNo, spec.originCallId)
            }.getOrNull()
            when {
                existing != null -> OperationStartResult.AlreadyStarted(existing.toModel())
                // checkpoint 冲突（同调用已有旧行）不是「重复受理」，单独报因，避免静默丢掉本次 operation
                runCatching { toolOpDao.get(spec.runId.toString(), spec.originCallId) }.getOrNull() != null ->
                    OperationStartResult.Rejected("checkpoint already exists for this call", e)
                else -> OperationStartResult.Rejected("operation insert conflicted without a visible row", e)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationStartResult.Rejected("operation start failed: ${e.message}", e)
        }
    }

    override suspend fun finish(spec: OperationFinish): OperationFinishResult {
        val runId = spec.runId.toString()
        return try {
            database.withTransaction {
                val row = opDao.getByKey(runId, spec.attemptNo, spec.originCallId)
                    ?: return@withTransaction OperationFinishResult.NotFound
                val current = row.toModel()
                if (RuntimeOperationRules.isTerminal(current.state)) {
                    // 已终态（可能是恢复后的 UNKNOWN）：只表示「不再写入」，不代表成功
                    return@withTransaction OperationFinishResult.AlreadySettled(current)
                }
                val target = if (spec.error != null) OperationState.FAILED else OperationState.SUCCEEDED
                val now = Instant.now().toEpochMilli()
                val output = ToolOperationRules.clampText(spec.output, ToolOperationRules.MAX_OUTPUT_CHARS)
                val affected = opDao.settleCas(
                    operationId = row.operationId,
                    expectedRevision = row.revision,
                    state = target.name,
                    outputText = output,
                    errorText = spec.error,
                    settledAt = now,
                )
                if (affected == 0) {
                    return@withTransaction OperationFinishResult.Conflict(
                        opDao.getByKey(runId, spec.attemptNo, spec.originCallId)?.toModel()
                    )
                }
                val checkpointState =
                    if (target == OperationState.FAILED) ToolOperationState.FAILED else ToolOperationState.SUCCEEDED
                val checkpointAffected = toolOpDao.finishCas(
                    runId = runId,
                    toolCallId = spec.originCallId,
                    state = checkpointState.name,
                    outputText = output,
                    errorText = spec.error,
                    finishedAt = now,
                )
                // 任一 0 行都整体回滚：不许 operation 已结算而检查点仍 INTENT。
                check(checkpointAffected == 1) { "tool checkpoint not settled: affected=$checkpointAffected" }
                val settled = opDao.getByKey(runId, spec.attemptNo, spec.originCallId)?.toModel()
                    ?: error("operation row vanished after settle")
                OperationFinishResult.Settled(settled)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationFinishResult.Unsettled("finish transaction failed: ${e.message}", e)
        }
    }

    override suspend fun accept(spec: OperationAccept): OperationAcceptResult {
        val runId = spec.runId.toString()
        return try {
            database.withTransaction {
                val row = opDao.getByKey(runId, spec.attemptNo, spec.originCallId)
                    ?: return@withTransaction OperationAcceptResult.NotFound
                val current = row.toModel()
                // 同一 call 重复受理只回显同一条 Accepted，不追加第二行
                if (current.state == OperationState.ACCEPTED) {
                    return@withTransaction OperationAcceptResult.AlreadyAccepted(current)
                }
                if (current.state != OperationState.RUNNING) {
                    return@withTransaction OperationAcceptResult.Conflict(current)
                }
                val now = Instant.now().toEpochMilli()
                val output = ToolOperationRules.clampText(spec.output, ToolOperationRules.MAX_OUTPUT_CHARS)
                val affected = opDao.acceptCas(
                    operationId = row.operationId,
                    expectedRevision = row.revision,
                    acceptedAt = now,
                    deliveryKey = spec.deliveryKey,
                    outputText = output,
                )
                if (affected == 0) {
                    return@withTransaction OperationAcceptResult.Conflict(
                        opDao.getByKey(runId, spec.attemptNo, spec.originCallId)?.toModel()
                    )
                }
                // 「已派发」是真实发生的副作用：检查点必须离开 INTENT，否则重启会被标 UNCERTAIN
                val checkpoint = toolOpDao.finishCas(
                    runId = runId,
                    toolCallId = spec.originCallId,
                    state = ToolOperationState.SUCCEEDED.name,
                    outputText = output,
                    errorText = null,
                    finishedAt = now,
                )
                check(checkpoint == 1) { "tool checkpoint not settled on accept: affected=$checkpoint" }
                val accepted = opDao.getByKey(runId, spec.attemptNo, spec.originCallId)?.toModel()
                    ?: error("operation row vanished after accept")
                OperationAcceptResult.Accepted(accepted)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            OperationAcceptResult.Rejected("operation accept failed: ${e.message}", e)
        }
    }

    override suspend fun settleAccepted(
        operationId: Uuid,
        expectedRevision: Long,
        outputText: String?,
    ): Boolean = runCatching {
        opDao.settleAcceptedCas(
            operationId = operationId.toString(),
            expectedRevision = expectedRevision,
            outputText = ToolOperationRules.clampText(outputText, ToolOperationRules.MAX_OUTPUT_CHARS),
            settledAt = Instant.now().toEpochMilli(),
        ) == 1
    }.getOrDefault(false)

    override suspend fun settleFailed(
        operationId: Uuid,
        expectedRevision: Long,
        errorText: String,
    ): Boolean = runCatching {
        opDao.settleAcceptedFailedCas(
            operationId = operationId.toString(),
            expectedRevision = expectedRevision,
            errorText = errorText,
            settledAt = Instant.now().toEpochMilli(),
        ) == 1
    }.getOrDefault(false)

    override suspend fun listAcceptedByDelivery(deliveryKey: String): List<RuntimeOperation> =
        opDao.listAcceptedByDelivery(deliveryKey).map { it.toModel() }

    override suspend fun countByDelivery(deliveryKey: String): Int = opDao.countByDelivery(deliveryKey)

    override suspend fun listForRun(runId: Uuid): List<RuntimeOperation> =
        opDao.listForRun(runId.toString()).map { it.toModel() }

    override suspend fun markInterruptedAsUnknown(): Int =
        opDao.markInterruptedAsUnknown(Instant.now().toEpochMilli())

    private fun RuntimeOperationEntity.toModel(): RuntimeOperation = RuntimeOperation(
        operationId = Uuid.parse(operationId),
        runId = Uuid.parse(runId),
        attemptNo = attemptNo,
        conversationId = Uuid.parse(conversationId),
        originCallId = originCallId,
        operationKey = operationKey,
        kind = runCatching { OperationKind.valueOf(kind) }.getOrDefault(OperationKind.TOOL),
        // 未知态保守降级为 UNKNOWN，绝不当成 SUCCEEDED。
        state = runCatching { OperationState.valueOf(state) }.getOrDefault(OperationState.UNKNOWN),
        inputText = inputText,
        outputText = outputText,
        errorText = errorText,
        generation = generation,
        revision = revision,
        createdAt = Instant.ofEpochMilli(createdAt),
        startedAt = startedAt?.let(Instant::ofEpochMilli),
        settledAt = settledAt?.let(Instant::ofEpochMilli),
        acceptedAt = acceptedAt?.let(Instant::ofEpochMilli),
        deliveryState = deliveryState,
        late = late,
    )
}
