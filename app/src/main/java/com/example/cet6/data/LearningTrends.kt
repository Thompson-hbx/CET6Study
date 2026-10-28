package com.example.cet6.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class LearningWeek(val start: LocalDate, val submissions: Int, val revisions: Int,
    val readingCorrect: Int, val readingKnown: Int, val remembered: Int, val wordRatings: Int)
data class LearningTrend(val weeks: List<LearningWeek>, val unreadableReading: Int)

/** Counts observed actions and sourced answer agreement; never infers a learning effect from AI scores. */
object LearningTrends {
    fun weeks(history: List<CoachPractice>, daily: List<DailyWordProgress>, today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()): LearningTrend {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var unreadable = 0
        val result = (7 downTo 0).map { offset ->
            val start = monday.minusWeeks(offset.toLong())
            val end = minOf(start.plusDays(7), today.plusDays(1))
            val attempts = history.filter { it.submittedAt > 0 }.filter {
                val date = Instant.ofEpochMilli(it.submittedAt).atZone(zone).toLocalDate()
                date >= start && date < end
            }
            var known = 0; var correct = 0
            attempts.filter { it.kind == CoachKind.READING.name }.forEach { entry ->
                val passages = try { ExamCodec.passages(entry) } catch (_: IllegalArgumentException) {
                    unreadable++; emptyList()
                } catch (_: org.json.JSONException) { unreadable++; emptyList() }
                passages.flatMap { it.questions }.filter {
                    it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank()
                }.forEach {
                    known++
                    if (ReadingQuestions.selected(entry.answer, "${it.number}") == it.referenceAnswer) correct++
                }
            }
            val ratings = daily.filter { LocalDate.parse(it.date) >= start && LocalDate.parse(it.date) < end }
            LearningWeek(start, attempts.size, attempts.count {
                it.kind in setOf(CoachKind.WRITING.name, CoachKind.TRANSLATION.name) && it.sourcePracticeId.isNotBlank()
            }, correct, known, ratings.sumOf { it.rememberedCount }, ratings.sumOf { it.totalCount })
        }
        return LearningTrend(result, unreadable)
    }
}
