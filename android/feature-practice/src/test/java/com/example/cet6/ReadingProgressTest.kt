package com.example.cet6

import com.example.cet6.data.*
import com.example.cet6.ui.components.Tone
import com.example.cet6.ui.readingStatus
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressTest {
    private fun paper() = ExamPaper(
        "sample-cet6-2026-06-13-1", "2026-06-13", "1",
        sourceName = "Original test", sourceUrl = "local:test", licenseStatus = "original",
        reviewed = true, sample = true,
        passages = (1..2).map { code ->
            ExamPassage(
                "$code",
                "Original sample",
                "Libraries provide quiet rooms.",
                (if (code == 1) 46..50 else 51..55).map { number ->
                    ExamQuestion(
                        number,
                        "What is provided?",
                        "ABCD".map { ExamOption("$it", "Option $it") },
                        "A",
                        "local:test",
                    )
                },
            )
        },
    )

    @Test fun openedButUnansweredReadingIsStillUnstarted() {
        val paper = paper()
        assertTrue(paper.issues(CoachKind.READING).isEmpty())
        val draft = ExamCodec.practice(paper, CoachKind.READING)
        assertEquals(10, ExamCodec.passages(draft).sumOf { it.questions.size })
        assertFalse(ExamCodec.hasReadingProgress(draft))
        assertEquals(0, ExamCodec.readingAnswered(draft))
        assertEquals("未做", readingStatus(draft).label)
        assertEquals(Tone.Muted, readingStatus(draft).tone)
    }

    @Test fun partialAnswersCountOnlyQuestionsInTheSnapshot() {
        val draft = ExamCodec.practice(paper(), CoachKind.READING).copy(answer = "1. A\n46. B\n55. A")
        assertTrue(ExamCodec.hasReadingProgress(draft))
        assertEquals(2, ExamCodec.readingAnswered(draft))
        assertEquals("已录 2/10", readingStatus(draft).label)
        assertEquals(Tone.Better, readingStatus(draft).tone)
    }

    @Test fun openingAnEmptyRetryDoesNotChangeTheCompletedAttempt() {
        val submitted = ExamCodec.practice(paper(), CoachKind.READING).copy(
            answer = "46. A\n55. A",
            submittedAt = 100,
            updatedAt = 200,
        )
        val retry = submitted.retryCopy()
        assertFalse(ExamCodec.hasReadingProgress(retry))
        assertTrue(ExamCodec.hasReadingProgress(submitted))
        assertEquals("未做", readingStatus(retry).label)
        assertEquals("2/10", readingStatus(submitted).label)
        assertEquals("46. A\n55. A", submitted.answer)
        assertEquals(100L, submitted.submittedAt)
    }

    @Test fun legacySinglePassageCountsOnlyItsOwnAnswers() {
        val paper = paper()
        val draft = ExamCodec.practice(paper, CoachKind.READING, paper.passages.first())
            .copy(answer = "46. A\n55. B")
        assertEquals(1, ExamCodec.readingAnswered(draft))
        assertEquals("已录 1/5", readingStatus(draft).label)
    }

    @Test fun unrelatedAnswerMappingsDoNotStartTheExam() {
        val draft = ExamCodec.practice(paper(), CoachKind.READING).copy(answer = "1. A\n2. B")
        assertFalse(ExamCodec.hasReadingProgress(draft))
        assertEquals("未做", readingStatus(draft).label)
    }
}
