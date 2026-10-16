package com.example.cet6.auth

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** Enforces issuer, audience, nonce, timestamps, and the RS256 signature. */
class OidcVerifier(private val http: OkHttpClient) {
    data class Identity(val subject: String, val email: String)
    suspend fun verify(idToken: String, clientId: String, nonce: String): Identity = withContext(Dispatchers.IO) {
        val parts = idToken.split('.')
        require(parts.size == 3) { "无效 ID token" }
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "签名算法不受支持" }
        val request = Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build()
        val jwks = http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "OpenAI 公钥获取失败：${response.code}" }
            JSONObject(response.body?.string() ?: error("公钥为空"))
        }
        val keys = jwks.getJSONArray("keys")
        var pub: java.security.PublicKey? = null
        for (i in 0 until keys.length()) {
            val j = keys.getJSONObject(i)
            if (j.optString("kid") == header.getString("kid") && j.optString("kty") == "RSA" &&
                j.optString("use", "sig") == "sig" && j.optString("alg", "RS256") == "RS256") {
                pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
                    BigInteger(1, decode(j.getString("n"))), BigInteger(1, decode(j.getString("e")))))
                break
            }
        }
        val checkedKey = pub ?: error("未找到匹配的 OpenAI 公钥")
        val verify = Signature.getInstance("SHA256withRSA")
        verify.initVerify(checkedKey)
        verify.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        check(verify.verify(decode(parts[2]))) { "ID token 签名错误" }
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        require(claims.getString("iss") == "https://auth.openai.com")
        val aud = claims.get("aud")
        val audienceOk = when (aud) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.getString(it) == clientId }
            else -> false
        }
        require(audienceOk) { "ID token audience 不符" }
        if ((aud is JSONArray && aud.length() > 1) || claims.has("azp")) {
            require(claims.getString("azp") == clientId) { "ID token azp 不符" }
        }
        require(claims.getString("nonce") == nonce) { "ID token nonce 不符" }
        val now = System.currentTimeMillis() / 1000L
        require(claims.getLong("exp") > now - 5) { "ID token 已过期" }
        require(claims.getLong("iat") <= now + 300) { "ID token iat 异常" }
        if (claims.has("nbf")) require(claims.getLong("nbf") <= now + 5)
        val subject = claims.getString("sub")
        require(subject.isNotBlank())
        Identity(subject, claims.optString("email", ""))
    }
    private fun decode(text: String) = Base64.decode(
        text + "=".repeat((4 - text.length % 4) % 4), Base64.URL_SAFE or Base64.NO_WRAP)
}
