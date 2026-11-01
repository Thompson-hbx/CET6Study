package com.example.cet6.desktop

import com.example.cet6.sync.SyncDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MarginNotesTest {
    @Test fun keepsOnlyIssueRowsInOrder() {
        val text = listOf(
            """{"type":"score","band":11,"summary":"ok"}""",
            """{"type":"issue","quote":"have","fix":"has","reason":"主谓一致","category":"语法"}""",
            "plain prose line",
            """{"type":"issue","quote":"very big","fix":"vast","level":"better"}""",
        ).joinToString("\n")
        assertEquals(
            listOf(
                MarginNote("have", "has", "主谓一致", "语法", must = true, id = 0),
                MarginNote("very big", "vast", "", "表达", must = false, id = 1),
            ),
            marginNotes(text),
        )
    }

    @Test fun malformedFieldsAndDuplicateScoresDoNotBecomeMarginNotes() {
        val issue = """{"type":"issue","quote":"have","fix":"has"}"""
        for (tail in listOf(
            """{"type":"issue","quote":" ","fix":""}""",
            """{"type":"issue","quote":123,"fix":"x"}""",
            """{"type":"issue","quote":"have","fix":"has","level":"unknown"}""",
            """{"type":"score","band":8}
                {"type":"score","band":11}""",
            "{broken}",
        )) {
            assertEquals(emptyList<MarginNote>(), marginNotes("$issue\n$tail"))
        }
    }

    @Test fun fencedAndPrettyPrintedReviewsUseThePhoneProtocol() {
        val raw = "```json\n{\n  \"type\": \"issue\",\n  \"quote\": \"have\",\n  \"fix\": \"has\"\n}\n```"
        assertEquals(listOf(MarginNote("have", "has", "", "表达", true)), marginNotes(raw))
    }

    @Test fun savedDecisionsMarkNotesAndOnlyAcceptedLocatedFixesReachTheRevision() {
        val review = listOf(
            """{"type":"issue","quote":"have","fix":"has"}""",
            """{"type":"issue","quote":"help","fix":"helps"}""",
            """{"type":"issue","quote":"not in the text","fix":"x"}""",
        ).joinToString("\n")
        val notes = marginNotes(review, """{"accepted":[0,2],"dismissed":[1]}""")
        assertEquals(listOf(NoteDecision.ACCEPTED, NoteDecision.DISMISSED, NoteDecision.ACCEPTED), notes.map { it.decision })
        // The dismissed fix and the accepted fix whose quote is missing leave the text alone.
        assertEquals("They has a journal that help.", revisedAnswer("They have a journal that help.", notes))
        assertEquals(List(3) { NoteDecision.OPEN }, marginNotes(review, "").map { it.decision })
    }

    @Test fun usesNewestReviewTurnAndIgnoresChat() {
        val practice = JSONObject().put("id", "p1").put("feedback", """{"type":"issue","quote":"old","fix":"older"}""")
        fun turn(id: String, kind: String, at: Long, quote: String) = "turns:$id" to JSONObject().put("id", id).put("practiceId", "p1")
            .put("kind", kind).put("role", "assistant").put("createdAt", at)
            .put("content", """{"type":"issue","quote":"$quote","fix":"x"}""").toString()
        val document = SyncDocument(
            mapOf(
                turn("a", "write-review/1", 1, "first"),
                turn("b", "write-review/1", 2, "second"),
                turn("c", "chat/1", 3, "chat"),
            ),
        )
        assertEquals(listOf("second"), reviewNotes(document, practice).map { it.quote })
        assertEquals(listOf("old"), reviewNotes(SyncDocument(), practice).map { it.quote })
    }
}
