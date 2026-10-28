package com.example.cet6

import com.example.cet6.data.*
import org.junit.Assert.*
import org.junit.Test

class PracticeVocabularyTest {
    private val entry = CoachPractice(id = "source-practice", material = "Libraries widen access to knowledge.")
    @Test fun confirmedWordKeepsContextAndStartsAsANewCard() {
        val word = PracticeVocabulary.word(entry, " ACCESS ", "获得的机会", entry.material)
        assertEquals("access", word.word)
        assertEquals(entry.material, word.example)
        assertTrue(word.source.contains(entry.id))
        assertEquals(0L, word.dueAt)
    }
    @Test fun rejectsInventedContextsAndMissingWordsOrDefinitions() {
        assertTrue(runCatching { PracticeVocabulary.word(entry, "access", "释义", "Invented sentence.") }.isFailure)
        assertTrue(runCatching { PracticeVocabulary.word(entry, "absent", "释义", entry.material) }.isFailure)
        assertTrue(runCatching { PracticeVocabulary.word(entry, "access", "", entry.material) }.isFailure)
    }
    @Test fun extractsMatchingSourceSentencesFromMaterialAndAnswer() {
        val multi = CoachPractice(
            id = "source-practice-2",
            material = "First sentence without target. Libraries widen access to knowledge.",
            answer = "Equal access matters for students."
        )
        val matches = PracticeVocabulary.candidates(multi, "ACCESS")
        assertEquals(listOf("Libraries widen access to knowledge.", "Equal access matters for students."), matches)
        matches.forEach { quote ->
            assertEquals(quote, PracticeVocabulary.word(multi, "access", "机会", quote).example)
        }
    }
}
