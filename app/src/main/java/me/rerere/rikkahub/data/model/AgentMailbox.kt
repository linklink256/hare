package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 持久收件箱的消息类别。
 *
 * 这里只记「交给谁处理什么」，不是聊天记录：UI 仍读会话转录，mailbox 负责崩溃后可查与可确认。
 */
@Serializable
enum class MailboxKind {
    ASSIGN,
    RESULT,
    FAILURE,
    NOTICE,
    STEER,
    ASK,
    ANSWER,
}

/** 投递状态只前移：PENDING → CLAIMED → ACKED，ACKED 为终态。 */
@Serializable
enum class MailboxDeliveryState {
    PENDING,
    CLAIMED,
    ACKED,
}

/**
 * 收件箱一行。正文只有 `bodyText`（小载荷）或 `bodyRef`（大载荷引用）二选一，
 * 迁移状态用 `revision` 做 CAS，claim 用 `claimToken` + `claimedAt` 支持 TTL 重新领取。
 */
@Serializable
data class AgentMailboxEntry(
    val id: Uuid = Uuid.random(),
    val teamId: Uuid? = null,
    val sourceConversationId: Uuid? = null,
    /** 收件方会话：assemble/RESULT 时通常是领队会话 */
    val targetConversationId: Uuid,
    val runId: Uuid? = null,
    val taskId: Uuid? = null,
    val kind: MailboxKind,
    /** 小载荷正文；超过 [AgentMailboxRules.MAX_BODY_TEXT_CHARS] 必须改用 [bodyRef]，不许截断 */
    val bodyText: String? = null,
    /** 大载荷引用（文件/消息/产物 id），与 [bodyText] 互不要求同时存在 */
    val bodyRef: String? = null,
    /** 派活/通知幂等键：同键只保留一行，重复 enqueue 返回既有 id */
    val idempotencyKey: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    val deliverState: MailboxDeliveryState = MailboxDeliveryState.PENDING,
    /** 状态 CAS 版本，从 1 开始，每次 claim/ack 提交 +1 */
    val revision: Long = 1L,
    /** 被领取次数（含 TTL 过期后的重新领取） */
    val attemptCount: Int = 0,
    /** 当前领取者令牌；ack 必须回填同一个，防止重复确认别人的租约 */
    val claimToken: String? = null,
    @Serializable(with = InstantSerializer::class)
    val claimedAt: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val ackedAt: Instant? = null,
)

/** 新建一行收件箱的入参；状态恒为 PENDING、revision=1，由持久层决定。 */
data class NewMailboxEntry(
    val targetConversationId: Uuid,
    val kind: MailboxKind,
    /** 幂等键，调用方负责语义化命名（如 `result:<runId>`）；空串表示非法，不做去重 */
    val idempotencyKey: String,
    val teamId: Uuid? = null,
    val sourceConversationId: Uuid? = null,
    val runId: Uuid? = null,
    val taskId: Uuid? = null,
    val bodyText: String? = null,
    val bodyRef: String? = null,
    val now: Instant = Instant.now(),
    val id: Uuid = Uuid.random(),
)

sealed interface MailboxEnqueueResult {
    data class Created(val entry: AgentMailboxEntry) : MailboxEnqueueResult

    /** 同 idempotencyKey 已有行：返回既有 id，不写第二行、不改已 ACKED 行 */
    data class Duplicate(val existingId: Uuid) : MailboxEnqueueResult

    /** 同一 target 未 ACK 行已达上限：显式拒绝，不静默丢弃 RESULT/ASK/STEER */
    data class CapacityExceeded(val pending: Int) : MailboxEnqueueResult
}

sealed interface MailboxClaimResult {
    data class Claimed(val entry: AgentMailboxEntry) : MailboxClaimResult

    data object None : MailboxClaimResult
}

sealed interface MailboxAckResult {
    data class Applied(val entry: AgentMailboxEntry) : MailboxAckResult

    /** 空值来自 token/版本不匹配，或该行已经是 ACKED（重复确认不算成功） */
    data class Conflict(val current: AgentMailboxEntry) : MailboxAckResult

    data object NotFound : MailboxAckResult
}

enum class MailboxEnqueueRejection { DUPLICATE, CAPACITY_EXCEEDED }

sealed interface MailboxClaimPlan {
    data class Apply(val nextRevision: Long, val attemptCount: Int) : MailboxClaimPlan

    data class NotClaimable(val current: AgentMailboxEntry?) : MailboxClaimPlan
}

enum class MailboxAckRejection { NOT_FOUND, ALREADY_ACKED, NOT_CLAIMED, TOKEN_MISMATCH, STALE_REVISION }

sealed interface MailboxAckPlan {
    data class Apply(val nextRevision: Long) : MailboxAckPlan

    data class Reject(val reason: MailboxAckRejection, val current: AgentMailboxEntry?) : MailboxAckPlan
}

/**
 * 收件箱的纯规则。没有 Android / Room 依赖，app JVM 单测（JUnit4）可直接覆盖。
 *
 * 这里只判「能不能、该写什么」；并发正确性由 repository 的事务 + `revision` CAS 负责。
 */
object AgentMailboxRules {
    /** 单行正文上限；超限必须走 bodyRef，**绝不截断** */
    const val MAX_BODY_TEXT_CHARS = 16384

