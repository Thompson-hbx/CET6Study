package com.example.cet6

import com.example.cet6.auth.callbackParameters
import com.example.cet6.auth.isTerminalRefreshError
import com.example.cet6.auth.receiveOAuthCallback
import com.example.cet6.auth.validateTokenFields
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OAuthPolicyTest {
    @Test fun validCallbackDecodesQuery() {
        val params = callbackParameters("GET /auth/callback?state=s&code=a%2Bb&client_id=c HTTP/1.1", "s")!!
        assertEquals("a+b", params["code"])
        assertEquals("c", params["client_id"])
    }

    @Test fun rejectsWrongStateAndDuplicateFields() {
        assertNull(callbackParameters("GET /auth/callback?state=wrong&code=c HTTP/1.1", "s"))
        assertNull(callbackParameters("GET /auth/callback?state=s&state=s HTTP/1.1", "s"))
        assertNull(callbackParameters("GET /auth/callback?state=s&code=a&code=b HTTP/1.1", "s"))
    }

    @Test fun rejectsOtherPathsMethodsAndAbsoluteTargets() {
        for (request in listOf("GET /favicon.ico?state=s HTTP/1.1",
            "POST /auth/callback?state=s HTTP/1.1", "GET http://evil/auth/callback?state=s HTTP/1.1",
            "GET //evil/auth/callback?state=s HTTP/1.1",
            "GET /auth/callback?state=%ZZ HTTP/1.1")) {
            assertNull(callbackParameters(request, "s"))
        }
    }

    @Test fun errorCallbackStillRequiresState() {
        assertEquals("access_denied", callbackParameters(
            "GET /auth/callback?state=s&error=access_denied HTTP/1.1", "s")!!["error"])
        assertNull(callbackParameters("GET /auth/callback?error=access_denied HTTP/1.1", "s"))
    }

    @Test fun listenerSurvivesUnrelatedRequest() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<Map<String, String>> { receiveOAuthCallback(server, "s", 5000) }
                fun send(path: String): String = Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readText()
                }
                assertTrue(send("/favicon.ico").startsWith("HTTP/1.1 400"))
                assertTrue(send("/auth/callback?state=bad&code=x").startsWith("HTTP/1.1 400"))
                assertTrue(send("/auth/callback?state=s&code=ok").startsWith("HTTP/1.1 200"))
                assertEquals("ok", pending.get(5, TimeUnit.SECONDS)["code"])
            } finally { executor.shutdownNow() }
        }
    }

    @Test(expected = SocketTimeoutException::class) fun callbackHasTotalDeadline() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use {
            receiveOAuthCallback(it, "s", 30)
        }
    }

    @Test fun terminalErrorsExcludeTemporaryAndClientFailures() {
        assertTrue(isTerminalRefreshError("invalid_grant"))
        assertTrue(isTerminalRefreshError("refresh_token_reused"))
        assertFalse(isTerminalRefreshError("server_error"))
        assertFalse(isTerminalRefreshError("invalid_client"))
    }

    @Test fun acceptsBearerAndReasonableLifetime() { validateTokenFields("access", "replacement", "bearer", 3600) }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingReplacement() {
        validateTokenFields("access", "", "Bearer", 3600)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongTokenType() {
        validateTokenFields("access", "replacement", "Basic", 3600)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidLifetime() {
        validateTokenFields("access", "replacement", "Bearer", 0)
    }
}
