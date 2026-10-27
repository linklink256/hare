package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

/** Shared per-team runtime admission, including UI/Web/regeneration/approval entry points. */
class TeamExecutionGate {
    private val mutex = Mutex()
    private val active = mutableMapOf<Uuid, MutableSet<Uuid>>()
    private val revision = MutableStateFlow(0L)
    private val admissions = mutableMapOf<Uuid, Uuid>()
    @Volatile private var runningConversations = emptySet<Uuid>()

    fun isAdmitted(conversationId: Uuid): Boolean = conversationId in runningConversations

    suspend fun <T> withPermit(team: Uuid, limit: suspend () -> Int, conversation: Uuid = Uuid.random(), block: suspend () -> T): T {
        val token = Uuid.random()
        while (true) {
            val observed = revision.value
            val admitted = mutex.withLock {
                val slots = active[team]
                if ((slots?.size ?: 0) >= limit().coerceAtLeast(1)) false else {
                    active.getOrPut(team) { mutableSetOf() }.add(token)
                    admissions[token] = conversation
                    runningConversations = admissions.values.toSet()
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
            // Cancellation must not strand a slot. Cleanup has no suspension points.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock {
                    active[team]?.let { slots ->
                        slots.remove(token)
                        if (slots.isEmpty()) active.remove(team)
                    }
                    admissions.remove(token)
                    runningConversations = admissions.values.toSet()
                    revision.value++
                }
            }
        }
    }
}
