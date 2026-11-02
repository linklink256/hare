package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.AgentMailboxDAO
import me.rerere.rikkahub.data.db.entity.AgentMailboxEntity
import me.rerere.rikkahub.data.model.AgentMailboxEntry
import me.rerere.rikkahub.data.model.AgentMailboxRules
import me.rerere.rikkahub.data.model.MailboxAckPlan
import me.rerere.rikkahub.data.model.MailboxAckRejection
import me.rerere.rikkahub.data.model.MailboxAckResult
import me.rerere.rikkahub.data.model.MailboxClaimPlan
import me.rerere.rikkahub.data.model.MailboxClaimResult
import me.rerere.rikkahub.data.model.MailboxKind
import me.rerere.rikkahub.data.model.MailboxDeliveryState
import me.rerere.rikkahub.data.model.MailboxEnqueueRejection
import me.rerere.rikkahub.data.model.MailboxEnqueueResult
import me.rerere.rikkahub.data.model.NewMailboxEntry
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 持久收件箱的稳定接口：只负责「存一条待处理消息 + 领取 + 确认」，不管调度、不改聊天转录。
 * 接线层只依赖本接口，测试可给 InMemory 假实现。
 */
interface AgentMailboxStore {
    /**
     * 入队一条待通知项。
     *
     * @return Created / Duplicate(既有 id) / CapacityExceeded(未 ACK 数)；正文超限直接抛
     * IllegalArgumentException（**不截断**，要求改用 bodyRef）。
     */
    suspend fun enqueue(
        entry: NewMailboxEntry,
        limit: Int = AgentMailboxRules.MAX_UNACKED_PER_TARGET,
    ): MailboxEnqueueResult

    suspend fun get(id: Uuid): AgentMailboxEntry?

    suspend fun findByIdempotencyKey(idempotencyKey: String): AgentMailboxEntry?

    /** 取该 target 的下一条可领取项（PENDING 或租约过期的 CLAIMED，FIFO）；没有则 [MailboxClaimResult.None] */
    suspend fun claimNext(
        targetConversationId: Uuid,
        now: Instant = Instant.now(),
        ttlMillis: Long = AgentMailboxRules.CLAIM_TTL_MILLIS,
    ): MailboxClaimResult

    /**
     * 按 id 领取一行（ASSIGN 真正落进会话时确认用）：语义是 [claimNext] 的单行版，
     * 不可领取（缺行 / 已 ACKED / CLAIMED 未过期）返回 [MailboxClaimResult.None]。
     */
    suspend fun claimOne(
        id: Uuid,
        now: Instant = Instant.now(),
        ttlMillis: Long = AgentMailboxRules.CLAIM_TTL_MILLIS,
    ): MailboxClaimResult

    /** 确认：claimToken 与 expectedRevision 必须同时匹配，ACKED 行不再算成功 */
    suspend fun ack(
        id: Uuid,
        claimToken: String,
        expectedRevision: Long,
        now: Instant = Instant.now(),
    ): MailboxAckResult

    suspend fun listForTarget(targetConversationId: Uuid, limit: Int = 64): List<AgentMailboxEntry>

    fun observeUnacked(targetConversationId: Uuid): Flow<List<AgentMailboxEntry>>
}

/**
 * 收件箱的 Room 实现。
 *
 * 事务边界：`enqueue` 的「幂等 + 容量 + 插入」在同一个短事务；`claimNext` 的「查 + CAS」、
 * `ack` 的「查 + CAS」也各自在同一个短事务里，避免并发下超发或重复确认。
 */
