package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次执行尝试（Run）的持久状态。
 *
 * 刻意不加外键：会话/团队被删除后 Run 仍作为审计历史保留（与 `usage_record` 同取舍），
 * 级联删除会把「谁在什么时候跑过什么、怎么结束的」一起抹掉。
 *
 * `source_key` 上唯一索引用于派活去重；SQLite 允许多行为 NULL，所以「不去重」的 Run 不受影响。
 * 调用方必须给 sourceKey 加命名空间（如 `team:<id>:task:<id>:attempt:1`）。
 */
@Entity(
    tableName = "agent_run",
    indices = [
        Index(value = ["source_key"], unique = true),
        // 会话内按时间取最近一条：latestForConversation / observeLatestForTeam 的排序键
        Index(value = ["conversation_id", "created_at"]),
        // 精确回复锚点反查：findLatestByReply
        Index(value = ["conversation_id", "reply_message_id"]),
        Index(value = ["team_id", "state"]),
        Index(value = ["state"]),
        Index(value = ["parent_run_id"]),
        Index(value = ["previous_run_id"]),
        Index(value = ["task_id"]),
    ]
)
data class AgentRunEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("team_id")
    val teamId: String?,
    @ColumnInfo("agent_id")
    val agentId: String?,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("task_id")
    val taskId: String?,
    /** 派生父 Run（嵌套/子任务归属） */
    @ColumnInfo("parent_run_id")
    val parentRunId: String?,
    /** 上一次尝试的 Run（重试链）；与 parent_run_id 分开存，避免嵌套图与尝试链混账 */
    @ColumnInfo("previous_run_id")
    val previousRunId: String?,
    @ColumnInfo("source_key")
    val sourceKey: String?,
    @ColumnInfo("attempt_no")
    val attemptNo: Int,
    @ColumnInfo("input_ref")
    val inputRef: String?,
    @ColumnInfo("input_json")
    val inputJson: String?,
    @ColumnInfo("reply_message_id")
    val replyMessageId: String?,
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo("reason")
    val reason: String?,
    @ColumnInfo("state_revision")
    val stateRevision: Long,
    @ColumnInfo("step_limit")
    val stepLimit: Int?,
    @ColumnInfo("steps_used")
    val stepsUsed: Int,
    @ColumnInfo("deadline_at")
    val deadlineAt: Long?,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("started_at")
    val startedAt: Long?,
    @ColumnInfo("finished_at")
    val finishedAt: Long?,
    /** 逻辑代次：只在取消/替代时递增；R2 恒 0（持久列，语义归 R4）。 */
    @ColumnInfo("execution_generation", defaultValue = "0")
    val executionGeneration: Long = 0L,
    /** 预算预留步数；R5 才写。 */
    @ColumnInfo("reserved_steps", defaultValue = "0")
    val reservedSteps: Int = 0,
    @ColumnInfo("cancel_requested_at")
    val cancelRequestedAt: Long? = null,
)

/**
 * Run 的 append-only 事实日志。`(run_id, revision)` 唯一：
 * 每次状态提交只允许一条同版本事件，重复提交会被数据库拒绝，而不是写出两条矛盾记录。
 */
@Entity(
    tableName = "agent_run_event",
    indices = [
        Index(value = ["run_id", "revision"], unique = true),
    ]
)
data class AgentRunEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("run_id")
    val runId: String,
    /** 等于该次提交之后的 `agent_run.state_revision` */
    @ColumnInfo("revision")
    val revision: Long,
    /** CREATED / STATE_CHANGED / REPLY_BOUND */
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("from_state")
    val fromState: String?,
    @ColumnInfo("to_state")
    val toState: String?,
    @ColumnInfo("reason")
    val reason: String?,
    /** 仅 STEP_PROGRESS 事件填写：该次提交后的累计步数 */
    @ColumnInfo("steps_used")
    val stepsUsed: Int?,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** R3 写事件时才填，R2 零写入。 */
    @ColumnInfo("operation_id")
    val operationId: String? = null,
    @ColumnInfo("step_id")
    val stepId: String? = null,
    @ColumnInfo("step_no")
    val stepNo: Int? = null,
)
