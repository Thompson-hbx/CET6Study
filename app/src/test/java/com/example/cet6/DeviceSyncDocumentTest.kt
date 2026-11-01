package com.example.cet6

import com.example.cet6.data.*
import com.example.cet6.sync.*
import org.junit.Assert.*
import org.junit.Test

class DeviceSyncDocumentTest {
    @Test fun learningBackupRoundTripPreservesAllFieldsAndIgnoresLocalLegacyIds() {
        val practice = CoachPractice(id = "original", kind = "WRITING", title = "Original sample",
            material = "Write about learning.", answer = "An original answer.", updatedAt = 100)
        val turn = CoachTurn(id = "reply", practiceId = practice.id, anchor = "review", kind = TurnKind.CHAT,
            role = CoachTurn.ASSISTANT, content = "Original feedback.", createdAt = 100)
        val backup = StudyBackup(100, listOf(WordEntity("learn", "学习", "We learn.", source = "原创")),
            emptyList(), emptyList(), listOf(DailyPlan("2026-10-31")),
            listOf(ReadingAttempt(91, "original-reading", "B", true, 100)),
            listOf(EssayEntity(72, "Original topic", "Original answer.", "Original feedback.", 100)), listOf(practice), listOf(turn))
        val document = SyncDocument.fromBackup(BackupCodec.encode(backup))
        val restored = BackupCodec.decode(document.backup())
        assertEquals(backup.coach, restored.coach)
        assertEquals(backup.turns, restored.turns)
        assertEquals(backup.words, restored.words)
        assertEquals(backup.plans, restored.plans)
        assertEquals(backup.reading.map { it.copy(id = 0) }, restored.reading.map { it.copy(id = 0) })
        val renumbered = backup.copy(createdAt = 999, reading = backup.reading.map { it.copy(id = 1) },
            essays = backup.essays.map { it.copy(id = 2) })
        assertEquals(document, SyncDocument.fromBackup(BackupCodec.encode(renumbered)))
    }

    @Test fun fsrsProgressAndReviewHistoryRemainOneAtomicSyncRecord() {
        val first = WordScheduler.next(WordEntity("learn", "学习", "We learn.", source = "原创"), null, WordRating.GOOD, 1_800_000_000_000L)
        val word = first.card
        val review = WordReview("review", "learn", true, true, "2026-10-31", first.state.lastReviewedAt,
            first.card.dueAt, 3, first.state.algorithm, first.state.stability, first.state.difficulty, 0)
        val backup = StudyBackup(100, listOf(word), listOf(review), listOf(first.state.copy(word = "learn")),
            emptyList(), emptyList(), emptyList())
        val document = SyncDocument.fromBackup(BackupCodec.encode(backup))
        assertEquals(1, document.records.size)
        val restored = BackupCodec.decode(document.backup())
        assertEquals(backup.words, restored.words)
        assertEquals(backup.schedules, restored.schedules)
        assertEquals(backup.reviews, restored.reviews)
    }
}
