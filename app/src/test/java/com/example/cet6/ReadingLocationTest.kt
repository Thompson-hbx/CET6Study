package com.example.cet6

import com.example.cet6.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
    private fun snapshot(turn: CoachTurn) = StudyBackup(1000, emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), listOf(entry), listOf(turn))

    @Test fun restoresExactQuoteWithoutTrustingTheAiParagraph() {
        val reply = turn()
        val saved = reply.copy(locatedQuote = ReadingLocation.extract(entry, reply))
        assertEquals("Libraries offer quiet rooms.", ReadingLocation.restored(entry, saved))
        assertEquals(2, PassageLayout.paragraphOf(source, saved.locatedQuote))
        assertNull(ReadingLocation.restored(entry, reply))
    }
    @Test fun rejectsFuzzyInventedWrongQuestionAndIncompleteReplies() {
        listOf(turn("Libraries offer more quiet rooms."), turn("Libraries offer  quiet rooms."),
            turn().copy(anchor = "q:47"), turn().copy(practiceId = "other"),
            turn().copy(role = CoachTurn.USER), turn().copy(kind = TurnKind.CHAT),
            turn().copy(content = turn().content + "\n{bad json")).forEach {
            assertEquals("", ReadingLocation.extract(entry, it))
        }
        assertNull(ReadingLocation.restored(entry, turn().copy(locatedQuote = "Invented quote.")))
    }
    @Test fun jsonAndCompleteZipRoundTripWithLocationAndMergeOldCopies() {
        val reply = turn().let { it.copy(locatedQuote = ReadingLocation.extract(entry, it)) }
        val data = snapshot(reply)
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
        val bytes = ByteArrayOutputStream().also { CompleteBackupCodec.write(CompleteBackup(data, emptyList()), it) }.toByteArray()
        assertEquals(data, CompleteBackupCodec.read(ByteArrayInputStream(bytes)).study)
        val old = JSONObject(BackupCodec.encode(data)).put("version", 5).put("databaseVersion", 8)
        old.getJSONArray("turns").getJSONObject(0).remove("locatedQuote")
        val decoded = BackupCodec.decode(old.toString())
        assertEquals("", decoded.turns.single().locatedQuote)
        assertTrue(BackupMerger.merge(data, decoded, false).turns.isEmpty())
    }
    @Test fun backupRejectsTamperedOrCrossQuestionLocation() {
        assertTrue(runCatching { BackupCodec.encode(snapshot(turn().copy(locatedQuote = "Made up."))) }.isFailure)
        val invalid = turn().copy(anchor = "q:47", locatedQuote = "Libraries offer quiet rooms.")
        assertTrue(runCatching { BackupCodec.encode(snapshot(invalid)) }.isFailure)
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
