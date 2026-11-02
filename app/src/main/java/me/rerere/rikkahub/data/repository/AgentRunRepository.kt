package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.dao.AgentRunTeamSummaryRow
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEventEntity
import me.rerere.rikkahub.data.model.AgentMailboxEntry
import me.rerere.rikkahub.data.model.AgentMailboxRules
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunEvent
import me.rerere.rikkahub.data.model.AgentRunEventKind
import me.rerere.rikkahub.data.model.AgentRunRules
import me.rerere.rikkahub.data.model.AgentRunState
import me.rerere.rikkahub.data.model.BindReplyPlan
import me.rerere.rikkahub.data.model.BindReplyRejection
import me.rerere.rikkahub.data.model.MailboxDeliveryState
import me.rerere.rikkahub.data.model.MailboxEnqueueRejection
import me.rerere.rikkahub.data.model.NewMailboxEntry
import me.rerere.rikkahub.data.model.RecoveryAction
import me.rerere.rikkahub.data.model.StepPlan
import me.rerere.rikkahub.data.model.StepProgressRejection
import me.rerere.rikkahub.data.model.TransitionPlan
import me.rerere.rikkahub.data.model.TransitionRejection
import java.time.Instant
import kotlin.uuid.Uuid

/** 新建一条 Run 的入参。身份字段空着表示「不属于团队 / 由 Lead 或单 agent 执行」。 */
data class NewAgentRun(
    val conversationId: Uuid,
    val teamId: Uuid? = null,
    /** 执行者：当前复用 TeamMember.id */
    val agentId: Uuid? = null,
    val taskId: Uuid? = null,
    /** 派生父 Run（嵌套/子任务归属） */
    val parentRunId: Uuid? = null,
    /** 上一次尝试的 Run（重试/续跑链），与 parentRunId 分开 */
    val previousRunId: Uuid? = null,
    /** 派活去重键，调用方负责命名空间化；空串等于不去重 */
    val sourceKey: String? = null,
    val attemptNo: Int = 1,
    val inputRef: String? = null,
    /** 内联有界输入；超过 [AgentRunRules.MAX_INPUT_JSON_CHARS] 请改用 inputRef */
    val inputJson: String? = null,
    /** 软提醒用的步数上限，默认 256；本层不拦截 */
    val stepLimit: Int? = AgentRunRules.DEFAULT_STEP_LIMIT,
    val deadlineAt: Instant? = null,
    val runId: Uuid = Uuid.random(),
    val now: Instant = Instant.now(),
)

sealed interface CreateRunResult {
    data class Created(val run: AgentRun) : CreateRunResult

    /** 同 sourceKey 已有 Run：返回既有那条，绝不覆盖、不 REPLACE */
    data class Duplicate(val existing: AgentRun) : CreateRunResult

    /** 入参不合法（如 inputJson 超限）：显式拒绝，**绝不截断**用户输入 */
    data class Rejected(val reason: String) : CreateRunResult
}

sealed interface RunTransitionResult {
    data class Applied(val run: AgentRun) : RunTransitionResult

    /** 版本或当前状态已变：`current` 是拒绝时的最新快照，调用方应据此重判 */
    data class Conflict(val current: AgentRun) : RunTransitionResult

    data object NotFound : RunTransitionResult

    /** 纯规则拒绝：终态覆写、同态、等待态互跳等 */
    data class IllegalTransition(val from: AgentRunState, val to: AgentRunState) : RunTransitionResult
}

sealed interface BindReplyResult {
    data class Applied(val run: AgentRun) : BindReplyResult

    /** 已经绑定了同一个回复节点：幂等重复调用 */
    data class AlreadyBound(val run: AgentRun) : BindReplyResult

    /** 版本过期（别人先改了）：冲突是并发语义，不是策略拒绝 */
    data class Conflict(val current: AgentRun) : BindReplyResult

    /** 策略拒绝：终态行禁止新绑/改绑、已绑到别的节点 */
    data class Rejected(val reason: BindReplyRejection, val current: AgentRun?) : BindReplyResult

    data object NotFound : BindReplyResult
}

