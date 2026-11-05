package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ToolOperationEntity

@Dao
interface ToolOperationDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ToolOperationEntity)

    @Query("SELECT * FROM run_tool_op WHERE run_id = :runId ORDER BY started_at ASC, id ASC")
    suspend fun listForRun(runId: String): List<ToolOperationEntity>

    @Query("SELECT * FROM run_tool_op WHERE run_id = :runId AND tool_call_id = :toolCallId LIMIT 1")
    suspend fun get(runId: String, toolCallId: String): ToolOperationEntity?

    /** 只在还是 INTENT 时收尾（幂等：重复 finish 不会再改） */
    @Query(
        """
        UPDATE run_tool_op
        SET state = :state, output_text = :outputText, error_text = :errorText, finished_at = :finishedAt
        WHERE run_id = :runId AND tool_call_id = :toolCallId AND state = 'INTENT'
        """
    )
    suspend fun finishCas(
        runId: String,
        toolCallId: String,
        state: String,
        outputText: String?,
        errorText: String?,
        finishedAt: Long,
    ): Int

    /** 启动恢复：所有残留 INTENT 一律标 UNCERTAIN，返回受影响行数 */
    @Query("UPDATE run_tool_op SET state = 'UNCERTAIN', finished_at = :finishedAt WHERE state = 'INTENT'")
    suspend fun markAllIntentUncertain(finishedAt: Long): Int

    @Query("SELECT COUNT(*) FROM run_tool_op WHERE run_id = :runId AND state IN ('INTENT','UNCERTAIN')")
    suspend fun countUnresolved(runId: String): Int
}
