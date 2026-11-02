package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentMailboxEntity

/**
 * 收件箱的数据访问。
 *
 * 约束：
 * 1. 插入一律 `ABORT`——同一 idempotency_key 的第二次 enqueue 必须让调用方看见，而不是 REPLACE。
 * 2. claim / ack 都是带 `revision` 的 CAS，返回受影响行数；状态名用参数传入，避免 SQL 里散落字面量。
 * 3. 容量判定（`countUnacked`）由调用方放在同一个事务里，防止并发超发。
 */
@Dao
interface AgentMailboxDAO {
    // ---------- 写入 ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AgentMailboxEntity)

    /**
     * 领取 CAS：只允许 PENDING，或 CLAIMED 且租约已过期（`claimed_at <= :staleBefore`）的行。
     * TTL 条件写在 SQL 自身：即使调用方忘了先查可领取性，也不能抢走别人的有效租约。
     * 成功时写入新 claim_token、claimed_at、revision+1、attempt_count+1。
     */
    @Query(
        """
        UPDATE agent_mailbox
        SET deliver_state = :claimedState,
            claim_token = :token,
            claimed_at = :now,
            revision = revision + 1,
            attempt_count = attempt_count + 1
        WHERE id = :id AND revision = :expectedRevision
            AND (
                deliver_state = :pendingState
                OR (deliver_state = :claimedState AND claimed_at IS NOT NULL AND claimed_at <= :staleBefore)
            )
        """
    )
    suspend fun claimCas(
        id: String,
        expectedRevision: Long,
        pendingState: String,
        claimedState: String,
        staleBefore: Long,
        token: String,
        now: Long,
    ): Int

    /** 确认 CAS：token、版本、状态三者都必须匹配，ACKED 终态不接受二次确认。 */
    @Query(
        """
        UPDATE agent_mailbox
        SET deliver_state = :ackedState,
            acked_at = :now,
            revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision
            AND claim_token = :token AND deliver_state = :claimedState
        """
    )
    suspend fun ackCas(
        id: String,
        expectedRevision: Long,
        claimedState: String,
        ackedState: String,
        token: String,
        now: Long,
    ): Int

    // ---------- 读取 ----------

    @Query("SELECT * FROM agent_mailbox WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentMailboxEntity?

    @Query("SELECT * FROM agent_mailbox WHERE idempotency_key = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): AgentMailboxEntity?

    /** 同一 target 的未 ACK 行数：容量判定必须在事务内与插入一起做 */
    @Query(
        "SELECT COUNT(*) FROM agent_mailbox WHERE target_conversation_id = :target AND deliver_state <> :ackedState"
    )
    suspend fun countUnacked(target: String, ackedState: String): Int

    /**
     * 取一条可领取的行：PENDING，或租约已过期的 CLAIMED；按 created_at,id FIFO。
     * `staleBefore` 由 [me.rerere.rikkahub.data.model.AgentMailboxRules.claimStaleBefore] 计算。
     */
    @Query(
        """
        SELECT * FROM agent_mailbox
        WHERE target_conversation_id = :target
            AND (
                deliver_state = :pendingState
                OR (deliver_state = :claimedState AND claimed_at IS NOT NULL AND claimed_at <= :staleBefore)
            )
        ORDER BY created_at ASC, id ASC
        LIMIT 1
        """
    )
    suspend fun findClaimable(
        target: String,
        pendingState: String,
        claimedState: String,
        staleBefore: Long,
    ): AgentMailboxEntity?

    @Query(
        "SELECT * FROM agent_mailbox WHERE target_conversation_id = :target ORDER BY created_at ASC, id ASC LIMIT :limit"
    )
    suspend fun listForTarget(target: String, limit: Int): List<AgentMailboxEntity>

    /** 未 ACK 观察：给状态投影用，按 FIFO 输出；不包含 body 之外的历史拼接逻辑。 */
    @Query(
        "SELECT * FROM agent_mailbox WHERE target_conversation_id = :target AND deliver_state <> :ackedState " +
            "ORDER BY created_at ASC, id ASC"
    )
    fun observeUnacked(target: String, ackedState: String): Flow<List<AgentMailboxEntity>>
}
