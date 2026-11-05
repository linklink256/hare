package me.rerere.rikkahub.runtime

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.AgentRun
import me.rerere.rikkahub.data.model.AgentRunState
import me.rerere.rikkahub.data.repository.AgentRunStore
import me.rerere.rikkahub.data.repository.RunTransitionResult
import me.rerere.rikkahub.service.TeamExecutionGate

enum class AdmitKind { LEAD, MEMBER }

data class FenceToken(val runId: Uuid, val generation: Long, val leaseId: Uuid)

data class AdmitRequest(
    val runId: Uuid?,
    val conversationId: Uuid,
    val teamId: Uuid?,
    val kind: AdmitKind,
)

sealed interface Admission {
    data class Admitted(val run: AgentRun, val fence: FenceToken) : Admission
    data class Rejected(val reason: AdmissionRejection, val current: AgentRun?) : Admission
    data class Untracked(val conversationId: Uuid, val leaseId: Uuid) : Admission
}

enum class AdmissionRejection {
    NOT_FOUND, TERMINAL, CONFLICT, ILLEGAL_TRANSITION
}

interface RunCoordinator {
    suspend fun <T> runAdmitted(
        request: AdmitRequest,
        onFinished: suspend (Long) -> Unit = {},
        block: suspend (Admission) -> T,
    ): T

    suspend fun isCurrent(fence: FenceToken): Boolean
}

/**
 * 单会话执行串行边界：同一 conversationId 同一时刻至多一个受跟踪执行。
 * R1 的 generation 恒为 0 且只在本进程内有效，不做持久/跨进程 fencing。
 */
