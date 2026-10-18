package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class BackupMergeTest {
    private val at = 1_800_000_000_000L
    private val word = WordEntity("allocate", "原释义", "Original sentence.")
    private fun empty() = StudyBackup(at, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun reviewed(rating: WordRating = WordRating.GOOD, time: Long = at): StudyBackup {
        val result = WordScheduler.next(word, null, rating, time)
        val log = WordReview("review-$time", word.word, rating == WordRating.GOOD, true, "2027-01-15", time,
            result.card.dueAt, rating.value, WordScheduler.ALGORITHM, result.state.stability, result.state.difficulty)
        return empty().copy(words = listOf(result.card), reviews = listOf(log), schedules = listOf(result.state))
    }
    @Test fun freshLibraryRestoresMemoryButKeepsExistingDefinition() {
        val fresh = empty().copy(words = listOf(word.copy(meaning = "本机自定义释义", source = "本人原创")))
        val source = reviewed()
        val result = BackupMerger.merge(fresh, source, false)
        assertEquals("本机自定义释义", result.words.single().meaning)
        assertEquals("本人原创", result.words.single().source)
        assertEquals(source.words.single().dueAt, result.words.single().dueAt)
        assertEquals(source.schedules, result.schedules)
        assertEquals(1, result.summary.updatedProgress)
    }
    @Test fun newerLearningWinsAndOlderBackupNeverRewindsIt() {
        val older = reviewed()
        val newer = reviewed(WordRating.AGAIN, at + 1000)
        assertTrue(BackupMerger.merge(newer, older, false).words.isEmpty())
        val result = BackupMerger.merge(older, newer, false)
        assertEquals(newer.words.single().dueAt, result.words.single().dueAt)
        assertEquals(newer.schedules.single(), result.schedules.single())
        assertEquals(1, result.reviews.size)
    }
    @Test fun repeatRestoreIsIdempotentAndLogConflictsRejectEverything() {
        val source = reviewed()
        val repeated = BackupMerger.merge(source, source, false)
        assertTrue(repeated.words.isEmpty() && repeated.reviews.isEmpty() && repeated.schedules.isEmpty())
        val conflicting = source.copy(reviews = source.reviews.map { it.copy(wasNew = false) })
        assertTrue(runCatching { BackupMerger.merge(source, conflicting, false) }.isFailure)
    }
    @Test fun oldUntimestampedProgressIsKeptAndReported() {
        val local = empty().copy(words = listOf(word.copy(dueAt = at + WordScheduler.DAY, repetitions = 5, intervalDays = 30)))
        val result = BackupMerger.merge(local, reviewed(), false)
        assertTrue(result.words.isEmpty())
        assertEquals(1, result.summary.keptUnknownProgress)
        assertEquals(1, result.reviews.size)
    }
    @Test fun autoIdsDoNotOverwriteOtherReadingOrEssaysAndMultiplicityIsPreserved() {
        val original = ReadingAttempt(1, "question", "B", true, at)
        val essay = EssayEntity(1, "", "draft", "feedback", at)
        val local = empty().copy(reading = listOf(original.copy(answer = "A")), essays = listOf(essay.copy(content = "local draft")))
        val source = empty().copy(reading = listOf(original, original.copy(id = 2)), essays = listOf(essay, essay.copy(id = 2)))
        val result = BackupMerger.merge(local, source, false)
        assertEquals(2, result.reading.size)
        assertEquals(2, result.essays.size)
        assertTrue(result.reading.all { it.id == 0L } && result.essays.all { it.id == 0L })
        val after = local.copy(reading = local.reading + source.reading.map { it.copy(id = it.id + 10) },
            essays = local.essays + source.essays.map { it.copy(id = it.id + 10) })
        assertTrue(BackupMerger.merge(after, source, false).reading.isEmpty())
        assertTrue(BackupMerger.merge(after, source, false).essays.isEmpty())
    }
    @Test fun targetsRequireExplicitChoiceToReplaceExistingDates() {
        val local = empty().copy(plans = listOf(DailyPlan("2026-10-16", 3, 5)))
        val source = empty().copy(plans = listOf(DailyPlan("2026-10-16", 10, 20), DailyPlan("2026-10-15", 7, 15)))
        assertEquals(1, BackupMerger.merge(local, source, false).plans.size)
        assertEquals(2, BackupMerger.merge(local, source, true).plans.size)
    }
    @Test fun rejectsMissingWordsInvalidMemoryAndMismatchedDueDates() {
        val source = reviewed()
        listOf(source.copy(words = emptyList()), source.copy(schedules = source.schedules.map { it.copy(stability = Double.NaN) }),
            source.copy(words = source.words.map { it.copy(dueAt = it.dueAt + 1) }),
            source.copy(schedules = source.schedules.map { it.copy(algorithm = "other") })).forEach {
            assertTrue(runCatching { BackupValidation.validate(it) }.isFailure)
        }
    }
}
