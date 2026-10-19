package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class ReadingDiagnosisTest {
    private val passage = ExamPassage("1", "Quiet hours", "Students feared losing group time.\n\nThe library kept rooms for collaboration.",
        listOf(ExamQuestion(46, "What did students fear?", listOf(
            ExamOption("A", "Higher book prices."), ExamOption("B", "Less time to collaborate."),
            ExamOption("C", "Longer opening hours."), ExamOption("D", "Fewer books.")),
            "B", "Original answer key", "Students feared losing group time.")))

    @Test fun diagnosisSeesActualWrongChoiceAndNumberedPassage() {
        val prompt = ReadingDiagnosis.prompt(passage, "46. A", 46)
        assertTrue(prompt.contains("我的选项：A. Higher book prices."))
        assertTrue(prompt.contains("第 2 段：\nThe library kept rooms for collaboration."))
        assertTrue(prompt.contains("B；来源：Original answer key"))
        assertFalse(prompt.contains("每次最多约 200 字"))
        val changed = ReadingDiagnosis.prompt(passage, "46. C", 46)
        assertTrue(changed.contains("我的选项：C. Longer opening hours."))
        assertFalse(changed.contains("我的选项：A."))
    }

    @Test fun unsourcedReferenceAndInventedEvidenceAreNotSentAsFacts() {
        val q = passage.questions.first().copy(answerSource = "", evidence = "Made up evidence.")
        val prompt = ReadingDiagnosis.prompt(passage.copy(questions = listOf(q)), "", 46)
        assertTrue(prompt.contains("暂无有来源的参考答案"))
        assertTrue(prompt.contains("我的选项：未作答"))
        assertFalse(prompt.contains("Made up evidence."))
        assertTrue(runCatching { ReadingDiagnosis.prompt(passage, "46. A", 99) }.isFailure)
        assertTrue(runCatching { ReadingDiagnosis.prompt(passage, "46. A", 46, "x".repeat(2001)) }.isFailure)
    }

    @Test fun savedDiagnosisIsIsolatedFromOtherQuestionsAndLegacyHints() {
        val history = ReadingDiagnosis.heading(46) + "\n\nFirst diagnosis.\n\n" +
            "## 第 47 题 · 第 3 层提示\n\nOther hint.\n\n" +
            ReadingDiagnosis.heading(47) + "\n\nOther diagnosis."
        assertEquals("First diagnosis.", ReadingDiagnosis.saved(history, 46))
        assertEquals("Other diagnosis.", ReadingDiagnosis.saved(history, 47))
        assertEquals("", ReadingDiagnosis.saved(history, 48))
        val continued = history + "\n\n" + ReadingDiagnosis.heading(46) + "\n\nLatest reply."
        assertEquals("Latest reply.", ReadingDiagnosis.saved(continued, 46))
        assertTrue(ReadingDiagnosis.prompt(passage, "46. A", 46, "Why not prices?", "First diagnosis.").contains("Why not prices?"))
    }

    @Test fun originalParagraphLookupDoesNotInventOrBlurEvidence() {
        val paragraphs = ReadingDiagnosis.paragraphs("First.\r\n\r\nSecond paragraph has quiet rooms.")
        assertEquals(2, paragraphs.size)
        assertEquals(listOf("Second paragraph has quiet rooms."), ReadingEvidence.suggestions(paragraphs.joinToString("\n\n"),
            "证据：“Second paragraph has quiet rooms.” 虚构：“Second paragraph has more books.”"))
        assertEquals(2, ReadingEvidence.quotations("证据：“Second paragraph has quiet rooms.” 虚构：“Second paragraph has more books.”").size)
    }
}
