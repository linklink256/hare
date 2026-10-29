package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.model.TeamRunTiming

/** Shared per-team runtime admission, including UI/Web/regeneration/approval entry points. */
class TeamExecutionGate(private val nowNanos: () -> Long = System::nanoTime) {
    private val mutex = Mutex()
    private val active = mutableMapOf<Uuid, MutableSet<Uuid>>()
    private val revision = MutableStateFlow(0L)
    private val admissions = mutableMapOf<Uuid, Uuid>()
    private val runningConversations = MutableStateFlow<Set<Uuid>>(emptySet())
    val admittedConversations = runningConversations.asStateFlow()
    private val timings = MutableStateFlow<Map<Uuid, TeamRunTiming>>(emptyMap())
    val runTimings = timings.asStateFlow()

    /**
     * 由 ChatService 启动时注册：只读采样该会话当前是否有待审批工具（不会建会话/读历史）。
     * 在真实拿到 permit 的这一轮结束时调用一次，结果随完成缓存留存。
     */
    @Volatile
    var approvalSampler: ((Uuid) -> Boolean?)? = null

    /**
     * 只含已完成轮次的审批标量快照。running 中的条目不在此列，重新派活会覆盖旧快照，
     * 因此不会把新一轮误判成上一轮的结论；也不随秒计时变化。
     */
    val finishedApprovalSnapshots: Flow<Map<Uuid, Boolean>> = timings
        .map { current ->
            current.mapNotNull { (id, timing) ->
                if (timing.running) null else timing.awaitingApprovalAtFinish?.let { id to it }
            }.toMap()
        }
        .distinctUntilChanged()

    fun isAdmitted(conversationId: Uuid): Boolean = conversationId in runningConversations.value

    suspend fun <T> withPermit(
        team: Uuid,
        limit: suspend () -> Int,
        conversation: Uuid = Uuid.random(),
        onFinished: suspend (Long) -> Unit = {},
        block: suspend () -> T,
    ): T {
        val token = Uuid.random()
        var startedAtNanos = 0L
        while (true) {
            val observed = revision.value
            val admitted = mutex.withLock {
                val slots = active[team]
                if ((slots?.size ?: 0) >= limit().coerceAtLeast(1)) false else {
                    active.getOrPut(team) { mutableSetOf() }.add(token)
                    admissions[token] = conversation
                    startedAtNanos = nowNanos()
                    timings.value = timings.value + (conversation to TeamRunTiming(startedAtNanos))
                    runningConversations.value = admissions.values.toSet()
                    true
                }
            }
            if (admitted) break
            // Completion wakes promptly; periodically re-read changed user settings as well.
            withTimeoutOrNull(250) { revision.first { it != observed } }
        }
        try {
            return block()
        } finally {
            // Measure before persistence/cleanup; cancellation must still finalize the timer and permit.
            val elapsedMillis = TeamRunTiming(startedAtNanos).elapsedMillisAt(nowNanos())
            // 此刻这一轮已写完 pending 工具、会话通常还在缓存里：采样一次审批状态，
            // 之后会话被空闲回收（1s）也还能维持分组。只存标量，不保活会话。
            val approvalAtFinish = runCatching { approvalSampler?.invoke(conversation) }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock {
                    timings.value = (timings.value - conversation) +
                        (conversation to TeamRunTiming(startedAtNanos, elapsedMillis, approvalAtFinish))
                }
                try {
                    onFinished(elapsedMillis)
                } finally {
                    mutex.withLock {
                        active[team]?.let { slots ->
                            slots.remove(token)
                            if (slots.isEmpty()) active.remove(team)
                        }
                        admissions.remove(token)
                        // A small finished cache bridges Room delivery lag and preserves the current
                        // result if persistence fails. Never retain every past teammate or evict a live run.
                        val completed = timings.value.filterValues { !it.running }.keys
                        val evicted = completed.take((completed.size - 64).coerceAtLeast(0)).toSet()
                        if (evicted.isNotEmpty()) timings.value = timings.value - evicted
                        runningConversations.value = admissions.values.toSet()
                        revision.value++
                    }
                }
            }
        }
    }
}
