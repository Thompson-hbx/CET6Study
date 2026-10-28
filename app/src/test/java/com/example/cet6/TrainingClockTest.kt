package com.example.cet6

import com.example.cet6.data.TrainingClock
import org.junit.Assert.*
import org.junit.Test

class TrainingClockTest {
    @Test fun restoresAcrossProcessAndIgnoresWallClockChangesWithinTheSameBoot() {
        val started = TrainingClock(1_000_000, 100, 5)
        val restored = TrainingClock.decode(started.encode())
        assertEquals(started, restored)
        assertEquals(10_000L, restored.elapsedMillis(1, 10_100, 5))
        assertFalse(restored.estimated(10_100, 5))
    }
    @Test fun rebootUsesAnExplicitEstimateAndNeverReportsNegativeElapsedTime() {
        val clock = TrainingClock(1_000_000, 100, 5)
        assertTrue(clock.estimated(50, 6))
        assertEquals(10_000L, clock.elapsedMillis(1_010_000, 50, 6))
        assertEquals(0L, clock.elapsedMillis(999_000, 50, 6))
        assertTrue(runCatching { TrainingClock.decode("{}") }.isFailure)
    }
}
