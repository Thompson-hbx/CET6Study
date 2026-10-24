package com.example.cet6

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.ui.CoachState
import kotlinx.coroutines.*
import java.io.File

class ExamInstrumentationChecks(private val context: Context) {
    private fun sample() = ExamPaper("sample-cet6-2026-06-13-1", "2026-06-13", "1",
        sourceName = "Original test", sourceUrl = "local:test", licenseStatus = "original", reviewed = true, sample = true,
        passages = (1..2).map { code -> ExamPassage("$code", "Original sample", "Libraries provide quiet rooms.",
            (if (code == 1) 46..50 else 51..55).map { n -> ExamQuestion(n, "What is provided?",
                "ABCD".map { ExamOption("$it", "Option $it") }, "A", "local:test", "quiet rooms") }) },
        writingPrompt = "Original writing task", writingInstructions = "Write 150 words", chineseText = "图书馆提供空间。")

    suspend fun verify() = withContext(Dispatchers.Main) {
        val folder = File(context.cacheDir, "exam-store-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = folder }
        val store = ExamStore(isolated)
        if ("personal-exam-pack.json" in context.assets.list("").orEmpty()) {
            val bundledFolder = File(context.cacheDir, "bundled-exams-${System.nanoTime()}").apply { mkdirs() }
            val bundledStore = ExamStore(object : ContextWrapper(context) { override fun getFilesDir() = bundledFolder })
            val expected = context.assets.open("personal-exam-pack.json").bufferedReader().use { ExamCodec.decode(it.readText()) }
            check(bundledStore.load().isEmpty())
            check(bundledStore.loadWithBundledContent() == expected)
            check(bundledStore.load() == expected)
            check(bundledStore.loadWithBundledContent() == expected)
            expected.forEach { paper ->
                CoachKind.entries.filter { paper.issues(it).isEmpty() }.forEach { kind ->
                    val practice = ExamCodec.practice(paper, kind)
                    if (kind == CoachKind.READING) {
                        val questions = ExamCodec.passages(practice).flatMap { it.questions }
                        check(questions.map { it.number } == (46..55).toList())
                        check(questions.all {
                            it.referenceAnswer in setOf("A", "B", "C", "D") && it.answerSource.isNotBlank()
                        })
                    }
                }
            }
        }
        val p = sample()
        val privatePack = File(context.getExternalFilesDir(null), "private-study-pack.json")
        if (privatePack.exists()) {
            val owned = ExamCodec.decode(privatePack.readText())
            check(owned.size == 24 && owned.count { it.issues(CoachKind.READING).isEmpty() } == 14)
            check(owned.none { it.sample })
            owned.filter { it.issues(CoachKind.READING).isEmpty() }.forEach { paper ->
                val snapshot = ExamCodec.practice(paper, CoachKind.READING)
                val qs = ExamCodec.passages(snapshot).flatMap { it.questions }
                check(qs.map { it.number } == (46..55).toList())
                check(qs.all { it.referenceAnswer in setOf("A", "B", "C", "D") && it.answerSource.isNotBlank() })
            }
        }
        check(ExamCodec.decode(ExamCodec.encode(listOf(p))) == listOf(p))
        val withWriting = p.copy(referenceWriting = "Original reference essay.", referenceWritingSource = "local:original")
        check(ExamCodec.decode(ExamCodec.encode(listOf(withWriting))) == listOf(withWriting))
        val writingSnapshot = ExamCodec.practice(withWriting, CoachKind.WRITING)
        check(ExamCodec.metadata(writingSnapshot)?.optString("referenceWriting") == withWriting.referenceWriting)
        check(ExamCodec.metadata(writingSnapshot)?.optString("referenceWritingSource") == "local:original")
        check(ExamCodec.metadata(ExamCodec.practice(withWriting, CoachKind.TRANSLATION))?.optString("referenceWriting") == "")
        val legacyJson = org.json.JSONObject(ExamCodec.encode(listOf(p))).apply {
            getJSONArray("papers").getJSONObject(0).apply { remove("referenceWriting"); remove("referenceWritingSource") }
        }.toString()
        check(ExamCodec.decode(legacyJson) == listOf(p))
        check(store.combineBundled(emptyList(), listOf(withWriting)) == listOf(withWriting))
        check(store.combineBundled(listOf(p), listOf(withWriting)) == listOf(p))
        check(store.combineBundled(listOf(p.copy(revision = 3)), listOf(withWriting.copy(revision = 2))) == listOf(p.copy(revision = 3)))
        check(store.combineBundled(listOf(p), listOf(withWriting.copy(revision = 2))) == listOf(withWriting.copy(revision = 2)))
        store.save(listOf(p))
        check(store.load() == listOf(p))
        check(store.merge(store.load(), listOf(p)).size == 1)
        check(runCatching { store.merge(listOf(p), listOf(p.copy(writingPrompt = "conflict"))) }.isFailure)
        val revision = p.copy(revision = 2, writingPrompt = "Corrected")
        store.save(store.merge(store.load(), listOf(revision)))
        check(store.load() == listOf(revision))
        check(runCatching { store.merge(store.load(), listOf(p)) }.isFailure)
        check(runCatching { ExamCodec.decode(ExamCodec.encode(listOf(p)).replace("2026-06-13", "2026-12-12")) }.isFailure)
        val combined = ExamCodec.practice(p, CoachKind.READING)
        check(ExamCodec.passages(combined).flatMap { it.questions }.size == 10)
        val paperAttempt = combined.copy(submittedAt = 100, answer = "46. B")
        val earlyHint = ReadingGuidance.prompt(paperAttempt, 46, 1)
        check(!earlyHint.contains("local:test") && !earlyHint.contains("Option A"))
        val finalHint = ReadingGuidance.prompt(paperAttempt, 46, 4)
        check(finalHint.contains("local:test") && finalHint.contains("Option A"))
        check(runCatching { ReadingGuidance.prompt(combined, 46, 1) }.isFailure)
        val db = Room.databaseBuilder(context, StudyDatabase::class.java, File(folder, "isolated.db").absolutePath).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main)
        try {
            val original = CoachPractice(title = "existing user draft", material = "Preserve this", answer = "User answer")
            db.dao().saveCoach(original)
            val state = CoachState(CoachRepository(db), scope, { false }, { "" }) { _, _ -> error("No network allowed") }
            suspend fun waitFor(test: () -> Boolean) = withTimeout(10_000) { while (!test()) delay(20) }
            waitFor { state.entries.size == CoachKind.entries.size && state.history.any { it.id == original.id } }
            val draft = ExamCodec.practice(p, CoachKind.READING, p.passages.first())
            state.startExam(draft)
            waitFor { !state.working }
            val id = state.entries.getValue(CoachKind.READING).id
            waitFor { state.history.any { it.id == id } }
            state.startExam(ExamCodec.practice(p, CoachKind.READING, p.passages.first()))
            waitFor { !state.working }
            check(state.entries.getValue(CoachKind.READING).id == id)
            check(db.dao().coachById(original.id) == original)
            var answer = ""
            (46..50).forEach { answer = ReadingQuestions.choose(answer, "$it", "A") }
            check(state.edit(CoachKind.READING) { it.copy(answer = answer) })
            answer = SequentialAnswers.apply(answer, "BA?AD", p.passages.first().questions)
            check(state.edit(CoachKind.READING) { it.copy(answer = answer) })
            val beforeInvalidPaste = state.entries.getValue(CoachKind.READING)
            check(runCatching { SequentialAnswers.apply(answer, "BAEAD", p.passages.first().questions) }.isFailure)
            check(state.entries.getValue(CoachKind.READING) == beforeInvalidPaste)
            state.submit(CoachKind.READING, false)
            waitFor { !state.working }
            val submitted = state.entries.getValue(CoachKind.READING)
            check(submitted.submittedAt > 0 && submitted.answer == answer)
            check(ReadingQuestions.selected(submitted.answer, "46") == "B")
            check(ReadingQuestions.selected(submitted.answer, "47") == "A")
            check(ReadingQuestions.selected(submitted.answer, "48") == null)
            check(ReadingQuestions.selected(submitted.answer, "50") == "D")
            check(db.dao().coachById(id)?.answer == answer)
            state.readingHint(46, 1)
            waitFor { !state.working }
            check(state.entries.getValue(CoachKind.READING) == submitted)
            check(ExamCodec.metadata(submitted)?.getJSONObject("passage")?.getJSONArray("questions")?.length() == 5)
            state.open(CoachKind.READING, submitted, retry = true)
            waitFor { !state.working }
            val retry = state.entries.getValue(CoachKind.READING)
            check(retry.id != id && retry.answer.isBlank() && retry.sourcePracticeId == id)
            check(db.dao().coachById(id) == submitted)
            val restored = BackupCodec.decode(BackupCodec.encode(BackupRepository(db).snapshot()))
            check(restored.coach.any { it.id == id && it.questions == submitted.questions && it.answer == answer })
        } finally { job.cancelAndJoin(); db.close() }
    }
}
