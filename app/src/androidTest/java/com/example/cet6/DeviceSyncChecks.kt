package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.domain.PracticeService
import com.example.cet6.sync.*
import kotlinx.coroutines.*
import org.json.JSONObject

class DeviceSyncChecks(private val context: Context) {
    suspend fun verify() {
        val db = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        try {
            val original = CoachPractice(
                id = "sync-original",
                kind = "WRITING",
                title = "Original sync sample",
                material = "Write about learning.",
                answer = "Written on phone.",
                updatedAt = 100,
            )
            db.dao().saveCoach(original)
            db.dao().addReadingAttempt(ReadingAttempt(questionId = "original", answer = "B", correct = true))
            val repository = DeviceSyncRepository(db)
            val before = repository.snapshot()
            val changed = JSONObject(before.records.getValue("coach:${original.id}"))
                .put("answer", "Continued on desktop.").put("updatedAt", 200)
            val remote = SyncDocument(before.records + ("coach:${original.id}" to canonical(changed)))
            check(repository.apply(before, remote))
            check(db.dao().coachById(original.id)?.answer == "Continued on desktop.")
            check(db.dao().allReading().size == 1)
            check(repository.apply(remote, remote))
            check(db.dao().allReading().size == 1) { "Repeated synchronization duplicated legacy history" }
            db.dao().saveCoach(original.copy(answer = "New typing while syncing.", updatedAt = 300))
            check(!repository.apply(remote, remote))
            check(db.dao().coachById(original.id)?.answer == "New typing while syncing.")
            val current = repository.snapshot()
            check(runCatching { repository.apply(current, SyncDocument()) }.isFailure)
            check(repository.snapshot() == current)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val practice = withContext(Dispatchers.Main.immediate) {
                    PracticeService(
                        CoachRepository(db),
                        scope,
                        canRequest = { false },
                        model = { "" },
                        startupReady = null,
                        reviewRequest = null,
                        request = { _, _ -> error("No network in sync checks") },
                    )
                }
                withContext(Dispatchers.Main.immediate) { check(practice.flush()) }
                val baseline = repository.snapshot()
                val next = JSONObject(baseline.records.getValue("coach:${original.id}"))
                    .put("answer", "Remote draft visible in editor.").put("updatedAt", 400)
                val updated = SyncDocument(baseline.records + ("coach:${original.id}" to canonical(next)))
                withContext(Dispatchers.Main.immediate) {
                    check(practice.applyExternal { repository.apply(baseline, updated) })
                    check(practice.state.value.entries.getValue(CoachKind.WRITING).answer == "Remote draft visible in editor.")
                    check(practice.edit(CoachKind.WRITING) { it.copy(answer = it.answer + " Next edit.") })
                    check(practice.flush())
                }
                check(db.dao().coachById(original.id)?.answer == "Remote draft visible in editor. Next edit.") {
                    "An old in-memory draft overwrote the synchronized draft"
                }
            } finally {
                scope.cancel()
            }
        } finally {
            db.close()
        }
    }
}
