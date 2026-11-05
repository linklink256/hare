package me.rerere.rikkahub.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.OperationKind
import me.rerere.rikkahub.data.model.OperationState
import me.rerere.rikkahub.data.model.RuntimeOperation
import me.rerere.rikkahub.data.model.RuntimeOperationRules
import me.rerere.rikkahub.data.repository.OperationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * R3a 的纯 JVM 覆盖：受理回执的文本契约、状态真值表、门面对仓库的委派。
 * 不测 Room/SQL（那部分只能 androidTest），也不假装覆盖 ChatService 接线。
 */
class Rr20261105OperationRuntimeTest {

    // ---------------- 回执解析 ----------------

    @Test
    fun `parses accepted receipt with delivery key`() {
        val text = """{"status":"accepted","final":true,"result_via":"receive_events","delivery_key":"waiting:$RUN","tool":"send_message"}"""
        val receipt = acceptedReceiptOf(text)
        assertTrue(receipt != null)
        assertEquals("waiting:$RUN", receipt!!.deliveryKey)
        assertEquals("send_message", receipt.tool)
    }

    @Test
    fun `rejects legacy pending placeholder`() {
        val text = """{"status":"pending","final":false,"handle":"researcher"}"""
        assertNull(acceptedReceiptOf(text))
    }

    @Test
    fun `rejects accepted receipt without delivery key`() {
        val text = """{"status":"accepted","final":true,"result_via":"receive_events"}"""
        assertNull(acceptedReceiptOf(text))
    }

    @Test
    fun `rejects plain text and blank output`() {
        assertNull(acceptedReceiptOf("dispatched to researcher"))
        assertNull(acceptedReceiptOf(null))
        assertNull(acceptedReceiptOf("   "))
    }

    @Test
    fun `receipt is not treated as deferred placeholder by shape`() {
        // 旧回填的判据是 pending + final:false；回执两者都不含
        val text = """{"status":"accepted","final":true,"result_via":"receive_events","delivery_key":"waiting:$RUN"}"""
        assertFalse(text.contains("\"final\": false"))
        assertFalse(text.contains("pending"))
    }

    // ---------------- 状态真值表 ----------------

    @Test
    fun `accepted and running are open, everything else is terminal`() {
        assertTrue(RuntimeOperationRules.isOpen(OperationState.ACCEPTED))
        assertTrue(RuntimeOperationRules.isOpen(OperationState.RUNNING))
        assertFalse(RuntimeOperationRules.isOpen(OperationState.SUCCEEDED))
        assertFalse(RuntimeOperationRules.isOpen(OperationState.FAILED))
        assertFalse(RuntimeOperationRules.isOpen(OperationState.UNKNOWN))
        assertFalse(RuntimeOperationRules.isOpen(OperationState.CANCELLED))
        assertFalse(RuntimeOperationRules.isTerminal(OperationState.ACCEPTED))
        assertTrue(RuntimeOperationRules.isTerminal(OperationState.SUCCEEDED))
        assertTrue(RuntimeOperationRules.isTerminal(OperationState.FAILED))
        assertTrue(RuntimeOperationRules.isTerminal(OperationState.UNKNOWN))
        assertTrue(RuntimeOperationRules.isTerminal(OperationState.CANCELLED))
    }

    @Test
    fun `delivery key is stable and prefixed by member run`() {
        assertEquals("waiting:$RUN", OperationRuntime.deliveryKeyFor(RUN))
        assertEquals("waiting:", OperationRuntime.WAITING_PREFIX)
    }

    // ---------------- 门面委派 ----------------

    @Test
    fun `accept delegates and returns the accepted row`() = runBlocking {
        val store = FakeStore()
        val runtime = OperationRuntime(store)
        val result = runtime.accept(
            OperationAccept(RUN, 2, "call-1", "waiting:$RUN", "receipt")
        )
        assertTrue(result is OperationAcceptResult.Accepted)
        assertEquals("call-1", store.lastAccept?.originCallId)
        assertEquals(2, store.lastAccept?.attemptNo)
        assertEquals("waiting:$RUN", store.lastAccept?.deliveryKey)
    }

