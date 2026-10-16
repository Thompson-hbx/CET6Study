package com.example.cet6.ai

/** A missing MIME header is accepted only after a real Responses creation event. */
internal class ResponsesStreamStart(headerMissing: Boolean) {
    private var needsValidation = headerMissing

    fun accept(type: String, responseObject: String?) {
        if (!needsValidation) return
        check(type == "response.created" && responseObject == "response") {
            "响应缺少 Content-Type，且首个事件不是有效 Responses 创建事件"
        }
        needsValidation = false
    }
}
