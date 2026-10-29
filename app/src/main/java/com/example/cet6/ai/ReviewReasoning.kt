package com.example.cet6.ai

import org.json.JSONObject

enum class ReasoningEffort(val wire: String, val label: String) {
    HIGH("high", "高"), XHIGH("xhigh", "更高"), MAX("max", "最大")
}

enum class ReasoningFormat(val label: String) {
    OPENAI("OpenAI 兼容"), DEEPSEEK("DeepSeek 兼容");

    val efforts: List<ReasoningEffort>
        get() = if (this == DEEPSEEK) listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX)
            else ReasoningEffort.entries
}

/** Explicit user choice; never guesses a model's capability or silently retries at a lower effort. */
data class ReviewReasoning(val effort: ReasoningEffort, val format: ReasoningFormat = ReasoningFormat.OPENAI) {
    init { require(effort in format.efforts) { "当前请求格式不支持此思考档位" } }

    internal fun applyToChat(payload: JSONObject) {
        payload.put("reasoning_effort", effort.wire)
        if (format == ReasoningFormat.DEEPSEEK) payload.put("thinking", JSONObject().put("type", "enabled"))
    }
}

class DeepReviewRejectedException : IllegalStateException(
    "服务拒绝了深度批改请求（HTTP 400/422）；请在「我的 → AI 连接」检查模型支持的档位和请求格式，或关闭深度批改后手动重试。未自动降档或重发。")
