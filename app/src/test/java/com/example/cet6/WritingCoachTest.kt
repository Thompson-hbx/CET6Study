package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class WritingCoachTest {
    private fun entry(kind: CoachKind) = CoachPractice(kind = kind.name,
        material = "原创测试要求：讨论图书馆。", answer = "Libraries helps students.")

    @Test fun specialtyCannotBeAppliedToOtherKind() {
        assertTrue(runCatching { CoachPrompts.build(entry(CoachKind.TRANSLATION), WritingFocus.STRUCTURE) }.isFailure)
        assertTrue(runCatching { CoachPrompts.build(entry(CoachKind.WRITING), WritingFocus.COVERAGE) }.isFailure)
        assertTrue(WritingFocus.forKind(CoachKind.READING).isEmpty())
    }

    @Test fun focusedPromptRetainsOriginalAndLimitsRewriting() {
        val original = entry(CoachKind.WRITING)
        val prompt = CoachPrompts.build(original, WritingFocus.STRUCTURE)
        assertTrue(prompt.contains(original.material) && prompt.contains(original.answer))
        assertTrue(prompt.contains("不代写全文"))
        assertFalse(prompt.contains(WritingFocus.COMPLETE.instruction))
        assertEquals("Libraries helps students.", original.answer)
    }

    @Test fun translationSeparatesFidelityFromStyle() {
        val prompt = CoachPrompts.build(entry(CoachKind.TRANSLATION), WritingFocus.COVERAGE)
        assertTrue(prompt.contains("遗漏") && prompt.contains("不擅自补充事实"))
        assertTrue(prompt.contains("参考译文不是唯一答案"))
        assertTrue(prompt.contains("不能编造用户写过的句子"))
    }
}
