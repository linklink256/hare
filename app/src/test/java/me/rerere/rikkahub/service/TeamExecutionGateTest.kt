package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TeamExecutionGateTest {
    @Test fun concurrentEntrantsNeverExceedLimit() = runTest {
        val gate = TeamExecutionGate()
        val team = Uuid.random()
        var running = 0
        var peak = 0
        val release = CompletableDeferred<Unit>()
        val jobs = List(20) { launch {
            gate.withPermit(team, { 4 }) {
                running++
                peak = maxOf(peak, running)
                release.await()
                running--
            }
        } }
        runCurrent()
        assertEquals(4, running)
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(4, peak)
        assertEquals(0, running)
    }
    @Test fun pendingWaiterNotReportedAsRunning() = runTest {
        val gate = TeamExecutionGate(); val team = Uuid.random()
        val a = Uuid.random(); val b = Uuid.random()
        val first = launch { gate.withPermit(team, { 1 }, a) { awaitCancellation() } }
        runCurrent()
        val second = launch { gate.withPermit(team, { 1 }, b) { awaitCancellation() } }
        runCurrent()
        assertTrue(gate.isAdmitted(a)); assertFalse(gate.isAdmitted(b))
        first.cancelAndJoin(); runCurrent()
        assertFalse(gate.isAdmitted(a)); assertTrue(gate.isAdmitted(b))
        second.cancelAndJoin(); assertFalse(gate.isAdmitted(b))
    }
    @Test fun cancellingActiveRunReleasesSlot() = runTest {
        val gate = TeamExecutionGate(); val team = Uuid.random()
        val first = launch { gate.withPermit(team, { 1 }) { awaitCancellation() } }
        runCurrent()
        var entered = false
        val second = launch { gate.withPermit(team, { 1 }) { entered = true } }
        runCurrent(); assertFalse(entered)
        first.cancelAndJoin(); second.join(); assertTrue(entered)
    }
    @Test fun cancellingWaiterDoesNotStrandSlot() = runTest {
        val gate = TeamExecutionGate(); val team = Uuid.random()
        val first = launch { gate.withPermit(team, { 1 }) { awaitCancellation() } }
        runCurrent()
        val waiting = launch { gate.withPermit(team, { 1 }) { fail("cancelled waiter entered") } }
        runCurrent(); waiting.cancelAndJoin(); first.cancelAndJoin()
        gate.withPermit(team, { 1 }) { }
    }
    @Test fun thrownFailureReleasesSlot() = runTest {
        val gate = TeamExecutionGate(); val team = Uuid.random()
        try { gate.withPermit(team, { 1 }) { error("test") } } catch (_: IllegalStateException) { }
        var entered = false
        gate.withPermit(team, { 1 }) { entered = true }
        assertTrue(entered)
    }
    @Test fun teamsHaveIndependentBudgetsAndLeaderIsNotCounted() = runTest {
        val gate = TeamExecutionGate()
        val first = launch { gate.withPermit(Uuid.random(), { 1 }) { awaitCancellation() } }
        runCurrent()
        var entered = false
        gate.withPermit(Uuid.random(), { 1 }) { entered = true }
        assertTrue(entered); first.cancelAndJoin()
    }
    @Test fun raisingLimitWakesWaitingMember() = runTest {
        val gate = TeamExecutionGate(); val team = Uuid.random(); var limit = 1
        val first = launch { gate.withPermit(team, { limit }) { awaitCancellation() } }
        runCurrent()
        var entered = false
        val second = launch { gate.withPermit(team, { limit }) { entered = true } }
        runCurrent(); assertFalse(entered)
        limit = 2; advanceTimeBy(251); runCurrent(); assertTrue(entered)
        first.cancelAndJoin(); second.join()
    }
    @Test fun invalidLimitClampedAndReductionDoesNotCancelRunningMembers() = runTest {
        val gate = TeamExecutionGate(); val team = Uuid.random(); var limit = 2
        var running = 0; val release = CompletableDeferred<Unit>()
        val jobs = List(2) { launch { gate.withPermit(team, { limit }) { running++; release.await(); running-- } } }
        runCurrent(); limit = 0
        var entered = false
        val waiting = launch { gate.withPermit(team, { limit }) { entered = true } }
        advanceTimeBy(251); runCurrent(); assertEquals(2, running); assertFalse(entered)
        release.complete(Unit); jobs.forEach { it.join() }; waiting.join(); assertTrue(entered)
    }
}
