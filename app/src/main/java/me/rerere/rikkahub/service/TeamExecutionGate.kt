package me.rerere.rikkahub.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock {
                    timings.value = (timings.value - conversation) + (conversation to TeamRunTiming(startedAtNanos, elapsedMillis))
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
