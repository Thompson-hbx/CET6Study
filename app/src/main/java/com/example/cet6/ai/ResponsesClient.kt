package com.example.cet6.ai

import com.example.cet6.auth.ChatGptAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AvailableModel(val slug: String, val displayName: String)

/** The ChatGPT-plan OSS endpoint contract is intentionally narrower than normal API keys. */
class ResponsesClient(private val http: OkHttpClient, private val auth: ChatGptAuth) {
    suspend fun models(): List<AvailableModel> = withContext(Dispatchers.IO) {
        val session = auth.validSession()
        check(session.canUsePlan) { "未授予 ChatGPT 套餐使用权限" }
        val request = Request.Builder().url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer ${session.accessToken}").get().build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { failureMessage("模型列表", response) }
            val json = JSONObject(response.body?.string() ?: error("模型列表为空"))
            val array = json.getJSONArray("models")
            buildList {
                for (i in 0 until array.length()) {
                    val model = array.getJSONObject(i)
                    if (model.optString("visibility") != "list") continue
                    val slug = model.optString("slug")
                    if (slug.isNotBlank()) add(AvailableModel(slug, model.optString("display_name", slug)))
                }
            }
        }
    }

    /** Streams text chunks, and only accepts success after response.completed. */
    suspend fun stream(model: String, prompt: String, onDelta: suspend (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val session = auth.validSession()
            check(session.canUsePlan) { "账号没有授权 chatgpt.tokens.use.direct" }
            val payload = JSONObject().apply {
                put("model", model)
                put("instructions", "你是严谨的大学英语六级备考教练。解释必须准确，区分原文证据与推测；不伪造评分标准。输出中文解释并保留必要英文术语。")
                put("input", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                put("store", false)
                put("stream", true)
            }
            val request = Request.Builder().url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { failureMessage("AI", response) }
                val contentType = response.body?.contentType()
                val headerMissing = response.header("Content-Type") == null
                check(headerMissing || contentType?.let { it.type == "text" && it.subtype == "event-stream" } == true) {
                    unexpectedStreamMessage(response)
                }
                val startGuard = ResponsesStreamStart(headerMissing)
                var complete = false
                var errorEvent: String? = null
                val out = StringBuilder()
                val reader = response.body?.charStream()?.buffered() ?: error("响应流为空")
                val records = SseReader(reader)
                while (!complete && errorEvent == null) {
                    val record = records.next() ?: break
                    if (record.data == "[DONE]") break
                    val json = try { JSONObject(record.data) } catch (_: Exception) {
                        error("响应包含无效 SSE JSON")
                    }
                    val type = json.optString("type", record.event)
                    startGuard.accept(type, json.optJSONObject("response")?.optString("object"))
                    when (type) {
                        "response.output_text.delta", "response.refusal.delta" -> {
                            val delta = json.getString("delta")
                            out.append(delta)
                            onDelta(delta)
                        }
                        "response.completed" -> complete = true
                        "response.failed", "response.incomplete", "error" -> {
                            val terminal = json.optJSONObject("response")
                            val detail = terminal?.optJSONObject("error")?.optString("code")
                                ?: terminal?.optJSONObject("incomplete_details")?.optString("reason")
                                ?: json.optJSONObject("error")?.optString("code")
                                ?: json.optString("code", "stream_error")
                            errorEvent = "AI 响应未完成：$detail"
                        }
                    }
                }
                check(errorEvent == null) { errorEvent ?: "AI 请求失败" }
                check(complete) { "网络流提前中断，没有收到 response.completed" }
                out.toString()
            }
        }
}

/** Inspect only protocol metadata; never display arbitrary response text or credentials. */
private fun unexpectedStreamMessage(response: Response): String {
    val preview = response.peekBody(4096).string()
    val json = try { JSONObject(preview) } catch (_: Exception) { null }
    val firstData = preview.lineSequence().firstOrNull { it.startsWith("data:") }
        ?.removePrefix("data:")?.trimStart()
    val event = try { firstData?.let { JSONObject(it) } } catch (_: Exception) { null }
    fun safe(value: String?): String = value?.takeIf {
        it.matches(Regex("[a-zA-Z0-9_.:/-]{1,160}"))
    }.orEmpty()
    val shape = when {
        event != null -> "sse"
        json?.has("error") == true -> "error"
        json?.has("detail") == true -> "detail"
        json != null -> "json"
        preview.trimStart().startsWith('<') -> "html"
        else -> "unknown"
    }
    return "服务端未返回预期流类型：HTTP ${response.code}" +
        "；content_type=${response.body?.contentType() ?: "missing"}；shape=$shape" +
        "；type=${safe((event ?: json)?.optString("type"))}" +
        "；code=${safe(json?.optJSONObject("error")?.optString("code"))}" +
        "；request_id=${safe(response.header("x-request-id"))}"
}

/** Preserve useful diagnostics without echoing arbitrary server text or credentials. */
private fun failureMessage(operation: String, response: Response): String {
    val json = try { JSONObject(response.peekBody(32_768).string()) } catch (_: Exception) { null }
    val error = json?.optJSONObject("error")
    fun safe(value: String?): String = value?.takeIf { it.matches(Regex("[a-zA-Z0-9_.:/-]{1,160}")) }.orEmpty()
    val code = safe(error?.optString("code"))
    val param = safe(error?.optString("param"))
    val requestId = safe(response.header("x-request-id"))
    val shape = when {
        error != null -> "error"
        json?.has("detail") == true -> "detail"
        json != null -> "json"
        else -> "non-json"
    }
    val hint = when (response.code) {
        401 -> "请检查登录与授权状态"
        403 -> "请检查账号、地区或应用接入资格"
        429 -> "请检查 ChatGPT 用量设置，稍后重试"
        503 -> "服务暂不可用，请稍后重试"
        else -> "请检查请求与网络"
    }
    return "$operation 请求失败：HTTP ${response.code}，$hint；shape=$shape" +
        (if (code.isNotEmpty()) "，code=$code" else "") +
        (if (param.isNotEmpty()) "，param=$param" else "") +
        (if (requestId.isNotEmpty()) "，request_id=$requestId" else "")
}
