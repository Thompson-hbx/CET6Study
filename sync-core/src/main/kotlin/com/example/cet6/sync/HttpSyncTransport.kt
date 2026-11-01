package com.example.cet6.sync

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** No generated toString: a connection must never print the pairing secret. */
class SyncConnection private constructor(val baseUrl: String, val secret: String) {
    fun endpoint(): HttpUrl = baseUrl.toHttpUrlOrNull()!!.newBuilder().addPathSegments("v1/study").build()
    companion object {
        fun create(address: String, secret: String): SyncConnection {
            val url = address.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "同步服务器必须使用 HTTPS，且地址不能包含账号、查询参数或片段"
            }
            require(secret.length in 32..256 && secret.all { it.code in 33..126 }) { "配对密钥须为 32–256 位可打印英文字符" }
            return SyncConnection(url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString(), secret)
        }
    }
}

class HttpSyncTransport(private val connection: SyncConnection, client: OkHttpClient = OkHttpClient()) : SyncTransport {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    override fun fetch(known: RemoteSnapshot?): RemoteSnapshot {
        val request = Request.Builder().url(connection.endpoint()).header("Authorization", "Bearer ${connection.secret}")
        if (known != null) request.header("If-None-Match", "\"${known.serverId}:${known.revision}\"")
        http.newCall(request.build()).execute().use { response ->
            if (response.code == 304) return requireNotNull(known)
            check(response.isSuccessful) { failure(response.code) }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= SyncDocument.MAX_BYTES + 4096L) { "服务器数据超过同步容量" }
            val source = body.source()
            require(!source.request(SyncDocument.MAX_BYTES + 4097L)) { "服务器数据超过同步容量" }
            return RemoteSnapshot.parse(JSONObject(source.readUtf8()))
        }
    }

    override fun push(expected: RemoteSnapshot, document: SyncDocument): RemoteSnapshot? {
        val changes = document.records.filter { (key, value) -> expected.document.records[key] != value }
        val body = JSONObject().put("serverId", expected.serverId).put("revision", expected.revision)
            .put("changes", JSONObject().apply { changes.forEach { (key, value) -> put(key, JSONObject(value)) } }).toString()
        require(body.toByteArray().size <= SyncDocument.MAX_BYTES + 4096) { "本次同步超过容量" }
        val request = Request.Builder().url(connection.endpoint()).header("Authorization", "Bearer ${connection.secret}")
            .put(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 409) return null
            check(response.isSuccessful) { failure(response.code) }
            val source = requireNotNull(response.body).source()
            require(!source.request(4097)) { "同步确认响应无效" }
            val json = JSONObject(source.readUtf8())
            fields(json, setOf("serverId", "revision"))
            require(json.get("serverId") == expected.serverId)
            val revision = integer(json, "revision")
            require(revision >= expected.revision)
            return RemoteSnapshot(expected.serverId, revision, document)
        }
    }

    private fun failure(status: Int): String = when (status) {
        401 -> "配对密钥无效，请核对服务器与两端配置"
        413 -> "同步数据超过服务器容量"
        429 -> "同步请求过于频繁，请稍后重试"
        else -> "同步服务器返回 HTTP $status，本机数据已保留"
    }
}
