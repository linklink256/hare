package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test

class TeamRunTimingTest {
    @Test fun runningTimeUsesMonotonicDelta() {
        val run = TeamRunTiming(2_000_000_000L)
        assertTrue(run.running)
        assertEquals(1234L, run.elapsedMillisAt(3_234_000_000L))
    }
    @Test fun finishedTimeStaysFixed() {
        val run = TeamRunTiming(0L, 1234L)
        assertFalse(run.running)
        assertEquals(1234L, run.elapsedMillisAt(Long.MAX_VALUE))
    }
    @Test fun negativeDeltaClamped() { assertEquals(0L, TeamRunTiming(500L).elapsedMillisAt(100L)) }
    @Test fun lessThanASecond() { assertEquals("0:00", formatTeamTaskElapsed(999L)) }
    @Test fun minutesAndSeconds() { assertEquals("1:23", formatTeamTaskElapsed(83_999L)) }
    @Test fun hourBoundary() { assertEquals("1:00:00", formatTeamTaskElapsed(3_600_000L)) }
    @Test fun longTaskIsNotClockOfDay() { assertEquals("25:01:02", formatTeamTaskElapsed(90_062_000L)) }
    @Test fun negativeDisplayClamped() { assertEquals("0:00", formatTeamTaskElapsed(-1L)) }
}
