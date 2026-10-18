package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class CoachPracticeTest {
    private fun reading() = CoachPractice(material = "Libraries need both digital resources and quiet rooms.",
        questions = "1. What should universities maintain?\nA. Both\nB. Neither", answer = "1. A")

    @Test fun readingRequiresIndependentAnswerAndQuestions() {
        assertTrue(runCatching { CoachPrompts.build(reading().copy(answer = "")) }.isFailure)
        assertTrue(runCatching { CoachPrompts.build(reading().copy(questions = "")) }.isFailure)
        assertTrue(runCatching { CoachPrompts.build(reading().copy(material = "")) }.isFailure)
        val prompt = CoachPrompts.build(reading())
        assertTrue(prompt.contains(reading().material) && prompt.contains("1. A"))
        assertTrue(prompt.contains("证据不足") && prompt.contains("AI 推断"))
    }

    @Test fun writingAndTranslationNeedNoReadingQuestions() {
        val writing = CoachPrompts.build(reading().copy(kind = CoachKind.WRITING.name, questions = ""))
        val translation = CoachPrompts.build(reading().copy(kind = CoachKind.TRANSLATION.name, questions = ""))
        assertTrue(writing.contains("建议修改") && writing.contains("模拟参考"))
        assertTrue(translation.contains("参考译文") && translation.contains("遗漏"))
    }

    @Test fun oversizedPracticeIsRejectedBeforeNetworkOrPersistence() {
        assertTrue(runCatching { CoachPrompts.build(reading().copy(material = "x".repeat(60_001))) }.isFailure)
        assertTrue(runCatching { CoachPrompts.validate(reading().copy(kind = "unknown")) }.isFailure)
    }

    @Test fun backupPreservesCoachHistoryAndRejectsConflictingIds() {
        val entry = reading().copy(feedback = "原文依据", errorReason = "逻辑推断", submittedAt = 100, updatedAt = 200)
        val empty = StudyBackup(300, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val source = empty.copy(coach = listOf(entry))
        assertEquals(entry, BackupMerger.merge(empty, source, false).coach.single())
        assertTrue(BackupMerger.merge(source, source, false).coach.isEmpty())
        assertTrue(runCatching { BackupMerger.merge(source, source.copy(coach = listOf(entry.copy(answer = "changed"))), false) }.isFailure)
        assertEquals("1. A", source.coach.single().answer)
    }
}
