package com.example.cet6

import com.example.cet6.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadingLocationTest {
    private val source = "Intro.\n\nLibraries offer quiet rooms."
    private val question = ExamQuestion(46, "What is offered?", emptyList())
    private val passage = ExamPassage("1", "Original test", source, listOf(question))
    private val entry = CoachPractice(id = "reading", kind = CoachKind.READING.name, material = source,
        questions = JSONObject().put("format", "CET6ExamAttempt").put("passage", ExamCodec.passageJson(passage)).toString(), updatedAt = 1000)
    private fun turn(quote: String = "Libraries offer quiet rooms.") = CoachTurn(id = "explain", practiceId = entry.id,
        anchor = Anchor.question(46), kind = TurnKind.READING_EXPLAIN, role = CoachTurn.ASSISTANT,
        content = JSONObject().put("type", "locate").put("paragraph", 999).put("quote", quote).put("note", "test").toString(),
        createdAt = 1000)

    @Test fun extractsExactQuoteWithoutTrustingTheAiParagraph() {
        val quote = ReadingLocation.extract(entry, turn())
        assertEquals("Libraries offer quiet rooms.", quote)
        assertEquals(2, PassageLayout.paragraphOf(source, quote))
    }
    @Test fun rejectsFuzzyInventedWrongQuestionAndIncompleteReplies() {
        listOf(turn("Libraries offer more quiet rooms."), turn("Libraries offer  quiet rooms."),
            turn().copy(anchor = "q:47"), turn().copy(practiceId = "other"),
            turn().copy(role = CoachTurn.USER), turn().copy(kind = TurnKind.CHAT),
            turn().copy(content = turn().content + "\n{bad json")).forEach {
            assertEquals("", ReadingLocation.extract(entry, it))
        }
    }
    @Test fun exactMatchingWithoutEvidenceSourceDoesNotImplyReview() {
        val q = question.copy(evidence = "Libraries offer quiet rooms.")
        assertNull(q.verifiedEvidence(passage))
        assertNull(ReadingGuidance.paragraph(passage, q))
        val reviewed = q.copy(evidenceSource = "local:original-test-passage#paragraph=2")
        assertEquals(q.evidence, reviewed.verifiedEvidence(passage))
        assertNull(reviewed.copy(evidence = "Libraries offer more quiet rooms.").verifiedEvidence(passage))
        val encoded = ExamCodec.passageJson(passage.copy(questions = listOf(reviewed)))
        assertEquals(reviewed, ExamCodec.passage(encoded).questions.single())
    }
}
