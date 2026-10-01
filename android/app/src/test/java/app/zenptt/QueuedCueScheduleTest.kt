package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedCueScheduleTest {
    @Test
    fun fiveSecondQueueUsesSevenAbsoluteBoundaries() {
        val schedule = QueuedCueSchedule()
        schedule.start(0)

        val audible = generateSequence { schedule.nextBoundary(0) }
            .takeWhile { it < 5_000 }
            .toList()

        assertEquals(listOf(0L, 800L, 1_600L, 2_400L, 3_200L, 4_000L, 4_800L), audible)
    }

    @Test
    fun missedBoundariesAreSkippedInsteadOfAccumulated() {
        val schedule = QueuedCueSchedule()
        schedule.start(0)

        assertEquals(0L, schedule.nextBoundary(0))
        assertEquals(3_200L, schedule.nextBoundary(2_550))
        assertEquals(4_000L, schedule.nextBoundary(3_201))
    }
}
