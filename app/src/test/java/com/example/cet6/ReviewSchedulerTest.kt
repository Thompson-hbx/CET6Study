package com.example.cet6

import com.example.cet6.data.WordEntity
import com.example.cet6.data.WordScheduler
import com.example.cet6.data.WordRating
import com.example.cet6.data.WordSchedule
import dev.bee.fsrs.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewSchedulerTest {
    private val card = WordEntity("allocate", "分配", "They allocate funds.")
    @Test fun firstGoodLearnsInTenMinutesWithFsrsMemory() {
        val actual = WordScheduler.next(card, null, WordRating.GOOD, 100_000L)
        assertEquals(700_000L, actual.card.dueAt)
        assertEquals(2.3065, actual.state.stability, 1e-10)
        assertEquals(WordScheduler.LEARNING, actual.state.phase)
        assertEquals(1, actual.state.step)
    }
    @Test fun legacyCardKeepsContentAndAgainRelearnsInOneMinute() {
        val old = card.copy(dueAt = 100L, intervalDays = 14, repetitions = 4)
        val actual = WordScheduler.next(old, null, WordRating.AGAIN, 100_000L)
        assertEquals(160_000L, actual.card.dueAt)
        assertEquals(0, actual.card.repetitions)
        assertEquals(old.meaning, actual.card.meaning)
        assertEquals(WordScheduler.RELEARNING, actual.state.phase)
    }
    @Test fun rejectsClockReversalAndUnknownAlgorithm() {
        val first = WordScheduler.next(card, null, WordRating.GOOD, 100_000L)
        assertTrue(runCatching { WordScheduler.next(first.card, first.state, WordRating.GOOD, 0L) }.isFailure)
        assertTrue(runCatching { WordScheduler.next(first.card, first.state.copy(algorithm = "other"), WordRating.GOOD, 700_000L) }.isFailure)
    }
    @Test fun sevenDayDeterministicSequenceSurvivesStateCopies() {
        val start = 1_800_000_000_000L
        var current = card
        var memory: WordSchedule? = null
        var now = start
        var count = 0
        while (now < start + 7 * WordScheduler.DAY) {
            val next = WordScheduler.next(current.copy(), memory?.copy(), WordRating.GOOD, now)
            assertTrue(next.state.stability.isFinite() && next.state.stability > 0)
            assertTrue(next.card.dueAt > now)
            current = next.card
            memory = next.state
            now = current.dueAt
            count++
        }
        assertTrue(count >= 3)
        assertEquals(WordScheduler.REVIEW, memory!!.phase)
    }
    @Test fun learningAndRelearningMatchThirtyOfficialPythonTransitions() {
        @Suppress("UNCHECKED_CAST")
        val root = Json.parse(File("testdata/fsrs-policy-reference.json").readText()) as Map<String, Any?>
        val traces = root["traces"] as List<*>
        var count = 0
        for (trace in traces) {
            var current = card
            var memory: WordSchedule? = null
            for (raw in trace as List<*>) {
                val item = raw as Map<*, *>
                fun number(name: String) = item[name] as Number
                val now = number("now").toLong()
                val rating = WordRating.entries.single { it.value == number("rating").toInt() }
                val next = WordScheduler.next(current, memory, rating, now)
                assertEquals(number("dueAt").toLong(), next.card.dueAt)
                assertEquals(number("stability").toDouble(), next.state.stability, 1e-9)
                assertEquals(number("difficulty").toDouble(), next.state.difficulty, 1e-9)
                assertEquals(number("phase").toInt(), next.state.phase)
                assertEquals(number("step").toInt(), next.state.step)
                current = next.card
                memory = next.state
                count++
            }
        }
        assertEquals(30, count)
    }
}
