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
            """{"type":"issue","quote":" ","fix":""}"""
        ).joinToString("\n")
        assertEquals(listOf(
            MarginNote("have", "has", "主谓一致", "语法", must = true),
            MarginNote("very big", "vast", "", "表达", must = false)
        ), marginNotes(text))
    }

    @Test fun usesNewestReviewTurnAndIgnoresChat() {
        val practice = JSONObject().put("id", "p1").put("feedback", """{"type":"issue","quote":"old","fix":"older"}""")
        fun turn(id: String, kind: String, at: Long, quote: String) = "turns:$id" to JSONObject().put("id", id).put("practiceId", "p1")
            .put("kind", kind).put("role", "assistant").put("createdAt", at)
            .put("content", """{"type":"issue","quote":"$quote","fix":"x"}""").toString()
        val document = SyncDocument(mapOf(
            turn("a", "write-review/1", 1, "first"),
            turn("b", "write-review/1", 2, "second"),
            turn("c", "chat/1", 3, "chat")
        ))
        assertEquals(listOf("second"), reviewNotes(document, practice).map { it.quote })
        assertEquals(listOf("old"), reviewNotes(SyncDocument(), practice).map { it.quote })
    }
}
