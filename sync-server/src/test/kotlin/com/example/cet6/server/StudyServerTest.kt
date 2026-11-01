package com.example.cet6.server

import com.example.cet6.sync.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StudyServerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = "test-only-pairing-material-at-least-32-characters"
    private fun plan(goal: Int) = canonical(JSONObject().put("date", "2026-10-31").put("newGoal", goal).put("reviewGoal", 20))

    @Test fun privateHttpApiRejectsAnonymousAndStaleWrites() = testApplication {
        StudyStore(temporary.newFolder().resolve("study.db")).use { store ->
            application { studyModule(store, secret) }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/study").status)
            assertEquals(HttpStatusCode.Unauthorized, client.put("/v1/study") { setBody("not JSON") }.status)
            val initial = client.get("/v1/study") { bearerAuth(secret) }
            val state = RemoteSnapshot.parse(JSONObject(initial.bodyAsText()))
            assertEquals(HttpStatusCode.NotModified, client.get("/v1/study") {
                bearerAuth(secret); header(HttpHeaders.IfNoneMatch, initial.headers[HttpHeaders.ETag]!!)
            }.status)
            val body = JSONObject().put("serverId", state.serverId).put("revision", 0)
                .put("changes", JSONObject().put("plans:2026-10-31", JSONObject(plan(10)))).toString()
            assertEquals(HttpStatusCode.OK, client.put("/v1/study") { bearerAuth(secret); setBody(body) }.status)
            assertEquals(HttpStatusCode.Conflict, client.put("/v1/study") { bearerAuth(secret); setBody(body) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.put("/v1/study") { bearerAuth(secret); setBody("{}") }.status)
            assertEquals(1L, store.snapshot().revision)
        }
    }

    @Test fun persistentHistoryAndInvalidWritesAreAtomic() {
        val file = temporary.newFolder().resolve("store.db")
        var id = ""
        StudyStore(file).use { store ->
            val first = store.snapshot(); id = first.serverId
            val one = store.update(id, 0, mapOf("plans:2026-10-31" to plan(10)))!!
            store.update(id, one.revision, mapOf("plans:2026-10-31" to plan(15)))!!
            assertThrows(IllegalArgumentException::class.java) {
                store.update(id, 2, mapOf("credentials:secret" to "{}"))
            }
            assertEquals(2L, store.snapshot().revision)
        }
        StudyStore(file).use { store ->
            assertEquals(id, store.snapshot().serverId)
            assertEquals(plan(15), store.snapshot().document.records["plans:2026-10-31"])
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM history").use { r -> r.next(); assertEquals(2, r.getInt(1)) } }
        }
    }

    @Test fun twoClientsExchangeOfflineEditsThroughPersistentServer() {
        StudyStore(temporary.newFolder().resolve("roundtrip.db")).use { store ->
            val transport = object : SyncTransport {
                override fun fetch(known: RemoteSnapshot?) = store.snapshot()
                override fun push(expected: RemoteSnapshot, document: SyncDocument) = store.update(expected.serverId, expected.revision,
                    document.records.filter { expected.document.records[it.key] != it.value })
            }
            class Local(var document: SyncDocument) : SyncLocalStore {
                override fun snapshot() = document
                override fun apply(expected: SyncDocument, updated: SyncDocument): Boolean {
                    if (document != expected) return false
                    document = updated; return true
                }
            }
            val phone = Local(SyncDocument(mapOf("plans:2026-10-31" to plan(10))))
            val computer = Local(SyncDocument())
            val a = SyncEngine(phone, FileSyncStateStore(temporary.newFolder()), transport)
            val b = SyncEngine(computer, FileSyncStateStore(temporary.newFolder()), transport)
            a.synchronize(); b.synchronize()
            computer.document = SyncDocument(mapOf("plans:2026-10-31" to plan(18)))
            b.synchronize(); a.synchronize()
            assertEquals(computer.document, phone.document)
            assertEquals(plan(18), phone.document.records["plans:2026-10-31"])
        }
    }
}
