package com.example.cet6

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.util.AtomicFile
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import com.example.cet6.data.*
import com.example.cet6.ui.StudyViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Observe startup model loading instead of racing its initial busy flag. */
internal suspend fun awaitEvaluationReady(isReady: () -> Boolean) {
    withTimeout(180_000) { snapshotFlow(isReady).first { it } }
}

/** Explicit opt-in only: exactly one request per original case, no learning rows are added. */
class AiEvaluationChecks(private val runner: Instrumentation) {
    fun verify(): String {
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: StudyViewModel
        runner.runOnMainSync { vm = ViewModelProvider(activity)[StudyViewModel::class.java] }
        val fixture = runner.context.assets.open("coach-evaluation-v1.json").bufferedReader().use { it.readText() }
        val cases = JSONObject(fixture).getJSONArray("cases")
        check(cases.length() == 11) { "Evaluation must contain the 11 approved original cases" }
        val result = JSONArray()
        val folder = File(runner.targetContext.getExternalFilesDir(null), "evaluation-${System.currentTimeMillis()}")
        check(folder.mkdirs())
        val output = AtomicFile(File(folder, "predictions.json"))
        runBlocking {
            val chosen = withContext(Dispatchers.Main) {
                check(vm.aiReady && vm.chosenModel.isNotBlank()) { "Configure the selected service/model in the app first" }
                check(!vm.busy && !vm.coach.working && !vm.talk.busy) { "Existing operation is still active" }
                vm.provider to vm.chosenModel
            }
            File(folder, "metadata.json").writeText(JSONObject().put("provider", chosen.first.name)
                .put("model", chosen.second).put("createdAt", System.currentTimeMillis())
                .put("fixtureSha256", MessageDigest.getInstance("SHA-256").digest(fixture.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }).toString(2))
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val prompt = prompt(case)
                val row = JSONObject().put("caseId", case.getString("id")).put("prompt", prompt)
                    .put("startedAt", System.currentTimeMillis())
                try {
                    val response = withContext(Dispatchers.Main) {
                        check(vm.provider == chosen.first && vm.chosenModel == chosen.second && !vm.busy && !vm.talk.busy)
                        withTimeout(180_000) {
                            var partial = ""
                            vm.streamCoach(prompt) { delta -> partial += delta; CoachTurns.checkReplyLength(partial) }
                        }
                    }
                    CoachTurns.checkReplyLength(response)
                    check(response.isNotBlank())
                    row.put("response", response)
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) throw e
                    row.put("response", "").put("error", "Request timeout; no automatic retry")
                } catch (e: Exception) {
                    row.put("response", "").put("error", CoachFailures.describe(e))
                }
                row.put("finishedAt", System.currentTimeMillis())
                result.put(row)
                val stream = output.startWrite()
                try { stream.write(result.toString(2).toByteArray()); output.finishWrite(stream) }
                catch (e: Throwable) { output.failWrite(stream); throw e }
                runner.sendStatus(0, Bundle().apply { putString("stream", "Evaluation ${index + 1}/11 ${case.getString("id")}: " +
                    if (row.has("error")) "request failed; no retry\n" else "complete ${row.getString("response").length} chars\n") })
            }
        }
        return "Evaluation output: ${folder.absolutePath}; 11 cases attempted once."
    }

    internal fun planV2(): Pair<String, List<EvaluationInput>> {
        val fixture = runner.context.assets.open("coach-evaluation-v2.json").bufferedReader().use { it.readText() }
        val hash = MessageDigest.getInstance("SHA-256").digest(fixture.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == "5975178a561026d5eb7e75d2b2be2c4f4cc9517f51d546eeb553c20b9e443249") { "Original evaluation corpus changed; review the new request scope first" }
        val data = JSONObject(fixture)
        check(data.getInt("version") == 2)
        val cases = data.getJSONArray("cases")
        check(cases.length() == 12) { "V2 evaluation must contain exactly 12 original cases" }
        val inputs = (0 until cases.length()).map { index ->
            val case = cases.getJSONObject(index)
            EvaluationInput(case.getString("id"), prompt(case))
        }
        check(inputs.map { it.id }.toSet().size == inputs.size)
        return hash to inputs
    }

    fun verifyV2(runId: String): String {
        val identifier = java.util.UUID.fromString(runId).toString()
        val (fixtureHash, inputs) = planV2()
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: StudyViewModel
        runner.runOnMainSync { vm = ViewModelProvider(activity)[StudyViewModel::class.java] }
        val parent = requireNotNull(runner.targetContext.getExternalFilesDir(null)) { "Evaluation storage unavailable" }
        val folder = File(parent, "evaluation-v2-$identifier")
        return runBlocking {
            withContext(Dispatchers.Main) {
                awaitEvaluationReady {
                    vm.coach.entries.size == CoachKind.entries.size && !vm.busy && !vm.coach.working &&
                        !vm.talk.busy && !vm.backupBusy && !vm.backupRecoveryRunning
                }
            }
            val chosen = withContext(Dispatchers.Main) {
                check(vm.aiReady && vm.chosenModel.isNotBlank()) { "Configure the selected service/model first" }
                check(!vm.busy && !vm.coach.working && !vm.talk.busy && !vm.backupBusy && !vm.backupRecoveryRunning) { "Operation state changed after startup; no live requests sent" }
                vm.provider to vm.chosenModel
            }
            val metadata = JSONObject().put("provider", chosen.first.name).put("model", chosen.second)
                .put("createdAt", System.currentTimeMillis()).put("runId", identifier).put("datasetVersion", 2)
                .put("fixtureSha256", fixtureHash).put("requestCountLimit", inputs.size)
                .put("calibrationStatus", "Author-provisional; independent review pending")
            val results = EvaluationBatch(folder, inputs, metadata).run(checkReady = {
                withContext(Dispatchers.Main) {
                    check(vm.provider == chosen.first && vm.chosenModel == chosen.second && !vm.busy &&
                        !vm.coach.working && !vm.talk.busy && !vm.backupBusy && !vm.backupRecoveryRunning) { "Service or operation state changed; batch stopped" }
                }
            }) { input, delta ->
                val response = withContext(Dispatchers.Main) {
                    check(vm.provider == chosen.first && vm.chosenModel == chosen.second && !vm.busy && !vm.talk.busy)
                    withTimeout(180_000) { vm.streamCoach(input.prompt, delta) }
                }
                runner.sendStatus(0, Bundle().apply { putString("stream", "V2 request completed: ${input.id}; no retry\n") })
                response
            }
            "Evaluation output: ${folder.absolutePath}; ${results.length()}/12 cases attempted once; author-provisional gold."
        }
    }

    internal fun prompt(case: JSONObject): String = when (case.getString("kind")) {
        "writing" -> WritingCoach.reviewPrompt(CoachPractice(kind = CoachKind.WRITING.name,
            title = "Original evaluation fixture", material = case.getString("material"), answer = case.getString("answer")), WritingFocus.COMPLETE)
        "translation" -> TranslationCoach.reviewPrompt(CoachPractice(kind = CoachKind.TRANSLATION.name,
            title = "Original evaluation fixture", material = case.getString("material"), answer = case.getString("answer")), WritingFocus.COMPLETE)
        "reading" -> {
            val options = case.getJSONObject("options")
            // Do not reveal the gold answer to the model: evaluate its own reading judgement.
            val passage = ExamPassage("1", "Original evaluation fixture", case.getString("material"),
                listOf(ExamQuestion(46, case.getString("question"), "ABCD".map { ExamOption("$it", options.getString("$it")) })))
            ReadingCoach.explainPrompt(passage, "46. A", 46)
        }
        else -> error("Unknown original fixture kind")
    }
}
