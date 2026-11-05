package me.rerere.rikkahub.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunEvent
import me.rerere.rikkahub.data.model.AgentRunState
import me.rerere.rikkahub.data.model.NewMailboxEntry
import me.rerere.rikkahub.data.repository.AgentRunStore
import me.rerere.rikkahub.data.repository.AgentRunTeamSummary
import me.rerere.rikkahub.data.repository.BindReplyResult
import me.rerere.rikkahub.data.repository.CreateRunResult
import me.rerere.rikkahub.data.repository.NewAgentRun
import me.rerere.rikkahub.data.repository.RecoveryReport
import me.rerere.rikkahub.data.repository.RecordStepsResult
import me.rerere.rikkahub.data.repository.RunTransitionAndEnqueueResult
import me.rerere.rikkahub.data.repository.RunTransitionResult
import me.rerere.rikkahub.service.TeamExecutionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * R1 `DefaultRunCoordinator` 单会话执行串行边界的纯 JVM 测试（Rr20261105 独有用例）。
 *
 * 范围：只测协调层（fake `AgentRunStore` + 真实纯 Kotlin `TeamExecutionGate`）。
 * **未覆盖**（不声称已测）：ChatService 的真实接线与 `awaitRunRecovery()` 屏障位置、
 * 拒绝分支下「不写转录/不 bindReply/不 recordSteps/不改 pause」的调用方行为、
 * 跨进程 fencing、Room/DB、真机 UAT。
 *
 * 期望值全部手写字面量，不从产品 allowed 集合生成；fake store 只镜像既有 store 的
 * CAS 契约（缺失 → NotFound、版本不符 → Conflict、非「CAS 到 RUNNING」→ IllegalTransition）。
 * 本文件写而不执行（executed:false），编译/运行由后续流程负责。
 */
class Rr20261105RunCoordinatorTest {