sealed interface RecordStepsResult {
    data class Applied(val run: AgentRun) : RecordStepsResult

    data class Conflict(val current: AgentRun) : RecordStepsResult

    /** 策略拒绝：终态行不能追加步数、步数不可减少 */
    data class Rejected(val reason: StepProgressRejection, val current: AgentRun?) : RecordStepsResult

    data object NotFound : RecordStepsResult
}

/** `transitionAndEnqueue` 的结果：三种失败都不会留下半截事实。 */
sealed interface RunTransitionAndEnqueueResult {
    data class Applied(val run: AgentRun, val mailbox: AgentMailboxEntry) : RunTransitionAndEnqueueResult

    /** Run 迁移被拒（NotFound / Conflict / IllegalTransition）：通知行**未写**，`mailbox` 恒为 null */
    data class Rejected(
        val transition: RunTransitionResult,
        val mailbox: AgentMailboxEntry? = null,
    ) : RunTransitionAndEnqueueResult

    /** 迁移本身合法，但通知行被幂等/容量拒绝：整个事务回滚，Run 保持原状 */
    data class MailboxRejected(
        val reason: MailboxEnqueueRejection,
        val current: AgentRun?,
        val existingMailboxId: Uuid? = null,
    ) : RunTransitionAndEnqueueResult
}

/** 保守恢复的结果：只有残留 RUNNING 会被改成 INTERRUPTED，其余状态保持。 */
data class RecoveryReport(
    val interrupted: List<Uuid> = emptyList(),
    val skipped: List<Uuid> = emptyList(),
)

/**
 * 团队面板/状态投影用的轻量 Run 摘要：**每个会话最多一行**（最近一条），
 * 不含 `inputJson` / `inputRef` 等负载，不回流全部 Run 历史。
 */
data class AgentRunTeamSummary(
    val runId: Uuid,
    val conversationId: Uuid,
    val agentId: Uuid?,
    val taskId: Uuid?,
    val state: AgentRunState,
    val stateRevision: Long,
    val stepsUsed: Int,
    val stepLimit: Int?,
    val updatedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val replyMessageId: String?,
)

/**
 * Run 持久层的稳定接口。接线层只依赖本接口，测试可给 InMemory 假实现。
 * 本层不管调度、不管模型执行，也不扣预算。
 */
interface AgentRunStore {
    suspend fun create(spec: NewAgentRun): CreateRunResult

    suspend fun get(runId: Uuid): AgentRun?

    suspend fun findBySourceKey(sourceKey: String): AgentRun?

    /**
     * 精确回复锚点反查：该会话里 `replyMessageId` 命中的最近一条 Run。
     *
     * 本层不做业务过滤（终态也会返回）：调用方需自行检查 `state`（如 `!isTerminal` / PAUSED / 等待态）
     * 再决定是否 resume，避免把已结束的历史当成可续跑目标。
     */
    suspend fun findLatestByReply(conversationId: Uuid, replyMessageId: String): AgentRun?

    /** 该会话最近一条 Run（LATEST 1）；日常判态用它，不要为了拿一行而拉全历史。 */
    suspend fun latestForConversation(conversationId: Uuid): AgentRun?

    /** 团队轻量投影：每会话只回最近一条 Run 摘要，不含输入负载。 */
    fun observeLatestForTeam(teamId: Uuid): Flow<List<AgentRunTeamSummary>>

    suspend fun transition(
        runId: Uuid,
        expectedRevision: Long,
        to: AgentRunState,
        reason: String? = null,
        now: Instant = Instant.now(),
    ): RunTransitionResult

    /**
     * R2-A：Run 状态迁移 + 事件 + 待通知行在同一个短事务；迁移被拒不写通知行。
     * 与 [transition] 同语义的拒绝原因走 `Rejected(transition, mailbox = null)`。
     */
    suspend fun transitionAndEnqueue(
        runId: Uuid,
        expectedRevision: Long,
        to: AgentRunState,
        reason: String?,
        now: Instant,
        entry: NewMailboxEntry,
        limit: Int = AgentMailboxRules.MAX_UNACKED_PER_TARGET,
    ): RunTransitionAndEnqueueResult

