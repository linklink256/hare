package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 持久收件箱（append-only 语义，状态只前移 PENDING→CLAIMED→ACKED）。
 *
 * 不加外键：会话/团队被删除后，已发生的派活与结果通知仍要可查（与 `agent_run` 同取舍）。
 * `idempotency_key` 唯一，重复 enqueue 只能命中既有行；`revision` 是 claim/ack 的 CAS 版本。
 */
@Entity(
    tableName = "agent_mailbox",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["target_conversation_id", "deliver_state", "created_at"]),
        Index(value = ["run_id"]),
    ]
)
data class AgentMailboxEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("team_id")
    val teamId: String?,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String?,
    @ColumnInfo("target_conversation_id")
    val targetConversationId: String,
    @ColumnInfo("run_id")
    val runId: String?,
    @ColumnInfo("task_id")
    val taskId: String?,
    /** ASSIGN | RESULT | FAILURE | NOTICE | STEER | ASK | ANSWER */
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("body_text")
    val bodyText: String?,
    @ColumnInfo("body_ref")
    val bodyRef: String?,
    @ColumnInfo("idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** PENDING | CLAIMED | ACKED */
    @ColumnInfo("deliver_state")
    val deliverState: String,
    @ColumnInfo("revision")
    val revision: Long,
    @ColumnInfo("attempt_count")
    val attemptCount: Int,
    @ColumnInfo("claim_token")
    val claimToken: String?,
    @ColumnInfo("claimed_at")
    val claimedAt: Long?,
    @ColumnInfo("acked_at")
    val ackedAt: Long?,
)
