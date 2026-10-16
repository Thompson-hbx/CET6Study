package com.example.cet6

import com.example.cet6.data.WordEntity
import com.example.cet6.data.nextReview
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewSchedulerTest {
    private val card = WordEntity("allocate", "分配", "They allocate funds.")
    @Test fun firstRememberSchedulesTomorrow() {
        val actual = nextReview(card, true, 100_000L)
        assertEquals(1, actual.intervalDays)
        assertEquals(1, actual.repetitions)
        assertEquals(86_500_000L, actual.dueAt)
    }
    @Test fun forgottenWordResetsInterval() {
        val actual = nextReview(card.copy(intervalDays = 14, repetitions = 4), false, 0L)
        assertEquals(1, actual.intervalDays)
        assertEquals(0, actual.repetitions)
    }
    @Test fun intervalGrowsWithinCap() {
        val actual = nextReview(card.copy(intervalDays = 60), true, 0L)
        assertEquals(90, actual.intervalDays)
    }
}