    suspend fun bindReply(
        runId: Uuid,
        expectedRevision: Long,
        replyMessageId: String,
        now: Instant = Instant.now(),
    ): BindReplyResult

    /**
     * 记录累计模型步数（绝对值，跨审批/暂停不重置）。
     * 只允许非终态、不可减少；成功时同一事务内 `state_revision + 1` 并追加 `STEP_PROGRESS` 事件。
     * 本方法不判断也不扣减预算，不是根账本。
     */
    suspend fun recordSteps(
        runId: Uuid,
        expectedRevision: Long,
        stepsUsed: Int,
        now: Instant = Instant.now(),
    ): RecordStepsResult

    suspend fun listByState(state: AgentRunState): List<AgentRun>

    /** 全历史：诊断/管理用途，**不是热路径**；常规判态用 [latestForConversation]。 */
    suspend fun listForConversation(conversationId: Uuid): List<AgentRun>

    /** 全历史观察：诊断/管理用途，**不是热路径**；团队投影用 [observeLatestForTeam]。 */
    fun observeConversation(conversationId: Uuid): Flow<List<AgentRun>>

    /** 全量观察：诊断/管理用途，**不是热路径**；团队投影用 [observeLatestForTeam]。 */
    fun observeTeam(teamId: Uuid): Flow<List<AgentRun>>

    /** 倒序取一页事件后翻成时间正序；[beforeRevision] 为 null 表示从最新开始 */
    suspend fun events(runId: Uuid, limit: Int = 50, beforeRevision: Long? = null): List<AgentRunEvent>

    suspend fun recoverInterrupted(
        reason: String = AgentRunRules.RECOVERY_REASON,
        now: Instant = Instant.now(),
    ): RecoveryReport
}

/**
 * Run 状态的 Room 实现。
 *
 * 每次状态迁移与事件追加都在**同一个** `withTransaction` 里：要么「Run 的新状态 + 对应事件」一起可见，
 * 要么都不发生。CAS 用 `state_revision`（迁移时再加 `state`）作条件，影响行数为 0 即视为别人先改了。
 *
 * 时间字段只在这里写：`started_at` 在真正进入 RUNNING 的那次迁移，
 * `finished_at` 在进入终态的那次迁移，不从回复文本或输出推断。
 */
