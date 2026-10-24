package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.ui.CoachState
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Uses isolated cache databases and a fake AI transport; never accesses credentials or the user's DB. */
class CoachInstrumentationChecks(private val context: Context) {
    suspend fun verify() = withContext(Dispatchers.Main) {
        val file = File(context.cacheDir, "coach-${System.nanoTime()}.db")
        fun open() = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
        var db = open()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main)
        try {
            var fail = true
            val state = CoachState(CoachRepository(db), scope, { true }, { "fake-model" }) { _, delta ->
                delta("原文证据：quiet rooms")
                if (fail) error("模拟中途断网")
                "原文证据：quiet rooms。答案为 AI 推断。"
            }
            suspend fun waitFor(test: () -> Boolean) = withTimeout(10_000) { while (!test()) delay(20) }
            val kind = CoachKind.READING
            waitFor { state.entries.size == 3 }
            val question = "1. What do libraries need?\nA. Quiet rooms.\nB. Noise."
            check(state.addReadingQuestion(question))
            check(!state.addReadingQuestion(question))
            check(state.entries.getValue(kind).questions == question)
            state.edit(kind) { it.copy(title = "原创阅读", material = "Libraries need quiet rooms.",
                questions = "1. What do libraries need?", answer = "1. quiet rooms") }
            waitFor { state.messages[kind] == "已保存到本机" }
            val original = state.entries.getValue(kind)
            check(db.dao().coachDraft(kind.name)?.answer == original.answer)
            state.submit(kind, true)
            waitFor { !state.working }
            check(db.dao().allCoach().single().feedback.isBlank()) { "partial response was saved as complete" }
            check(db.dao().allCoach().single().answer == original.answer)
            check(state.partial.isNotBlank() && state.messages.getValue(kind).contains("AI 未完成"))
            fail = false
            state.submit(kind, true)
            waitFor { !state.working }
            val complete = db.dao().allCoach().single()
            check(complete.feedback.contains("quiet rooms") && complete.model == "fake-model")
            state.edit(kind) { it.copy(errorReason = "长难句：误读主语") }
            // Opening a history card captured before an edit must not overwrite that newer edit.
            state.open(kind, complete)
            waitFor { !state.working }
            check(state.entries.getValue(kind).errorReason == "长难句：误读主语")
            state.edit(kind) { it.copy(savedSentences = ReadingEvidence.add(it.material, it.savedSentences, "quiet rooms.")) }
            state.save(kind)
            waitFor { !state.working }
            state.open(kind, state.entries.getValue(kind), retry = true)
            waitFor { !state.working }
            check(state.entries.getValue(kind).id != complete.id && state.entries.getValue(kind).answer.isBlank())
            check(db.dao().allCoach().size == 2)
            for (other in listOf(CoachKind.WRITING, CoachKind.TRANSLATION)) {
                state.edit(other) { it.copy(material = "独立题目", answer = "My draft.") }
                state.save(other)
                waitFor { !state.working }
                check(db.dao().coachDraft(other.name)?.answer == "My draft.")
                state.submit(other, false)
                waitFor { !state.working }
                val previous = state.entries.getValue(other)
                state.open(other, previous, retry = true)
                waitFor { !state.working }
                val revision = state.entries.getValue(other)
                check(revision.id != previous.id && revision.sourcePracticeId == previous.id && revision.answer == previous.answer)
                check(revision.feedback.isBlank() && revision.errorReason.isBlank() && revision.submittedAt == 0L)
                check(db.dao().coachById(previous.id) == previous)
            }
            val turn = CoachTurn(practiceId = complete.id, anchor = Anchor.question(1), kind = TurnKind.CHAT,
                role = CoachTurn.ASSISTANT, content = "原文证据：quiet rooms", model = "fake-model")
            CoachRepository(db).saveTurn(turn)
            val snapshot = BackupRepository(db).snapshot()
            check(snapshot.turns == listOf(turn)) { "turn missing from snapshot" }
            check(BackupCodec.decode(BackupCodec.encode(snapshot)) == snapshot)
            val v4 = JSONObject(BackupCodec.encode(snapshot)).apply { put("version", 4); put("databaseVersion", 7); remove("turns") }
            check(BackupCodec.decode(v4.toString()).turns.isEmpty())
            val orphan = JSONObject(BackupCodec.encode(snapshot)).apply { getJSONArray("turns").getJSONObject(0).put("practiceId", "missing") }
            check(runCatching { BackupCodec.decode(orphan.toString()) }.isFailure) { "turn without its practice accepted" }
            val v2 = JSONObject(BackupCodec.encode(snapshot)).apply {
                put("version", 2); put("databaseVersion", 5); remove("turns")
                val rows = getJSONArray("coach")
                for (i in 0 until rows.length()) rows.getJSONObject(i).remove("savedSentences")
            }
            check(BackupCodec.decode(v2.toString()).coach.all { it.savedSentences.isBlank() })
            state.edit(CoachKind.WRITING) { it.copy(answer = "Immediate background save.") }
            check(state.flush())
            check(db.dao().coachDraft(CoachKind.WRITING.name)?.answer == "Immediate background save.")
            val legacy = JSONObject(BackupCodec.encode(snapshot)).apply {
                put("version", 1); put("databaseVersion", 4); remove("coach"); remove("turns")
            }
            check(BackupCodec.decode(legacy.toString()).coach.isEmpty())
            job.cancelAndJoin()
            db.close()
            db = open()
            check(db.dao().allCoach().single { it.id == complete.id }.errorReason == "长难句：误读主语")
            check(db.dao().allCoach().single { it.id == complete.id }.savedSentences == "quiet rooms.")
            check(db.dao().coachDraft(CoachKind.WRITING.name)?.answer == "Immediate background save.")
            check(db.dao().coachDraft(CoachKind.TRANSLATION.name)?.answer == "My draft.")
        } finally { job.cancelAndJoin(); db.close() }
    }
}