class AgentMailboxRepository(
    private val database: AppDatabase,
    private val dao: AgentMailboxDAO = database.agentMailboxDao(),
) : AgentMailboxStore {

    override suspend fun enqueue(entry: NewMailboxEntry, limit: Int): MailboxEnqueueResult {
        val key = AgentMailboxRules.normalizeIdempotencyKey(entry.idempotencyKey)
            ?: throw IllegalArgumentException("idempotencyKey must not be blank")
        AgentMailboxRules.validateNewEntry(entry.bodyText, key)?.let { throw IllegalArgumentException(it) }
        val prepared = entry.toPendingEntry().copy(idempotencyKey = key)
        return try {
            database.withTransaction {
                // 幂等与容量都在同一个事务内判定：重复派活不写第二行，满额显式拒绝而不是丢弃
                val existingId = dao.findByIdempotencyKey(key)?.let { Uuid.parse(it.id) }
                val unacked = dao.countUnacked(
                    target = prepared.targetConversationId.toString(),
                    ackedState = MailboxDeliveryState.ACKED.name,
                )
                when (AgentMailboxRules.planEnqueue(existingId, unacked, limit)) {
                    MailboxEnqueueRejection.DUPLICATE -> MailboxEnqueueResult.Duplicate(
                        checkNotNull(existingId) { "duplicate requires an existing id" }
                    )

                    MailboxEnqueueRejection.CAPACITY_EXCEEDED -> MailboxEnqueueResult.CapacityExceeded(unacked)

                    null -> {
                        dao.insert(prepared.toEntity())
                        MailboxEnqueueResult.Created(prepared)
                    }
                }
            }
        } catch (e: SQLiteConstraintException) {
            // 并发下同 key 抢先插入：回读既有行，仍然只返回 Duplicate
            val existing = dao.findByIdempotencyKey(key) ?: throw e
            MailboxEnqueueResult.Duplicate(Uuid.parse(existing.id))
        }
    }

    override suspend fun get(id: Uuid): AgentMailboxEntry? = dao.get(id.toString())?.toModel()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): AgentMailboxEntry? {
        val key = AgentMailboxRules.normalizeIdempotencyKey(idempotencyKey) ?: return null
        return dao.findByIdempotencyKey(key)?.toModel()
    }

    override suspend fun claimNext(
        targetConversationId: Uuid,
        now: Instant,
        ttlMillis: Long,
    ): MailboxClaimResult = database.withTransaction {
        // 同一个过期线同时用于「查」与「CAS」：PENDING 随时可领，CLAIMED 必须租约过期
        val staleBefore = AgentMailboxRules.claimStaleBefore(now, ttlMillis)
        var attempt = 0
        while (attempt < CLAIM_ATTEMPTS) {
            attempt++
            val row = dao.findClaimable(
                target = targetConversationId.toString(),
                pendingState = MailboxDeliveryState.PENDING.name,
                claimedState = MailboxDeliveryState.CLAIMED.name,
                staleBefore = staleBefore,
            ) ?: return@withTransaction MailboxClaimResult.None
            val entry = row.toModel()
            val plan = AgentMailboxRules.planClaim(entry, now, ttlMillis)
            if (plan !is MailboxClaimPlan.Apply) return@withTransaction MailboxClaimResult.None
            val token = Uuid.random().toString()
            val affected = dao.claimCas(
                id = entry.id.toString(),
                expectedRevision = entry.revision,
                pendingState = MailboxDeliveryState.PENDING.name,
                claimedState = MailboxDeliveryState.CLAIMED.name,
                staleBefore = staleBefore,
                token = token,
                now = now.toEpochMilli(),
            )
            if (affected == 1) {
                return@withTransaction MailboxClaimResult.Claimed(
                    entry.copy(
                        deliverState = MailboxDeliveryState.CLAIMED,
                        revision = plan.nextRevision,
                        attemptCount = plan.attemptCount,
                        claimToken = token,
                        claimedAt = now,
                    )
                )
            }
            // CAS 落空：别人先抢走了，重来一轮（由 CLAIM_ATTEMPTS 兜住）
        }
        MailboxClaimResult.None
    }

    override suspend fun claimOne(
        id: Uuid,
        now: Instant,
        ttlMillis: Long,
    ): MailboxClaimResult = database.withTransaction {
        // 与 claimNext 同一过期线：PENDING 随时可领，CLAIMED 必须租约过期，ACKED 不可领
        val staleBefore = AgentMailboxRules.claimStaleBefore(now, ttlMillis)
        val row = dao.get(id.toString())?.toModel()
        val plan = AgentMailboxRules.planClaim(row, now, ttlMillis)
        if (row == null || plan !is MailboxClaimPlan.Apply) {
            return@withTransaction MailboxClaimResult.None
        }
        val token = Uuid.random().toString()
        val affected = dao.claimCas(
            id = row.id.toString(),
            expectedRevision = row.revision,
            pendingState = MailboxDeliveryState.PENDING.name,
            claimedState = MailboxDeliveryState.CLAIMED.name,
            staleBefore = staleBefore,
            token = token,
            now = now.toEpochMilli(),
        )
        if (affected != 1) return@withTransaction MailboxClaimResult.None
        MailboxClaimResult.Claimed(
            row.copy(
                deliverState = MailboxDeliveryState.CLAIMED,
                revision = plan.nextRevision,
                attemptCount = plan.attemptCount,
                claimToken = token,
                claimedAt = now,
            )
        )
    }

    override suspend fun ack(
        id: Uuid,
        claimToken: String,
        expectedRevision: Long,
        now: Instant,
    ): MailboxAckResult = database.withTransaction {
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
        val current = dao.get(id.toString())?.toModel()
        when (val plan = AgentMailboxRules.planAck(current, claimToken, expectedRevision)) {
            is MailboxAckPlan.Reject -> when (plan.reason) {
                MailboxAckRejection.NOT_FOUND -> MailboxAckResult.NotFound

                // 已 ACK（终态）/ 未领取 / token 或版本不匹配：都不算成功，交出最新快照
                else -> MailboxAckResult.Conflict(
                    checkNotNull(current) { "conflict requires a snapshot" }
                )
            }

            is MailboxAckPlan.Apply -> {
                val row = checkNotNull(current) { "apply requires a snapshot" }
                val affected = dao.ackCas(
                    id = id.toString(),
                    expectedRevision = expectedRevision,
                    claimedState = MailboxDeliveryState.CLAIMED.name,
                    ackedState = MailboxDeliveryState.ACKED.name,
                    token = claimToken,
                    now = now.toEpochMilli(),
                )
                if (affected == 0) {
                    val latest = dao.get(id.toString())?.toModel()
                        ?: return@withTransaction MailboxAckResult.NotFound
                    return@withTransaction MailboxAckResult.Conflict(latest)
                }
                MailboxAckResult.Applied(
                    row.copy(
                        deliverState = MailboxDeliveryState.ACKED,
                        revision = plan.nextRevision,
                        ackedAt = now,
                    )
                )
            }
        }
    }

    override suspend fun listForTarget(targetConversationId: Uuid, limit: Int): List<AgentMailboxEntry> {
        require(limit in 1..1000) { "limit must be in 1..1000" }
        return dao.listForTarget(targetConversationId.toString(), limit).map { it.toModel() }
    }

    override fun observeUnacked(targetConversationId: Uuid): Flow<List<AgentMailboxEntry>> =
        dao.observeUnacked(
            target = targetConversationId.toString(),
            ackedState = MailboxDeliveryState.ACKED.name,
        ).map { rows -> rows.map { it.toModel() } }

    private companion object {
        /** claim 的 CAS 重试次数：只吸收并发抢同一行的抖动，不做无限重试 */
        const val CLAIM_ATTEMPTS = 3
    }
}

