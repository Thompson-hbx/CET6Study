package com.example.cet6.auth

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/** Single-account, on-device OSS OAuth flow. No embedded WebView, secret, or API key. */
class ChatGptAuth(http: OkHttpClient, private val store: SecureSessionStore) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private val refreshLock = Mutex()
    private val signInLock = Mutex()
    private val resource = "https://api.openai.com/v1"
    private val tokenEndpoint = "https://auth.openai.com/api/accounts/oauth/token"
    private val scopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private val random = SecureRandom()
    // Retain an issued registration across a failed first exchange, within this process.
    private var pendingClientId: String? = null

    fun session(): ChatGptSession? = store.load()?.takeIf { it.accessToken.isNotBlank() }

    suspend fun signOut(): Boolean = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val saved = store.load()
            var revoked = false
            try {
                if (saved != null && saved.refreshToken.isNotBlank()) {
                    val discovery = Request.Builder()
                        .url("https://auth.openai.com/.well-known/openid-configuration").build()
                    val endpoint = http.newCall(discovery).execute().use { response ->
                        check(response.isSuccessful) { "无法获取撤销端点" }
                        JSONObject(response.body?.string() ?: error("discovery 为空"))
                            .getString("revocation_endpoint")
                    }
                    val endpointUri = java.net.URI(endpoint)
                    check(endpointUri.scheme == "https" && endpointUri.host == "auth.openai.com" &&
                        endpointUri.userInfo == null && endpointUri.port in listOf(-1, 443))
                    val body = FormBody.Builder().add("token", saved.refreshToken)
                        .add("token_type_hint", "refresh_token").add("client_id", saved.clientId).build()
                    for (attempt in 0..1) {
                        try {
                            val retry = http.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { response ->
                                revoked = response.code == 200
                                response.code >= 500
                            }
                            if (!retry) break
                        } catch (_: java.io.IOException) { /* Retry once, without exposing the token. */ }
                        if (attempt == 0) delay(500)
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                // Discovery/transport/validation failure cannot confirm remote revocation.
            } finally { store.clear() }
            revoked
        }
    }

    suspend fun signIn(openBrowser: (Uri) -> Unit): ChatGptSession = signInLock.withLock {
        withContext(Dispatchers.IO) {
            val existing = store.load()
            val state = secureRandom()
            val nonce = secureRandom()
            val verifier = secureRandom(64)
            val challenge = encode(MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII)))
            // Listener exists before the system browser is opened.
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 180_000
                val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
                val clientId = existing?.clientId ?: pendingClientId ?: "dynamic_agent_client"
                val newRegistration = clientId == "dynamic_agent_client"
                val url = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
                    .appendQueryParameter("client_id", clientId)
                    .appendQueryParameter("ext_agent_host_id", store.hostId())
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("scope", scopes)
                    .appendQueryParameter("resource", resource)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge)
                if (newRegistration) url.appendQueryParameter("agent_name_hint", "CET6 Study")
                else if (existing != null) {
                    if (!existing.canUsePlan) url.appendQueryParameter("prompt", "consent")
                    if (existing.idToken.isNotBlank()) url.appendQueryParameter("id_token_hint", existing.idToken)
                    if (existing.email.isNotBlank()) url.appendQueryParameter("login_hint", existing.email)
                }
                withContext(Dispatchers.Main) { openBrowser(url.build()) }
                val query = try {
                    runInterruptible { receiveOAuthCallback(listener, state) }
                } catch (_: SocketTimeoutException) {
                    error("等待 ChatGPT 登录超时，请重试")
                }
                val oauthError = query["error"]
                if (oauthError != null) error("OpenAI 授权未完成，请重试或检查应用授权")
                val code = query["code"]?.takeIf { it.isNotBlank() } ?: error("回调没有授权 code")
                val returnedId = query["client_id"]
                val issuedId: String = if (newRegistration) {
                    val issued = returnedId ?: error("首次注册未返回 client_id")
                    require(issued.isNotBlank() && issued != "dynamic_agent_client") { "首次注册未返回有效 client_id" }
                    issued
                } else {
                    require(returnedId == null || returnedId == clientId) {
                        "客户端注册标识变化，已拒绝"
                    }
                    clientId
                }
                if (existing == null) pendingClientId = issuedId
                val body = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", issuedId)
                    .add("code", code)
                    .add("code_verifier", verifier)
                    .add("redirect_uri", redirect)
                    .add("resource", resource).build()
                val tokens = postToken(body)
                val identity = OidcVerifier(http).verify(tokens.getString("id_token"), issuedId, nonce)
                if (existing != null) require(existing.subject == identity.subject) {
                    "登录的是另一个 ChatGPT 账号，未覆盖已有凭证"
                }
                val session = ChatGptSession(
                    clientId = issuedId, subject = identity.subject, email = identity.email,
                    accessToken = tokens.getString("access_token"),
                    refreshToken = tokens.optString("refresh_token", ""),
                    idToken = tokens.getString("id_token"),
                    scopes = tokens.optString("scope", ""),
                    expiresAtMillis = System.currentTimeMillis() + tokens.getLong("expires_in") * 1000
                )
                store.save(session)
                pendingClientId = null
                session
            }
        }
    }

    suspend fun validSession(): ChatGptSession = refreshLock.withLock {
        val saved = store.load() ?: error("请先登录 ChatGPT")
        check(saved.accessToken.isNotBlank()) { "请重新登录 ChatGPT" }
        if (saved.expiresAtMillis > System.currentTimeMillis() + 120_000L) return@withLock saved
        check(saved.refreshToken.isNotBlank()) { "授权已过期，请重新登录" }
        val body = FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", saved.clientId)
            .add("refresh_token", saved.refreshToken)
            .add("resource", resource).build()
        val tokens = try {
            withContext(Dispatchers.IO) { postToken(body) }
        } catch (e: OAuthTokenException) {
            if (isTerminalRefreshError(e.code)) withContext(Dispatchers.IO) { store.clear() }
            throw e
        }
        // Token refresh is atomic because refreshLock serializes rotation.
        val updated = saved.copy(
            accessToken = tokens.getString("access_token"),
            refreshToken = tokens.getString("refresh_token"),
            // A refreshed ID token would need independent signature verification;
            // retain the previously verified token only as an authorization hint.
            idToken = saved.idToken,
            scopes = tokens.optString("scope", saved.scopes),
            expiresAtMillis = System.currentTimeMillis() + tokens.getLong("expires_in") * 1000
        )
        withContext(Dispatchers.IO) { store.save(updated) }
        updated
    }

    private fun postToken(body: FormBody): JSONObject {
        val request = Request.Builder().url(tokenEndpoint).post(body).build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: error("Token 响应为空")
            if (!response.isSuccessful) {
                val error = try { JSONObject(text).opt("error") } catch (_: Exception) { null }
                val rawCode = when (error) {
                    is String -> error
                    is JSONObject -> error.optString("code")
                    else -> "oauth_error"
                }
                val code = rawCode.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,100}")) } ?: "oauth_error"
                throw OAuthTokenException(code, response.code)
            }
            JSONObject(text).also {
                validateTokenFields(it.getString("access_token"), it.getString("refresh_token"),
                    it.getString("token_type"), it.getLong("expires_in"))
            }
        }
    }

    private fun secureRandom(n: Int = 32): String = ByteArray(n).also { random.nextBytes(it) }.let(::encode)
    private fun encode(bytes: ByteArray) = Base64.encodeToString(
        bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private class OAuthTokenException(val code: String, status: Int) :
    IllegalStateException("OAuth Token 请求失败：HTTP $status，$code")
