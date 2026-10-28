package com.example.cet6

import com.example.cet6.auth.ChatGptAuth
import com.example.cet6.auth.ChatGptSession
import com.example.cet6.auth.SessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** All sessions and replies are original placeholders; interceptors never reach the network. */
class OAuthRefreshChecks {
    suspend fun verify() {
        malformedSuccessfulReplyDoesNotReplaceSession()
        liveSessionDoesNotRefresh()
        concurrentRequestsRotateExactlyOnce()
        temporaryHttpAndTransportFailuresPreserveSavedSession()
        terminalRefreshFailureInvalidatesOnlySessionCredentials()
        cancellingCallerStillPersistsRotatedSession()
        failedLocalSaveCannotReportRefreshSuccess()
        missingSessionOrReplacementDoesNotRequest()
        refreshedOptionalFieldsKeepVerifiedIdentity()
        cancelledWaitingCallerDoesNotStartAnotherRefresh()
    }

    private fun saved(expired: Boolean = true) = ChatGptSession("offline-client", "offline-subject", "",
        "offline-access-old", "offline-refresh-old", "offline-verified-id", "chatgpt.tokens.use.direct",
        System.currentTimeMillis() + if (expired) -1000L else 3_600_000L)

    private class MemoryStore(var value: ChatGptSession?) : SessionStorage {
        var saves = 0
        var clears = 0
        var failSave = false
        @Synchronized override fun load() = value
        @Synchronized override fun save(session: ChatGptSession) {
            if (failSave) throw IOException("controlled local save failure")
            value = session
            saves++
        }
        @Synchronized override fun clear() {
            value = value?.copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMillis = 0)
            clears++
        }
        override fun hostId() = "offline-host"
    }

    private fun reply() = JSONObject().put("access_token", "offline-access-new")
        .put("refresh_token", "offline-refresh-new").put("token_type", "Bearer").put("expires_in", 3600)

    private fun auth(store: MemoryStore, calls: AtomicInteger = AtomicInteger(), status: Int = 200,
        body: () -> String = { reply().toString() }): ChatGptAuth = ChatGptAuth(
        OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            check(chain.request().url.toString() == "https://auth.openai.com/api/accounts/oauth/token")
            check(chain.request().method == "POST")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("offline fixture")
                .body(body().toResponseBody("application/json".toMediaType())).build()
        }.build(), store)

    suspend fun liveSessionDoesNotRefresh() {
        val original = saved(false)
        val store = MemoryStore(original)
        val calls = AtomicInteger()
        check(auth(store, calls).validSession() == original)
        check(calls.get() == 0 && store.saves == 0 && store.clears == 0)
    }

    suspend fun concurrentRequestsRotateExactlyOnce() {
        val original = saved()
        val store = MemoryStore(original)
        val calls = AtomicInteger()
        val client = auth(store, calls)
        val sessions = coroutineScope { (1..20).map { async(Dispatchers.Default) { client.validSession() } }.awaitAll() }
        check(calls.get() == 1 && store.saves == 1 && store.clears == 0)
        check(sessions.all { it == store.value })
        check(store.value!!.accessToken == "offline-access-new" && store.value!!.refreshToken == "offline-refresh-new")
        check(store.value!!.idToken == original.idToken && store.value!!.subject == original.subject)
    }

    suspend fun temporaryHttpAndTransportFailuresPreserveSavedSession() {
        for (status in listOf(401, 429, 503)) {
            val original = saved()
            val store = MemoryStore(original)
            val calls = AtomicInteger()
            val failure = runCatching { auth(store, calls, status) { """{"error":"server_error"}""" }.validSession() }.exceptionOrNull()
            check(failure != null && calls.get() == 1 && store.value == original && store.clears == 0 && store.saves == 0)
        }
        val original = saved()
        val store = MemoryStore(original)
        val failure = runCatching { auth(store) { throw IOException("controlled offline") }.validSession() }.exceptionOrNull()
        check(failure is IOException && store.value == original && store.clears == 0 && store.saves == 0)
    }

    suspend fun terminalRefreshFailureInvalidatesOnlySessionCredentials() {
        val store = MemoryStore(saved())
        val original = store.value!!
        val client = auth(store, status = 400) { """{"error":"invalid_grant"}""" }
        check(runCatching { client.validSession() }.isFailure)
        check(store.clears == 1 && store.saves == 0 && client.session() == null)
        check(store.value == original.copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMillis = 0))
    }

    suspend fun cancellingCallerStillPersistsRotatedSession() {
        val store = MemoryStore(saved())
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = auth(store, calls) {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "Offline refresh fixture was not released" }
            reply().toString()
        }
        coroutineScope {
            var continued = false
            val request = launch(Dispatchers.Default) { client.validSession(); continued = true }
            try {
                entered.await()
                request.cancel()
            } finally { release.countDown() }
            request.join()
            check(request.isCancelled && !continued) { "Cancelled caller received a refreshed session" }
        }
        check(store.value!!.refreshToken == "offline-refresh-new" && store.saves == 1) {
            "Cancelled refresh lost the rotated session"
        }
        check(client.validSession() == store.value && calls.get() == 1)
    }

    suspend fun failedLocalSaveCannotReportRefreshSuccess() {
        val original = saved()
        val store = MemoryStore(original).apply { failSave = true }
        check(runCatching { auth(store).validSession() }.isFailure)
        check(store.value == original && store.saves == 0 && store.clears == 0)
    }

    suspend fun malformedSuccessfulReplyDoesNotReplaceSession() {
        val malformed = mutableListOf<JSONObject>()
        for (field in listOf("access_token", "refresh_token", "token_type", "scope", "id_token")) {
            for (value in listOf(JSONObject().put("data", "fixture-private-marker"), 123, true, JSONObject.NULL)) {
                malformed.add(reply().put(field, value))
            }
        }
        for (value in listOf("3600", 3600.5, true, JSONObject.NULL, 0, 86401, Long.MAX_VALUE)) {
            malformed.add(reply().put("expires_in", value))
        }
        for (field in listOf("access_token", "refresh_token", "token_type", "expires_in")) {
            malformed.add(reply().apply { remove(field) })
        }
        val records = malformed.map { it.toString() } + listOf("not-json fixture-private-marker", "[]", "")
        for ((index, text) in records.withIndex()) {
            val original = saved()
            val store = MemoryStore(original)
            val failure = runCatching { auth(store) { text }.validSession() }.exceptionOrNull()
            check(failure != null) { "Malformed token reply $index was accepted" }
            check(!failure.message.orEmpty().contains("fixture-private-marker")) { "Malformed token reply exposed body content" }
            check(store.value == original && store.saves == 0 && store.clears == 0)
        }
    }

    suspend fun missingSessionOrReplacementDoesNotRequest() {
        for (original in listOf(null, saved().copy(refreshToken = ""))) {
            val store = MemoryStore(original)
            val calls = AtomicInteger()
            check(runCatching { auth(store, calls).validSession() }.isFailure)
            check(calls.get() == 0 && store.value == original && store.saves == 0 && store.clears == 0)
        }
    }

    suspend fun refreshedOptionalFieldsKeepVerifiedIdentity() {
        for (scope in listOf(null, "", "openid chatgpt.tokens.use.direct")) {
            val original = saved()
            val store = MemoryStore(original)
            val body = reply().put("id_token", "offline-unverified-new-id")
            if (scope != null) body.put("scope", scope)
            val updated = auth(store) { body.toString() }.validSession()
            check(updated == store.value && store.saves == 1 && store.clears == 0)
            check(updated.clientId == original.clientId && updated.subject == original.subject && updated.idToken == original.idToken)
            check(updated.scopes == (scope ?: original.scopes))
        }
    }

    suspend fun cancelledWaitingCallerDoesNotStartAnotherRefresh() {
        val store = MemoryStore(saved())
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = auth(store, calls) {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "Offline refresh fixture was not released" }
            reply().toString()
        }
        coroutineScope {
            val first = async(Dispatchers.Default) { client.validSession() }
            try {
                entered.await()
                val queued = CompletableDeferred<Unit>()
                val waiting = launch(Dispatchers.Default) { queued.complete(Unit); client.validSession() }
                queued.await()
                waiting.cancelAndJoin()
                check(waiting.isCancelled)
            } finally { release.countDown() }
            check(first.await() == store.value)
        }
        check(calls.get() == 1 && store.saves == 1)
    }
}
