package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.service.ChatFloatingTracker.Action
import me.rerere.rikkahub.service.ChatFloatingTracker.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FloatingMainConversationFilterTest {
    private val main = Uuid.random()
    private val otherMain = Uuid.random()
    private val child = Uuid.random()

    private suspend fun action(vararg running: Uuid): Action.JobsChanged =
        Action.JobsChanged(floatingMainConversations(running.toSet()) { it == child })

    @Test fun emptyJobsDoNotQueryMembership() = runBlocking {
        var calls = 0
        assertEquals(emptySet<Uuid>(), floatingMainConversations(emptySet()) { calls++; false })
        assertEquals(0, calls)
    }

    @Test fun ordinaryConversationsStayEligible() = runBlocking {
        assertEquals(setOf(main, otherMain), floatingMainConversations(setOf(main, otherMain)) { false })
    }

    @Test fun childrenAreRemovedFromMixedJobs() = runBlocking {
        assertEquals(setOf(main), floatingMainConversations(setOf(main, child)) { it == child })
    }

    @Test fun childrenAloneHaveNoEligibleTarget() = runBlocking {
        assertEquals(emptySet<Uuid>(), floatingMainConversations(setOf(child)) { true })
    }

    @Test fun membershipWinsEvenForLegacyChildLead() = runBlocking {
        val legacyLeads = setOf(main, child)
        val result = floatingMainConversations(legacyLeads) { it == child }
        assertFalse(child in result)
        assertEquals(setOf(main), result)
    }

    @Test fun mainConversationOrderIsPreserved() = runBlocking {
        val result = floatingMainConversations(linkedSetOf(main, child, otherMain)) { it == child }
        assertEquals(listOf(main, otherMain), result.toList())
    }

    @Test(expected = CancellationException::class)
    fun membershipLookupCancellationIsNotSwallowed() = runBlocking {
        floatingMainConversations(setOf(main)) { throw CancellationException("cancel lookup") }
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun lookupFailureDoesNotClassifyUnknownJobsAsMain() = runBlocking {
        floatingMainConversations(setOf(child)) { throw IllegalStateException("lookup unavailable") }
        Unit
    }

    @Test fun childStartAndCompletionNeverReplaceRunningMain() = runBlocking {
        val initial = TrackingState().reduce(action(main))
        val started = initial.reduce(action(main, child))
        val ended = started.reduce(action(main))
        assertEquals(initial, started)
        assertEquals(initial, ended)
        assertEquals(main, ended.window?.conversationId)
    }

    @Test fun onlyChildTasksNeverOpenAWindow() = runBlocking {
        val started = TrackingState().reduce(action(child))
        val ended = started.reduce(action())
        assertNull(started.window)
        assertNull(ended.window)
    }

    @Test fun backgroundEntryWithMixedJobsSelectsMain() = runBlocking {
        val state = TrackingState().reduce(action(main, child))
        assertEquals(main, state.window?.conversationId)
    }

    @Test fun mainCompletionDoesNotWaitForChildren() = runBlocking {
        val state = TrackingState().reduce(action(main, child)).reduce(action(child))
        assertTrue(state.running.isEmpty())
        assertEquals(main, state.window?.conversationId)
    }

    @Test fun childActivityDoesNotReopenDismissedMainWindow() = runBlocking {
        val dismissed = TrackingState().reduce(action(main)).reduce(Action.Dismiss)
        val childStarted = dismissed.reduce(action(main, child))
        val childEnded = childStarted.reduce(action(main))
        assertNull(childStarted.window)
        assertNull(childEnded.window)
    }
}
