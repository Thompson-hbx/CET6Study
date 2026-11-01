package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.cet6.data.TextMarks
import com.example.cet6.sync.SyncDocument
import org.json.JSONObject

val reviewKinds = setOf("write-review/1", "trans-review/1")

/** Practices in the synced document, newest first. */
fun practices(document: SyncDocument): List<Pair<String, JSONObject>> =
    document.records.filterKeys { it.startsWith("coach:") }.map { (key, value) -> key to JSONObject(value) }
        .sortedByDescending { it.second.getLong("updatedAt") }

/** The newest complete review turn of [row] with its record key; null when only the legacy feedback field exists. */
fun latestReviewTurn(document: SyncDocument, row: JSONObject): Pair<String, JSONObject>? = document.records
    .filterKeys { it.startsWith("turns:") }.map { (key, value) -> key to JSONObject(value) }
    .filter { (_, turn) ->
        turn.getString("practiceId") == row.getString("id") && turn.optString("kind") in reviewKinds && turn.optString("role") != "user"
    }
    .maxByOrNull { it.second.getLong("createdAt") }

/** Corrections from the newest complete review of [row]; older saves keep theirs in the row's own feedback field. */
fun reviewNotes(document: SyncDocument, row: JSONObject): List<MarginNote> {
    val latest = latestReviewTurn(document, row)?.second
    return if (latest != null) marginNotes(latest.getString("content"), latest.optString("state")) else marginNotes(row.optString("feedback"))
}

/**
 * [answer] with every accepted fix applied, the same way the phone builds its revision: quotes are placed in order
 * and a fix whose quote cannot be found in the text is left out rather than guessed.
 */
fun revisedAnswer(answer: String, notes: List<MarginNote>): String {
    val ranges = TextMarks.place(answer, notes.map { it.quote })
    val edits = notes.indices.mapNotNull { i -> ranges[i]?.takeIf { notes[i].decision == NoteDecision.ACCEPTED }?.let { it to notes[i].fix } }
    return TextMarks.apply(answer, edits)
}

data class NotedPractice(val key: String, val row: JSONObject, val notes: List<MarginNote>)

fun noted(document: SyncDocument) = practices(document).filter { it.second.getLong("submittedAt") > 0 }
    .map { (key, row) -> NotedPractice(key, row, reviewNotes(document, row)) }.filter { it.notes.isNotEmpty() }

fun open(model: DesktopModel, key: String, row: JSONObject) {
    model.section = row.getString("kind")
    model.selected = key
}
