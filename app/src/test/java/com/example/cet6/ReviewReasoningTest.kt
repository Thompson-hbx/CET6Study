package com.example.cet6

import com.example.cet6.ai.*
import com.example.cet6.data.CoachFailures
import com.example.cet6.data.TurnKind
import com.example.cet6.ui.isDeepReviewKind
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

class ReviewReasoningTest {
    @Test fun defaultRequestsDoNotOverrideServerReasoning() {
        for (payload in listOf(chatPayload("fixture", "draft"), responsesPayload("fixture", "draft"))) {
            assertFalse(payload.has("reasoning"))
            assertFalse(payload.has("reasoning_effort"))
            assertFalse(payload.has("thinking"))
        }
    }

    @Test fun responsesUseNestedEffortAndKeepPrivacyAndStreamingFlags() {
        for (effort in ReasoningEffort.entries) {
            val payload = responsesPayload("fixture", "draft", ReviewReasoning(effort))
            assertEquals(effort.wire, payload.getJSONObject("reasoning").getString("effort"))
            assertFalse(payload.getBoolean("store"))
            assertTrue(payload.getBoolean("stream"))
            assertFalse(payload.has("reasoning_effort"))
            assertFalse(payload.has("thinking"))
            assertEquals("draft", payload.getJSONArray("input").getJSONObject(0).getString("content"))
        }
    }

    @Test fun compatibleApiUsesTopLevelEffortWithoutResponsesFields() {
        for (effort in ReasoningEffort.entries) {
            val payload = chatPayload("fixture", "draft", ReviewReasoning(effort))
            assertEquals(effort.wire, payload.getString("reasoning_effort"))
            assertFalse(payload.has("reasoning"))
            assertFalse(payload.has("thinking"))
            assertFalse(payload.has("store"))
            assertEquals("draft", payload.getJSONArray("messages").getJSONObject(1).getString("content"))
        }
    }

    @Test fun deepSeekExplicitlyEnablesThinkingAndUsesItsOwnEffortValues() {
        for (effort in ReasoningFormat.DEEPSEEK.efforts) {
            val payload = chatPayload("fixture", "draft", ReviewReasoning(effort, ReasoningFormat.DEEPSEEK))
            assertEquals("enabled", payload.getJSONObject("thinking").getString("type"))
            assertEquals(effort.wire, payload.getString("reasoning_effort"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReviewReasoning(ReasoningEffort.XHIGH, ReasoningFormat.DEEPSEEK)
        }
        assertThrows(IllegalArgumentException::class.java) {
            responsesPayload("fixture", "draft", ReviewReasoning(ReasoningEffort.MAX, ReasoningFormat.DEEPSEEK))
        }
    }

    @Test fun onlyFullReviewsAndReadingEvidenceAnalysisUseDeepReview() {
        assertEquals(setOf(TurnKind.WRITING_REVIEW, TurnKind.TRANSLATION_REVIEW, TurnKind.READING_EXPLAIN),
            TurnKind.all.filter(::isDeepReviewKind).toSet())
        assertFalse(isDeepReviewKind("unknown"))
    }

    @Test fun compatibleTransportSendsSelectedSettingsAndIgnoresThinkingText() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            assertEquals("max", payload.getString("reasoning_effort"))
            assertEquals("enabled", payload.getJSONObject("thinking").getString("type"))
            response(chain.request(), 200, "text/event-stream",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"private-reasoning\"}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"review\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        }.build()
        val chunks = StringBuilder()
        val result = ChatCompletionsClient(http).stream(
            ApiConnection.create("https://fixture.invalid/v1", "fixture-placeholder"), "fixture", "draft",
            ReviewReasoning(ReasoningEffort.MAX, ReasoningFormat.DEEPSEEK)) { chunks.append(it) }
        assertEquals("review", result)
        assertEquals("review", chunks.toString())
        assertEquals(1, calls)
    }

    @Test fun rejectedDepthIsActionableAndNeverRetriedOrDowngraded() = runBlocking {
        for (status in listOf(400, 422)) {
            var calls = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                response(chain.request(), status, "application/json", "private-marker")
            }.build()
            val failure = runCatching {
                ChatCompletionsClient(http).stream(
                    ApiConnection.create("https://fixture.invalid/v1", "fixture-placeholder"), "fixture", "draft",
                    ReviewReasoning(ReasoningEffort.MAX)) { fail("Rejected request emitted text") }
            }.exceptionOrNull()
            assertTrue(failure is DeepReviewRejectedException)
            val message = CoachFailures.describe(failure as Exception)
            assertTrue(message.contains("档位"))
            assertTrue(message.contains("未自动降档"))
            assertFalse(message.contains("private-marker"))
            assertEquals(1, calls)
        }
    }

    @Test fun rejectedResponsesDepthIsNotAcceptedAsAnAnswer() = runBlocking {
        val failure = runCatching {
            readResponsesStream(response(Request.Builder().url("https://fixture.invalid").build(), 400,
                "application/json", "private-marker"), deepReview = true) { fail("No answer expected") }
        }.exceptionOrNull()
        assertTrue(failure is DeepReviewRejectedException)
        assertFalse(failure!!.message.orEmpty().contains("private-marker"))
    }

    private fun response(request: Request, status: Int, type: String, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("offline fixture")
            .body(body.toResponseBody(type.toMediaType())).build()
}
