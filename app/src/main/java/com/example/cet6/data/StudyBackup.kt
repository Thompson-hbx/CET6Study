package com.example.cet6.data

import dev.bee.fsrs.FsrsMemoryState
import java.time.LocalDate

data class StudyBackup(
    val createdAt: Long,
    val words: List<WordEntity>,
    val reviews: List<WordReview>,
    val schedules: List<WordSchedule>,
    val plans: List<DailyPlan>,
    val reading: List<ReadingAttempt>,
    val essays: List<EssayEntity>,
    val coach: List<CoachPractice> = emptyList(),
    val turns: List<CoachTurn> = emptyList()
)

object BackupValidation {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_TIME = 253_402_300_799_999L
    fun validate(data: StudyBackup) {
        require(data.createdAt in 0..MAX_TIME) { "备份时间无效" }
        require(data.words.size <= 20_000 && data.reviews.size <= 100_000 && data.schedules.size <= 20_000 &&
            data.plans.size <= 20_000 && data.reading.size <= 100_000 && data.essays.size <= 20_000 && data.coach.size <= 20_000 &&
            data.turns.size <= 200_000) { "备份记录数量超限" }
        fun <T> unique(rows: List<T>, key: (T) -> Any) {
            require(rows.map(key).toSet().size == rows.size) { "备份包含重复标识" }
        }
        unique(data.words) { it.word }; unique(data.reviews) { it.id }; unique(data.schedules) { it.word }
        unique(data.plans) { it.date }; unique(data.reading) { it.id }; unique(data.essays) { it.id }
        unique(data.coach) { it.id }; unique(data.turns) { it.id }
        data.coach.forEach {
            CoachPrompts.validate(it)
            require(it.id.length in 1..100 && it.model.length <= 200 && it.updatedAt in 0..MAX_TIME &&
                it.submittedAt in 0..it.updatedAt) { "训练标识或时间无效" }
        }
        val practices = data.coach.associateBy { it.id }
        val practiceIds = practices.keys
        data.turns.forEach {
            CoachTurns.validate(it)
            require(it.practiceId in practiceIds && it.createdAt in 0..MAX_TIME) { "对话记录引用或时间无效" }
            require(it.locatedQuote.isEmpty() || ReadingLocation.restored(practices.getValue(it.practiceId), it) != null) {
                "保存的 AI 定位无法在对应题目原文中核实"
            }
        }
        val words = data.words.associateBy { it.word }
        val wordPattern = Regex("[a-z]+(?:[ '-][a-z]+)*")
        data.words.forEach {
            require(it.word.length in 1..100 && it.word.matches(wordPattern) && it.meaning.length in 1..1000 &&
                it.example.length <= 2000 && it.source.length in 1..300) { "词条内容无效或过长" }
            require(it.dueAt in 0..MAX_TIME && it.repetitions >= 0 && it.intervalDays in 0..36_500) { "词条进度无效" }
        }
        data.plans.forEach {
            require(LocalDate.parse(it.date).toString() == it.date && it.newGoal in 1..100 && it.reviewGoal in 1..200) { "每日目标无效" }
        }
        val reviewsByWord = data.reviews.groupBy { it.word }
        data.reviews.forEach {
            require(it.id.length in 1..100 && it.word in words && it.createdAt in 0..MAX_TIME &&
                it.nextDueAt in (it.createdAt + 1)..MAX_TIME && it.elapsedDays >= 0) { "学习日志时间或引用无效" }
            require(LocalDate.parse(it.studiedOn).toString() == it.studiedOn) { "学习日志日期无效" }
            if (it.algorithm == "legacy") require(it.rating == 0 && it.stability == 0.0 && it.difficulty == 0.0) { "旧版日志字段冲突" }
            else {
                require(it.algorithm == WordScheduler.ALGORITHM && it.rating in 1..3 && it.remembered == (it.rating == 3)) { "复习算法或评分不兼容" }
                FsrsMemoryState(it.stability, it.difficulty)
            }
        }
        data.schedules.forEach { state ->
            val word = words[state.word]
            require(word != null && state.algorithm == WordScheduler.ALGORITHM && state.lastReviewedAt in 0..MAX_TIME &&
                state.phase in 1..3 && state.step in 0..1 && (state.phase == WordScheduler.LEARNING || state.step == 0)) { "FSRS 状态无效或不兼容" }
            FsrsMemoryState(state.stability, state.difficulty)
            require(reviewsByWord[state.word].orEmpty().any {
                it.createdAt == state.lastReviewedAt && it.algorithm == state.algorithm && it.stability == state.stability &&
                    it.difficulty == state.difficulty && it.nextDueAt == word.dueAt
            }) { "FSRS 状态、学习日志与到期时间不一致" }
        }
        data.reading.forEach {
            require(it.id > 0 && it.questionId.length in 1..200 && it.answer.length in 1..1000 && it.createdAt in 0..MAX_TIME) { "阅读记录无效" }
        }
        data.essays.forEach {
            require(it.id > 0 && it.topic.length <= 1000 && it.content.length in 1..100_000 &&
                it.feedback.length <= 200_000 && it.createdAt in 0..MAX_TIME) { "作文记录无效或过长" }
        }
    }
}

