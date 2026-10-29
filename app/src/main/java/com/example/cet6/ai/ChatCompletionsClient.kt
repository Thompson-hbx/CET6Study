package com.example.cet6.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ChatCompletionsClient(http: OkHttpClient) {
    // Never forward API credentials to a redirected host or retry a billed generation automatically.
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    suspend fun models(connection: ApiConnection): List<AvailableModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(connection.endpoint("models"))
            .header("Authorization", "Bearer ${connection.apiKey}").build()
        val call = http.newCall(request)
        coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { apiFailure(response.code) }
                    val array = JSONObject(response.body?.string() ?: error("模型列表为空")).getJSONArray("data")
                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            val id = item.getString("id")
                            if (id.isNotBlank()) add(AvailableModel(id, item.optString("name", id)))
                        }
                    }.distinctBy { it.slug }
                }
            } finally { watcher.cancel() }
        }
    }

    suspend fun stream(connection: ApiConnection, model: String, prompt: String,
        onDelta: suspend (String) -> Unit): String = stream(connection, model, prompt, null, onDelta)

    suspend fun stream(connection: ApiConnection, model: String, prompt: String,
        reasoning: ReviewReasoning?, onDelta: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(connection.endpoint("chat").newBuilder().addPathSegment("completions").build())
            .header("Authorization", "Bearer ${connection.apiKey}")
            .header("Accept", "text/event-stream")
            .post(chatPayload(model, prompt, reasoning).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = http.newCall(request)
        coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if (reasoning != null && response.code in setOf(400, 422)) throw DeepReviewRejectedException()
                    check(response.isSuccessful) { apiFailure(response.code) }
                    val body = response.body ?: error("响应流为空")
                    check(body.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } == true) {
                        "服务端未返回 SSE 流，请检查 Base URL 和接口兼容性"
                    }
                    val parser = ChatCompletionStream()
                    val records = SseReader(body.charStream().buffered())
                    while (!parser.complete) {
                        val record = records.next() ?: break
                        val delta = parser.accept(record.data)
                        if (delta.isNotEmpty()) onDelta(delta)
                    }
                    parser.result()
                }
            } finally { watcher.cancel() }
        }
    }
}

private fun apiFailure(code: Int): String = "API 请求失败：HTTP $code，" + when (code) {
    401 -> "请检查 API Key"
    402 -> "余额不足，请检查服务商账户"
    403 -> "请检查账户权限与地区限制"
    404 -> "请检查 Base URL 和模型名称"
    429 -> "请求过于频繁或额度不足，请稍后重试"
    in 500..599 -> "服务暂不可用，请稍后重试"
    else -> "请检查接口配置与请求参数"
}
