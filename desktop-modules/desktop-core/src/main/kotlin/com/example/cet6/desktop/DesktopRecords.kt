package com.example.cet6.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.cet6.sync.SyncDocument
import org.json.JSONObject

val reviewKinds = setOf("write-review/1", "trans-review/1")

/** Practices in the synced document, newest first. */
fun practices(document: SyncDocument): List<Pair<String, JSONObject>> =
    document.records.filterKeys { it.startsWith("coach:") }.map { (key, value) -> key to JSONObject(value) }
        .sortedByDescending { it.second.getLong("updatedAt") }

/** Corrections from the newest complete review of [row]; older saves keep theirs in the row's own feedback field. */
fun reviewNotes(document: SyncDocument, row: JSONObject): List<MarginNote> {
    val latest = document.records.filterKeys { it.startsWith("turns:") }.values.map(::JSONObject)
        .filter { it.getString("practiceId") == row.getString("id") && it.optString("kind") in reviewKinds && it.optString("role") != "user" }
        .maxByOrNull { it.getLong("createdAt") }
    return marginNotes(latest?.getString("content") ?: row.optString("feedback"))
}

data class NotedPractice(val key: String, val row: JSONObject, val notes: List<MarginNote>)

fun noted(document: SyncDocument) = practices(document).filter { it.second.getLong("submittedAt") > 0 }
    .map { (key, row) -> NotedPractice(key, row, reviewNotes(document, row)) }.filter { it.notes.isNotEmpty() }

fun open(model: DesktopModel, key: String, row: JSONObject) {
    model.section = row.getString("kind")
    model.selected = key
}