class AgentRunRepository(
    private val database: AppDatabase,
    private val dao: AgentRunDAO = database.agentRunDao(),
) : AgentRunStore {

    override suspend fun create(spec: NewAgentRun): CreateRunResult {
        val sourceKey = AgentRunRules.normalizeSourceKey(spec.sourceKey)
        val inputJson = spec.inputJson
        // 校验规则同样放在纯规则里，测试无需构造 Room 也能覆盖；超限显式拒绝，绝不截断
        AgentRunRules.validateNewRun(
            attemptNo = spec.attemptNo,
            inputJsonLength = inputJson?.length,
            stepLimit = spec.stepLimit,
        )?.let { return CreateRunResult.Rejected(it) }

        val run = AgentRun(
            runId = spec.runId,
            teamId = spec.teamId,
            agentId = spec.agentId,
            conversationId = spec.conversationId,
            taskId = spec.taskId,
            parentRunId = spec.parentRunId,
            previousRunId = spec.previousRunId,
            sourceKey = sourceKey,
            attemptNo = spec.attemptNo,
            inputRef = spec.inputRef,
            inputJson = inputJson,
            replyMessageId = null,
            state = AgentRunState.QUEUED,
            reason = null,
            stateRevision = 1L,
            stepLimit = spec.stepLimit,
            stepsUsed = 0,
            deadlineAt = spec.deadlineAt,
            createdAt = spec.now,
            updatedAt = spec.now,
            startedAt = null,
            finishedAt = null,
        )
        try {
            database.withTransaction {
                dao.insertRun(run.toEntity())
                dao.insertEvent(
                    AgentRunEventEntity(
                        runId = run.runId.toString(),
                        revision = run.stateRevision,
                        kind = AgentRunEventKind.CREATED.name,
                        fromState = null,
                        toState = run.state.name,
                        reason = null,
                        stepsUsed = null,
                        createdAt = spec.now.toEpochMilli(),
                    )
                )
            }
        } catch (e: SQLiteConstraintException) {
            // 只有 sourceKey 冲突才算「这次派活已经建过 Run」；runId 撞车是调用方 bug，重新抛出
            val existing = sourceKey?.let { dao.getRunBySourceKey(it) }
            if (existing != null) return CreateRunResult.Duplicate(existing.toModel())
            throw e
        }
        return CreateRunResult.Created(run)
    }

    override suspend fun get(runId: Uuid): AgentRun? = dao.getRun(runId.toString())?.toModel()

    override suspend fun findBySourceKey(sourceKey: String): AgentRun? {
        val key = AgentRunRules.normalizeSourceKey(sourceKey) ?: return null
        return dao.getRunBySourceKey(key)?.toModel()
    }

    override suspend fun findLatestByReply(conversationId: Uuid, replyMessageId: String): AgentRun? {
        require(replyMessageId.isNotBlank()) { "replyMessageId must not be blank" }
        return dao.findLatestByReply(conversationId.toString(), replyMessageId)?.toModel()
    }

    override suspend fun latestForConversation(conversationId: Uuid): AgentRun? =
        dao.latestRunForConversation(conversationId.toString())?.toModel()

    override fun observeLatestForTeam(teamId: Uuid): Flow<List<AgentRunTeamSummary>> =
        dao.observeLatestRunPerConversationForTeam(teamId.toString())
            .map { rows -> rows.map { it.toSummary() } }

    override suspend fun transition(
        runId: Uuid,
        expectedRevision: Long,
        to: AgentRunState,
        reason: String?,
        now: Instant,
    ): RunTransitionResult = database.withTransaction {
        val current = dao.getRun(runId.toString())?.toModel()
        // 判定顺序（NotFound → Conflict → IllegalTransition）在纯规则里固定，产品与测试走同一条路径
        when (val plan = AgentRunRules.planTransition(current, expectedRevision, to)) {
            is TransitionPlan.Reject -> when (plan.reason) {
                TransitionRejection.NOT_FOUND -> RunTransitionResult.NotFound

                TransitionRejection.STALE_REVISION -> RunTransitionResult.Conflict(
                    checkNotNull(current) { "stale revision requires a snapshot" }
                )

                TransitionRejection.ILLEGAL_TRANSITION -> RunTransitionResult.IllegalTransition(
                    checkNotNull(current) { "illegal transition requires a snapshot" }.state,
                    to,
                )
            }

            is TransitionPlan.Apply -> {
                val run = checkNotNull(current) { "apply requires a snapshot" }
                val nowMillis = now.toEpochMilli()
                val affected = dao.updateStateCas(
                    runId = runId.toString(),
                    expectedRevision = expectedRevision,
                    fromState = plan.from.name,
                    toState = plan.to.name,
                    reason = reason ?: run.reason,
                    now = nowMillis,
                    startedAt = if (plan.stampStartedAt) nowMillis else null,
                    finishedAt = if (plan.stampFinishedAt) nowMillis else null,
                )
                if (affected == 0) {
                    // 读到之后又被别人改了：交出最新快照，绝不覆盖
                    val latest = dao.getRun(runId.toString())?.toModel()
                        ?: return@withTransaction RunTransitionResult.NotFound
                    return@withTransaction RunTransitionResult.Conflict(latest)
                }
                dao.insertEvent(
                    AgentRunEventEntity(
                        runId = runId.toString(),
                        revision = plan.nextRevision,
                        kind = AgentRunEventKind.STATE_CHANGED.name,
                        fromState = plan.from.name,
                        toState = plan.to.name,
                        reason = reason,
                        stepsUsed = null,
                        createdAt = nowMillis,
                    )
                )
                RunTransitionResult.Applied(
                    run.copy(
                        state = plan.to,
                        reason = reason ?: run.reason,
                        stateRevision = plan.nextRevision,
                        updatedAt = now,
                        startedAt = if (plan.stampStartedAt) now else run.startedAt,
                        finishedAt = if (plan.stampFinishedAt) now else run.finishedAt,
                    )
                )
            }
        }
    }

    override suspend fun bindReply(
        runId: Uuid,
        expectedRevision: Long,
        replyMessageId: String,
        now: Instant,
    ): BindReplyResult = database.withTransaction {
        require(replyMessageId.isNotBlank()) { "replyMessageId must not be blank" }
        val current = dao.getRun(runId.toString())?.toModel()
        when (val plan = AgentRunRules.planBindReply(current, expectedRevision, replyMessageId)) {
            is BindReplyPlan.AlreadyBound -> BindReplyResult.AlreadyBound(plan.run)

            is BindReplyPlan.Reject -> when (plan.reason) {
                BindReplyRejection.NOT_FOUND -> BindReplyResult.NotFound

                // 并发语义：版本过期交给调用方重读
                BindReplyRejection.STALE_REVISION -> BindReplyResult.Conflict(
                    checkNotNull(current) { "conflict requires a snapshot" }
                )

                // 策略拒绝：终态行禁止新绑/改绑、已绑到别的节点，明确返回 Rejected 而不是成功
                BindReplyRejection.TERMINAL_STATE,
                BindReplyRejection.ALREADY_BOUND_ELSEWHERE,
                -> BindReplyResult.Rejected(plan.reason, current)
            }

            is BindReplyPlan.Apply -> {
                val run = checkNotNull(current) { "apply requires a snapshot" }
                val nowMillis = now.toEpochMilli()
                val affected = dao.bindReplyCas(
                    runId = runId.toString(),
                    expectedRevision = expectedRevision,
                    expectedState = run.state.name,
                    replyMessageId = replyMessageId,
                    now = nowMillis,
                )
                if (affected == 0) {
                    val latest = dao.getRun(runId.toString())?.toModel()
                        ?: return@withTransaction BindReplyResult.NotFound
                    return@withTransaction BindReplyResult.Conflict(latest)
                }
                dao.insertEvent(
                    AgentRunEventEntity(
                        runId = runId.toString(),
                        revision = plan.nextRevision,
                        kind = AgentRunEventKind.REPLY_BOUND.name,
                        fromState = run.state.name,
                        toState = run.state.name,
                        reason = null,
                        stepsUsed = null,
                        createdAt = nowMillis,
                    )
                )
                BindReplyResult.Applied(
                    run.copy(
                        replyMessageId = replyMessageId,
                        stateRevision = plan.nextRevision,
                        updatedAt = now,
                    )
                )
            }
        }
    }

    override suspend fun recordSteps(
        runId: Uuid,
        expectedRevision: Long,
        stepsUsed: Int,
        now: Instant,
    ): RecordStepsResult = database.withTransaction {
        val current = dao.getRun(runId.toString())?.toModel()
        when (val plan = AgentRunRules.planRecordSteps(current, expectedRevision, stepsUsed)) {
            is StepPlan.Reject -> when (plan.reason) {
                StepProgressRejection.NOT_FOUND -> RecordStepsResult.NotFound

                StepProgressRejection.STALE_REVISION -> RecordStepsResult.Conflict(
                    checkNotNull(current) { "conflict requires a snapshot" }
                )

                // 终态历史不可改；累计步数不可减少
                StepProgressRejection.TERMINAL_STATE,
                StepProgressRejection.DECREASING,
                -> RecordStepsResult.Rejected(plan.reason, plan.current)
            }

            is StepPlan.Apply -> {
                val run = checkNotNull(current) { "apply requires a snapshot" }
                val nowMillis = now.toEpochMilli()
                val affected = dao.updateStepsCas(
                    runId = runId.toString(),
                    expectedRevision = expectedRevision,
                    expectedState = run.state.name,
                    stepsUsed = plan.stepsUsed,
                    now = nowMillis,
                )
                if (affected == 0) {
                    // 读到之后状态/步数被别人改了：交出最新快照
                    val latest = dao.getRun(runId.toString())?.toModel()
                        ?: return@withTransaction RecordStepsResult.NotFound
                    return@withTransaction RecordStepsResult.Conflict(latest)
                }
                dao.insertEvent(
                    AgentRunEventEntity(
                        runId = runId.toString(),
                        revision = plan.nextRevision,
                        kind = AgentRunEventKind.STEP_PROGRESS.name,
                        fromState = run.state.name,
                        toState = run.state.name,
                        reason = null,
                        stepsUsed = plan.stepsUsed,
                        createdAt = nowMillis,
                    )
                )
                RecordStepsResult.Applied(
                    run.copy(
                        stepsUsed = plan.stepsUsed,
                        stateRevision = plan.nextRevision,
                        updatedAt = now,
                    )
                )
            }
        }
    }

    override suspend fun listByState(state: AgentRunState): List<AgentRun> =
        dao.listRunsByState(state.name).map { it.toModel() }

    override suspend fun listForConversation(conversationId: Uuid): List<AgentRun> =
        dao.listRunsByConversation(conversationId.toString()).map { it.toModel() }

    override fun observeConversation(conversationId: Uuid): Flow<List<AgentRun>> =
        dao.observeRunsByConversation(conversationId.toString())
            .map { list -> list.map { it.toModel() } }

    override fun observeTeam(teamId: Uuid): Flow<List<AgentRun>> =
        dao.observeRunsByTeam(teamId.toString()).map { list -> list.map { it.toModel() } }

    override suspend fun events(runId: Uuid, limit: Int, beforeRevision: Long?): List<AgentRunEvent> {
        require(limit in 1..1000) { "limit must be in 1..1000" }
        return dao.eventsBefore(
            runId = runId.toString(),
            beforeRevision = beforeRevision ?: Long.MAX_VALUE,
            limit = limit,
        ).map { it.toModel() }.asReversed()
    }

    override suspend fun recoverInterrupted(reason: String, now: Instant): RecoveryReport {
        val interrupted = mutableListOf<Uuid>()
        val skipped = mutableListOf<Uuid>()
        // 只挑残留 RUNNING；QUEUED / WAITING_* / PAUSED 原样保留，排队不因重启丢失
        dao.listRunsByState(AgentRunState.RUNNING.name).forEach { entity ->
            val run = entity.toModel()
            if (AgentRunRules.recoveryAction(run.state) != RecoveryAction.INTERRUPT) {
                // 查询已只取 RUNNING；这里再按纯规则守一道，非中断策略一律不动
                skipped += run.runId
                return@forEach
            }
            val result = transition(
                runId = run.runId,
                expectedRevision = run.stateRevision,
                to = AgentRunState.INTERRUPTED,
                reason = reason,
                now = now,
            )
            if (result is RunTransitionResult.Applied) {
                interrupted += run.runId
            } else {
                skipped += run.runId
            }
        }
        return RecoveryReport(interrupted = interrupted, skipped = skipped)
    }

    /**
     * R2-A：把「Run 状态迁移 + 事件 + 待通知行」放进**同一个** Room 短事务。
     *
     * - 迁移被拒（NotFound/Conflict/IllegalTransition）→ 不写通知行，返回 `Rejected(mailbox = null)`；
     * - 通知行被幂等/容量拒绝 → 整个事务回滚，Run 保持原状，返回 `MailboxRejected`；
     * - 正文超过 [AgentMailboxRules.MAX_BODY_TEXT_CHARS] → 直接抛 IllegalArgumentException，**不截断**。
     */
    override suspend fun transitionAndEnqueue(
        runId: Uuid,
        expectedRevision: Long,
        to: AgentRunState,
        reason: String?,
        now: Instant,
        entry: NewMailboxEntry,
        limit: Int,
    ): RunTransitionAndEnqueueResult {
        val key = AgentMailboxRules.normalizeIdempotencyKey(entry.idempotencyKey)
            ?: throw IllegalArgumentException("idempotencyKey must not be blank")
        AgentMailboxRules.validateNewEntry(entry.bodyText, key)?.let { throw IllegalArgumentException(it) }
        val prepared = entry.toPendingEntry().copy(idempotencyKey = key)
        val mailboxDao = database.agentMailboxDao()
        return try {
            database.withTransaction {
                // ---- 同一短事务：agent_run 迁移 + 事件追加 + agent_mailbox 插入 ----
                val current = dao.getRun(runId.toString())?.toModel()
                when (val plan = AgentRunRules.planTransition(current, expectedRevision, to)) {
                    is TransitionPlan.Reject -> RunTransitionAndEnqueueResult.Rejected(
                        transition = plan.toRunTransitionResult(current, to),
                        mailbox = null,
                    )

                    is TransitionPlan.Apply -> {
                        val run = checkNotNull(current) { "apply requires a snapshot" }
                        // 通知行先做幂等/容量预检：不合法就整段不写，避免先改 Run 再回滚
                        val existingId = mailboxDao.findByIdempotencyKey(key)?.let { Uuid.parse(it.id) }
                        val unacked = mailboxDao.countUnacked(
                            target = prepared.targetConversationId.toString(),
                            ackedState = MailboxDeliveryState.ACKED.name,
                        )
                        when (AgentMailboxRules.planEnqueue(existingId, unacked, limit)) {
                            MailboxEnqueueRejection.DUPLICATE -> RunTransitionAndEnqueueResult.MailboxRejected(
                                reason = MailboxEnqueueRejection.DUPLICATE,
                                current = run,
                                existingMailboxId = existingId,
                            )

                            MailboxEnqueueRejection.CAPACITY_EXCEEDED -> RunTransitionAndEnqueueResult.MailboxRejected(
                                reason = MailboxEnqueueRejection.CAPACITY_EXCEEDED,
                                current = run,
                                existingMailboxId = null,
                            )

                            null -> {
                                val nowMillis = now.toEpochMilli()
                                val affected = dao.updateStateCas(
                                    runId = runId.toString(),
                                    expectedRevision = expectedRevision,
                                    fromState = plan.from.name,
                                    toState = plan.to.name,
                                    reason = reason ?: run.reason,
                                    now = nowMillis,
                                    startedAt = if (plan.stampStartedAt) nowMillis else null,
                                    finishedAt = if (plan.stampFinishedAt) nowMillis else null,
                                )
                                if (affected == 0) {
                                    val latest = dao.getRun(runId.toString())?.toModel()
                                    return@withTransaction if (latest == null) {
                                        RunTransitionAndEnqueueResult.Rejected(RunTransitionResult.NotFound, null)
                                    } else {
                                        RunTransitionAndEnqueueResult.Rejected(RunTransitionResult.Conflict(latest), null)
                                    }
                                }
                                dao.insertEvent(
                                    AgentRunEventEntity(
                                        runId = runId.toString(),
                                        revision = plan.nextRevision,
                                        kind = AgentRunEventKind.STATE_CHANGED.name,
                                        fromState = plan.from.name,
                                        toState = plan.to.name,
                                        reason = reason,
                                        stepsUsed = null,
                                        createdAt = nowMillis,
                                    )
                                )
                                mailboxDao.insert(prepared.toEntity())
                                RunTransitionAndEnqueueResult.Applied(
                                    run = run.copy(
                                        state = plan.to,
                                        reason = reason ?: run.reason,
                                        stateRevision = plan.nextRevision,
                                        updatedAt = now,
                                        startedAt = if (plan.stampStartedAt) now else run.startedAt,
                                        finishedAt = if (plan.stampFinishedAt) now else run.finishedAt,
                                    ),
                                    mailbox = prepared,
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: SQLiteConstraintException) {
            // 并发插入同 key：事务已整体回滚，Run 未改；回读后报告 Duplicate
            val run = dao.getRun(runId.toString())?.toModel()
            val existingId = mailboxDao.findByIdempotencyKey(key)?.let { Uuid.parse(it.id) }
            RunTransitionAndEnqueueResult.MailboxRejected(
                reason = MailboxEnqueueRejection.DUPLICATE,
                current = run,
                existingMailboxId = existingId,
            )
        }
    }

    // ---------- 映射 ----------

    /** 迁移拒绝 → 与 `transition()` 相同的三类输出（供 transitionAndEnqueue 复用） */
    private fun TransitionPlan.Reject.toRunTransitionResult(
        current: AgentRun?,
        to: AgentRunState,
    ): RunTransitionResult = when (reason) {
        TransitionRejection.NOT_FOUND -> RunTransitionResult.NotFound

        TransitionRejection.STALE_REVISION -> RunTransitionResult.Conflict(
            checkNotNull(current) { "stale revision requires a snapshot" }
        )

        TransitionRejection.ILLEGAL_TRANSITION -> RunTransitionResult.IllegalTransition(
            checkNotNull(current) { "illegal transition requires a snapshot" }.state,
            to,
        )
    }

    private fun AgentRunEntity.toModel(): AgentRun = AgentRun(
        runId = Uuid.parse(id),
        teamId = teamId?.let { Uuid.parse(it) },
        agentId = agentId?.let { Uuid.parse(it) },
        conversationId = Uuid.parse(conversationId),
        taskId = taskId?.let { Uuid.parse(it) },
        parentRunId = parentRunId?.let { Uuid.parse(it) },
        previousRunId = previousRunId?.let { Uuid.parse(it) },
        sourceKey = sourceKey,
        attemptNo = attemptNo,
        inputRef = inputRef,
        inputJson = inputJson,
        replyMessageId = replyMessageId,
        state = runCatching { AgentRunState.valueOf(state) }.getOrDefault(AgentRunState.INTERRUPTED),
        reason = reason,
        stateRevision = stateRevision,
        stepLimit = stepLimit,
        stepsUsed = stepsUsed,
        deadlineAt = deadlineAt?.let(Instant::ofEpochMilli),
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        startedAt = startedAt?.let(Instant::ofEpochMilli),
        finishedAt = finishedAt?.let(Instant::ofEpochMilli),
    )

    private fun AgentRun.toEntity(): AgentRunEntity = AgentRunEntity(
        id = runId.toString(),
        teamId = teamId?.toString(),
        agentId = agentId?.toString(),
        conversationId = conversationId.toString(),
        taskId = taskId?.toString(),
        parentRunId = parentRunId?.toString(),
        previousRunId = previousRunId?.toString(),
        sourceKey = sourceKey,
        attemptNo = attemptNo,
        inputRef = inputRef,
        inputJson = inputJson,
        replyMessageId = replyMessageId,
        state = state.name,
        reason = reason,
        stateRevision = stateRevision,
        stepLimit = stepLimit,
        stepsUsed = stepsUsed,
        deadlineAt = deadlineAt?.toEpochMilli(),
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        startedAt = startedAt?.toEpochMilli(),
        finishedAt = finishedAt?.toEpochMilli(),
    )

    private fun AgentRunTeamSummaryRow.toSummary(): AgentRunTeamSummary = AgentRunTeamSummary(
        runId = Uuid.parse(runId),
        conversationId = Uuid.parse(conversationId),
        agentId = agentId?.let { Uuid.parse(it) },
        taskId = taskId?.let { Uuid.parse(it) },
        state = runCatching { AgentRunState.valueOf(state) }.getOrDefault(AgentRunState.INTERRUPTED),
        stateRevision = stateRevision,
        stepsUsed = stepsUsed,
        stepLimit = stepLimit,
        updatedAt = Instant.ofEpochMilli(updatedAt),
        startedAt = startedAt?.let(Instant::ofEpochMilli),
        finishedAt = finishedAt?.let(Instant::ofEpochMilli),
        replyMessageId = replyMessageId,
    )

    private fun AgentRunEventEntity.toModel(): AgentRunEvent = AgentRunEvent(
        runId = Uuid.parse(runId),
        revision = revision,
        kind = runCatching { AgentRunEventKind.valueOf(kind) }
            .getOrDefault(AgentRunEventKind.STATE_CHANGED),
        fromState = fromState?.let { runCatching { AgentRunState.valueOf(it) }.getOrNull() },
        toState = toState?.let { runCatching { AgentRunState.valueOf(it) }.getOrNull() },
        reason = reason,
        stepsUsed = stepsUsed,
        at = Instant.ofEpochMilli(createdAt),
    )
}