data class RestoreSummary(
    val newWords: Int, val updatedProgress: Int, val keptUnknownProgress: Int,
    val newReviews: Int, val writtenPlans: Int, val newReading: Int, val newEssays: Int,
    val newCoach: Int = 0, val newTurns: Int = 0
) {
    fun message() = "新增词汇 $newWords，更新进度 $updatedProgress，新增学习日志 $newReviews、阅读 $newReading、作文 $newEssays、G3 训练 $newCoach、讲解与对话 $newTurns、目标 $writtenPlans。" +
        if (keptUnknownProgress > 0) "另有 $keptUnknownProgress 个旧版进度无法比较时间，已保留本机。" else ""
}

data class BackupMerge(
    val words: List<WordEntity>, val schedules: List<WordSchedule>, val clearSchedules: List<String>,
    val reviews: List<WordReview>, val plans: List<DailyPlan>, val reading: List<ReadingAttempt>,
    val essays: List<EssayEntity>, val summary: RestoreSummary,
    val coach: List<CoachPractice> = emptyList(), val turns: List<CoachTurn> = emptyList()
)

/** Pure merge decisions; database writes are applied later as one transaction. */
object BackupMerger {
    fun merge(current: StudyBackup, incoming: StudyBackup, adoptPlans: Boolean): BackupMerge {
        BackupValidation.validate(current); BackupValidation.validate(incoming)
        val currentWords = current.words.associateBy { it.word }
        val currentSchedules = current.schedules.associateBy { it.word }
        val incomingSchedules = incoming.schedules.associateBy { it.word }
        val currentReviews = current.reviews.associateBy { it.id }
        incoming.reviews.forEach { review ->
            require(currentReviews[review.id]?.let { it == review } != false) { "相同学习日志标识内容冲突，未恢复任何数据" }
        }
        val currentByWord = current.reviews.groupBy { it.word }
        val incomingByWord = incoming.reviews.groupBy { it.word }
        fun stamp(word: WordEntity, states: Map<String, WordSchedule>, logs: Map<String, List<WordReview>>): Long? =
            states[word.word]?.lastReviewedAt ?: logs[word.word].orEmpty().filter { it.nextDueAt == word.dueAt }.maxOfOrNull { it.createdAt }
        val words = mutableListOf<WordEntity>()
        val schedules = mutableListOf<WordSchedule>()
        val clears = mutableListOf<String>()
        var added = 0; var updated = 0; var unknown = 0
        incoming.words.forEach { word ->
            val local = currentWords[word.word]
            val incomingStamp = stamp(word, incomingSchedules, incomingByWord)
            val localStamp = local?.let { stamp(it, currentSchedules, currentByWord) }
            val take = local == null || (local.dueAt == 0L && word.dueAt != 0L) ||
                (incomingStamp != null && localStamp != null && incomingStamp > localStamp)
            if (local != null && local.dueAt != 0L && localStamp == null && word != local) unknown++
            if (take) {
                val restored = local?.copy(dueAt = word.dueAt, repetitions = word.repetitions, intervalDays = word.intervalDays) ?: word
                if (restored != local) { words += restored; if (local == null) added++ else updated++ }
                val memory = incomingSchedules[word.word]
                if (memory != null && memory != currentSchedules[word.word]) schedules += memory
                if (memory == null && currentSchedules[word.word] != null) clears += word.word
            }
        }
        val reviews = incoming.reviews.filter { it.id !in currentReviews }
        val currentPlans = current.plans.associateBy { it.date }
        val plans = incoming.plans.filter { currentPlans[it.date] == null || (adoptPlans && currentPlans[it.date] != it) }
        fun <T> missing(existing: List<T>, restored: List<T>): List<T> {
            val counts = existing.groupingBy { it }.eachCount().toMutableMap()
            return restored.filter { item ->
                val count = counts[item] ?: 0
                if (count > 0) { counts[item] = count - 1; false } else true
            }
        }
        val reading = missing(current.reading.map { it.copy(id = 0) }, incoming.reading.map { it.copy(id = 0) })
        val essays = missing(current.essays.map { it.copy(id = 0) }, incoming.essays.map { it.copy(id = 0) })
        val currentCoach = current.coach.associateBy { it.id }
        incoming.coach.forEach {
            require(currentCoach[it.id]?.let { local -> local == it } != false) { "相同训练标识内容冲突，未恢复任何数据；本机练习已保留" }
        }
        val coach = incoming.coach.filter { it.id !in currentCoach }
        val currentTurns = current.turns.associateBy { it.id }
        incoming.turns.forEach {
            require(currentTurns[it.id]?.let { local ->
                local == it || (it.locatedQuote.isEmpty() && local.copy(locatedQuote = "") == it)
            } != false) { "相同对话标识内容冲突，未恢复任何数据；本机记录已保留" }
        }
        val turns = incoming.turns.filter { it.id !in currentTurns }
        return BackupMerge(words, schedules, clears, reviews, plans, reading, essays,
            RestoreSummary(added, updated, unknown, reviews.size, plans.size, reading.size, essays.size, coach.size, turns.size), coach, turns)
    }
}