// ---------- 模型 <-> 实体（同模块内共享，供 AgentRunRepository.transitionAndEnqueue 复用） ----------

internal fun NewMailboxEntry.toPendingEntry(): AgentMailboxEntry = AgentMailboxEntry(
    id = id,
    teamId = teamId,
    sourceConversationId = sourceConversationId,
    targetConversationId = targetConversationId,
    runId = runId,
    taskId = taskId,
    kind = kind,
    bodyText = bodyText,
    bodyRef = bodyRef,
    idempotencyKey = idempotencyKey,
    createdAt = now,
    deliverState = MailboxDeliveryState.PENDING,
    revision = 1L,
    attemptCount = 0,
    claimToken = null,
    claimedAt = null,
    ackedAt = null,
)

internal fun AgentMailboxEntry.toEntity(): AgentMailboxEntity = AgentMailboxEntity(
    id = id.toString(),
    teamId = teamId?.toString(),
    sourceConversationId = sourceConversationId?.toString(),
    targetConversationId = targetConversationId.toString(),
    runId = runId?.toString(),
    taskId = taskId?.toString(),
    kind = kind.name,
    bodyText = bodyText,
    bodyRef = bodyRef,
    idempotencyKey = idempotencyKey,
    createdAt = createdAt.toEpochMilli(),
    deliverState = deliverState.name,
    revision = revision,
    attemptCount = attemptCount,
    claimToken = claimToken,
    claimedAt = claimedAt?.toEpochMilli(),
    ackedAt = ackedAt?.toEpochMilli(),
)

internal fun AgentMailboxEntity.toModel(): AgentMailboxEntry = AgentMailboxEntry(
    id = Uuid.parse(id),
    teamId = teamId?.let { Uuid.parse(it) },
    sourceConversationId = sourceConversationId?.let { Uuid.parse(it) },
    targetConversationId = Uuid.parse(targetConversationId),
    runId = runId?.let { Uuid.parse(it) },
    taskId = taskId?.let { Uuid.parse(it) },
    kind = runCatching { MailboxKind.valueOf(kind) }.getOrDefault(MailboxKind.NOTICE),
    bodyText = bodyText,
    bodyRef = bodyRef,
    idempotencyKey = idempotencyKey,
    createdAt = Instant.ofEpochMilli(createdAt),
    deliverState = runCatching { MailboxDeliveryState.valueOf(deliverState) }
        .getOrDefault(MailboxDeliveryState.PENDING),
    revision = revision,
    attemptCount = attemptCount,
    claimToken = claimToken,
    claimedAt = claimedAt?.let(Instant::ofEpochMilli),
    ackedAt = ackedAt?.let(Instant::ofEpochMilli),
)
