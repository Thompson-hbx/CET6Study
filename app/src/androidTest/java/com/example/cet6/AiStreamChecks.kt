package com.example.cet6

import android.content.Context
import com.example.cet6.ai.ChatCompletionStream
import com.example.cet6.ai.readResponsesStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

/** Original, offline records; no client, account or network is used. */
class AiStreamChecks(private val context: Context) {
    fun verify(): Int = runBlocking {
        val root = context.assets.open("ai-stream-regression-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        check(root.getInt("version") == 1)
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) verifyCase(cases.getJSONObject(i))
        cases.length()
    }

    private suspend fun verifyCase(case: JSONObject) {
        val emitted = StringBuilder()
        val outcome = runCatching {
            if (case.getString("protocol") == "chat") {
                val parser = ChatCompletionStream()
                val records = case.getJSONArray("records")
                for (i in 0 until records.length()) emitted.append(parser.accept(records.getString(i)))
                parser.result()
            } else {
                val type = case.optString("contentType", "text/event-stream")
                val response = Response.Builder().request(Request.Builder().url("https://fixture.invalid/").build())
                    .protocol(Protocol.HTTP_1_1).code(case.optInt("httpCode", 200)).message("offline fixture")
                    .body(case.getString("wire").toResponseBody(type.toMediaType())).apply {
                        if (!case.optBoolean("missingHeader")) header("Content-Type", type)
                    }.build()
                readResponsesStream(response) { emitted.append(it) }
            }
        }
        val id = case.getString("id")
        if (case.isNull("expected")) {
            val failure = outcome.exceptionOrNull()
            check(failure is IllegalStateException) { "$id accepted malformed or incomplete stream" }
            check(!failure.message.orEmpty().contains("fixture-private-marker")) { "$id echoed response content in error" }
            check(!emitted.contains("fixture-private-marker")) { "$id emitted malformed content" }
        } else {
            check(outcome.isSuccess) { "$id rejected a compatible stream: ${outcome.exceptionOrNull()?.javaClass?.simpleName}" }
            check(outcome.getOrThrow() == case.getString("expected")) { "$id lost or changed answer text" }
            check(emitted.toString() == case.getString("expected")) { "$id emitted different answer text" }
        }
    }
}
