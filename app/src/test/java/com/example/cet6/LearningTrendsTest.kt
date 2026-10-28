package com.example.cet6

import com.example.cet6.data.*
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class LearningTrendsTest {
    @Test fun weeksUseLocalDatesAndOnlySourcedReadingKeysWithExplicitDenominators() {
        val date = LocalDate.parse("2026-10-23")
        val at = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val paper = ExamPaper("sample-cet6-2026-06-13-1", "2026-06-13", "1", sourceName = "original",
            sourceUrl = "local:test", licenseStatus = "original", reviewed = true, sample = true,
            passages = (1..2).map { code -> ExamPassage("$code", "", "Original passage.",
                (if (code == 1) 46..50 else 51..55).map { n -> ExamQuestion(n, "Question $n",
                    "ABCD".map { ExamOption("$it", "Option $it") }, if (n == 46) "A" else "", if (n == 46) "local:key" else "") }) })
        val reading = ExamCodec.practice(paper, CoachKind.READING).copy(answer = "46. A", submittedAt = at)
        val revision = CoachPractice(kind = CoachKind.WRITING.name, sourcePracticeId = "previous", submittedAt = at)
        val draft = revision.copy(id = "draft", submittedAt = 0)
        val trend = LearningTrends.weeks(listOf(reading, revision, draft),
            listOf(DailyWordProgress(date.toString(), 1, 2, 2, 3)), date, ZoneOffset.UTC)
        val week = trend.weeks.last()
        assertEquals(LocalDate.parse("2026-10-19"), week.start)
        assertEquals(2, week.submissions); assertEquals(1, week.revisions)
        assertEquals(1, week.readingCorrect); assertEquals(1, week.readingKnown)
        assertEquals(2, week.remembered); assertEquals(3, week.wordRatings)
        assertEquals(0, trend.unreadableReading)
        assertEquals(8, trend.weeks.size)
    }
}
