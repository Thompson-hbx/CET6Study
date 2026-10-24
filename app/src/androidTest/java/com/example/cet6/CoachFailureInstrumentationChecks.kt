package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import com.example.cet6.ui.CoachState
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/** Controlled failures only: no live quota consumption, authentication changes or user storage. */
class CoachFailureInstrumentationChecks(private val context: Context) {
    suspend fun verify() = withContext(Dispatchers.Main) {
        val file = File(context.cacheDir, "coach-failures-${System.nanoTime()}.db")
        fun open() = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
        var db = open()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main)
        try {
            val original = CoachPractice(title = "原创异常恢复检查", material = "Libraries need quiet rooms.",
                questions = "1. What do libraries need?", answer = "Quiet rooms.", feedback = "Previously completed feedback.",
                model = "old-model", errorReason = "长难句", savedSentences = "Libraries need quiet rooms.", submittedAt = 100)
            db.dao().saveCoach(original)
            var failure: Exception? = null
            var calls = 0
            val state = CoachState(CoachRepository(db), scope, { true }, { "new-model" }) { _, delta ->
                calls++
                delta("Temporary response only.")
                failure?.let { throw it }
                "Complete follow-up."
            }
            suspend fun waitFor(test: () -> Boolean) = withTimeout(10_000) { while (!test()) delay(20) }
            waitFor { state.entries.size == 3 }
            state.open(CoachKind.READING, original)
            waitFor { !state.working }
            val beforeRecords = db.dao().allCoach().associateBy { it.id }
            val failures = listOf(
                IOException("simulated connection reset"), SocketTimeoutException("simulated timeout"),
                IllegalStateException("AI 请求失败：HTTP 429，code=rate_limit_exceeded"),
                IllegalStateException("AI 请求失败：HTTP 429，code=insufficient_quota"),
                IllegalStateException("OAuth Token 请求失败：HTTP 400，invalid_grant"),
                IllegalStateException("网络流提前中断，没有收到 response.completed")
            )
            for (problem in failures) {
                failure = problem
                val beforeCalls = calls
                state.submit(CoachKind.READING, true, "Explain the evidence.")
                waitFor { !state.working }
                val stored = db.dao().coachById(original.id)!!
                check(stored.answer == original.answer && stored.feedback == original.feedback && stored.model == original.model)
                check(stored.errorReason == original.errorReason && stored.savedSentences == original.savedSentences && stored.submittedAt == 100L)
                check(calls == beforeCalls + 1) { "failure triggered an automatic retry" }
                check(state.partial == "Temporary response only." && state.partialEntryId == original.id && state.activeRequest == null)
                check(state.messages.getValue(CoachKind.READING).contains("AI 未完成"))
            }
            failure = null
            state.submit(CoachKind.READING, true, "Explain the evidence.")
            waitFor { !state.working }
            val completed = db.dao().coachById(original.id)!!
            check(completed.feedback == "${original.feedback}\n\n追问：Explain the evidence.\n\nComplete follow-up.")
            check(completed.model == "new-model" && completed.answer == original.answer && state.partial.isBlank())
            val afterRecords = db.dao().allCoach().associateBy { it.id }
            check(afterRecords.keys == beforeRecords.keys) { "retry created an additional practice" }
            check(afterRecords.filterKeys { it != original.id } == beforeRecords.filterKeys { it != original.id })
            // Writing feedback is additive: a focused second pass must not erase the first.
            val writing = original.copy(id = "writing-focused-check", kind = CoachKind.WRITING.name,
                questions = "", title = "原创写作专项保全检查")
            db.dao().saveCoach(writing)
            state.open(CoachKind.WRITING, writing)
            waitFor { !state.working }
            state.submit(CoachKind.WRITING, true, focus = WritingFocus.STRUCTURE)
            waitFor { !state.working }
            val revised = db.dao().coachById(writing.id)!!
            check(revised.answer == writing.answer && revised.feedback.startsWith(writing.feedback))
            check(revised.feedback.contains("## 审题与结构"))
            failure = IOException("controlled writing interruption")
            state.submit(CoachKind.WRITING, true, focus = WritingFocus.EXPRESSION)
            waitFor { !state.working }
            check(db.dao().coachById(writing.id)!!.feedback == revised.feedback)
            job.cancelAndJoin()
            db.close()
            db = open()
            check(db.dao().coachById(original.id) == completed)
        } finally { job.cancelAndJoin(); db.close() }
    }
}
