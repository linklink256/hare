package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 持久 operation：每个真正执行的工具调用一行，`state/revision/settled_at` 会被原地推进。
 * 与 `run_tool_op` 的分工：这里答「调用被受理/结算到哪一步」，那里只答「副作用检查点」。
 */
@Entity(
    tableName = "runtime_operation",
    indices = [
        Index(value = ["operation_key"], unique = true),
        Index(value = ["run_id", "attempt_no", "origin_call_id"], unique = true),
        Index(value = ["run_id", "state"]),
        Index(value = ["conversation_id", "created_at"]),
        Index(value = ["state"]),
    ]
)
data class RuntimeOperationEntity(
    @PrimaryKey @ColumnInfo("operation_id") val operationId: String,
    @ColumnInfo("run_id") val runId: String,
    @ColumnInfo("attempt_no") val attemptNo: Int,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("origin_call_id") val originCallId: String,
    @ColumnInfo("operation_key") val operationKey: String,
    @ColumnInfo("kind") val kind: String,
    @ColumnInfo("state") val state: String,
    @ColumnInfo("input_text") val inputText: String? = null,
    @ColumnInfo("output_text") val outputText: String? = null,
    @ColumnInfo("error_text") val errorText: String? = null,
    @ColumnInfo("generation") val generation: Long,
    @ColumnInfo("revision") val revision: Long,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("started_at") val startedAt: Long? = null,
    @ColumnInfo("settled_at") val settledAt: Long? = null,
    @ColumnInfo("accepted_at") val acceptedAt: Long? = null,
    @ColumnInfo("delivery_state") val deliveryState: String? = null,
    /** null = 未判定；R2 不写，R4 迟到结算才写。 */
    @ColumnInfo("late") val late: Boolean? = null,
)
