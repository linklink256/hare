package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEventEntity

/**
 * Run 持久层的数据访问。
 *
 * 两条硬约束：
 * 1. 插入一律 `ABORT`（默认）——派活冲突/同版本事件冲突必须让调用方看见，不能 REPLACE 覆盖。
 * 2. 状态更新一律带 `state_revision`（必要时再加 `state`）条件的 CAS，返回受影响行数。
 *
 * 事务边界在 AgentRunRepository：Run 行与事件行必须在同一个短事务里提交。
 */
@Dao
interface AgentRunDAO {
    // ---------- 写入 ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: AgentRunEventEntity)

    /**
     * 状态迁移 CAS：只有版本与当前状态都匹配才写入，并把版本 +1。
     * `started_at` 只写第一次进入执行的时刻，`finished_at` 只接受非空（进入终态那次）。
     */
    @Query(
        """
        UPDATE agent_run
        SET state = :toState,
            reason = :reason,
            state_revision = state_revision + 1,
            updated_at = :now,
            started_at = COALESCE(started_at, :startedAt),
            finished_at = COALESCE(:finishedAt, finished_at)
        WHERE id = :runId AND state_revision = :expectedRevision AND state = :fromState
        """
    )
    suspend fun updateStateCas(
        runId: String,
        expectedRevision: Long,
        fromState: String,
        toState: String,
        reason: String?,
        now: Long,
        startedAt: Long?,
        finishedAt: Long?,
    ): Int

    /**
     * 绑定回复消息节点：只在尚未绑定且仍是调用方看到的状态时写入。
     * 终态守卫在纯规则里（`planBindReply`）；这里再用 `state` 条件兜一层，避免迟到写入。
     */
    @Query(
        """
        UPDATE agent_run
        SET reply_message_id = :replyMessageId,
            state_revision = state_revision + 1,
            updated_at = :now
        WHERE id = :runId AND state_revision = :expectedRevision
            AND state = :expectedState AND reply_message_id IS NULL
        """
    )
    suspend fun bindReplyCas(
        runId: String,
        expectedRevision: Long,
        expectedState: String,
        replyMessageId: String,
        now: Long,
    ): Int

    /**
     * 步数推进 CAS：写入累计绝对值（不是增量），只允许不减少。
     * 终态/版本/单调性先在纯规则判定，这里再用 `steps_used <= :stepsUsed` 与 `state` 兜底。
     */
    @Query(
        """
        UPDATE agent_run
        SET steps_used = :stepsUsed,
            state_revision = state_revision + 1,
            updated_at = :now
        WHERE id = :runId AND state_revision = :expectedRevision
            AND state = :expectedState AND steps_used <= :stepsUsed
        """
    )
    suspend fun updateStepsCas(
        runId: String,
        expectedRevision: Long,
        expectedState: String,
        stepsUsed: Int,
        now: Long,
    ): Int

    // ---------- 读取 ----------

    @Query("SELECT * FROM agent_run WHERE id = :runId LIMIT 1")
    suspend fun getRun(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_run WHERE source_key = :sourceKey LIMIT 1")
    suspend fun getRunBySourceKey(sourceKey: String): AgentRunEntity?

    @Query("SELECT * FROM agent_run WHERE state = :state ORDER BY created_at ASC")
    suspend fun listRunsByState(state: String): List<AgentRunEntity>

    @Query("SELECT * FROM agent_run WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun listRunsByConversation(conversationId: String): List<AgentRunEntity>

    @Query("SELECT * FROM agent_run WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun observeRunsByConversation(conversationId: String): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_run WHERE team_id = :teamId ORDER BY created_at ASC")
    fun observeRunsByTeam(teamId: String): Flow<List<AgentRunEntity>>

    /** 倒序取一页事件；调用方传 `revision < beforeRevision`，首屏传 Long.MAX_VALUE。 */
    @Query(
        "SELECT * FROM agent_run_event WHERE run_id = :runId AND revision < :beforeRevision " +
            "ORDER BY revision DESC LIMIT :limit"
    )
    suspend fun eventsBefore(runId: String, beforeRevision: Long, limit: Int): List<AgentRunEventEntity>

    // ---------- 定向查询（R1 精确 resume / 轻量投影；热路径不要用全量 list/observe） ----------

    /**
     * 精确回复锚点反查：同一会话里 `reply_message_id` 命中的最近一条 Run。
     * 不对状态做业务过滤——调用方拿到后自行判定是否还在进行/暂停时才 resume。
     */
    @Query(
        """
        SELECT * FROM agent_run
        WHERE conversation_id = :conversationId AND reply_message_id = :replyMessageId
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun findLatestByReply(conversationId: String, replyMessageId: String): AgentRunEntity?

    /** 会话最近一条 Run：判态只需一行，避免拉全历史。 */
    @Query(
        """
        SELECT * FROM agent_run
        WHERE conversation_id = :conversationId
        ORDER BY created_at DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun latestRunForConversation(conversationId: String): AgentRunEntity?

    /**
     * 团队轻量投影：每个会话只取最近一条 Run 的摘要列，完全不读 input_json / input_ref 等负载。
     * 相关子查询保证一个会话最多一行（`created_at` 相同用 id 兜底）。
     */
    @Query(
        """
        SELECT r.id AS runId,
               r.conversation_id AS conversationId,
               r.agent_id AS agentId,
               r.task_id AS taskId,
               r.state AS state,
               r.state_revision AS stateRevision,
               r.steps_used AS stepsUsed,
               r.step_limit AS stepLimit,
               r.updated_at AS updatedAt,
               r.started_at AS startedAt,
               r.finished_at AS finishedAt,
               r.reply_message_id AS replyMessageId
        FROM agent_run r
        WHERE r.team_id = :teamId
            AND r.id = (
                SELECT r2.id FROM agent_run r2
                WHERE r2.conversation_id = r.conversation_id
                    AND r2.team_id = :teamId
                ORDER BY r2.created_at DESC, r2.id DESC
                LIMIT 1
            )
        ORDER BY r.updated_at DESC
        """
    )
    fun observeLatestRunPerConversationForTeam(teamId: String): Flow<List<AgentRunTeamSummaryRow>>

    // ---------- 辅助 ----------

    /** 某状态的 Run 数：恢复/调度侧只读统计，不提供按 Run 删除（历史不可抹） */
    @Query("SELECT COUNT(*) FROM agent_run WHERE state = :state")
    suspend fun countRunsByState(state: String): Int
}

/** 团队轻量投影的一行：每会话最近一条 Run，**不含** inputJson / inputRef 等正文负载。 */
data class AgentRunTeamSummaryRow(
    val runId: String,
    val conversationId: String,
    val agentId: String?,
    val taskId: String?,
    val state: String,
    val stateRevision: Long,
    val stepsUsed: Int,
    val stepLimit: Int?,
    val updatedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val replyMessageId: String?,
)
