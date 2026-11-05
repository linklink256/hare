package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 接受凭据：任务只有拿到一行才算「已验收」，下游依赖才可派发。
 * `(task_id, task_revision)` 唯一 —— 同一版结果只能被接受一次，append-only。
 */
@Entity(
    tableName = "task_acceptance",
    indices = [
        Index(value = ["task_id", "task_revision"], unique = true),
        Index(value = ["team_id"]),
        Index(value = ["run_id"]),
    ]
)
data class TaskAcceptanceEntity(
    @PrimaryKey @ColumnInfo("id") val id: String,
    @ColumnInfo("team_id") val teamId: String,
    @ColumnInfo("task_id") val taskId: String,
    /** 接受后任务所在的 revision（= 接受前 revision + 1，也就是 DONE 那一版） */
    @ColumnInfo("task_revision") val taskRevision: Long,
    @ColumnInfo("run_id") val runId: String?,
    @ColumnInfo("result_ref") val resultRef: String,
    @ColumnInfo("result_sha256") val resultSha256: String?,
    @ColumnInfo("evidence_ref") val evidenceRef: String,
    @ColumnInfo("accepted_by") val acceptedBy: String,
    @ColumnInfo("created_at") val createdAt: Long,
)
