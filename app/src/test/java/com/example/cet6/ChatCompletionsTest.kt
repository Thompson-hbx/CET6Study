package com.example.cet6

import com.example.cet6.ai.ApiConnection
import com.example.cet6.ai.ChatCompletionStream
import com.example.cet6.ai.ChatCompletionsClient
import com.example.cet6.ai.chatPayload
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsTest {
    private val connection = ApiConnection.create("https://provider.example/v1", "test-placeholder")
    private val text = """{"choices":[{"index":0,"delta":{"content":"解释"},"finish_reason":null}]}"""
    private val stop = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""

    private fun client(code: Int = 200, type: String = "text/event-stream", body: String,
        inspect: (Request) -> Unit = {}): ChatCompletionsClient = ChatCompletionsClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build())

    private fun stream(vararg records: String) = records.joinToString("") { "data: $it\n\n" }

    @Test fun baseUrlsKeepVersionPrefixAndNormalizeSlash() {
        assertEquals("https://provider.example/v1/models", connection.endpoint("models").toString())
        assertEquals("https://provider.example/v1/chat/completions",
            connection.endpoint("chat").newBuilder().addPathSegment("completions").build().toString())
        assertEquals("https://api.deepseek.com/models",
            ApiConnection.create("https://api.deepseek.com/", "test-placeholder").endpoint("models").toString())
    }

    @Test fun unsafeOrIncorrectBaseUrlsAreRejected() {
        listOf("http://provider.example", "https://user:pass@provider.example", "https://provider.example/?key=value",
            "https://provider.example/#fragment", "https://provider.example/v1/chat/completions/",
            "https://provider.example/models", "invalid").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ApiConnection.create(value, "test-placeholder") }
        }
    }

    @Test fun invalidKeysAreRejectedAndToStringDoesNotExposeKey() {
        for (key in listOf("", "  ", "test\nheader", "test value")) {
            assertThrows(IllegalArgumentException::class.java) { ApiConnection.create("https://provider.example", key) }
        }
        assertFalse(connection.toString().contains("test-placeholder"))
    }

    @Test fun requestUsesMessagesWithoutResponsesOnlyParameters() {
        val payload = chatPayload("example-model", "原稿")
        assertEquals("example-model", payload.getString("model"))
        assertTrue(payload.getBoolean("stream"))
        val messages = payload.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("原稿", messages.getJSONObject(1).getString("content"))
        assertFalse(payload.has("input"))
        assertFalse(payload.has("store"))
    }

    @Test fun reasoningAndUsageAreNotAnswerText() {
        val parser = ChatCompletionStream()
        assertEquals("", parser.accept("""{"choices":[{"index":0,"delta":{"reasoning_content":"internal","content":null}}]}"""))
        assertEquals("解释", parser.accept(text))
        parser.accept(stop)
        parser.accept("""{"choices":[],"usage":{"total_tokens":10}}""")
        parser.accept("[DONE]")
        assertEquals("解释", parser.result())
    }

    @Test fun incompleteFilteredOrUnsupportedResultsAreNeverAccepted() {
        for (reason in listOf("length", "content_filter", "tool_calls", "unexpected")) {
            val parser = ChatCompletionStream()
            parser.accept(text)
            assertThrows(IllegalStateException::class.java) {
                parser.accept("""{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}""")
            }
        }
        val parser = ChatCompletionStream()
        parser.accept(text)
        parser.accept(stop)
        assertThrows(IllegalStateException::class.java) { parser.result() }
        val noStop = ChatCompletionStream()
        noStop.accept(text)
        assertThrows(IllegalStateException::class.java) { noStop.accept("[DONE]") }
    }

    @Test fun emptyAnswerAndMalformedOrErrorEventsAreRejected() {
        val parser = ChatCompletionStream()
        parser.accept(stop)
        parser.accept("[DONE]")
        assertThrows(IllegalStateException::class.java) { parser.result() }
        assertThrows(IllegalStateException::class.java) { ChatCompletionStream().accept("bad JSON") }
        val error = assertThrows(IllegalStateException::class.java) {
            ChatCompletionStream().accept("""{"error":{"message":"private-marker"}}""")
        }
        assertFalse(error.message.orEmpty().contains("private-marker"))
    }

    @Test fun malformedFieldsDoNotLeakServerContentInExceptions() {
        val error = assertThrows(IllegalStateException::class.java) {
            ChatCompletionStream().accept("""{"choices":[{"index":0,"delta":{"content":{"data":"private-marker"}}}]}""")
        }
        assertFalse(error.message.orEmpty().contains("private-marker"))
    }

    @Test fun redirectsAreRejectedWithoutForwardingCredentials() = runBlocking {
        var calls = 0
        val client = ChatCompletionsClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(307).message("redirect")
                .header("Location", "https://another.example/chat/completions")
                .body("".toResponseBody()).build()
        }.build())
        try {
            client.stream(connection, "example-model", "原稿") {}
            fail("Redirect must fail")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("HTTP 307")) }
        assertEquals(1, calls)
    }

    @Test fun generationUsesCorrectEndpointHeadersAndStreamsAnswer() = runBlocking {
        var inspected = false
        val client = client(body = ": keepalive\n\n" + stream(text, stop, "[DONE]")) { request ->
            assertEquals("https://provider.example/v1/chat/completions", request.url.toString())
            assertEquals("Bearer test-placeholder", request.header("Authorization"))
            assertEquals("text/event-stream", request.header("Accept"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            assertEquals("原稿", JSONObject(buffer.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content"))
            inspected = true
        }
        val deltas = mutableListOf<String>()
        assertEquals("解释", client.stream(connection, "example-model", "原稿") { deltas.add(it) })
        assertTrue(inspected)
        assertEquals(listOf("解释"), deltas)
    }

    @Test fun modelsUseDataArrayAndPreserveProviderIds() = runBlocking {
        val client = client(type = "application/json", body = """{"data":[{"id":"model-1","name":"名称"},{"id":"model-2"}]}""") {
            assertEquals("https://provider.example/v1/models", it.url.toString())
            assertEquals("GET", it.method)
        }
        val models = client.models(connection)
        assertEquals(listOf("model-1", "model-2"), models.map { it.slug })
        assertEquals(listOf("名称", "model-2"), models.map { it.displayName })
    }

    @Test fun networkEofWithoutDoneIsFailureEvenAfterStop() = runBlocking {
        try {
            client(body = stream(text, stop)).stream(connection, "example-model", "原稿") {}
            fail("EOF must not count as success")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("[DONE]")) }
    }

    @Test fun httpErrorsAndUnexpectedContentTypeDoNotExposeResponseBody() = runBlocking {
        for (status in listOf(401, 402, 403, 404, 429, 503)) {
            try {
                client(code = status, body = "private-marker").stream(connection, "example-model", "原稿") {}
                fail("HTTP errors must fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("HTTP $status"))
                assertFalse(e.message.orEmpty().contains("private-marker"))
            }
        }
        try {
            client(type = "text/html", body = "private-marker").stream(connection, "example-model", "原稿") {}
            fail("HTML must not count as SSE")
        } catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("private-marker")) }
    }
}
