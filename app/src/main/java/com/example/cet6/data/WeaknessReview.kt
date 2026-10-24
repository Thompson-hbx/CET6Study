package com.example.cet6.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Counts user notes by submission date; never infers proficiency from model feedback. */
object WeaknessReview {
    fun choices(kind: CoachKind) = when (kind) {
        CoachKind.READING -> listOf("词汇理解", "长难句", "逻辑推断", "选项陷阱", "语法", "表达")
        CoachKind.WRITING -> listOf("审题", "论证", "衔接", "语法", "表达")
        CoachKind.TRANSLATION -> listOf("遗漏", "误译", "长难句", "语法", "表达")
    }
    val categories = CoachKind.entries.flatMap(::choices).distinct() + "其他笔记"

    fun categoriesOf(entry: CoachPractice): Set<String> {
        if (entry.errorReason.isBlank()) return emptySet()
        val parts = entry.errorReason.split(Regex("[；;，,\n]+" )).map(String::trim)
        val matched = categories.dropLast(1).filter { category ->
            parts.any { it == category || it.startsWith("$category：") || it.startsWith("$category:") }
        }.toSet()
        return matched.ifEmpty { setOf("其他笔记") }
    }

    fun records(history: List<CoachPractice>, category: String? = null): List<CoachPractice> = history
        .filter { it.submittedAt > 0 && it.errorReason.isNotBlank() &&
            (category == null || category in categoriesOf(it)) }
        .sortedByDescending { it.submittedAt }

    data class PeriodCounts(val recent: Int, val previous: Int)

    fun periods(records: List<CoachPractice>, today: LocalDate, zone: ZoneId): PeriodCounts {
        val dates = records.filter { it.submittedAt > 0 && it.errorReason.isNotBlank() }
            .map { Instant.ofEpochMilli(it.submittedAt).atZone(zone).toLocalDate() }
        return PeriodCounts(
            dates.count { it >= today.minusDays(6) && it <= today },
            dates.count { it >= today.minusDays(13) && it < today.minusDays(6) }
        )
    }
}
