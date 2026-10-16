package com.example.cet6

import com.example.cet6.ai.ResponsesStreamStart
import org.junit.Assert.fail
import org.junit.Test

class ResponsesStreamStartTest {
    @Test fun acceptsObservedMissingHeaderStream() {
        val stream = ResponsesStreamStart(headerMissing = true)
        stream.accept("response.created", "response")
        stream.accept("response.output_text.delta", null)
        stream.accept("response.completed", "response")
    }

    @Test fun rejectsUnrelatedSseAndCompletionWithoutCreation() {
        for (type in listOf("message", "response.output_text.delta", "response.completed", "")) {
            try {
                ResponsesStreamStart(headerMissing = true).accept(type, "response")
                fail("Accepted unrelated first event: $type")
            } catch (_: IllegalStateException) { /* Expected protocol rejection. */ }
        }
    }

    @Test(expected = IllegalStateException::class) fun rejectsCreatedWithoutResponseObject() {
        ResponsesStreamStart(headerMissing = true).accept("response.created", null)
    }

    @Test fun preservesExplicitSseHeaderBehavior() {
        ResponsesStreamStart(headerMissing = false).accept("response.in_progress", "response")
    }
}