    /** 同一 target 的未 ACK 行上限（RESULT/ASK/STEER 等关键消息满额时显式拒绝，不丢弃） */
    const val MAX_UNACKED_PER_TARGET = 64

    /** claim 租约 TTL：超过后其他消费者可重新领取 */
    const val CLAIM_TTL_MILLIS = 10 * 60 * 1000L

    fun isTerminal(state: MailboxDeliveryState): Boolean = state == MailboxDeliveryState.ACKED

    fun isUnacked(state: MailboxDeliveryState): Boolean = state != MailboxDeliveryState.ACKED

    /** PENDING→CLAIMED→ACKED；ACKED 终态，禁止回退与跳级 */
    fun validMailboxTransition(from: MailboxDeliveryState, to: MailboxDeliveryState): Boolean = when {
        from == MailboxDeliveryState.PENDING && to == MailboxDeliveryState.CLAIMED -> true
        from == MailboxDeliveryState.CLAIMED && to == MailboxDeliveryState.ACKED -> true
        else -> false
    }

    /** 幂等键规范化：去空白；空串/null 视为非法（enqueue 会显式失败，而不是静默不去重） */
    fun normalizeIdempotencyKey(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** 入参校验；返回 null 表示通过，否则是给调用方看的说明（repository 用 IllegalArgumentException 抛出） */
    fun validateNewEntry(bodyText: String?, idempotencyKey: String): String? = when {
        idempotencyKey.isBlank() -> "idempotencyKey must not be blank"
        bodyText != null && bodyText.length > MAX_BODY_TEXT_CHARS ->
            "bodyText too large (${bodyText.length} > $MAX_BODY_TEXT_CHARS); put the payload in bodyRef, truncation is forbidden"

        else -> null
    }

    /** 容量：同一 target 未 ACK 行达到上限即拒绝 */
    fun shouldRejectForCapacity(
        unackedCount: Int,
        limit: Int = MAX_UNACKED_PER_TARGET,
    ): Boolean = unackedCount >= limit

    /** claim 查询用的「过期线」：`claimed_at <= staleBefore` 的 CLAIMED 行可被重新领取 */
    fun claimStaleBefore(now: Instant, ttlMillis: Long = CLAIM_TTL_MILLIS): Long =
        now.toEpochMilli() - ttlMillis

    /** 该行此刻能否被领取：PENDING，或 CLAIMED 且租约已过期 */
    fun canClaim(
        state: MailboxDeliveryState,
        claimedAt: Instant?,
        now: Instant,
        ttlMillis: Long = CLAIM_TTL_MILLIS,
    ): Boolean = when (state) {
        MailboxDeliveryState.PENDING -> true
        MailboxDeliveryState.CLAIMED ->
            claimedAt != null && claimedAt.toEpochMilli() + ttlMillis <= now.toEpochMilli()

        MailboxDeliveryState.ACKED -> false
    }

    /** enqueue 的纯决策；返回 null 表示可以插入 */
    fun planEnqueue(
        existingId: Uuid?,
        unackedCount: Int,
        limit: Int = MAX_UNACKED_PER_TARGET,
    ): MailboxEnqueueRejection? = when {
        existingId != null -> MailboxEnqueueRejection.DUPLICATE
        shouldRejectForCapacity(unackedCount, limit) -> MailboxEnqueueRejection.CAPACITY_EXCEEDED
        else -> null
    }

    /** claim 的纯决策：不可领取时返回原因，可领取时给出新版本与尝试次数 */
    fun planClaim(
        current: AgentMailboxEntry?,
        now: Instant,
        ttlMillis: Long = CLAIM_TTL_MILLIS,
    ): MailboxClaimPlan {
        if (current == null) return MailboxClaimPlan.NotClaimable(null)
        if (!canClaim(current.deliverState, current.claimedAt, now, ttlMillis)) {
            return MailboxClaimPlan.NotClaimable(current)
        }
        return MailboxClaimPlan.Apply(
            nextRevision = current.revision + 1,
            attemptCount = current.attemptCount + 1,
        )
    }

    /** ack 的纯决策：token 与版本都要匹配，ACKED 视为已结束（不当作成功） */
    fun planAck(
        current: AgentMailboxEntry?,
        claimToken: String,
        expectedRevision: Long,
    ): MailboxAckPlan {
        if (current == null) return MailboxAckPlan.Reject(MailboxAckRejection.NOT_FOUND, null)
        if (current.deliverState == MailboxDeliveryState.ACKED) {
            return MailboxAckPlan.Reject(MailboxAckRejection.ALREADY_ACKED, current)
        }
        if (current.deliverState != MailboxDeliveryState.CLAIMED) {
            return MailboxAckPlan.Reject(MailboxAckRejection.NOT_CLAIMED, current)
        }
        if (current.claimToken == null || current.claimToken != claimToken) {
            return MailboxAckPlan.Reject(MailboxAckRejection.TOKEN_MISMATCH, current)
        }
        if (current.revision != expectedRevision) {
            return MailboxAckPlan.Reject(MailboxAckRejection.STALE_REVISION, current)
        }
        return MailboxAckPlan.Apply(nextRevision = current.revision + 1)
    }
}
