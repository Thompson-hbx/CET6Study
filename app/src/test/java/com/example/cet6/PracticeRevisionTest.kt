package com.example.cet6

import com.example.cet6.data.*
import com.example.cet6.ui.versionsOf
import org.junit.Assert.*
import org.junit.Test

class PracticeRevisionTest {
    private val source = CoachPractice(title = "题目", material = "原文", questions = "1. Q", answer = "1. A",
        feedback = "原版批改", errorReason = "语法", submittedAt = 100, updatedAt = 200, savedSentences = "原文")

    @Test fun readingRetryDoesNotRevealPreviousAnswer() {
        val retry = source.retryCopy()
        assertNotEquals(source.id, retry.id)
        assertEquals(source.id, retry.sourcePracticeId)
        assertEquals(source.material, retry.material)
        assertEquals(source.questions, retry.questions)
        assertTrue(retry.answer.isBlank() && retry.feedback.isBlank() && retry.errorReason.isBlank())
        assertEquals(0L, retry.submittedAt)
    }

    @Test fun writingAndTranslationRevisionPrefillWithoutChangingHistory() {
        for (kind in listOf(CoachKind.WRITING, CoachKind.TRANSLATION)) {
            val original = source.copy(kind = kind.name, practiceGoal = "练习主谓一致")
            val revision = original.retryCopy()
            assertEquals(original.answer, revision.answer)
            assertEquals(original.id, revision.sourcePracticeId)
            assertEquals(original.practiceGoal, revision.practiceGoal)
            assertTrue(revision.feedback.isBlank() && revision.savedSentences.isBlank())
            val edited = revision.copy(answer = "My revised answer.")
            assertNotEquals(original.answer, edited.answer)
            assertEquals("原版批改", original.feedback)
            assertEquals("1. A", original.answer)
        }
    }

    @Test fun selfReferencingPracticeIsRejected() {
        assertTrue(runCatching { CoachPrompts.validate(source.copy(sourcePracticeId = source.id)) }.isFailure)
    }

    @Test fun versionsOfKeepsRevisionsAndSeparatesTargetedMicroPractices() {
        val first = source.copy(id = "w-1", kind = CoachKind.WRITING.name, material = "Topic A", answer = "Draft 1", submittedAt = 100, updatedAt = 100)
        val second = first.retryCopy().copy(id = "w-2", answer = "Draft 2", submittedAt = 0, updatedAt = 200)
        val targeted = first.copy(id = "w-3", sourcePracticeId = first.id, material = "Topic B (Targeted)", practiceGoal = "语法", answer = "New task", submittedAt = 0, updatedAt = 300)
        val history = listOf(targeted, second, first)
        assertEquals(listOf("w-1", "w-2"), versionsOf(history, second).map { it.id })
        assertEquals(listOf("w-3"), versionsOf(history, targeted).map { it.id })
    }
}
