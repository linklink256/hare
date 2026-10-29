package me.rerere.rikkahub.service

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 完成瞬间的审批标量快照（TeamExecutionGate.approvalSampler + finishedApprovalSnapshots）。
 *
 * 只证明一件事：会话被空闲回收后，只要上一轮是真结束，分组需要的「明确无审批」就不会丢；
 * 而采样不到（null）永远不能被当成 false。用假时钟跑真实 gate，不建会话、不订阅 UI。
 */
class TeamExecutionGateCompletionSnapshotTest {

    // ---- 采样值的三种结果 ----

    @Test
    fun `denied approval at finish is recorded as false`() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        val id = Uuid.random()
        gate.approvalSampler = { if (it == id) false else null }

        gate.withPermit(Uuid.random(), { 1 }, id) { now = 2_000_000_000L }

        assertEquals(false, gate.runTimings.value[id]?.awaitingApprovalAtFinish)
        assertEquals(mapOf(id to false), gate.finishedApprovalSnapshots.first())
    }

    @Test
    fun `pending approval at finish is recorded as true`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val id = Uuid.random()
        gate.approvalSampler = { true }

        gate.withPermit(Uuid.random(), { 1 }, id) { }

        assertEquals(true, gate.runTimings.value[id]?.awaitingApprovalAtFinish)
        assertEquals(mapOf(id to true), gate.finishedApprovalSnapshots.first())
    }

    @Test
    fun `unknown approval at finish stays null and never becomes false`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val id = Uuid.random()
        // 会话已被回收：采样只能返回未知
        gate.approvalSampler = { null }

        gate.withPermit(Uuid.random(), { 1 }, id) { }

        assertNull(gate.runTimings.value[id]?.awaitingApprovalAtFinish)
        assertFalse(gate.runTimings.value[id]!!.running)
        // 未知不进快照，下游拿不到值即继续按「未知」处理，不会被写成 false
        assertFalse(gate.finishedApprovalSnapshots.first().containsKey(id))
    }

    @Test
    fun `no sampler registered keeps unknown instead of assuming false`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val id = Uuid.random()

        gate.withPermit(Uuid.random(), { 1 }, id) { }

        assertNull(gate.approvalSampler)
        assertNull(gate.runTimings.value[id]?.awaitingApprovalAtFinish)
        assertTrue(gate.finishedApprovalSnapshots.first().isEmpty())
    }

    // ---- 无观察者也要记录 ----

    @Test
    fun `snapshot is recorded with no UI or flow collector`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val id = Uuid.random()
        gate.approvalSampler = { false }

        // 全程不订阅 runTimings / finishedApprovalSnapshots / admittedConversations
        gate.withPermit(Uuid.random(), { 1 }, id) { }

        assertEquals(false, gate.runTimings.value[id]?.awaitingApprovalAtFinish)
        // 事后才读，值仍在（说明记录发生在结束时，而不是靠 UI 驱动）
        assertEquals(false, gate.finishedApprovalSnapshots.first()[id])
    }

    // ---- 回收后回退 ----

    @Test
    fun `finished false survives after live peek is gone`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val id = Uuid.random()
        gate.approvalSampler = { false }
        gate.withPermit(Uuid.random(), { 1 }, id) { }

        // 会话回收后 peekAwaitingApproval 只能回 null（这里直接代表消费端拿到的 live 值）
        val livePeek: Boolean? = null
        val fallback = livePeek ?: gate.finishedApprovalSnapshots.first()[id]

        assertEquals(false, fallback)
    }

    @Test
    fun `finished true survives after live peek is gone`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val id = Uuid.random()
        gate.approvalSampler = { true }
        gate.withPermit(Uuid.random(), { 1 }, id) { }

        val livePeek: Boolean? = null
        assertEquals(true, livePeek ?: gate.finishedApprovalSnapshots.first()[id])
    }

    @Test
    fun `live peek still wins over the stored snapshot`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val id = Uuid.random()
        gate.approvalSampler = { false }
        gate.withPermit(Uuid.random(), { 1 }, id) { }

        // 会话还在缓存里、用户新挂了审批：实时值优先于上一轮结束时的旧标量
        val livePeek: Boolean? = true
        assertEquals(true, livePeek ?: gate.finishedApprovalSnapshots.first()[id])
    }

    // ---- 重派新轮 ----

    @Test
    fun `redispatch does not reuse the previous completion marker`() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        val team = Uuid.random()
        val id = Uuid.random()
        gate.approvalSampler = { false }
        gate.withPermit(team, { 1 }, id) { now = 1_000_000_000L }
        assertEquals(mapOf(id to false), gate.finishedApprovalSnapshots.first())

        gate.approvalSampler = { true }
        val release = launch { gate.withPermit(team, { 1 }, id) { awaitCancellation() } }
        runCurrent()

        // 新一轮在跑：这不是完成记录，旧标记必须消失，否则会被当成「已结束」
        assertTrue(gate.runTimings.value[id]!!.running)
        assertTrue(gate.finishedApprovalSnapshots.first().isEmpty())
        assertTrue(gate.isAdmitted(id))

        release.cancelAndJoin()
        assertEquals(true, gate.finishedApprovalSnapshots.first()[id])
    }

    @Test
    fun `queued redispatch is running-state owned and not finished`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val team = Uuid.random()
        val blocker = Uuid.random()
        val id = Uuid.random()
        gate.approvalSampler = { false }
        gate.withPermit(team, { 1 }, blocker) { }

        val holder = launch { gate.withPermit(team, { 1 }, blocker) { awaitCancellation() } }
        runCurrent()
        val queued = launch { gate.withPermit(team, { 1 }, id) { awaitCancellation() } }
        runCurrent()

        // 还没拿到 permit：不写完成标记，也不进快照
        assertFalse(gate.runTimings.value.containsKey(id))
        assertFalse(gate.finishedApprovalSnapshots.first().containsKey(id))
        assertFalse(gate.isAdmitted(id))

        queued.cancelAndJoin()
        holder.cancelAndJoin()
        // 等待名额时取消同样不写一个假完成
        assertFalse(gate.runTimings.value.containsKey(id))
    }

    @Test
    fun `cancel before permit keeps the previous round marker untouched`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val team = Uuid.random()
        val holderId = Uuid.random()
        val waiterId = Uuid.random()
        gate.approvalSampler = { false }
        gate.withPermit(team, { 1 }, waiterId) { }
        assertEquals(mapOf(waiterId to false), gate.finishedApprovalSnapshots.first())

        val holder = launch { gate.withPermit(team, { 1 }, holderId) { awaitCancellation() } }
        runCurrent()
        val waiter = launch { gate.withPermit(team, { 1 }, waiterId) { fail("must not be admitted") } }
        runCurrent()
        waiter.cancelAndJoin()

        // 排队中被取消：上一轮的 false 仍是该成员唯一结论（holder 还在跑，不在快照里）
        assertEquals(mapOf(waiterId to false), gate.finishedApprovalSnapshots.first())

        holder.cancelAndJoin()
        assertEquals(false, gate.finishedApprovalSnapshots.first()[waiterId])
    }

    // ---- 64 条完成缓存边界 ----

    @Test
    fun `finished cache stays bounded with snapshots and never evicts a live run`() = runTest {
        val gate = TeamExecutionGate { 0L }
        val team = Uuid.random()
        val live = Uuid.random()
        gate.approvalSampler = { false }

        val job = launch { gate.withPermit(team, { 2 }, live) { awaitCancellation() } }
        runCurrent()
        val completed = List(80) { Uuid.random() }
        completed.forEach { gate.withPermit(team, { 2 }, it) { } }

        assertEquals(65, gate.runTimings.value.size)
        assertTrue(gate.runTimings.value[live]!!.running)
        // 运行中的那条不在快照里
        val snapshots = gate.finishedApprovalSnapshots.first()
        assertFalse(snapshots.containsKey(live))
        assertEquals(64, snapshots.size)
        assertTrue(snapshots.containsKey(completed.last()))

        job.cancelAndJoin()
        assertEquals(64, gate.runTimings.value.size)
        assertEquals(64, gate.finishedApprovalSnapshots.first().size)
        // 被裁掉的是最早的完成条目，活跃轮从未被裁
        assertTrue(gate.runTimings.value.containsKey(live))
    }

    // ---- 采样失败不得影响调度 ----

    @Test
    fun `throwing sampler cannot mask the block failure`() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        val team = Uuid.random()
        val id = Uuid.random()
        gate.approvalSampler = { error("peek blew up") }

        var saved: Long? = null
        try {
            gate.withPermit(team, { 1 }, id, onFinished = { saved = it }) {
                now = 3_000_000_000L
                error("model failed")
            }
            fail("block failure must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("model failed", e.message)
        }

        assertEquals(3000L, saved)
        val timing = gate.runTimings.value[id]!!
        assertFalse(timing.running)
        assertEquals(3000L, timing.elapsedMillisAt(99_000_000_000L))
        assertNull(timing.awaitingApprovalAtFinish)
        assertFalse(gate.finishedApprovalSnapshots.first().containsKey(id))
    }

    @Test
    fun `throwing sampler still releases the permit and records the run`() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        val team = Uuid.random()
        val id = Uuid.random()
        gate.approvalSampler = { throw IllegalStateException("peek blew up") }

        gate.withPermit(team, { 1 }, id) { now = 1_500_000_000L }

        assertEquals(1500L, gate.runTimings.value[id]?.elapsedMillisAt(9_000_000_000L))
        assertFalse(gate.isAdmitted(id))
        assertTrue(gate.admittedConversations.value.isEmpty())
        // 名额已释放：同队同并发上限下可以立刻再派一轮
        gate.approvalSampler = { false }
        gate.withPermit(team, { 1 }, id) { }
        assertEquals(mapOf(id to false), gate.finishedApprovalSnapshots.first())
    }
}
