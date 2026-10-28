package com.example.cet6

import android.util.AtomicFile
import com.example.cet6.data.CoachFailures
import com.example.cet6.data.CoachTurns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class EvaluationInput(val id: String, val prompt: String)

/** One directory is one non-resumable request budget; uncertain outcomes must be inspected, never replayed. */
internal class EvaluationBatch(
    private val folder: File,
    private val inputs: List<EvaluationInput>,
    private val metadata: JSONObject
) {
    private fun checkpoint(file: File, bytes: ByteArray) {
        val output = AtomicFile(file)
        val stream = output.startWrite()
        try { stream.write(bytes); output.finishWrite(stream) }
        catch (failure: Throwable) { output.failWrite(stream); throw failure }
        check(output.openRead().use { it.readBytes().contentEquals(bytes) }) { "Evaluation checkpoint did not persist; no further requests allowed" }
    }

    suspend fun run(
        checkReady: suspend () -> Unit = {},
        request: suspend (EvaluationInput, suspend (String) -> Unit) -> String
    ): JSONArray {
        require(inputs.isNotEmpty() && inputs.map { it.id }.toSet().size == inputs.size)
        check(folder.mkdir()) { "Evaluation run already exists or cannot be created; never replay an uncertain request" }
        checkpoint(File(folder, "metadata.json"), metadata.toString(2).toByteArray(Charsets.UTF_8))
        val rows = JSONArray()
        val output = File(folder, "predictions.json")
        fun save() = checkpoint(output, rows.toString(2).toByteArray(Charsets.UTF_8))
        save()
        for (input in inputs) {
            checkReady()
            val row = JSONObject().put("caseId", input.id).put("prompt", input.prompt)
                .put("startedAt", System.currentTimeMillis()).put("requestState", "in-flight").put("response", "")
                .put("error", "Outcome not checkpointed; inspect this run and do not automatically retry")
            rows.put(row)
            save() // Durable evidence precedes the potentially billed call.
            var partial = ""
            try {
                val response = request(input) { delta ->
                    row.put("receivedChars", partial.length.toLong() + delta.length)
                    CoachTurns.checkReplyLength(delta)
                    val next = partial + delta
                    CoachTurns.checkReplyLength(next)
                    partial = next
                }
                CoachTurns.checkReplyLength(response)
                check(response.isNotBlank()) { "AI returned an empty complete response" }
                row.put("response", response).put("requestState", "complete").remove("error")
            } catch (failure: CancellationException) {
                row.put("requestState", "failed").put("partial", partial).put("finishedAt", System.currentTimeMillis())
                    .put("error", if (failure is TimeoutCancellationException) "Request timeout; no automatic retry" else "Request cancelled; no automatic retry")
                withContext(NonCancellable) { save() }
                if (failure !is TimeoutCancellationException) throw failure
            } catch (failure: Exception) {
                row.put("requestState", "failed").put("partial", partial).put("error", CoachFailures.describe(failure))
            }
            row.put("finishedAt", System.currentTimeMillis())
            save() // A persistence failure stops the batch before the next potentially billed call.
        }
        return rows
    }
}
