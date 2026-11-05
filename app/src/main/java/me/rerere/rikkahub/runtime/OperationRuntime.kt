package me.rerere.rikkahub.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.model.RuntimeOperation
import me.rerere.rikkahub.data.repository.OperationStore
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** 受理入参：复用 start 已写好的 operation 行，只补投递键与回执摘要。 */
data class OperationAccept(
    val runId: Uuid,
    val attemptNo: Int,
    val originCallId: String,
    val deliveryKey: String,
    val output: String?,
)

sealed interface OperationAcceptResult {
    data class Accepted(val operation: RuntimeOperation) : OperationAcceptResult

    /** 同一 call 重复受理：回显既有 Accepted，不写第二行。 */
    data class AlreadyAccepted(val operation: RuntimeOperation) : OperationAcceptResult

    data object NotFound : OperationAcceptResult

    data class Conflict(val operation: RuntimeOperation?) : OperationAcceptResult

    data class Rejected(val reason: String, val cause: Throwable?) : OperationAcceptResult
}

/** 受理回执里的投递键：结算时按它对上 `waiting:<memberRunId>`。 */
data class AcceptedReceipt(val deliveryKey: String, val tool: String)

/**
 * ACCEPTED 的读写门面：工具只产回执，受理/结算都走 OperationStore 的事务。
 * 不在进程内保存待办状态；重启后按行事实继续（ACCEPTED 就等消费侧结算）。
 */
class OperationRuntime(private val store: OperationStore) {

    suspend fun accept(spec: OperationAccept): OperationAcceptResult = store.accept(spec)

    suspend fun listAcceptedByDelivery(deliveryKey: String): List<RuntimeOperation> =
        store.listAcceptedByDelivery(deliveryKey)

    suspend fun countByDelivery(deliveryKey: String): Int = store.countByDelivery(deliveryKey)

    suspend fun listForRun(runId: Uuid): List<RuntimeOperation> = store.listForRun(runId)

    /** 消费成功后结算；0 行说明别人先结算了，属正常竞争。 */
    suspend fun settleAccepted(operation: RuntimeOperation, outputText: String?): Boolean =
        store.settleAccepted(operation.operationId, operation.revision, outputText)

    /** 成员终态且结果已不可交付时结算 FAILED，保证每个 ACCEPTED 都有出口。 */
    suspend fun settleFailed(operation: RuntimeOperation, errorText: String): Boolean =
        store.settleFailed(operation.operationId, operation.revision, errorText)

    companion object {
        const val WAITING_PREFIX = "waiting:"

        fun deliveryKeyFor(memberRunId: Uuid): String = "$WAITING_PREFIX$memberRunId"
    }
}

/**
 * 识别 send_message 的受理回执：`status=accepted` + `result_via=receive_events` + 非空 delivery_key。
 * 工具拿不到自己的 toolCallId，因此受理判定只能读回执本身（重启安全，不依赖进程内状态）。
 */
fun acceptedReceiptOf(output: String?): AcceptedReceipt? {
    val text = output?.trim().orEmpty()
    if (text.isEmpty()) return null
    val obj = runCatching { JsonInstant.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    fun field(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
    if (field("status") != "accepted") return null
    if (field("result_via") != "receive_events") return null
    val key = field("delivery_key")?.trim().orEmpty()
    if (key.isEmpty()) return null
    return AcceptedReceipt(deliveryKey = key, tool = field("tool").orEmpty())
}
