package com.example.cet6.ai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class AiProvider(val label: String, val defaultUrl: String) {
    CHATGPT("ChatGPT", ""),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com"),
    CUSTOM("自定义兼容接口", "")
}

/** Deliberately not a data class: generated toString must never expose the key. */
class ApiConnection private constructor(val baseUrl: String, val apiKey: String) {
    fun endpoint(path: String): HttpUrl = validatedBaseUrl(baseUrl).newBuilder().addPathSegment(path).build()

    companion object {
        fun validatedBaseUrl(value: String): HttpUrl {
            val url = value.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null) { "Base URL 必须是 HTTPS 地址，且不能包含账号、查询参数或片段" }
            require(!url.encodedPath.trimEnd('/').endsWith("/chat/completions") &&
                !url.encodedPath.trimEnd('/').endsWith("/models")) { "请填写 Base URL，不要包含 /chat/completions 或 /models" }
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
        }

        fun create(baseUrl: String, apiKey: String): ApiConnection {
            require(apiKey.isNotBlank() && apiKey.trim().all { it.code in 33..126 }) { "请填写有效的 API Key" }
            return ApiConnection(validatedBaseUrl(baseUrl).toString(), apiKey.trim())
        }
    }
}

internal fun chatPayload(model: String, prompt: String): JSONObject = JSONObject().apply {
    require(model.isNotBlank()) { "请先选择或填写模型" }
    put("model", model)
    put("messages", JSONArray()
        .put(JSONObject().put("role", "system").put("content", "你是严谨的大学英语六级备考教练。解释必须准确，区分原文证据与推测；不伪造评分标准。输出中文解释并保留必要英文术语。"))
        .put(JSONObject().put("role", "user").put("content", prompt)))
    put("stream", true)
}

/** Only a stopped choice followed by [DONE] is a complete answer; reasoning is not answer text. */
internal class ChatCompletionStream {
    private val output = StringBuilder()
    private var stopped = false
    var complete = false
        private set

    fun accept(data: String): String {
        check(!complete) { "AI 流已结束" }
        if (data == "[DONE]") {
            check(stopped) { "AI 响应未正常结束" }
            complete = true
            return ""
        }
        try {
            val json = JSONObject(data)
            check(!json.has("error")) { "AI 服务返回流错误，请检查账号额度或稍后重试" }
            val choices = json.getJSONArray("choices")
            var text = ""
            val indexes = mutableSetOf<Long>()
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                val index = choice.opt("index")
                check((index is Int || index is Long) && (index as Number).toLong() >= 0) {
                    "AI 选项编号格式无效"
                }
                check(indexes.add((index as Number).toLong())) { "AI 选项编号重复" }
                if (index.toLong() != 0L) continue
                check(!stopped) { "AI 在结束后继续返回内容" }
                check(choice.isNull("delta") || choice.opt("delta") is JSONObject) { "AI 文本增量格式无效" }
                val delta = choice.optJSONObject("delta")
                if (delta != null && !delta.isNull("content")) {
                    text = delta.opt("content") as? String ?: error("AI 文本增量格式无效")
                }
                if (!choice.isNull("finish_reason")) {
                    check(choice.opt("finish_reason") is String && choice.opt("finish_reason") == "stop") {
                        "AI 响应未完成（长度限制、内容过滤或不支持的结束原因）"
                    }
                    stopped = true
                }
            }
            output.append(text)
            return text
        } catch (_: JSONException) {
            error("响应包含无效 SSE JSON")
        }
    }

    fun result(): String {
        check(complete) { "网络流提前中断，没有收到 [DONE]" }
        check(output.isNotBlank()) { "AI 没有返回可用文本" }
        return output.toString()
    }
}
