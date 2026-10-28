package com.example.cet6

import android.app.Instrumentation
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Check billing boundaries and persistence with fake calls; never create an Activity or AI client. */
class EvaluationOfflineChecks(private val runner: Instrumentation) {
    suspend fun verify(): Int {
        coroutineScope {
            val ready = androidx.compose.runtime.mutableStateOf(false)
            val observed = CompletableDeferred<Unit>()
            val waiting = async {
                awaitEvaluationReady { observed.complete(Unit); ready.value }
            }
            observed.await()
            check(!waiting.isCompleted) { "Startup busy state was not awaited" }
            ready.value = true
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            waiting.await()
            check(runCatching {
                withTimeout(1) { awaitEvaluationReady { false } }
            }.exceptionOrNull() is TimeoutCancellationException) { "Readiness wait swallowed cancellation" }
        }
        val context = runner.targetContext
        val evaluator = AiEvaluationChecks(runner)
        val (hash, inputs) = evaluator.planV2()
        check(inputs.size == 12 && inputs.all { it.prompt.isNotBlank() })
        val gold = runner.context.assets.open("coach-evaluation-v2.json").bufferedReader().use { JSONObject(it.readText()) }
        val cases = gold.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val original = cases.getJSONObject(i)
            val changedGold = JSONObject(original.toString()).put("errors", JSONArray().put("HIDDEN GOLD SENTINEL"))
                .put("correct", "HIDDEN GOLD SENTINEL").put("evidence", JSONArray().put("HIDDEN GOLD SENTINEL"))
                .put("goldScope", "HIDDEN GOLD SENTINEL")
            check(evaluator.prompt(original) == evaluator.prompt(changedGold)) { "Gold labels leaked into the model prompt" }
        }
        val prompts = JSONObject().put("datasetVersion", 2).put("fixtureSha256", hash).put("realRequestsSent", 0)
            .put("cases", JSONArray().apply { inputs.forEach { put(JSONObject().put("caseId", it.id).put("prompt", it.prompt)) } })
        File(context.cacheDir, "evaluation-v2-prompts.json").writeText(prompts.toString(2))
        fun folder() = File(context.cacheDir, "evaluation-offline-${UUID.randomUUID()}")
        val mixed = folder()
        var calls = 0
        val rows = EvaluationBatch(mixed, inputs, JSONObject().put("provider", "offline-original-fixture")).run { _, delta ->
            when (calls++) {
                1 -> { delta("Original partial diagnostic"); throw IOException("controlled offline failure") }
                2 -> withTimeout(1) { awaitCancellation() }
                3 -> "x".repeat(60_001)
                else -> { delta("Original fake complete reply"); "Original fake complete reply" }
            }
        }
        check(calls == 12 && rows.length() == 12)
        check((0 until rows.length()).count { rows.getJSONObject(it).getString("requestState") == "failed" } == 3)
        check(rows.getJSONObject(1).getString("partial") == "Original partial diagnostic")
        check(rows.getJSONObject(1).getString("response").isEmpty())
        check(runCatching { EvaluationBatch(mixed, inputs, JSONObject()).run { _, _ -> calls++; "Must not call" } }.isFailure)
        check(calls == 12) { "Same run folder was replayed" }

        val beforeSend = folder()
        calls = 0
        check(runCatching {
            EvaluationBatch(beforeSend, inputs, JSONObject()).run(checkReady = {
                check(File(beforeSend, "predictions.json.new").mkdir())
            }) { _, _ -> calls++; "Must not call" }
        }.isFailure)
        check(calls == 0) { "Request sent before its checkpoint was durable" }

        val afterSend = folder()
        calls = 0
        check(runCatching {
            EvaluationBatch(afterSend, inputs, JSONObject()).run { _, _ ->
                calls++
                check(File(afterSend, "predictions.json.new").mkdir())
                "Original complete reply whose checkpoint fails"
            }
        }.isFailure)
        check(calls == 1)
        val uncertain = JSONArray(File(afterSend, "predictions.json").readText()).getJSONObject(0)
        check(uncertain.getString("requestState") == "in-flight")
        check(runCatching { EvaluationBatch(afterSend, inputs, JSONObject()).run { _, _ -> calls++; "Must not call" } }.isFailure)
        check(calls == 1) { "Uncertain billed request was replayed" }

        val cancelled = folder()
        calls = 0
        coroutineScope {
            val started = CompletableDeferred<Unit>()
            val job = launch {
                EvaluationBatch(cancelled, inputs, JSONObject()).run { _, delta ->
                    calls++
                    delta("Original diagnostic before cancellation")
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            job.cancelAndJoin()
        }
        val stopped = JSONArray(File(cancelled, "predictions.json").readText())
        check(calls == 1 && stopped.length() == 1 && stopped.getJSONObject(0).getString("requestState") == "failed")
        check(stopped.getJSONObject(0).getString("partial") == "Original diagnostic before cancellation")
        return inputs.size
    }
}
