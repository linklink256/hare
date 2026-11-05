package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.RuntimeOperationEntity

@Dao
interface RuntimeOperationDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: RuntimeOperationEntity)

    @Query(
        "SELECT * FROM runtime_operation WHERE run_id = :runId AND attempt_no = :attemptNo AND origin_call_id = :originCallId LIMIT 1"
    )
    suspend fun getByKey(runId: String, attemptNo: Int, originCallId: String): RuntimeOperationEntity?

    @Query("SELECT * FROM runtime_operation WHERE operation_key = :operationKey LIMIT 1")
    suspend fun getByOperationKey(operationKey: String): RuntimeOperationEntity?

    /** 只在仍是 RUNNING 且版本未变时结算；返回 0 表示冲突（不覆盖别人已写的结果）。 */
    @Query(
        """
        UPDATE runtime_operation
        SET state = :state, output_text = :outputText, error_text = :errorText,
            settled_at = :settledAt, revision = revision + 1
        WHERE operation_id = :operationId AND state = 'RUNNING' AND revision = :expectedRevision
        """
    )
    suspend fun settleCas(
        operationId: String,
        expectedRevision: Long,
        state: String,
        outputText: String?,
        errorText: String?,
        settledAt: Long,
    ): Int

    /** 受理：RUNNING→ACCEPTED 并绑定投递键；CAS 失败返回 0。 */
    @Query(
        """
        UPDATE runtime_operation
        SET state = 'ACCEPTED', accepted_at = :acceptedAt, delivery_state = :deliveryKey,
            output_text = :outputText, revision = revision + 1
        WHERE operation_id = :operationId AND state = 'RUNNING' AND revision = :expectedRevision
        """
    )
    suspend fun acceptCas(
        operationId: String,
        expectedRevision: Long,
        acceptedAt: Long,
        deliveryKey: String,
        outputText: String?,
    ): Int

    /** 消费成功后结算：只在 ACCEPTED 且版本未变时写 SUCCEEDED。 */
    @Query(
        """
        UPDATE runtime_operation
        SET state = 'SUCCEEDED', output_text = :outputText, settled_at = :settledAt, revision = revision + 1
        WHERE operation_id = :operationId AND state = 'ACCEPTED' AND revision = :expectedRevision
        """
    )
    suspend fun settleAcceptedCas(
        operationId: String,
        expectedRevision: Long,
        outputText: String?,
        settledAt: Long,
    ): Int

    /** 受理后确认不会再有结果时结算 FAILED 并记原因。 */
    @Query(
        """
        UPDATE runtime_operation
        SET state = 'FAILED', error_text = :errorText, settled_at = :settledAt, revision = revision + 1
        WHERE operation_id = :operationId AND state = 'ACCEPTED' AND revision = :expectedRevision
        """
    )
    suspend fun settleAcceptedFailedCas(
        operationId: String,
        expectedRevision: Long,
        errorText: String,
        settledAt: Long,
    ): Int

    /** 投递键上的行数（任意状态）：只给 purge 豁免判断用。 */
    @Query("SELECT COUNT(*) FROM runtime_operation WHERE delivery_state = :deliveryKey")
    suspend fun countByDelivery(deliveryKey: String): Int

    /** 投递键上仍为 ACCEPTED 的行（含 revision）：结算的唯一取行入口。 */
    @Query(
        "SELECT * FROM runtime_operation WHERE delivery_state = :deliveryKey AND state = 'ACCEPTED' ORDER BY created_at ASC, operation_id ASC"
    )
    suspend fun listAcceptedByDelivery(deliveryKey: String): List<RuntimeOperationEntity>

    /** 启动恢复：跨进程残留的 RUNNING 降级为 UNKNOWN；ACCEPTED 属于「等消费」，不得降级。 */
    @Query(
        "UPDATE runtime_operation SET state = 'UNKNOWN', settled_at = :at, revision = revision + 1 WHERE state = 'RUNNING'"
    )
    suspend fun markInterruptedAsUnknown(at: Long): Int

    @Query("SELECT * FROM runtime_operation WHERE run_id = :runId ORDER BY created_at ASC, operation_id ASC")
    suspend fun listForRun(runId: String): List<RuntimeOperationEntity>
}
