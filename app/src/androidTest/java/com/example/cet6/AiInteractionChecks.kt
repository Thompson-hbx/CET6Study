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
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
