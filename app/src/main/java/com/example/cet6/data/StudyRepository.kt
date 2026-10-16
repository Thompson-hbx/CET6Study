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
    val schedules = dao.wordSchedules()

    suspend fun initialize() = dao.addWords(starterWords)

    suspend fun importWords(words: List<WordEntity>): Int = db.withTransaction {
        dao.addWords(words).count { it != -1L }
    }

    suspend fun plan(date: LocalDate): DailyPlan = db.withTransaction {
        dao.dailyPlan(date.toString()) ?: (dao.previousPlan(date.toString())?.copy(date = date.toString())
            ?: DailyPlan(date.toString())).also { dao.saveDailyPlan(it) }
    }

    suspend fun savePlan(plan: DailyPlan) {
        require(plan.newGoal in 1..100 && plan.reviewGoal in 1..200) { "新词目标 1–100，复习目标 1–200" }
        dao.saveDailyPlan(plan)
    }

    /** Re-read inside the transaction: stale UI/double taps must never reschedule a card twice. */
    suspend fun review(word: String, rating: WordRating, now: Long = System.currentTimeMillis()): Boolean =
        db.withTransaction {
            val current = dao.word(word) ?: return@withTransaction false
            if (current.dueAt > now) return@withTransaction false
            val scheduled = WordScheduler.next(current, dao.schedule(word), rating, now)
            val updated = scheduled.card
            val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            dao.recordReview(word, updated.dueAt, updated.repetitions, updated.intervalDays)
            dao.saveSchedule(scheduled.state)
            dao.addWordReview(WordReview(
                id = UUID.randomUUID().toString(), word = word, remembered = rating == WordRating.GOOD,
                wasNew = current.dueAt == 0L || dao.wasIntroducedOn(word, date),
                studiedOn = date, createdAt = now, nextDueAt = updated.dueAt,
                rating = rating.value, algorithm = WordScheduler.ALGORITHM,
                stability = scheduled.state.stability, difficulty = scheduled.state.difficulty,
                elapsedDays = scheduled.elapsedDays
            ))
            true
        }
}
