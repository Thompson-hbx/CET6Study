package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class SequentialAnswersTest {
    private fun questions(start: Int) = (start..start + 4).map { ExamQuestion(it, "Original sample", emptyList()) }

    @Test fun fiveLettersMapToEachPassageInOrder() {
        val first = SequentialAnswers.apply("", "BCADB", questions(46))
        val both = SequentialAnswers.apply(first, "DACBA", questions(51))
        assertEquals("BCADB", SequentialAnswers.sequence(both, questions(46)))
        assertEquals("DACBA", SequentialAnswers.sequence(both, questions(51)))
        assertEquals("B", ReadingQuestions.selected(both, "46"))
        assertEquals("D", ReadingQuestions.selected(both, "51"))
    }

    @Test fun editingOnePassagePreservesTheOtherAndOldAnswerFormat() {
        val old = "46. A\n47. B\n51. C\n52. D"
        val changed = SequentialAnswers.apply(old, "D?ABC", questions(46))
        assertEquals("C", ReadingQuestions.selected(changed, "51"))
        assertEquals("D", ReadingQuestions.selected(changed, "52"))
        assertEquals("D?ABC", SequentialAnswers.sequence(changed, questions(46)))
        assertTrue(ReadingQuestions.canSelect(changed))
    }

    @Test fun emptyAndSkippedAnswersDoNotShiftFollowingQuestions() {
        val saved = SequentialAnswers.apply("51. C", "A?C?D", questions(46))
        assertNull(ReadingQuestions.selected(saved, "47"))
        assertEquals("C", ReadingQuestions.selected(saved, "48"))
        assertEquals("D", ReadingQuestions.selected(saved, "50"))
        assertEquals("51. C", SequentialAnswers.apply(saved, "", questions(46)))
    }

    @Test fun acceptsLowercaseSpacesAndChineseSeparators() {
        val saved = SequentialAnswers.apply("", "b c，a；d？", questions(46))
        assertEquals("BCAD", SequentialAnswers.sequence(saved, questions(46)))
        assertEquals("BCAD?", SequentialAnswers.normalize("b c，a；d？"))
    }

    @Test fun refusesOriginalNumbersInvalidLettersAndExtraAnswers() {
        listOf("46.B", "12345", "ABCDE", "BCADBB", "B/OCD", "A".repeat(101)).forEach {
            assertTrue("Must reject $it", runCatching { SequentialAnswers.apply("51. C", it, questions(46)) }.isFailure)
        }
    }

    @Test fun missingOrReorderedSourceQuestionsAreHandledExplicitly() {
        assertTrue(runCatching { SequentialAnswers.apply("", "BCAD", questions(46).dropLast(1)) }.isFailure)
        val saved = SequentialAnswers.apply("", "BCADB", questions(46).reversed())
        assertEquals("BCADB", SequentialAnswers.sequence(saved, questions(46)))
    }
}
