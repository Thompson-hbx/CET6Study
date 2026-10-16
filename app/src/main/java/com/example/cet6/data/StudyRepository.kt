package com.example.cet6.data

import androidx.room.withTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** All local learning writes pass through transactions; OAuth storage is independent. */
class StudyRepository(private val db: StudyDatabase) {
    private val dao = db.dao()
    val words = dao.words()
    val wordReviews = dao.wordReviewHistory()
    val dailyProgress = dao.dailyWordProgress()

    suspend fun initialize() = dao.addWords(starterWords)

    suspend fun plan(date: LocalDate): DailyPlan = db.withTransaction {
        dao.dailyPlan(date.toString()) ?: (dao.previousPlan(date.toString())?.copy(date = date.toString())
            ?: DailyPlan(date.toString())).also { dao.saveDailyPlan(it) }
    }

    suspend fun savePlan(plan: DailyPlan) {
        require(plan.newGoal in 1..100 && plan.reviewGoal in 1..200) { "新词目标 1–100，复习目标 1–200" }
        dao.saveDailyPlan(plan)
    }

    /** Re-read inside the transaction: stale UI/double taps must never reschedule a card twice. */
    suspend fun review(word: String, remembered: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        db.withTransaction {
            val current = dao.word(word) ?: return@withTransaction false
            if (current.dueAt > now) return@withTransaction false
            val updated = nextReview(current, remembered, now)
            dao.recordReview(word, updated.dueAt, updated.repetitions, updated.intervalDays)
            dao.addWordReview(WordReview(
                id = UUID.randomUUID().toString(), word = word, remembered = remembered,
                wasNew = current.dueAt == 0L,
                studiedOn = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                createdAt = now, nextDueAt = updated.dueAt
            ))
            true
        }
}
