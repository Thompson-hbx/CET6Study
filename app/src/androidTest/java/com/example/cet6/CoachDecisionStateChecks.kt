package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Database-only checks in an isolated in-memory store; no learner records or network. */
class CoachDecisionStateChecks(private val appContext: Context, private val fixtureContext: Context) {
    suspend fun verify() {
        val db = Room.inMemoryDatabaseBuilder(appContext, StudyDatabase::class.java).build()
        try {
            val repository = CoachRepository(db)
            val practice = CoachPractice(kind = CoachKind.WRITING.name, title = "Original decision test", material = "Original task.")
            repository.save(practice)
            val content = (0..1).joinToString("\n") {
                """{"type":"issue","quote":"sample $it","fix":"fixed $it","level":"must"}"""
            }
            val corpus = fixtureContext.assets.open("coach-decision-state-v1.json").bufferedReader().use { JSONObject(it.readText()) }
            check(corpus.getInt("version") == 1)
            val cases = corpus.getJSONArray("cases")
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val turn = CoachTurn(practiceId = practice.id, anchor = Anchor.REVIEW, kind = TurnKind.WRITING_REVIEW,
                    role = CoachTurn.ASSISTANT, content = content, state = case.getString("state"))
                repository.saveTurn(turn)
                if (case.getBoolean("valid")) {
                    repository.updateDecision(turn.id, 1, ReviewDecision.ACCEPT)
                    fun ids(key: String): Set<Int> {
                        val values = case.getJSONArray(key)
                        return (0 until values.length()).map { values.getInt(it) }.toSet()
                    }
                    val saved = requireNotNull(db.dao().turnById(turn.id))
                    check(ReviewDecisions.decode(saved.state) == ReviewDecisions(ids("afterAccepted"), ids("afterDismissed")))
                    check(saved.copy(state = turn.state) == turn)
                } else {
                    check(runCatching { repository.updateDecision(turn.id, 1, ReviewDecision.ACCEPT) }.isFailure) {
                        "Invalid state ${case.getString("id")} was overwritten"
                    }
                    check(db.dao().turnById(turn.id) == turn) { "Rejected update changed stored content or state" }
                }
            }
            val legacy = CoachTurn(practiceId = practice.id, anchor = Anchor.REVIEW, kind = TurnKind.WRITING_REVIEW,
                role = CoachTurn.ASSISTANT, content = content, state = """{"accepted":[0]}""")
            repository.saveTurn(legacy)
            repository.updateDecision(legacy.id, 1, ReviewDecision.DISMISS)
            check(ReviewDecisions.decode(db.dao().turnById(legacy.id)!!.state) == ReviewDecisions(setOf(0), setOf(1)))

            val concurrent = CoachTurn(practiceId = practice.id, anchor = Anchor.REVIEW, kind = TurnKind.WRITING_REVIEW,
                role = CoachTurn.ASSISTANT, content = (0..39).joinToString("\n") {
                    """{"type":"issue","quote":"sample $it","fix":"fixed $it","level":"must"}"""
                })
            repository.saveTurn(concurrent)
            val other = CoachRepository(db)
            coroutineScope {
                (0..39).map { id -> async(Dispatchers.Default) {
                    (if (id % 2 == 0) repository else other).updateDecision(concurrent.id, id,
                        if (id % 2 == 0) ReviewDecision.ACCEPT else ReviewDecision.DISMISS)
                } }.awaitAll()
            }
            val saved = requireNotNull(db.dao().turnById(concurrent.id))
            check(ReviewDecisions.decode(saved.state) == ReviewDecisions((0..38 step 2).toSet(), (1..39 step 2).toSet()))
            check(saved.copy(state = "") == concurrent)
        } finally { db.close() }
    }
}
