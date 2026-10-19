package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.ui.CoachState
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

/** Isolated Room storage and a controlled request callback; never calls the real AI service. */
class ReadingDiagnosisInstrumentationChecks(private val context: Context) {
    suspend fun verify() = withContext(Dispatchers.Main) {
        val file = File(context.cacheDir, "reading-diagnosis-${System.nanoTime()}.db")
        val db = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main)
        var diagnosisId = ""
        try {
            val paper = ExamPaper("sample-cet6-2026-06-13-1", "2026-06-13", "1", sourceName = "Original diagnosis test",
                sourceUrl = "local:test", licenseStatus = "original", reviewed = true, sample = true,
                passages = (1..2).map { code -> ExamPassage("$code", "Original", if (code == 1) "Students need quiet rooms." else "UNRELATED SECOND PASSAGE.",
                    (if (code == 1) 46..50 else 51..55).map { n -> ExamQuestion(n, "What is needed?",
                        "ABCD".map { ExamOption("$it", "Option $it") }, "A", "Original test key", if (code == 1) "quiet rooms" else "SECOND PASSAGE") }) })
            val original = ExamCodec.practice(paper, CoachKind.READING).copy(answer = "46. B\n47. C", submittedAt = 100,
                feedback = "## 第 46 题 · 第 1 层提示\n\nOld local hint.", errorReason = "User confirmed note")
            diagnosisId = original.id
            db.dao().saveCoach(original)
            var fail = false
            var allowed = true
            val prompts = mutableListOf<String>()
            val state = CoachState(CoachRepository(db), scope, { allowed }, { "controlled-test (非真实 AI)" }) { prompt, delta ->
                prompts += prompt
                delta("Unfinished fragment.")
                if (fail) throw IOException("controlled interruption")
                "### 原文定位\n\n“Students need quiet rooms.”\n\n可能的误读是忽略了 quiet。"
            }
            suspend fun waitFor(test: () -> Boolean) = withTimeout(10_000) { while (!test()) delay(20) }
            waitFor { state.entries.size == 3 }
            state.open(CoachKind.READING, original)
            waitFor { !state.working }
            state.readingDiagnosis(46)
            waitFor { !state.working }
            val first = db.dao().coachById(original.id)!!
            check(prompts.size == 1 && prompts.single().contains("我的选项：B. Option B"))
            check(!prompts.single().contains("UNRELATED SECOND PASSAGE"))
            check(ReadingDiagnosis.saved(first.feedback, 46).contains("Students need quiet rooms."))
            check(ReadingDiagnosis.saved(first.feedback, 47).isBlank())
            check(first.feedback.startsWith(original.feedback) && first.errorReason == original.errorReason)
            check(first.answer == original.answer && first.questions == original.questions && first.submittedAt == original.submittedAt)
            check(state.partial.isBlank())

            fail = true
            state.readingDiagnosis(46, "Why did I choose B?")
            waitFor { !state.working }
            check(db.dao().coachById(original.id) == first)
            check(prompts.size == 2 && prompts.last().contains("Why did I choose B?"))
            check(prompts.last().contains(ReadingDiagnosis.saved(first.feedback, 46)))
            check(state.partial == "Unfinished fragment." && state.partialReadingKey == "46/diagnosis" && state.activeRequest == null)
            fail = false
            state.readingDiagnosis(47)
            waitFor { !state.working }
            val second = db.dao().coachById(original.id)!!
            check(prompts.size == 3 && !prompts.last().contains("可能的误读是忽略了 quiet。"))
            check(ReadingDiagnosis.saved(second.feedback, 46) == ReadingDiagnosis.saved(first.feedback, 46))
            check(ReadingDiagnosis.saved(second.feedback, 47).isNotBlank())
            check(second.errorReason == original.errorReason && second.answer == original.answer)
            allowed = false
            state.readingDiagnosis(46)
            waitFor { !state.working }
            check(prompts.size == 3 && db.dao().coachById(original.id) == second)
            check(state.messages.getValue(CoachKind.READING).contains("连接 ChatGPT"))
        } finally { job.cancelAndJoin(); db.close() }
        val reopened = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
        try {
            val restored = reopened.dao().coachById(diagnosisId)!!
            check(ReadingDiagnosis.saved(restored.feedback, 46).isNotBlank() && ReadingDiagnosis.saved(restored.feedback, 47).isNotBlank())
            check(restored.answer == "46. B\n47. C" && restored.errorReason == "User confirmed note")
        } finally { reopened.close() }
    }
}
