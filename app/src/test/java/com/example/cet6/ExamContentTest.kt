package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class ExamContentTest {
    @Test fun writingReferenceNeedsItsOwnSourceWithoutBlockingOtherSubjects() {
        val p = complete().copy(referenceWriting = "Original reference essay.")
        assertTrue(p.issues(CoachKind.WRITING).contains("参考范文缺少来源"))
        assertTrue(p.issues(CoachKind.TRANSLATION).isEmpty())
        assertTrue(p.issues(CoachKind.READING).isEmpty())
        assertTrue(p.copy(referenceWritingSource = "local:original").issues().isEmpty())
    }
    private fun complete() = ExamPaper("cet6-2026-06-13-1", "2026-06-13", "1", sourceName = "User material", sourceUrl = "local:owned",
        licenseStatus = "personal-use", reviewed = true, passages = (1..2).map { code ->
            ExamPassage("$code", "Test", "Libraries offer quiet rooms.", (if (code == 1) 46..50 else 51..55).map { n ->
                ExamQuestion(n, "What is offered?", listOf("A", "B", "C", "D").map { ExamOption(it, "Option $it") }, "A", "local:key", "quiet rooms", "local:original-test-passage")
            }) }, writingPrompt = "Write about libraries", writingInstructions = "150–200 words", chineseText = "图书馆提供安静的空间。")
    @Test fun catalogContainsOnlyHeldSessionsAndStableSets() {
        assertEquals(8, ExamCatalog.sessions.size)
        assertEquals(24, ExamCatalog.papers().map { it.stableId }.distinct().size)
        assertTrue(ExamCatalog.sessions.any { it.date == "2023-03-12" })
        assertFalse(ExamCatalog.sessions.any { it.date.startsWith("2026-12") })
        ExamCatalog.papers().forEach { assertEquals("待导入", it.status) }
    }
    @Test fun completePackRequiresReviewAndRights() {
        assertTrue(complete().issues().isEmpty())
        assertEquals("待校对", complete().copy(reviewed = false).status)
        assertTrue(complete().copy(licenseStatus = "unknown").issues().contains("使用权未确认"))
    }
    @Test fun missingDuplicateNumbersAndOptionsAreNotRepaired() {
        val p = complete()
        val passage = p.passages.first()
        val q = passage.questions.first().copy(number = 47, options = listOf(ExamOption("A", "")))
        val invalid = p.copy(passages = listOf(passage.copy(questions = listOf(q) + passage.questions.drop(1))))
        assertTrue(invalid.issues().any { it.contains("题号") })
        assertTrue(invalid.issues().any { it.contains("选项") })
    }
    @Test fun evidenceAndAnswerMustHaveVerifiableProvenance() {
        val p = complete()
        val passage = p.passages.first()
        val q = passage.questions.first().copy(answerSource = "", evidence = "Invented quotation", referenceAnswer = "A/B")
        val errors = p.copy(passages = listOf(passage.copy(questions = listOf(q) + passage.questions.drop(1))) + p.passages.drop(1)).issues()
        assertTrue(errors.any { it.contains("来源") })
        assertTrue(errors.any { it.contains("证据") })
        assertTrue(errors.any { it.contains("冲突") })
    }
    @Test fun identityRejectsMixedAndFuturePapers() {
        assertTrue(runCatching { complete().copy(examDate = "2026-12-12").identityIssues() }.isFailure)
        assertTrue(runCatching { complete().copy(setCode = "2").identityIssues() }.isFailure)
        complete().identityIssues()
    }
    @Test fun markingUsesOnlySourcedKeysAndDoesNotRevealTheCorrectLetter() {
        val q = complete().passages.first().questions.first()
        assertEquals("与参考一致", ReadingGuidance.verdict(q, "A"))
        assertEquals("需要回看", ReadingGuidance.verdict(q, "B"))
        assertEquals("未录入", ReadingGuidance.verdict(q, ""))
        assertEquals("暂无可核实答案", ReadingGuidance.verdict(q.copy(answerSource = ""), "A"))
        assertEquals("暂无可核实答案", ReadingGuidance.verdict(q.copy(referenceAnswer = ""), "A"))
    }
    @Test fun paragraphHintsRequireExactEvidenceInTheOriginal() {
        val passage = complete().passages.first().copy(content = "First paragraph.\n\nSecond paragraph has quiet rooms.")
        val q = passage.questions.first()
        assertEquals(2, ReadingGuidance.paragraph(passage, q))
        assertNull(ReadingGuidance.paragraph(passage, q.copy(evidence = "Invented")))
    }
    @Test fun aMissingSubjectDoesNotBlockVerifiedReading() {
        val p = complete().copy(writingPrompt = "", chineseText = "")
        assertTrue(p.issues(CoachKind.READING).isEmpty())
        assertTrue(p.issues(CoachKind.WRITING).isNotEmpty())
        assertTrue(p.issues(CoachKind.TRANSLATION).isNotEmpty())
        assertEquals("待校对", p.status)
    }
}
