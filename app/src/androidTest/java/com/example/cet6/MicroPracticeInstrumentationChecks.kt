package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.ui.CoachState
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Exercises generation failures and provenance using isolated storage and a fake AI transport. */
class MicroPracticeInstrumentationChecks(private val context: Context) {
    suspend fun verify() = withContext(Dispatchers.Main) {
        val file = File(context.cacheDir, "micro-${System.nanoTime()}.db")
        fun open() = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
        var db = open()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main)
        try {
            val source = CoachPractice(title = "原创旧题", material = "Libraries need both books and quiet rooms.",
                questions = "1. What is needed?", answer = "1. A", feedback = "根据原文分析 both 的范围。",
                errorReason = "选项陷阱：忽略 both 的范围", submittedAt = 100, updatedAt = 200)
            db.dao().saveCoach(source)
            var mode = 0
            var calls = 0
            var online = true
            val response = JSONObject().put("title", "范围限定微练习").put("material",
                "A campus garden supports both research and relaxation. Students use it for experiments and quiet breaks.")
                .put("questions", "1. What does the garden support?\nA. Research only.\nB. Relaxation only.\nC. Both research and relaxation.\nD. Neither activity.").toString()
            val state = CoachState(CoachRepository(db), scope, { online }, { "fake-model" }) { prompt, _ ->
                calls++
                check(prompt.contains(source.errorReason) && prompt.contains("只返回一个JSON对象"))
                when (mode) {
                    0 -> error("模拟断网")
                    1 -> "{\"title\":\"unfinished\""
                    else -> response
                }
            }
            suspend fun waitFor(test: () -> Boolean) = withTimeout(10_000) { while (!test()) delay(20) }
            waitFor { state.entries.size == 3 }
            state.open(CoachKind.READING, source)
            waitFor { !state.working }
            val baseline = db.dao().allCoach()
            for (failure in 0..1) {
                mode = failure
                state.generate(CoachKind.READING)
                waitFor { !state.working }
                check(state.messages.getValue(CoachKind.READING).contains("微练习未生成"))
                check(db.dao().allCoach() == baseline) { "generation failure changed existing data" }
                check(state.entries.getValue(CoachKind.READING) == source)
            }
            online = false
            val beforeOffline = calls
            state.generate(CoachKind.READING)
            waitFor { !state.working }
            check(calls == beforeOffline && db.dao().allCoach() == baseline)
            check(state.messages.getValue(CoachKind.READING).contains("离线"))
            online = true
            mode = 2
            state.generate(CoachKind.READING)
            waitFor { !state.working }
            val generated = state.entries.getValue(CoachKind.READING)
            check(generated.id != source.id && generated.sourcePracticeId == source.id && generated.practiceGoal == source.errorReason)
            check(generated.answer.isBlank() && generated.feedback.isBlank() && generated.errorReason.isBlank() && generated.submittedAt == 0L)
            check(ReadingQuestions.parse(generated.questions).single().choices.size == 4)
            check(db.dao().coachById(source.id) == source)
            check(MicroPractice.parse("```json\n$response\n```", source).material == generated.material)
            val invalid = listOf(response + " trailing text", JSONObject(response).put("answer", "C").toString(),
                JSONObject(response).put("questions", "1. Q\nA. Only one option").toString(),
                JSONObject(response).put("material", "参考答案：C").toString(), JSONObject(response).put("material", 123).toString())
            invalid.forEach { check(runCatching { MicroPractice.parse(it, source) }.isFailure) }
            for (kind in listOf(CoachKind.WRITING, CoachKind.TRANSLATION)) {
                val writingSource = source.copy(kind = kind.name)
                val task = JSONObject().put("title", "语法微任务").put("material", "请练习主谓一致。").put("questions", "").toString()
                check(MicroPractice.parse(task, writingSource).kind == kind.name)
                check(MicroPractice.prompt(writingSource).contains("questions为空字符串"))
            }
            check(runCatching { MicroPractice.prompt(source.copy(errorReason = "")) }.isFailure)
            val snapshot = BackupRepository(db).snapshot()
            check(BackupCodec.decode(BackupCodec.encode(snapshot)) == snapshot)
            val v3 = JSONObject(BackupCodec.encode(snapshot)).apply {
                put("version", 3); put("databaseVersion", 6); remove("turns")
                val rows = getJSONArray("coach")
                for (i in 0 until rows.length()) rows.getJSONObject(i).apply { remove("sourcePracticeId"); remove("practiceGoal") }
            }
            check(BackupCodec.decode(v3.toString()).coach.all { it.sourcePracticeId.isBlank() && it.practiceGoal.isBlank() })
            job.cancelAndJoin()
            db.close()
            db = open()
            check(db.dao().coachById(source.id) == source)
            check(db.dao().coachById(generated.id) == generated)
        } finally { job.cancelAndJoin(); db.close() }
    }
}
