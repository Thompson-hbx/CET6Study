package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class ReadingToolsTest {
    private val text = "1. What do libraries need?\nA. Quiet rooms.\nB. Noise.\n\n2. Why?\nA. To focus.\nB. To sleep."

    @Test fun parsesQuestionsAndPreservesChoiceText() {
        val questions = ReadingQuestions.parse(text)
        assertEquals(listOf("1", "2"), questions.map { it.number })
        assertEquals("Quiet rooms.", questions.first().choices.first().text)
        assertEquals("Why?", questions.last().text)
        assertEquals("To sleep.", questions.last().choices.last().text)
    }
    @Test fun ambiguousOrFreeTextStaysInManualMode() {
        assertTrue(ReadingQuestions.parse("Explain the author's attitude.").isEmpty())
        assertTrue(ReadingQuestions.parse("1. Why?\nA. first\nA. duplicate").isEmpty())
        assertTrue(ReadingQuestions.parse(text.replace("2. Why?", "1. Why?")).isEmpty())
        assertFalse(ReadingQuestions.canSelect("1. A because of the evidence"))
        assertFalse(ReadingQuestions.canSelect("1. A\n1. B"))
        assertTrue(runCatching { ReadingQuestions.choose("my explanation", "1", "A") }.isFailure)
    }
    @Test fun selectionsUpdateOnlyRequestedQuestionAndRemainOrdered() {
        assertEquals("1. B\n2. A", ReadingQuestions.choose("1. A\n2. A", "1", "B"))
        assertEquals("1. A\n2. B", ReadingQuestions.choose("2. B", "1", "A"))
        assertEquals("B", ReadingQuestions.selected("1. B\n2. A", "1"))
    }
    @Test fun evidenceMatchesWhitespaceButNeverInventsOrFuzzyMatches() {
        val material = "Intro. Libraries need\n  quiet rooms. End."
        val range = ReadingEvidence.find(material, "Libraries need quiet rooms.")!!
        assertEquals("Libraries need\n  quiet rooms.", material.substring(range))
        assertNull(ReadingEvidence.find(material, "Libraries need more quiet rooms."))
        assertNull(ReadingEvidence.find(material, ""))
        assertEquals(listOf("Libraries need quiet rooms."), ReadingEvidence.suggestions(material,
            "证据：“Libraries need quiet rooms.” 错误引用：“Libraries need more quiet rooms.”"))
    }
    @Test fun savedSentencesAreVerifiedAndDeduplicated() {
        val material = "Libraries need quiet rooms."
        val saved = ReadingEvidence.add(material, "", material)
        assertEquals(saved, ReadingEvidence.add(material, saved, material))
        assertTrue(runCatching { ReadingEvidence.add(material, saved, "invented quote") }.isFailure)
    }
}
