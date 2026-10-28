package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TeamRunDurationTest {
    @Test fun startsOnlyWhenAdmittedAndExcludesQueueWaiting() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        val team = Uuid.random()
        val firstId = Uuid.random()
        val nextId = Uuid.random()
        val release = CompletableDeferred<Unit>()
        val first = launch { gate.withPermit(team, { 1 }, firstId) { release.await() } }
        runCurrent()
        val second = launch {
            gate.withPermit(team, { 1 }, nextId) { assertEquals(now, gate.runTimings.value[nextId]?.startedAtNanos) }
        }
        runCurrent()
        assertNull(gate.runTimings.value[nextId])
        now = 9_000_000_000L
        release.complete(Unit)
        first.join(); second.join()
        assertTrue(gate.runTimings.value.values.none { it.running })
    }

    @Test fun completedDurationIsReportedAndLiveSamplesAreReleased() = runTest {
        var now = 1_000_000_000L
        val gate = TeamExecutionGate { now }
        val id = Uuid.random()
        var saved: Long? = null
        gate.withPermit(Uuid.random(), { 1 }, id, onFinished = { saved = it }) { now += 3_400_000_000L }
        assertEquals(3400L, saved)
        assertTrue(gate.runTimings.value.values.none { it.running })
        assertFalse(gate.isAdmitted(id))
    }

    @Test fun cancelledRunStillSavesDuration() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        val id = Uuid.random()
        var saved: Long? = null
        val job = launch { gate.withPermit(Uuid.random(), { 1 }, id, onFinished = { saved = it }) { awaitCancellation() } }
        runCurrent(); now = 2_345_000_000L; job.cancelAndJoin()
        assertEquals(2345L, saved)
        assertTrue(gate.runTimings.value.values.none { it.running })
    }

    @Test fun failedRunStillSavesDuration() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        var saved: Long? = null
        try {
            gate.withPermit(Uuid.random(), { 1 }, onFinished = { saved = it }) {
                now = 5_000_000_000L; error("model failed")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(5000L, saved)
    }

    @Test fun cancelledQueuedRunDoesNotWriteFakeDuration() = runTest {
        val gate = TeamExecutionGate { 0L }
        val team = Uuid.random()
        val first = launch { gate.withPermit(team, { 1 }) { awaitCancellation() } }
        runCurrent()
        var saved = false
        val queued = launch { gate.withPermit(team, { 1 }, onFinished = { saved = true }) { fail("not admitted") } }
        runCurrent(); queued.cancelAndJoin(); first.cancelAndJoin()
        assertFalse(saved)
    }

    @Test fun redispatchStartsFromZeroInsteadOfAccumulatingLifetime() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }
        val team = Uuid.random(); val id = Uuid.random()
        val saved = mutableListOf<Long>()
        gate.withPermit(team, { 1 }, id, onFinished = { saved += it }) { now = 5_000_000_000L }
        now = 20_000_000_000L
        gate.withPermit(team, { 1 }, id, onFinished = { saved += it }) {
            assertEquals(0L, gate.runTimings.value[id]?.elapsedMillisAt(now))
            now += 1_000_000_000L
        }
        assertEquals(listOf(5000L, 1000L), saved)
    }

    @Test fun persistenceFailureCannotStrandPermitOrTiming() = runTest {
        val gate = TeamExecutionGate { 0L }
        val team = Uuid.random(); val id = Uuid.random()
        try { gate.withPermit(team, { 1 }, id, onFinished = { error("database failed") }) { } }
        catch (_: IllegalStateException) { }
        assertTrue(gate.runTimings.value.values.none { it.running })
        gate.withPermit(team, { 1 }, id) { }
    }

    @Test fun timerFreezesBeforePersistenceAndDoesNotCountSaveDelay() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }; val id = Uuid.random()
        gate.withPermit(Uuid.random(), { 1 }, id, onFinished = { elapsed ->
            assertEquals(1000L, elapsed)
            now = 90_000_000_000L
            delay(10)
            assertEquals(1000L, gate.runTimings.value[id]?.elapsedMillisAt(now))
            assertFalse(gate.runTimings.value[id]!!.running)
        }) { now = 1_000_000_000L }
    }
    @Test fun finishedCacheIsBoundedAndDoesNotEvictLiveRuns() = runTest {
        val gate = TeamExecutionGate { 0L }; val team = Uuid.random(); val live = Uuid.random()
        val job = launch { gate.withPermit(team, { 2 }, live) { awaitCancellation() } }
        runCurrent()
        repeat(80) { gate.withPermit(team, { 2 }) { } }
        assertEquals(65, gate.runTimings.value.size)
        assertTrue(gate.runTimings.value[live]!!.running)
        job.cancelAndJoin()
        assertEquals(64, gate.runTimings.value.size)
    }

    @Test fun finalSnapshotSurvivesSaveErrorUntilNextRunOrEviction() = runTest {
        var now = 0L
        val gate = TeamExecutionGate { now }; val id = Uuid.random()
        try {
            gate.withPermit(Uuid.random(), { 1 }, id, onFinished = { error("save failed") }) {
                now = 7_000_000_000L
            }
        } catch (_: IllegalStateException) { }
        assertEquals(7000L, gate.runTimings.value[id]?.elapsedMillisAt(99_000_000_000L))
        assertFalse(gate.runTimings.value[id]!!.running)
    }

}
