package com.example.cet6.server

import com.example.cet6.sync.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

fun main() {
    val secret = System.getenv("XIYE_SYNC_SECRET") ?: error("请先配置 XIYE_SYNC_SECRET（32–256 位随机英文字符），程序不会打印密钥")
    require(secret.length in 32..256 && secret.all { it.code in 33..126 }) { "同步密钥格式无效" }
    val port = (System.getenv("XIYE_SYNC_PORT") ?: "8787").toInt().also { require(it in 1..65535) }
    val store = StudyStore(File(System.getenv("XIYE_SYNC_DATABASE") ?: "data/xiye-sync.db"))
    Runtime.getRuntime().addShutdownHook(Thread { store.close() })
    embeddedServer(Netty, host = "127.0.0.1", port = port) { studyModule(store, secret) }.start(wait = true)
}

fun Application.studyModule(store: StudyStore, secret: String) {
    val expected = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
    install(StatusPages) {
        exception<IllegalArgumentException> { call, _ -> call.respond(HttpStatusCode.BadRequest, "同步数据未通过校验") }
        exception<JSONException> { call, _ -> call.respond(HttpStatusCode.BadRequest, "同步 JSON 格式无效") }
        exception<java.time.DateTimeException> { call, _ -> call.respond(HttpStatusCode.BadRequest, "同步日期无效") }
        exception<java.nio.charset.CharacterCodingException> { call, _ -> call.respond(HttpStatusCode.BadRequest, "同步文字编码无效") }
        exception<Throwable> { call, cause ->
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            call.respond(HttpStatusCode.InternalServerError, "同步暂未完成，请稍后重试")
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (call.request.path() != "/health") {
            val header = call.request.header(HttpHeaders.Authorization).orEmpty()
            val supplied = if (header.startsWith("Bearer ") && header.length <= 263) header.substring(7) else ""
            val hash = MessageDigest.getInstance("SHA-256").digest(supplied.toByteArray())
            if (supplied.isEmpty() || !MessageDigest.isEqual(expected, hash)) {
                call.respond(HttpStatusCode.Unauthorized, "配对密钥无效")
                finish()
            }
        }
    }
    routing {
        get("/health") { call.respondText("ok") }
        get("/v1/study") {
            val snapshot = store.snapshot()
            val etag = "\"${snapshot.serverId}:${snapshot.revision}\""
            call.response.header(HttpHeaders.ETag, etag)
            if (call.request.header(HttpHeaders.IfNoneMatch) == etag) {
                call.respond(HttpStatusCode.NotModified)
            } else {
                call.respondText(snapshot.json().toString(), ContentType.Application.Json)
            }
        }
        put("/v1/study") {
            val limit = SyncDocument.MAX_BYTES + 4096
            val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (declared != null && declared > limit) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@put
            }
            val input = call.receiveChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.readAvailable(buffer)
                if (count < 0) break
                if (output.size() + count > limit) {
                    call.respond(HttpStatusCode.PayloadTooLarge)
                    return@put
                }
                output.write(buffer, 0, count)
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
            val json = JSONObject(text)
            fields(json, setOf("serverId", "revision", "changes"))
            require(json.get("serverId") is String)
            val revision = integer(json, "revision").also { require(it >= 0) }
            val changes = json.getJSONObject("changes")
            require(changes.length() <= SyncDocument.MAX_RECORDS)
            val result = store.update(
                json.getString("serverId"),
                revision,
                changes.keys().asSequence().associateWith { canonical(changes.getJSONObject(it)) },
            )
            if (result == null) {
                call.respond(HttpStatusCode.Conflict, "服务器已有新版本")
            } else {
                call.respondText(JSONObject().put("serverId", result.serverId).put("revision", result.revision).toString(), ContentType.Application.Json)
            }
        }
    }
}