    // ---- 1. 同一会话两个不同 runId：串行；先到先执行 ----
    @Test
    fun sameConversationSerializesDifferentRuns() = runBlocking {
        val conv = Uuid.random()
        val probeConv = Uuid.random()
        val first = run(conv)
        val second = run(conv)
        val store = FakeAgentRunStore(first, second)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 4)
        val order = java.util.Collections.synchronizedList(mutableListOf<Uuid>())
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val firstIn = CompletableDeferred<Unit>()
        val probeIn = CompletableDeferred<Unit>()
        val secondIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val a = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, first.runId)) {
                order += first.runId
                peak.set(maxOf(peak.get(), inFlight.incrementAndGet()))
                firstIn.complete(Unit)
                releaseFirst.await()
                inFlight.decrementAndGet()
            }
        }
        withTimeout(TIMEOUT_MS) { firstIn.await() }

        // 排除「真实 gate 才是假串行原因」：首个持会话 lease/名额期间，另一会话的 MEMBER（Untracked，无 CAS）
        // 用 UNDISPATCHED 同步跑到首个挂起点；它能立刻拿到名额，说明 gate 仍有余量、不是串行来源。
        val probe = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runAdmitted(member(probeConv, null)) { probeIn.complete(Unit) }
        }
        assertTrue("gate 必须仍有余量，串行不能来自名额不足", probeIn.isCompleted)
        probe.join()

        // 同会话不同 runId：UNDISPATCHED 启动已确定跑到第一个真实挂起点（会话 lease 竞争），
        // 所以「此刻未进入执行」是确定性事实，而不是「250ms 内没被调度」。
        val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runAdmitted(member(conv, second.runId)) {
                order += second.runId
                peak.set(maxOf(peak.get(), inFlight.incrementAndGet()))
                secondIn.complete(Unit)
                inFlight.decrementAndGet()
            }
        }
        assertFalse("同会话第二个 run 不得与首个重叠执行", secondIn.isCompleted)

        releaseFirst.complete(Unit)
        withTimeout(TIMEOUT_MS) { secondIn.await() }
        a.join()
        b.join()
        assertEquals(listOf(first.runId, second.runId), order)
        assertEquals("同会话同一时刻至多一个执行体", 1, peak.get())
        // 两个不同 runId 各一次 QUEUED→RUNNING CAS（probe 是 Untracked，无 CAS）。
        assertEquals(2, store.appliedTransitions)
        assertTrue(gate.admittedConversations.value.isEmpty())
    }

    // ---- 2. 跨会话：并行（lease 键是 conversationId，互不阻塞） ----
    @Test
    fun crossConversationRunsInParallel() = runBlocking {
        val convA = Uuid.random()
        val convB = Uuid.random()
        val runA = run(convA)
        val runB = run(convB)
        val store = FakeAgentRunStore(runA, runB)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 2)
        val inFlight = AtomicInteger(0)
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val jobs = listOf(convA to runA.runId, convB to runB.runId).map { (conv, runId) ->
            launch(Dispatchers.Default) {
                coordinator.runAdmitted(member(conv, runId)) { admission ->
                    assertTrue("跨会话应准入成功", admission is Admission.Admitted)
                    if (inFlight.incrementAndGet() == 2) bothEntered.complete(Unit)
                    release.await()
                }
            }
        }
        // 期望：两个会话的执行同时在飞，谁都没等谁。
        withTimeout(TIMEOUT_MS) { bothEntered.await() }
        assertEquals(setOf(convA, convB), gate.admittedConversations.value)
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertTrue(gate.admittedConversations.value.isEmpty())
    }

    // ---- 3. LEAD：不取成员 gate/名额、不调 onFinished ----
    @Test
    fun leadDoesNotTakeGateSlotAndSkipsOnFinished() = runBlocking {
        val leadConv = Uuid.random()
        val otherConv = Uuid.random()
        val leadRun = run(leadConv)
        val store = FakeAgentRunStore(leadRun)
        val gate = TeamExecutionGate()
        // 名额只有 1：LEAD 若占名额，后面的 MEMBER 就进不来。
        val coordinator = coordinator(store, gate, limit = 1)
        val leadIn = CompletableDeferred<Unit>()
        val releaseLead = CompletableDeferred<Unit>()
        val onFinishedCalls = AtomicInteger(0)

        val lead = launch(Dispatchers.Default) {
            coordinator.runAdmitted(
                AdmitRequest(leadRun.runId, leadConv, null, AdmitKind.LEAD),
                { onFinishedCalls.incrementAndGet() },
            ) { admission ->
                assertTrue(admission is Admission.Admitted)
                assertNotNull((admission as Admission.Admitted).fence)
                // 期望：LEAD 不进成员 gate 的 admittedConversations。
                assertFalse(gate.isAdmitted(leadConv))
                leadIn.complete(Unit)
                releaseLead.await()
            }
        }
        withTimeout(TIMEOUT_MS) { leadIn.await() }
        assertFalse("LEAD 期间该会话不得出现在成员 gate 中", gate.admittedConversations.value.contains(leadConv))

        // 期望：唯一名额仍可用给 MEMBER（runId=null，无 CAS）。
        val memberIn = CompletableDeferred<Unit>()
        val member = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(otherConv, null)) { memberIn.complete(Unit) }
        }
        withTimeout(TIMEOUT_MS) { memberIn.await() }
        releaseLead.complete(Unit)
        lead.join()
        member.join()
        assertEquals("LEAD 不调 onFinished", 0, onFinishedCalls.get())
        assertEquals("LEAD 仍做一次 CAS RUNNING", 1, store.appliedTransitions)
    }

    // ---- 4. MEMBER 且 runId=null：lease+gate，返回 Untracked，无 CAS ----
    @Test
    fun memberWithNullRunIdTakesLeaseAndGateWithoutCas() = runBlocking {
        val convA = Uuid.random()
        val convB = Uuid.random()
        val store = FakeAgentRunStore()
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondIn = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(convA, null)) { admission ->
                // 期望：无 Run 行 → Untracked，带 conversationId，不返回 fence。
                assertTrue("member+null 应为 Untracked", admission is Admission.Untracked)
                assertEquals(convA, (admission as Admission.Untracked).conversationId)
                // 期望：仍占 gate 名额。
                assertTrue(gate.isAdmitted(convA))
                firstIn.complete(Unit)
                releaseFirst.await()
            }
        }
        withTimeout(TIMEOUT_MS) { firstIn.await() }

        val second = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runAdmitted(member(convB, null)) { secondIn.complete(Unit) }
        }
        // 期望：limit=1 时第二个 MEMBER 已同步跑到 gate 等待点（不是「尚未被调度」），不得越过名额。
        assertFalse("第二个 member 已发起但不得越过 gate", secondIn.isCompleted)
        releaseFirst.complete(Unit)
        withTimeout(TIMEOUT_MS) { secondIn.await() }
        first.join()
        second.join()

        // 期望：Untracked 路径不写 DB、不建 Run。
        assertEquals(0, store.appliedTransitions)
        assertEquals(0, store.transitionAttempts)
        assertEquals(0, store.createCalls)
        assertTrue(gate.admittedConversations.value.isEmpty())
    }

    // ---- 5. 显式 runId 缺失 → NOT_FOUND，交给 block 判失败，不写 DB ----
    @Test
    fun explicitRunMissingIsRejectedNotFound() = runBlocking {
        val conv = Uuid.random()
        val store = FakeAgentRunStore()
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        var seen: Admission? = null

        // 泛型签名要求 runAdmitted 必须把 Admission 交给 block 才能产出 T。
        val outcome = coordinator.runAdmitted(member(conv, Uuid.random())) { admission ->
            seen = admission
            "rejected-path"
        }
        assertEquals("rejected-path", outcome)
        val rejected = seen as? Admission.Rejected
            ?: throw AssertionError("缺失行必须把 Rejected 交给 block，实际 $seen")
        assertEquals(AdmissionRejection.NOT_FOUND, rejected.reason)
        assertNull("缺失行没有 current 快照", rejected.current)
        assertEquals(0, store.appliedTransitions)
        assertEquals(0, store.createCalls)
        assertTrue(gate.admittedConversations.value.isEmpty())
    }

    // ---- 6. 终态 Run → TERMINAL，不重开 ----
    @Test
    fun terminalRunIsRejectedTerminal() = runBlocking {
        val conv = Uuid.random()
        val done = run(conv, state = AgentRunState.COMPLETED)
        val store = FakeAgentRunStore(done)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        var seen: Admission? = null

        coordinator.runAdmitted(member(conv, done.runId)) { seen = it }
        val rejected = seen as? Admission.Rejected
            ?: throw AssertionError("终态必须把 Rejected 交给 block，实际 $seen")
        // 期望：COMPLETED 无出边 → TERMINAL。current 是 AgentRun?（契约未要求必带快照）：
        // 只有实现给了快照才核身份，不强求 non-null。
        assertEquals(AdmissionRejection.TERMINAL, rejected.reason)
        rejected.current?.let { snapshot ->
            assertEquals(done.runId, snapshot.runId)
            assertEquals(AgentRunState.COMPLETED, snapshot.state)
        }
        assertEquals(0, store.appliedTransitions)
    }

    // ---- 7. CAS 冲突 → CONFLICT，重试有界 ----
    @Test
    fun revisionConflictIsRejectedConflictWithBoundedRetries() = runBlocking {
        val conv = Uuid.random()
        val stale = run(conv)
        val store = FakeAgentRunStore(stale)
        // 该 run 的每次 CAS 都撞版本冲突（模拟「别人先改了」的持续状态）。
        store.conflictRunId = stale.runId
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        var seen: Admission? = null

        coordinator.runAdmitted(member(conv, stale.runId)) { seen = it }
        val rejected = seen as? Admission.Rejected
            ?: throw AssertionError("冲突必须把 Rejected 交给 block，实际 $seen")
        // 期望：冲突 fail-closed 为 CONFLICT，不做幂等放行；快照可空，有则核身份。
        assertEquals(AdmissionRejection.CONFLICT, rejected.reason)
        rejected.current?.let { snapshot -> assertEquals(stale.runId, snapshot.runId) }
        assertEquals(0, store.appliedTransitions)
        // R1 沿用既有 CAS 重试次数 3：手写期望，依据是冻结前像 ChatService.transitionRun 的 repeat(3)
        // （before/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:398），不读当前 WIP 常量。
        assertEquals("CAS 重试次数应与冻结前像一致", 3, store.transitionAttempts)
    }

    // ---- 8. 同一 runId 已 RUNNING → ILLEGAL_TRANSITION，不等待、不幂等重跑 ----
    @Test
    fun alreadyRunningIsRejectedIllegalTransition() = runBlocking {
        val conv = Uuid.random()
        val running = run(conv, state = AgentRunState.RUNNING)
        val store = FakeAgentRunStore(running)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        var seen: Admission? = null

        val blockCalls = AtomicInteger(0)
        // 若实现等待放行/幂等重跑，这个调用会挂住：用 withTimeout 收口，避免无界挂起。
        withTimeout(TIMEOUT_MS) {
            coordinator.runAdmitted(member(conv, running.runId)) { admission ->
                blockCalls.incrementAndGet()
                seen = admission
            }
        }
        val rejected = seen as? Admission.Rejected
            ?: throw AssertionError("RUNNING 必须把 Rejected 交给 block，实际 $seen")
        assertEquals(AdmissionRejection.ILLEGAL_TRANSITION, rejected.reason)
        // current 可空（契约不保证带快照）：有则核状态，且必须只有一次结论、无重跑。
        rejected.current?.let { snapshot -> assertEquals(AgentRunState.RUNNING, snapshot.state) }
        assertEquals("只应有一次结论，不得重跑", 1, blockCalls.get())
        assertEquals(0, store.appliedTransitions)
    }

    // ---- 9. 等待中被取消：不进入执行，且不留下卡死的等待者 ----
    @Test
    fun cancelWhileWaitingNeverEntersBlock() = runBlocking {
        val conv = Uuid.random()
        val holder = run(conv)
        val waiter = run(conv)
        val later = run(conv)
        val store = FakeAgentRunStore(holder, waiter, later)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 4)
        val holderIn = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val waiterOutcome = CompletableDeferred<Admission>()

        val first = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, holder.runId)) { holderIn.complete(Unit); releaseHolder.await() }
        }
        withTimeout(TIMEOUT_MS) { holderIn.await() }
        val second = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runAdmitted(member(conv, waiter.runId)) { admission -> waiterOutcome.complete(admission) }
        }
        // 期望：UNDISPATCHED 已把等待者同步跑到会话 lease 的挂起点（不是未调度），它尚未拿到准入结论。
        assertFalse("等待者不得在首个释放前拿到准入结论", waiterOutcome.isCompleted)
        second.cancelAndJoin()
        assertFalse("被取消的等待者不得拿到准入结论", waiterOutcome.isCompleted)

        releaseHolder.complete(Unit)
        first.join()
        // 期望：取消的等待者不占住会话锁，后续 run 仍能进入。
        val laterIn = CompletableDeferred<Unit>()
        val third = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, later.runId)) { laterIn.complete(Unit) }
        }
        withTimeout(TIMEOUT_MS) { laterIn.await() }
        third.join()
        assertEquals("holder + later 各一次 CAS，被取消的等待者无 CAS", 2, store.appliedTransitions)
    }

    // ---- 10. 执行中被取消：finally 释放 lease 与名额 ----
    @Test
    fun cancelInsideBlockReleasesLeaseAndPermit() = runBlocking {
        val conv = Uuid.random()
        val otherConv = Uuid.random()
        val running = run(conv)
        val resumed = run(conv)
        val store = FakeAgentRunStore(running, resumed)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        val entered = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, running.runId)) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        job.cancelAndJoin()

        // 期望 1：名额已归还（limit=1，另一会话的 MEMBER 可进）。
        val otherIn = CompletableDeferred<Unit>()
        val other = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(otherConv, null)) { otherIn.complete(Unit) }
        }
        withTimeout(TIMEOUT_MS) { otherIn.await() }
        other.join()

        // 期望 2：会话 lease 已释放（同会话新 run 可立即准入）。
        val againIn = CompletableDeferred<Unit>()
        val again = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, resumed.runId)) { againIn.complete(Unit) }
        }
        withTimeout(TIMEOUT_MS) { againIn.await() }
        again.join()
        assertTrue(gate.admittedConversations.value.isEmpty())
    }

    // ---- 11. fence：generation 恒 0、仅内存；活跃为 current，结束后 false ----
    @Test
    fun fenceGenerationZeroAndLifetime() = runBlocking {
        val conv = Uuid.random()
        // PAUSED → RUNNING 是正常续跑路径（不是新 attempt / 取消替代）。
        val paused = run(conv, state = AgentRunState.PAUSED)
        val store = FakeAgentRunStore(paused)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fence: FenceToken? = null

        val job = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, paused.runId)) { admission ->
                val admitted = admission as Admission.Admitted
                fence = admitted.fence
                // 期望：R1 generation 恒 0。
                assertEquals(0L, admitted.fence.generation)
                assertTrue("活跃 lease 是 current", coordinator.isCurrent(admitted.fence))
                // 期望：stateRevision 只作 CAS，不参与 isCurrent 的代次判定。
                store.bumpRevision(paused.runId)
                assertTrue("stateRevision 变化不影响 isCurrent", coordinator.isCurrent(admitted.fence))
                entered.complete(Unit)
                release.await()
            }
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        assertTrue(coordinator.isCurrent(fence!!))
        release.complete(Unit)
        job.join()

        // 期望：结束后同一 fence 不再 current；未知 lease、错 generation 一律 false（fail-closed）。
        assertFalse("已结束 lease 不得 current", coordinator.isCurrent(fence!!))
        assertFalse(coordinator.isCurrent(FenceToken(paused.runId, 0L, Uuid.random())))
        assertFalse(coordinator.isCurrent(FenceToken(Uuid.random(), 0L, Uuid.random())))
        assertFalse("generation 不匹配必须 false", coordinator.isCurrent(FenceToken(paused.runId, 1L, fence!!.leaseId)))
        assertEquals(0L, fence!!.generation)
    }

    // ---- 12. 旧 fence 不指向后来者的新 lease ----
    @Test
    fun staleFenceDoesNotInvalidateNewLease() = runBlocking {
        val conv = Uuid.random()
        val first = run(conv)
        val second = run(conv)
        val store = FakeAgentRunStore(first, second)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 4)
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondIn = CompletableDeferred<Unit>()
        var firstFence: FenceToken? = null

        val a = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, first.runId)) { admission ->
                firstFence = (admission as Admission.Admitted).fence
                firstIn.complete(Unit)
                releaseFirst.await()
            }
        }
        withTimeout(TIMEOUT_MS) { firstIn.await() }
        // 后来者在旧 lease 仍活跃时排队。
        val b = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, second.runId)) { admission ->
                val newFence = (admission as Admission.Admitted).fence
                // 期望：新 lease 生效；旧 fence 不因「同会话」而被当成 current。
                assertTrue(coordinator.isCurrent(newFence))
                assertFalse("旧 fence 不得指向新 lease", coordinator.isCurrent(firstFence!!))
                assertNotEquals(firstFence!!.leaseId, newFence.leaseId)
                secondIn.complete(Unit)
            }
        }
        releaseFirst.complete(Unit)
        withTimeout(TIMEOUT_MS) { secondIn.await() }
        a.join()
        b.join()
        assertFalse("旧 lease 结束后其 fence 失效", coordinator.isCurrent(firstFence!!))
        assertTrue(gate.admittedConversations.value.isEmpty())
    }

    // ---- 13. 同一 runId 仍在 RUNNING 且首个持 lease：再次调用必须直接拒绝，不得排队等首个让位 ----
    @Test
    fun sameRunWhileRunningIsRejectedInsteadOfQueueing() = runBlocking {
        val conv = Uuid.random()
        val target = run(conv)
        val store = FakeAgentRunStore(target)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 4)
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondOutcome = CompletableDeferred<Admission>()
        val secondAdmissions =
            java.util.Collections.synchronizedList(mutableListOf<Admission>())

        val first = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, target.runId)) {
                firstIn.complete(Unit)
                releaseFirst.await()
            }
        }
        // 首个已 CAS 成 RUNNING、持会话 lease、占名额。
        withTimeout(TIMEOUT_MS) { firstIn.await() }

        val second = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, target.runId)) { admission ->
                secondAdmissions += admission
                secondOutcome.complete(admission)
            }
        }
        // 期望：与「不同 runId 同会话串行」不同，同 runId 不得排队等首个结束/WAITING，
        // 必须在首个仍持 lease 时就给出拒绝结论。
        val early = withTimeoutOrNull(DECIDE_MS) { secondOutcome.await() }
        assertNotNull("同 runId 必须立即判拒绝，不能排队到首个结束之后", early)
        val rejected = early as? Admission.Rejected
            ?: throw AssertionError("同 runId 应 Rejected，实际 $early")
        assertEquals(AdmissionRejection.ILLEGAL_TRANSITION, rejected.reason)
        // current 可空；有快照才核状态（此时库里就是 RUNNING）。
        rejected.current?.let { snapshot -> assertEquals(AgentRunState.RUNNING, snapshot.state) }

        // 首个照原计划结束；被拒的第二次调用不得借此执行。
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        // 事后断言（确定性）：整个调用只产生一条被拒结论，从未出现 Admitted。
        assertTrue("被拒的调用不得在首个结束后被重放为执行", secondAdmissions.none { it is Admission.Admitted })
        assertEquals("第二次调用只应得到一次拒绝结论", 1, secondAdmissions.size)
        assertEquals("拒绝分支不做第二次 CAS", 1, store.appliedTransitions)
    }

    // ---- 14. isCurrent 读 store 期间旧 lease 被释放：必须返回 false ----
    // 本轨迹只回归「当前实现每次 isCurrent 都 suspend 读 store」的交织；若实现改为缓存判定（不读 store），
    // 该交织不适用——不据此声称这是任意实现的通用保证。
    @Test
    fun isCurrentIsFalseWhenLeaseReleasedDuringStoreRead() = runBlocking {
        val conv = Uuid.random()
        val target = run(conv)
        val store = FakeAgentRunStore(target)
        val gate = TeamExecutionGate()
        val coordinator = coordinator(store, gate, limit = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fence: FenceToken? = null

        val job = launch(Dispatchers.Default) {
            coordinator.runAdmitted(member(conv, target.runId)) { admission ->
                fence = (admission as Admission.Admitted).fence
                entered.complete(Unit)
                release.await()
            }
        }
        withTimeout(TIMEOUT_MS) { entered.await() }
        assertTrue("基线：活跃 lease 是 current", coordinator.isCurrent(fence!!))

        // 让下一次 store.get 挂起：isCurrent 会先过 lease 检查，再停在这里。
        val getEntered = CompletableDeferred<Unit>()
        val getRelease = CompletableDeferred<Unit>()
        store.getHold = GetHold(getEntered, getRelease)

        var probed: Boolean? = null
        val probe = launch(Dispatchers.Default) { probed = coordinator.isCurrent(fence!!) }
        withTimeout(TIMEOUT_MS) { getEntered.await() }

        // 读取期间释放旧 lease（首个执行结束）；此时 run 仍是 RUNNING、非终态。
        release.complete(Unit)
        job.join()
        getRelease.complete(Unit)
        withTimeout(TIMEOUT_MS) { probe.join() }

        // 期望：读到快照后必须复核 lease 仍是同一个；旧 lease 已释放 → false（不能只看「非终态」）。
        assertFalse("旧 lease 在读取 store 期间被释放，isCurrent 必须 false", probed!!)
    }

    // ------------------------------------------------------------------
    // fixtures（只用冻结 API；不改产品、不抽产品 helper）
    // ------------------------------------------------------------------

    private fun coordinator(
        store: FakeAgentRunStore,
        gate: TeamExecutionGate,
        limit: Int,
    ): RunCoordinator {
        val rootTeam = Uuid.random()
        return DefaultRunCoordinator(store, gate, { _: Uuid -> rootTeam }, { limit })
    }

    private fun run(
        conversationId: Uuid,
        state: AgentRunState = AgentRunState.QUEUED,
    ) = AgentRun(runId = Uuid.random(), conversationId = conversationId, state = state, stateRevision = 1L)

    /** MEMBER 必须带非空 teamId（实现侧对 null 走 fail-closed 拒绝；本组只测有队属的成员准入）。 */
    private fun member(conversationId: Uuid, runId: Uuid?) =
        AdmitRequest(runId = runId, conversationId = conversationId, teamId = TEAM_ID, kind = AdmitKind.MEMBER)

    /** 让 fake 的下一次 `get` 先通知再挂起，用于制造「读取期间 lease 被释放」的交错。 */
    private class GetHold(val entered: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>)

    /**
     * 只镜像 `AgentRunStore` 的 CAS 契约：缺失 → NotFound、版本不符 → Conflict、
     * 除「CAS 到 RUNNING」以外的迁移（同态/终态出边/其他目标态）→ IllegalTransition。
     * 这是本测试手写的极小子集，不读产品 allowed 集合；被测对象是协调器的拒绝映射。
     */
    private class FakeAgentRunStore(vararg initial: AgentRun) : AgentRunStore {
        private val runs = LinkedHashMap<Uuid, AgentRun>()

        init {
            initial.forEach { runs[it.runId] = it }
        }

        var appliedTransitions = 0
            private set
        var transitionAttempts = 0
            private set
        var createCalls = 0
            private set

        /** 该 run 的每次 CAS 都撞冲突（模拟「别人先改了」的持续状态）。 */
        var conflictRunId: Uuid? = null

        fun bumpRevision(runId: Uuid) {
            val current = runs.getValue(runId)
            runs[runId] = current.copy(stateRevision = current.stateRevision + 1)
        }

        /** 手写真值：只有这些状态可以进入 RUNNING；同态与终态都不行。 */
        private fun casAllowsRunning(from: AgentRunState): Boolean = when (from) {
            AgentRunState.QUEUED,
            AgentRunState.PAUSED,
            AgentRunState.WAITING_APPROVAL,
            AgentRunState.WAITING_INPUT,
            AgentRunState.WAITING_CHILDREN,
            -> true

            else -> false
        }

        override suspend fun create(spec: NewAgentRun): CreateRunResult {
            createCalls++
            return CreateRunResult.Rejected("Rr20261105 fake: RunCoordinator R1 不得创建 Run")
        }

        /** 测试侧钩子：下一次 get 先 complete 再挂起，用来把「读 store」与「释放 lease」交错。 */
        @Volatile
        var getHold: GetHold? = null

        override suspend fun get(runId: Uuid): AgentRun? {
            getHold?.let { hold ->
                getHold = null
                hold.entered.complete(Unit)
                hold.release.await()
            }
            return runs[runId]
        }

        override suspend fun findBySourceKey(sourceKey: String): AgentRun? =
            runs.values.firstOrNull { it.sourceKey == sourceKey }

        override suspend fun findLatestByReply(conversationId: Uuid, replyMessageId: String): AgentRun? = null

        override suspend fun latestForConversation(conversationId: Uuid): AgentRun? =
            runs.values.filter { it.conversationId == conversationId }.maxByOrNull { it.createdAt }

        override fun observeLatestForTeam(teamId: Uuid): Flow<List<AgentRunTeamSummary>> =
            MutableStateFlow(emptyList())

        override suspend fun transition(
            runId: Uuid,
            expectedRevision: Long,
            to: AgentRunState,
            reason: String?,
            now: Instant,
        ): RunTransitionResult {
            transitionAttempts++
            val current = runs[runId] ?: return RunTransitionResult.NotFound
            if (conflictRunId == runId) return RunTransitionResult.Conflict(current)
            if (current.stateRevision != expectedRevision) return RunTransitionResult.Conflict(current)
            if (to != AgentRunState.RUNNING || !casAllowsRunning(current.state)) {
                return RunTransitionResult.IllegalTransition(current.state, to)
            }
            val next = current.copy(
                state = to,
                stateRevision = current.stateRevision + 1,
                reason = reason ?: current.reason,
                updatedAt = now,
                startedAt = current.startedAt ?: now,
            )
            runs[runId] = next
            appliedTransitions++
            return RunTransitionResult.Applied(next)
        }

        override suspend fun transitionAndEnqueue(
            runId: Uuid,
            expectedRevision: Long,
            to: AgentRunState,
            reason: String?,
            now: Instant,
            entry: NewMailboxEntry,
            limit: Int,
        ): RunTransitionAndEnqueueResult = RunTransitionAndEnqueueResult.Rejected(RunTransitionResult.NotFound)

        override suspend fun bindReply(
            runId: Uuid,
            expectedRevision: Long,
            replyMessageId: String,
            now: Instant,
        ): BindReplyResult = BindReplyResult.NotFound

        override suspend fun recordSteps(
            runId: Uuid,
            expectedRevision: Long,
            stepsUsed: Int,
            now: Instant,
        ): RecordStepsResult = RecordStepsResult.NotFound

        override suspend fun listByState(state: AgentRunState): List<AgentRun> =
            runs.values.filter { it.state == state }

        override suspend fun listForConversation(conversationId: Uuid): List<AgentRun> =
            runs.values.filter { it.conversationId == conversationId }

        override suspend fun listForTask(taskId: Uuid): List<AgentRun> =
            runs.values.filter { it.taskId == taskId }

        override suspend fun listDescendantRuns(runId: Uuid): List<AgentRun> = emptyList()

        override suspend fun listLiveRunsByTeams(teamIds: Collection<Uuid>): List<AgentRun> = emptyList()

        override fun observeConversation(conversationId: Uuid): Flow<List<AgentRun>> =
            MutableStateFlow(emptyList())

        override fun observeTeam(teamId: Uuid): Flow<List<AgentRun>> = MutableStateFlow(emptyList())

        override suspend fun events(runId: Uuid, limit: Int, beforeRevision: Long?): List<AgentRunEvent> =
            emptyList()

        override suspend fun recoverInterrupted(reason: String, now: Instant): RecoveryReport = RecoveryReport()
    }

    private companion object {
        /** 正的等待上限：只为不挂死测试，不是行为断言。 */
        const val TIMEOUT_MS = 3_000L

        /** 「必须在无 I/O 等待的情况下给出结论」的窗口：同 runId 拒绝不得排在 lease 之后。 */
        const val DECIDE_MS = 1_000L

        /** 成员请求的队属；`rootTeamId` lambda 固定映射到同一根队（子队共享根名额）。 */
        val TEAM_ID: Uuid = Uuid.random()
    }
}