class DefaultRunCoordinator(
    private val runStore: AgentRunStore,
    private val gate: TeamExecutionGate,
    private val rootTeamId: suspend (Uuid) -> Uuid,
    private val memberLimit: suspend () -> Int,
) : RunCoordinator {
    private class LeaseEntry {
        val mutex = Mutex()

        /** 持有者 + 等待者引用计数；只在 [leases] 的 compute 内读写，归零即回收 */
        var refs: Int = 0
    }

    /** 当前活跃 lease：只记 runId，供 [isCurrent] 判失活 */
    private data class ActiveLease(val runId: Uuid?)

    private val leases = ConcurrentHashMap<Uuid, LeaseEntry>()
    private val activeLeases = ConcurrentHashMap<Uuid, ActiveLease>()

    /** 已登记但尚未收尾的 Run → 本次 leaseId；同 Run 的第二次准入不排队，直接拒绝 */
    private val admittedRuns = ConcurrentHashMap<Uuid, Uuid>()

    override suspend fun <T> runAdmitted(
        request: AdmitRequest,
        onFinished: suspend (Long) -> Unit,
        block: suspend (Admission) -> T,
    ): T {
        // 已取消的上下文不再开新操作：Mutex/Map 快路径不会自己检查取消
        currentCoroutineContext().ensureActive()
        // LEAD 必须显式带 runId；非法请求 fail-closed，不落转录
        if (request.kind == AdmitKind.LEAD && request.runId == null) {
            return block(Admission.Rejected(AdmissionRejection.ILLEGAL_TRANSITION, null))
        }
        val runId = request.runId
        val leaseId = Uuid.random()
        var acquired = false
        if (runId != null && admittedRuns.putIfAbsent(runId, leaseId) != null) {
            // 同一 Run 仍在执行/排队：不等待首个让出名额，按非法迁移拒绝重跑
            return block(Admission.Rejected(AdmissionRejection.ILLEGAL_TRANSITION, runStore.get(runId)))
        }
        try {
            if (runId != null) {
                val current = runStore.get(runId)
                if (current?.state == AgentRunState.RUNNING) {
                    // 库里已是 RUNNING：RUNNING→RUNNING 不是合法续跑，拒绝而不是等待后重跑
                    return block(Admission.Rejected(AdmissionRejection.ILLEGAL_TRANSITION, current))
                }
            }
            acquireLease(request.conversationId, runId, leaseId)
            acquired = true
            // 等 lease 期间可能已被取消：显式检查后再进 gate/CAS
            currentCoroutineContext().ensureActive()
            return when (request.kind) {
                AdmitKind.LEAD -> block(admitRunning(request.runId!!, leaseId))
                AdmitKind.MEMBER -> admitMember(request, leaseId, onFinished, block)
            }
        } finally {
            if (runId != null) admittedRuns.remove(runId, leaseId)
            if (acquired) withContext(NonCancellable) { releaseLease(request.conversationId, leaseId) }
        }
    }

    override suspend fun isCurrent(fence: FenceToken): Boolean {
        val lease = activeLeases[fence.leaseId] ?: return false
        if (lease.runId != fence.runId || fence.generation != GENERATION) return false
        val run = runStore.get(fence.runId) ?: return false
        // get 是挂起点：返回前重核同一 lease 仍活跃，避免读取期间已释放的旧 fence 被判 true
        val stillActive = activeLeases[fence.leaseId] ?: return false
        if (stillActive.runId != fence.runId) return false
        return !run.isTerminal
    }

    private suspend fun <T> admitMember(
        request: AdmitRequest,
        leaseId: Uuid,
        onFinished: suspend (Long) -> Unit,
        block: suspend (Admission) -> T,
    ): T {
        val teamId = request.teamId
            ?: return block(Admission.Rejected(AdmissionRejection.ILLEGAL_TRANSITION, null))
        // 先拿会话 lease 再排 gate：同一会话不会同时占两个名额
        return gate.withPermit(
            team = rootTeamId(teamId),
            conversation = request.conversationId,
            limit = { memberLimit() },
            onFinished = onFinished,
        ) {
            // gate 无竞争时会快路径返回：执行前再确认协程仍活跃
            currentCoroutineContext().ensureActive()
            val runId = request.runId
            block(
                if (runId == null) Admission.Untracked(request.conversationId, leaseId)
                else admitRunning(runId, leaseId)
            )
        }
    }

    /** 受跟踪 Run 的 CAS 准入；RUNNING→RUNNING 按非法迁移拒绝，不等待放行 */
    private suspend fun admitRunning(runId: Uuid, leaseId: Uuid): Admission {
        currentCoroutineContext().ensureActive()
        repeat(CAS_ATTEMPTS) {
            val current = runStore.get(runId)
                ?: return Admission.Rejected(AdmissionRejection.NOT_FOUND, null)
            if (current.isTerminal) {
                return Admission.Rejected(AdmissionRejection.TERMINAL, current)
            }
            when (val result = runStore.transition(runId, current.stateRevision, AgentRunState.RUNNING)) {
                is RunTransitionResult.Applied ->
                    return Admission.Admitted(result.run, FenceToken(runId, GENERATION, leaseId))

                is RunTransitionResult.Conflict -> Unit
                is RunTransitionResult.IllegalTransition ->
                    return Admission.Rejected(AdmissionRejection.ILLEGAL_TRANSITION, current)

                RunTransitionResult.NotFound ->
                    return Admission.Rejected(AdmissionRejection.NOT_FOUND, null)
            }
        }
        return Admission.Rejected(AdmissionRejection.CONFLICT, runStore.get(runId))
    }

    private suspend fun acquireLease(conversationId: Uuid, runId: Uuid?, leaseId: Uuid) {
        val entry = leases.compute(conversationId) { _, existing ->
            (existing ?: LeaseEntry()).also { it.refs += 1 }
        }!!
        try {
            entry.mutex.lock()
        } catch (e: Throwable) {
            // 等待期间被取消：没拿到锁，只退还引用计数
            releaseRef(conversationId)
            throw e
        }
        activeLeases[leaseId] = ActiveLease(runId)
    }

    private fun releaseLease(conversationId: Uuid, leaseId: Uuid) {
        // 按 leaseId 条件移除，不误删后来者的条目
        activeLeases.remove(leaseId)
        leases[conversationId]?.mutex?.unlock()
        releaseRef(conversationId)
    }

    private fun releaseRef(conversationId: Uuid) {
        leases.compute(conversationId) { _, existing ->
            existing?.also { it.refs -= 1 }?.takeIf { it.refs > 0 }
        }
    }

    private companion object {
        const val GENERATION = 0L
        const val CAS_ATTEMPTS = 3
    }
}
