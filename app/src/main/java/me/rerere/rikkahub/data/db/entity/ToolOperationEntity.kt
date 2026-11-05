package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工具操作日志（append-only）：同一 run + tool_call_id 只有一行，靠 state 区分
 * 「有结果」与「只剩 INTENT（不知道是否已执行）」。
 */
@Entity(
    tableName = "run_tool_op",
    indices = [
        Index(value = ["run_id", "tool_call_id"], unique = true),
        Index(value = ["run_id"]),
        Index(value = ["conversation_id"]),
        // 一个 operation 至多一个检查点；旧行 operation_id 为 NULL（SQLite 允许多行 NULL）
        Index(value = ["operation_id"], unique = true),
    ]
)
data class ToolOperationEntity(
    @PrimaryKey @ColumnInfo("id") val id: String,
    @ColumnInfo("run_id") val runId: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("tool_call_id") val toolCallId: String,
    @ColumnInfo("tool_name") val toolName: String,
    @ColumnInfo("operation_key") val operationKey: String,
    @ColumnInfo("state") val state: String,
    @ColumnInfo("input_text") val inputText: String?,
    @ColumnInfo("output_text") val outputText: String?,
    @ColumnInfo("error_text") val errorText: String?,
    @ColumnInfo("started_at") val startedAt: Long,
    @ColumnInfo("finished_at") val finishedAt: Long?,
    @ColumnInfo("operation_id") val operationId: String? = null,
)
