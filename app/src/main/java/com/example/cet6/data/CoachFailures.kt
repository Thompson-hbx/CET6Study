package com.example.cet6.data

import java.io.IOException
import java.net.SocketTimeoutException

/** UI guidance for the existing transport; never echoes arbitrary exception messages. */
object CoachFailures {
    fun describe(error: Exception): String {
        val message = error.message.orEmpty()
        val http = Regex("HTTP ([1-5][0-9]{2})\\b").find(message)?.groupValues?.get(1)?.toIntOrNull()
        val code = Regex("(?:code=|OAuth Token 请求失败：HTTP [0-9]{3}，|AI 响应未完成：)([a-z_]+)\\b")
            .find(message)?.groupValues?.get(1)
        return when {
            code in setOf("invalid_grant", "invalid_token", "token_expired") || http == 401 ||
                message in setOf("请先登录 ChatGPT", "请重新登录 ChatGPT", "授权已过期，请重新登录") ->
                "登录授权或 API Key 需要检查，请到「连接」页重新连接所选 AI 服务后重试。"
            http == 402 -> "API 余额不足，请到服务商平台检查余额；反复重试通常无法解决。"
            code in setOf("insufficient_quota", "credit_balance_exhausted", "usage_limit_exceeded",
                "organization_usage_limit_exceeded", "organization_spend_limit_exceeded", "project_spend_limit_exceeded") ->
                "服务返回用量或额度限制，请查看账号用量及具体限制；反复重试通常无法解决。"
            http == 429 || code in setOf("rate_limit_exceeded", "slow_down") ->
                "服务限制了本次请求，请稍后手动重试；若持续出现，请检查账号用量。"
            http == 403 -> "服务拒绝访问，请检查账号授权、当前网络地区或应用接入资格。"
            http != null && http >= 500 -> "AI 服务暂时不可用，请稍后手动重试。"
            error is SocketTimeoutException -> "连接或响应超时，请检查网络，稍后手动重试。"
            error is IOException -> "连接未能完成，请检查网络后手动重试。"
            message.contains("response.completed") || message.contains("[DONE]") || message.startsWith("AI 响应未完成") ||
                message == "AI 响应未正常结束" ->
                "响应尚未完整结束，请稍后重试；已收到的片段只作临时参考。"
            error is IllegalArgumentException || message.contains("SSE JSON") || message.contains("预期流类型") ->
                "返回内容未通过格式检查，请稍后重试。"
            else -> "本次请求未完成，请稍后手动重试；若仍失败，可到「连接」页检查连接。"
        }
    }
}
