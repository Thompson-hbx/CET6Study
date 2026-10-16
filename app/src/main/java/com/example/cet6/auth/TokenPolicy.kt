package com.example.cet6.auth

/** Only terminal refresh errors invalidate stored credentials; transport errors do not. */
internal fun isTerminalRefreshError(code: String): Boolean = code in setOf(
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
    "refresh_token_invalidated", "refresh_token_reused"
)

internal fun validateTokenFields(access: String, refresh: String, type: String, expires: Long) {
    require(access.isNotBlank()) { "Token 响应缺少 access_token" }
    require(refresh.isNotBlank()) { "Token 响应缺少轮换 refresh_token" }
    require(type.equals("Bearer", ignoreCase = true)) { "不支持的 token_type" }
    require(expires in 1..86_400L) { "Token 有效期无效" }
}
