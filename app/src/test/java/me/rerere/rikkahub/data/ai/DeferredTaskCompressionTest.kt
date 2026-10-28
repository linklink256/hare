package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeferredTaskCompressionTest {
    @Test fun `crossing threshold during tool steps only marks pending`() {
        val schedule = DeferredTaskCompression()
        repeat(20) { schedule.observeThreshold(it >= 2) }
        assertTrue(schedule.pending)
    }

    @Test fun `completed task rechecks then compresses in order`() = runTest {
        val schedule = DeferredTaskCompression()
        val events = mutableListOf("model", "tools", "final answer saved")
        schedule.observeThreshold(true)
        schedule.afterTask(true, { events += "final threshold check"; true }) { events += "compression" }
        assertEquals(listOf("model", "tools", "final answer saved", "final threshold check", "compression"), events)
        assertFalse(schedule.pending)
    }

    @Test fun `last reply alone can cross threshold without another model step`() = runTest {
        val schedule = DeferredTaskCompression()
        var compressions = 0
        schedule.observeThreshold(false)
        schedule.afterTask(true, { true }) { compressions++ }
        assertEquals(1, compressions)
    }

    @Test fun `approval pause cannot check or compress`() = runTest { assertNotCompleted() }
    @Test fun `step limit is not task completion`() = runTest { assertNotCompleted() }
    @Test fun `failed task cannot check or compress`() = runTest { assertNotCompleted() }
    @Test fun `cancelled task cannot check or compress`() = runTest { assertNotCompleted() }

    private suspend fun assertNotCompleted() {
        val schedule = DeferredTaskCompression()
        schedule.observeThreshold(true)
        schedule.afterTask(false, { fail("unfinished task must not recheck"); true }) {
            fail("unfinished task must not compress")
        }
        assertTrue(schedule.pending)
    }

    @Test fun `below final threshold does not compress stale pending estimate`() = runTest {
        val schedule = DeferredTaskCompression()
        schedule.observeThreshold(true)
        schedule.afterTask(true, { false }) { fail("no compression needed") }
        assertFalse(schedule.pending)
    }

    @Test fun `no threshold reached never compresses`() = runTest {
        DeferredTaskCompression().afterTask(true, { false }) { fail("no compression needed") }
    }

    @Test fun `one task attempts maintenance only once`() = runTest {
        val schedule = DeferredTaskCompression()
        var compressions = 0
        schedule.afterTask(true, { true }) { compressions++ }
        schedule.observeThreshold(true)
        schedule.afterTask(true, { fail("completion already consumed"); true }) { compressions++ }
        assertEquals(1, compressions)
        assertFalse(schedule.pending)
    }

    @Test fun `compression failure propagates without retrying the completed task`() = runTest {
        val schedule = DeferredTaskCompression()
        val failure = IllegalStateException("summary failed")
        try {
            schedule.afterTask(true, { true }) { throw failure }
            fail("must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertFalse(schedule.pending)
        schedule.afterTask(true, { fail("already attempted"); true }) { fail("must not replay") }
    }

    @Test fun `maintenance cancellation is not swallowed`() = runTest {
        val schedule = DeferredTaskCompression()
        val cancelled = CancellationException("stop maintenance")
        try {
            schedule.afterTask(true, { true }) { throw cancelled }
            fail("must propagate cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancelled, caught)
        }
        assertFalse(schedule.pending)
    }
}
