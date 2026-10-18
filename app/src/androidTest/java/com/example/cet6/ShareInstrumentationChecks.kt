package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.ui.CoachState
import kotlinx.coroutines.*
import java.io.File

/** Isolated storage: sharing must preserve edits and must not start or interrupt AI. */
class ShareInstrumentationChecks(private val context: Context) {
    suspend fun verify() = withContext(Dispatchers.Main) {
        val file = File(context.cacheDir, "share-${System.nanoTime()}.db")
        val db = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main)
        try {
            val old = CoachPractice(title = "existing draft", material = "Original material.", answer = "Unsubmitted answer.")
            db.dao().saveCoach(old)
            var calls = 0
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val state = CoachState(CoachRepository(db), scope, { true }, { "fake-model" }) { _, _ ->
                calls++
                started.complete(Unit)
                finish.await()
                "Complete feedback."
            }
            // A cold share waits for the persisted draft rather than replacing it before loading.
            val first = SharedReading.from("text/plain", "Shared original article.", "first share")
            state.importReading(first).join()
            check(calls == 0 && db.dao().coachById(old.id) == old)
            val imported = state.entries.getValue(CoachKind.READING)
            check(imported.id != old.id && imported.material == first.material)
            check(imported.answer.isBlank() && imported.feedback.isBlank() && imported.submittedAt == 0L)
            check(state.edit(CoachKind.READING) { it.copy(questions = "1. What is shared?", answer = "My independent answer.") })
            val edited = state.entries.getValue(CoachKind.READING)
            // Sharing during the debounce must flush edits to the previous draft.
            state.importReading(SharedReading.from("text/plain", "Second article.")).join()
            check(db.dao().coachById(edited.id) == edited && calls == 0)
            state.open(CoachKind.READING, edited)
            suspend fun waitFor(test: () -> Boolean) = withTimeout(10_000) { while (!test()) delay(20) }
            waitFor { !state.working }
            state.submit(CoachKind.READING, true)
            withTimeout(10_000) { started.await() }
            val active = state.entries.getValue(CoachKind.READING)
            state.importReading(SharedReading.from("text/plain", "Third article while AI is running.")).join()
            check(state.working && state.entries.getValue(CoachKind.READING).id == active.id)
            check(state.shareStatus.contains("历史"))
            check(db.dao().allCoach().any { it.material == "Third article while AI is running." && it.answer.isBlank() })
            finish.complete(Unit)
            waitFor { !state.working }
            check(calls == 1 && db.dao().coachById(active.id)?.feedback == "Complete feedback.")
            check(db.dao().coachById(old.id) == old)
            check(db.dao().allCoach().size == 4)
        } finally { job.cancelAndJoin(); db.close() }
    }
}
