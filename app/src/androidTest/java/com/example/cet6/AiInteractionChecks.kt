package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.ui.CoachTalk
import kotlinx.coroutines.*
import java.io.IOException

/** Isolated request lifecycle checks: no credentials, network or learner database. */
class AiInteractionChecks(private val context: Context) {
    suspend fun verify() = withContext(Dispatchers.Main) {
        val db = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val repository = CoachRepository(db)
            val practice = CoachPractice(title = "Interaction test", material = "Original sample.")
            repository.save(practice)
            var mode = "wait"
            var calls = 0
            var started = CompletableDeferred<Unit>()
            val talk = CoachTalk(repository, scope, { true }, { "test-model" }) { _, delta ->
                calls++
                delta("partial")
                started.complete(Unit)
                when (mode) {
                    "wait" -> awaitCancellation()
                    "fail" -> throw IOException("controlled failure")
                    "limit" -> "x".repeat(CoachTurns.MAX_CONTENT)
                    "oversized" -> "x".repeat(CoachTurns.MAX_CONTENT + 1)
                    "stream-oversized" -> { delta("x".repeat(CoachTurns.MAX_CONTENT + 1)); "unreachable" }
                    else -> "Complete reply"
                }
            }
            talk.send(practice, Anchor.REVIEW, TurnKind.CHAT, "Original question") { "prompt" }
            started.await()
            talk.stop()
            withTimeout(5000) { while (talk.busy) yield() }
            check(talk.failure?.stopped == true)
            check(talk.failure?.question == "Original question")
            check(db.dao().allTurns().isEmpty()) { "Stopped response was persisted" }
            check(calls == 1) { "Stop retried automatically" }
            mode = "success"
            talk.failure!!.retry!!.invoke()
            withTimeout(5000) { while (talk.busy) yield() }
            val saved = db.dao().allTurns()
            check(saved.size == 2 && saved.any { it.content == "Complete reply" })
            check(talk.failure == null)
            mode = "fail"
            started = CompletableDeferred()
            talk.send(practice, Anchor.REVIEW, TurnKind.CHAT, "Retry question") { "prompt" }
            started.await()
            withTimeout(5000) { while (talk.busy) yield() }
            check(talk.failure?.stopped == false && talk.failure?.question == "Retry question")
            check(db.dao().allTurns().toSet() == saved.toSet()) { "Failure changed complete replies" }
            check(calls == 3)
            db.openHelper.writableDatabase.execSQL("""
                CREATE TEMP TRIGGER fail_assistant BEFORE INSERT ON coach_turns
                WHEN NEW.role = 'assistant' BEGIN SELECT RAISE(ABORT, 'controlled insert failure'); END
            """.trimIndent())
            mode = "success"
            talk.send(practice, Anchor.REVIEW, TurnKind.CHAT, "Rollback question") { "prompt" }
            withTimeout(5000) { while (talk.busy) yield() }
            check(talk.failure != null)
            check(db.dao().allTurns().toSet() == saved.toSet()) { "Reply insert failure left an orphan question" }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_assistant")
            talk.failure!!.retry!!.invoke()
            withTimeout(5000) { while (talk.busy) yield() }
            check(db.dao().allTurns().count { it.content == "Rollback question" } == 1)
            val completeBeforeLimit = db.dao().allTurns().toSet()
            for (oversized in listOf("oversized", "stream-oversized")) {
                mode = oversized
                talk.send(practice, Anchor.REVIEW, TurnKind.CHAT, "Long question") { "prompt" }
                withTimeout(5000) { while (talk.busy) yield() }
                check(talk.failure?.message?.contains("60,000") == true)
                check(talk.failure?.question == "Long question")
                check(db.dao().allTurns().toSet() == completeBeforeLimit) { "Oversized reply was saved or truncated" }
            }
            mode = "limit"
            talk.send(practice, Anchor.REVIEW, TurnKind.CHAT) { "prompt" }
            withTimeout(5000) { while (talk.busy) yield() }
            check(talk.failure == null)
            check(db.dao().allTurns().single { it.content.length == CoachTurns.MAX_CONTENT }.content == "x".repeat(CoachTurns.MAX_CONTENT))
            val review = CoachTurn(practiceId = practice.id, anchor = Anchor.REVIEW, kind = TurnKind.WRITING_REVIEW,
                role = CoachTurn.ASSISTANT, content = (0..39).joinToString("\n") {
                    """{"type":"issue","quote":"sample $it","fix":"fixed $it","reason":"test","level":"must"}"""
                })
            repository.saveTurn(review)
            // Every click deliberately retains the same old turn; no intervening recomposition.
            val clicks = (0..39).map { id -> talk.updateDecision(review, id,
                if (id % 2 == 0) ReviewDecision.ACCEPT else ReviewDecision.DISMISS) }
            clicks.joinAll()
            talk.updateDecision(review, 0, ReviewDecision.DISMISS).join()
            talk.updateDecision(review, 1, ReviewDecision.RESET).join()
            val storedReview = db.dao().turnById(review.id)!!
            val decisions = ReviewDecisions.decode(storedReview.state)
            check(decisions.accepted == (2..38 step 2).toSet())
            check(decisions.dismissed == (3..39 step 2).toSet() + 0)
            check(storedReview.copy(state = "") == review) { "Decision update rewrote review content" }
            val invalidReview = CoachTurn(practiceId = practice.id, anchor = Anchor.REVIEW, kind = TurnKind.WRITING_REVIEW,
                role = CoachTurn.ASSISTANT, content = review.content + "\n" + """{"type":"score","band":"11"}""",
                state = ReviewDecisions().dismiss(0).encode())
            repository.saveTurn(invalidReview)
            check(runCatching { repository.updateDecision(invalidReview.id, 1, ReviewDecision.ACCEPT) }.isFailure) {
                "Malformed mixed review accepted a decision"
            }
            check(db.dao().turnById(invalidReview.id) == invalidReview) { "Rejected decision changed raw review or existing state" }
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
