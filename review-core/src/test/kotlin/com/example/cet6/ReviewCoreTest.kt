package com.example.cet6

import com.example.cet6.data.CoachReview
import com.example.cet6.data.PassageLayout
import com.example.cet6.data.ReadingExplanation
import com.example.cet6.data.TextMarks
import org.junit.Assert.*
import org.junit.Test

class ReviewCoreTest {
    @Test fun malformedReviewsKeepRawTextAndExposeNoCorrections() {
        val raw = """{"type":"issue","quote":123,"fix":"has"}"""
        val review = CoachReview.parse(raw)
        assertFalse(review.structured)
        assertTrue(review.issues.isEmpty())
        assertEquals(raw, review.raw)
    }

    @Test fun formattedJsonAndUnknownFieldsRemainCompatible() {
        val raw = """```json
            {
              "type": "issue", "quote": "have", "fix": "has", "future": true
            }
            ```"""
        val review = CoachReview.parse(raw)
        assertTrue(review.structured)
        assertEquals("have", review.issues.single().quote)
    }

    @Test fun duplicateReadingOptionsCannotLookComplete() {
        val raw = """{"type":"option","label":"A","verdict":"correct"}
            {"type":"option","label":"a","verdict":"wrong"}"""
        assertFalse(ReadingExplanation.parse(raw).structured)
    }

    @Test fun paragraphReflowAndCorrectionsPreserveOriginalCharacters() {
        val original = "Libraries have\nquiet rooms.\n\nStudents study."
        assertEquals(listOf("Libraries have quiet rooms.", "Students study."), PassageLayout.paragraphs(original))
        val range = TextMarks.locate(original, "have quiet")!!
        assertEquals("have\nquiet", original.substring(range))
        assertEquals("Libraries offer rooms.\n\nStudents study.", TextMarks.apply(original, listOf(range to "offer")))
    }
}