    @Test
    fun `settleAccepted uses the row revision and forwards output`() = runBlocking {
        val store = FakeStore()
        val runtime = OperationRuntime(store)
        val row = operation(state = OperationState.ACCEPTED, revision = 7L)
        assertTrue(runtime.settleAccepted(row, "done"))
        assertEquals(row.operationId, store.lastSettleId)
        assertEquals(7L, store.lastSettleRevision)
        assertEquals("done", store.lastSettleOutput)
    }

    @Test
    fun `settleFailed uses the row revision and forwards reason`() = runBlocking {
        val store = FakeStore()
        val runtime = OperationRuntime(store)
        val row = operation(state = OperationState.ACCEPTED, revision = 3L)
        assertTrue(runtime.settleFailed(row, "result_unavailable"))
        assertEquals(3L, store.lastFailRevision)
        assertEquals("result_unavailable", store.lastFailReason)
    }

    @Test
    fun `list and count by delivery go through the store`() = runBlocking {
        val store = FakeStore()
        val runtime = OperationRuntime(store)
        runtime.listAcceptedByDelivery("waiting:$RUN")
        runtime.countByDelivery("waiting:$RUN")
        assertEquals("waiting:$RUN", store.lastDeliveryKey)
    }

    private fun operation(state: OperationState, revision: Long) = RuntimeOperation(
        runId = RUN,
        attemptNo = 1,
        conversationId = CONV,
        originCallId = "call-1",
        operationKey = "op:$RUN:1:call-1",
        kind = OperationKind.TOOL,
        state = state,
        revision = revision,
        createdAt = Instant.EPOCH,
    )

    private class FakeStore : OperationStore {
        var lastAccept: OperationAccept? = null
        var lastSettleId: Uuid? = null
        var lastSettleRevision: Long? = null
        var lastSettleOutput: String? = null
        var lastFailRevision: Long? = null
        var lastFailReason: String? = null
        var lastDeliveryKey: String? = null

        override suspend fun start(spec: OperationStart): OperationStartResult = OperationStartResult.Rejected("unused", null)

        override suspend fun finish(spec: OperationFinish): OperationFinishResult = OperationFinishResult.NotFound

        override suspend fun accept(spec: OperationAccept): OperationAcceptResult {
            lastAccept = spec
            return OperationAcceptResult.Accepted(
                RuntimeOperation(
                    runId = spec.runId, attemptNo = spec.attemptNo, conversationId = CONV,
                    originCallId = spec.originCallId, operationKey = "op", kind = OperationKind.TOOL,
                    state = OperationState.ACCEPTED, createdAt = Instant.EPOCH,
                )
            )
        }

        override suspend fun settleAccepted(operationId: Uuid, expectedRevision: Long, outputText: String?): Boolean {
            lastSettleId = operationId; lastSettleRevision = expectedRevision; lastSettleOutput = outputText
            return true
        }

        override suspend fun settleFailed(operationId: Uuid, expectedRevision: Long, errorText: String): Boolean {
            lastSettleId = operationId; lastFailRevision = expectedRevision; lastFailReason = errorText
            return true
        }

        override suspend fun listAcceptedByDelivery(deliveryKey: String): List<RuntimeOperation> {
            lastDeliveryKey = deliveryKey
            return emptyList()
        }

        override suspend fun countByDelivery(deliveryKey: String): Int {
            lastDeliveryKey = deliveryKey
            return 0
        }

        override suspend fun listForRun(runId: Uuid): List<RuntimeOperation> = emptyList()

        override suspend fun markInterruptedAsUnknown(): Int = 0
    }

    private companion object {
        val RUN: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val CONV: Uuid = Uuid.parse("22222222-2222-2222-2222-222222222222")
    }
}
