package com.example.cet6.data

import androidx.room.withTransaction
import com.example.cet6.sync.SyncDocument

/** Sync applies only after comparing a fresh Room snapshot inside the write transaction. */
class DeviceSyncRepository(private val db: StudyDatabase) {
    private val dao = db.dao()
    private suspend fun read() = StudyBackup(0, dao.allWords(), dao.allWordReviews(), dao.allSchedules(),
        dao.allPlans(), dao.allReading(), dao.allEssays(), dao.allCoach(), dao.allTurns())
    private fun document(data: StudyBackup) = SyncDocument.fromBackup(BackupCodec.encode(data))

    suspend fun snapshot(): SyncDocument = db.withTransaction { document(read()) }

    suspend fun apply(expected: SyncDocument, incoming: SyncDocument): Boolean = db.withTransaction {
        val current = read()
        if (document(current) != expected) return@withTransaction false
        if (incoming == expected) return@withTransaction true
        val next = BackupCodec.decode(incoming.backup())
        require(incoming.records.keys.containsAll(expected.records.keys)) { "同步不能删除本机学习记录" }
        val reviews = current.reviews.associateBy { it.id }
        next.reviews.forEach { require(reviews[it.id]?.let { old -> old == it } != false) { "同标识学习日志发生冲突" } }
        // Choosing a word's other schedule must still retain every append-only review event.
        require(next.reviews.map { it.id }.containsAll(reviews.keys)) { "同步不能移除学习日志" }
        val currentWords = current.words.associateBy { it.word }
        dao.saveWords(next.words.filter { currentWords[it.word] != it })
        val schedules = next.schedules.associateBy { it.word }
        val currentSchedules = current.schedules.associateBy { it.word }
        current.schedules.filter { it.word !in schedules }.forEach { dao.clearSchedule(it.word) }
        next.schedules.filter { currentSchedules[it.word] != it }.forEach { dao.saveSchedule(it) }
        dao.addWordReviews(next.reviews.filter { it.id !in reviews })
        val plans = current.plans.associateBy { it.date }
        val coach = current.coach.associateBy { it.id }
        val turns = current.turns.associateBy { it.id }
        next.plans.filter { plans[it.date] != it }.forEach { dao.saveDailyPlan(it) }
        next.coach.filter { coach[it.id] != it }.forEach { dao.saveCoach(it) }
        next.turns.filter { turns[it.id] != it }.forEach { dao.saveTurn(it) }
        fun <T> missing(old: List<T>, rows: List<T>): List<T> {
            val counts = old.groupingBy { it }.eachCount().toMutableMap()
            return rows.filter { row ->
                val count = counts[row] ?: 0
                if (count == 0) true else { counts[row] = count - 1; false }
            }
        }
        dao.addReadingAttempts(missing(current.reading.map { it.copy(id = 0) }, next.reading.map { it.copy(id = 0) }))
        dao.addEssays(missing(current.essays.map { it.copy(id = 0) }, next.essays.map { it.copy(id = 0) }))
        true
    }
}
