package com.example.cet6.data

import dev.bee.fsrs.FsrsEngine
import dev.bee.fsrs.FsrsMemoryState
import dev.bee.fsrs.FsrsRating

enum class WordRating(val value: Int, val label: String) {
    AGAIN(1, "不认识"), HARD(2, "模糊"), GOOD(3, "认识")
}

data class ScheduledWord(val card: WordEntity, val state: WordSchedule, val elapsedDays: Int)

/** Fixed FSRS-6 memory engine plus explicit application learning steps; no wall clock inside. */
object WordScheduler {
    const val DAY = 86_400_000L
    const val MINUTE = 60_000L
    const val LEARNING = 1
    const val REVIEW = 2
    const val RELEARNING = 3
    // Identity pins engine, default parameters (v6.3.1), retention and learning policy together.
    const val ALGORITHM = "fsrs6-py6.3.1-bee98f3ed75-fix1-r0.9-steps1,10-relearn1-v1"
    private val engine = FsrsEngine.latestDefault()

    fun next(card: WordEntity, previous: WordSchedule?, rating: WordRating, now: Long): ScheduledWord {
        require(now >= 0L) { "学习时间无效" }
        if (previous != null) {
            require(previous.algorithm == ALGORITHM && previous.word == card.word) { "不兼容的复习状态，请先备份数据并更新应用" }
            require(now >= previous.lastReviewedAt) { "系统时间早于上次学习，请校正时间后重试" }
            require(previous.phase in LEARNING..RELEARNING && previous.step in 0..1) { "复习状态无效" }
        }
        val elapsed = previous?.let { ((now - it.lastReviewedAt) / DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: 0
        val grade = FsrsRating.fromValue(rating.value)
        val memory = if (previous == null) engine.initialState(grade) else engine.nextState(
            FsrsMemoryState(previous.stability, previous.difficulty), grade, elapsed)
        var phase = previous?.phase ?: if (card.dueAt == 0L) LEARNING else REVIEW
        var step = previous?.step ?: 0
        val duration = when (phase) {
            LEARNING -> when (rating) {
                WordRating.AGAIN -> { step = 0; MINUTE }
                WordRating.HARD -> if (step == 0) 330_000L else 10 * MINUTE
                WordRating.GOOD -> if (step == 0) { step = 1; 10 * MINUTE } else {
                    phase = REVIEW; step = 0
                    engine.nextIntervalDays(memory.stability, 0.9, 36_500) * DAY
                }
            }
            RELEARNING -> when (rating) {
                WordRating.AGAIN -> MINUTE
                WordRating.HARD -> 90_000L
                WordRating.GOOD -> {
                    phase = REVIEW; step = 0
                    engine.nextIntervalDays(memory.stability, 0.9, 36_500) * DAY
                }
            }
            else -> if (rating == WordRating.AGAIN) { phase = RELEARNING; step = 0; MINUTE }
                else engine.nextIntervalDays(memory.stability, 0.9, 36_500) * DAY
        }
        val state = WordSchedule(card.word, memory.stability, memory.difficulty, now, phase, step, ALGORITHM)
        return ScheduledWord(card.copy(dueAt = Math.addExact(now, duration),
            intervalDays = (duration / DAY).toInt(),
            repetitions = if (rating == WordRating.AGAIN) 0 else card.repetitions + 1), state, elapsed)
    }

    fun intervalText(duration: Long): String = when {
        duration < MINUTE -> "不到 1 分钟"
        duration < DAY -> "${duration / MINUTE}${if (duration % MINUTE > 0) ".5" else ""} 分钟"
        else -> "${duration / DAY} 天"
    }
}
