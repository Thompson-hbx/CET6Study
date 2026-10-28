package com.example.cet6.auth

import org.json.JSONException
import org.json.JSONObject

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

/** Android's JSON getters coerce values; validate wire types before reading or storing them. */
internal fun parseTokenResponse(text: String): JSONObject {
    val tokens = try { JSONObject(text) } catch (_: JSONException) {
        throw IllegalArgumentException("Token 响应格式无效")
    }
    fun string(field: String): String = (tokens.opt(field) as? String)
        ?: throw IllegalArgumentException("Token 响应字段格式无效：$field")
    val expires = tokens.opt("expires_in")
    require(expires is Int || expires is Long) { "Token 有效期格式无效" }
    validateTokenFields(string("access_token"), string("refresh_token"), string("token_type"),
        (expires as Number).toLong())
    if (tokens.has("scope")) string("scope")
    if (tokens.has("id_token")) string("id_token")
    return tokens
}
