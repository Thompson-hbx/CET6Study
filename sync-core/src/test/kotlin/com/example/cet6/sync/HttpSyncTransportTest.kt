package com.example.cet6.sync

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HttpSyncTransportTest {
    @Test fun verifiedHttpsFetchConditionalGetAndChangedRecordsOnlyUpload() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val secret = "test-only-sync-pairing-not-a-real-secret"
            val transport = HttpSyncTransport(
                SyncConnection.create(server.url("/").toString(), secret),
                OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build(),
            )
            val initial = RemoteSnapshot("11111111-1111-1111-1111-111111111111", 0, SyncDocument())
            server.enqueue(MockResponse().setBody(initial.json().toString()))
            assertEquals(initial, transport.fetch(null))
            val request = server.takeRequest()
            assertEquals("/v1/study", request.path)
            assertTrue(request.getHeader("Authorization") == "Bearer $secret")
            server.enqueue(MockResponse().setResponseCode(304))
            assertEquals(initial, transport.fetch(initial))
            assertEquals("\"${initial.serverId}:0\"", server.takeRequest().getHeader("If-None-Match"))
            val plan = canonical(JSONObject().put("date", "2026-10-31").put("newGoal", 10).put("reviewGoal", 20))
            val document = SyncDocument(mapOf("plans:2026-10-31" to plan))
            server.enqueue(MockResponse().setBody(JSONObject().put("serverId", initial.serverId).put("revision", 1).toString()))
            assertEquals(document, transport.push(initial, document)!!.document)
            val upload = server.takeRequest()
            assertEquals("PUT", upload.method)
            val changes = JSONObject(upload.body.readUtf8()).getJSONObject("changes")
            assertEquals(1, changes.length())
            server.enqueue(MockResponse().setResponseCode(409))
            assertNull(transport.push(initial, document))
        }
    }

    @Test fun redirectsAreNotFollowedAndUntrustedCertificatesAreRejected() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val connection = SyncConnection.create(server.url("/").toString(), "test-only-sync-material-at-least-32-characters")
            assertThrows(IOException::class.java) { HttpSyncTransport(connection).fetch(null) }
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.invalid/"))
            val transport = HttpSyncTransport(
                connection,
                OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build(),
            )
            assertThrows(IllegalStateException::class.java) { transport.fetch(null) }
            assertEquals(1, server.requestCount)
        }
    }
}
