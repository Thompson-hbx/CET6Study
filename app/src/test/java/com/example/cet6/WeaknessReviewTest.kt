package com.example.cet6

import com.example.cet6.data.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WeaknessReviewTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 18)
    private fun note(day: LocalDate, reason: String = "语法") = CoachPractice(errorReason = reason,
        submittedAt = day.atStartOfDay(zone).toInstant().toEpochMilli())

    @Test fun classifiesExplicitNotesWithoutInferringFromFeedback() {
        val entry = note(today, "语法：主谓一致；表达: 搭配；语法")
        assertEquals(setOf("语法", "表达"), WeaknessReview.categoriesOf(entry))
        assertEquals(emptySet<String>(), WeaknessReview.categoriesOf(entry.copy(errorReason = "", feedback = "语法错误")))
        assertEquals(setOf("其他笔记"), WeaknessReview.categoriesOf(note(today, "没有语法问题，只需回顾段落结构")))
    }

    @Test fun excludesDraftsAndBlankNotesAndOrdersBySubmission() {
        val older = note(today.minusDays(2))
        val newer = note(today, "长难句")
        val input = listOf(older, newer, note(today).copy(submittedAt = 0), note(today, "  "))
        assertEquals(listOf(newer, older), WeaknessReview.records(input))
        assertEquals(listOf(older), WeaknessReview.records(input, "语法"))
    }

    @Test fun specialtyNotesAreCountedAndLegacyCategoriesRemainAvailable() {
        assertEquals(setOf("遗漏", "误译", "语法"), WeaknessReview.categoriesOf(note(today, "遗漏：第二句；误译：主语；语法")))
        assertEquals(setOf("审题", "论证"), WeaknessReview.categoriesOf(note(today, "审题；论证：例证不支持观点")))
        assertTrue("选项陷阱" in WeaknessReview.categories)
        assertFalse("选项陷阱" in WeaknessReview.choices(CoachKind.WRITING))
        assertEquals(setOf("其他笔记"), WeaknessReview.categoriesOf(note(today, "没有遗漏或误译")))
    }

    @Test fun usesLocalCalendarBoundariesAndExcludesFutureDates() {
        val records = listOf(0L, 6L, 7L, 13L, 14L, -1L).map { note(today.minusDays(it)) }
        assertEquals(WeaknessReview.PeriodCounts(2, 2), WeaknessReview.periods(records, today, zone))
        val immediatelyBeforeMidnight = note(today).copy(submittedAt = today.atStartOfDay(zone).toInstant().toEpochMilli() - 1)
        assertEquals(WeaknessReview.PeriodCounts(0, 1),
            WeaknessReview.periods(listOf(immediatelyBeforeMidnight), today.plusDays(6), zone))
    }

    @Test fun editsToOldNotesDoNotPretendToBeRecentSubmissions() {
        val old = note(today.minusDays(20)).copy(updatedAt = today.atStartOfDay(zone).toInstant().toEpochMilli())
        assertEquals(WeaknessReview.PeriodCounts(0, 0), WeaknessReview.periods(listOf(old), today, zone))
        assertEquals(listOf(old), WeaknessReview.records(listOf(old), "语法"))
    }
}
