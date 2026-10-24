package com.example.cet6.data

import androidx.room.withTransaction

class BackupRepository(private val db: StudyDatabase) {
    private val dao = db.dao()
    private suspend fun readSnapshot() = StudyBackup(System.currentTimeMillis(), dao.allWords(), dao.allWordReviews(),
        dao.allSchedules(), dao.allPlans(), dao.allReading(), dao.allEssays(), dao.allCoach(), dao.allTurns())

    suspend fun snapshot(): StudyBackup = db.withTransaction { readSnapshot() }

    suspend fun restore(incoming: StudyBackup, adoptPlans: Boolean): RestoreSummary = db.withTransaction {
        // Re-read after user confirmation so learning since preview is preserved.
        val merge = BackupMerger.merge(readSnapshot(), incoming, adoptPlans)
        dao.saveWords(merge.words)
        merge.clearSchedules.forEach { dao.clearSchedule(it) }
        merge.schedules.forEach { dao.saveSchedule(it) }
        dao.addWordReviews(merge.reviews)
        merge.plans.forEach { dao.saveDailyPlan(it) }
        dao.addReadingAttempts(merge.reading)
        dao.addEssays(merge.essays)
        merge.coach.forEach { dao.saveCoach(it) }
        dao.addTurns(merge.turns)
        merge.summary
    }
}
