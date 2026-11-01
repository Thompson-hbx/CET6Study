package com.example.cet6.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {
    private fun row(id: String, answer: String = "original") = canonical(
        JSONObject()
            .put("id", id).put("kind", "WRITING").put("title", "Original sample").put("material", "Write about learning.")
            .put("questions", "").put("answer", answer).put("feedback", "").put("model", "").put("errorReason", "")
            .put("submittedAt", 0).put("updatedAt", 100).put("savedSentences", "").put("sourcePracticeId", "").put("practiceGoal", ""),
    )
    private fun doc(answer: String = "original") = SyncDocument(mapOf("coach:p" to row("p", answer)))
    private class MemoryLocal(var data: SyncDocument = SyncDocument()) : SyncLocalStore {
        var interrupt = false
        override fun snapshot() = data
        override fun apply(expected: SyncDocument, updated: SyncDocument): Boolean {
            if (interrupt || data != expected) return false
            data = updated
            return true
        }
    }
    private class MemoryState : SyncStateStore {
        var data: RemoteSnapshot? = null
        val archives = mutableListOf<Pair<SyncDocument, RemoteSnapshot>>()
        override fun load() = data
        override fun save(snapshot: RemoteSnapshot) {
            data = snapshot
        }
        override fun archive(local: SyncDocument, remote: RemoteSnapshot) {
            archives += local to remote
        }
    }
    private class Server : SyncTransport {
        var state = RemoteSnapshot("11111111-1111-1111-1111-111111111111", 0, SyncDocument())
        var collisions = 0
        override fun fetch(known: RemoteSnapshot?) = state
        override fun push(expected: RemoteSnapshot, document: SyncDocument): RemoteSnapshot? {
            if (collisions > 0) {
                collisions--
                state = state.copy(revision = state.revision + 1)
                return null
            }
            if (expected.revision != state.revision) return null
            state = state.copy(revision = state.revision + 1, document = document)
            return state
        }
    }

    @Test fun phoneDesktopPhoneRoundTripAndRepeatedSync() {
        val server = Server()
        val phone = MemoryLocal(doc())
        val phoneState = MemoryState()
        val desktop = MemoryLocal()
        val desktopState = MemoryState()
        SyncEngine(phone, phoneState, server).synchronize()
        SyncEngine(desktop, desktopState, server).synchronize()
        assertEquals(phone.data, desktop.data)
        desktop.data = doc("written on computer")
        SyncEngine(desktop, desktopState, server).synchronize()
        SyncEngine(phone, phoneState, server).synchronize()
        assertEquals(doc("written on computer"), phone.data)
        val revision = server.state.revision
        SyncEngine(phone, phoneState, server).synchronize()
        assertEquals(revision, server.state.revision)
    }

    @Test fun conflictDoesNotOverwriteAndArchivesBothVersionsBeforeChoice() {
        val server = Server()
        val local = MemoryLocal(doc())
        val state = MemoryState()
        val engine = SyncEngine(local, state, server)
        engine.synchronize()
        local.data = doc("phone")
        server.state = server.state.copy(revision = 2, document = doc("computer"))
        val result = engine.synchronize()
        assertEquals(1, result.conflicts.size)
        assertEquals(doc("phone"), local.data)
        assertEquals(doc("computer"), server.state.document)
        assertTrue(state.archives.isNotEmpty())
        engine.synchronize(listOf(ConflictChoice(result.conflicts.single(), true)))
        assertEquals(doc("computer"), local.data)
    }

    @Test fun staleConflictChoiceCannotApproveANewerUnseenVersion() {
        val base = doc()
        val local = doc("phone")
        val remote = doc("computer")
        val conflict = SyncMerge.merge(base, local, remote).conflicts.single()
        val result = SyncMerge.merge(base, local, doc("newer computer"), listOf(ConflictChoice(conflict, true)))
        assertEquals(1, result.conflicts.size)
    }

    @Test fun unrelatedRecordsAndAbsenceNeverDeleteWork() {
        val base = doc()
        val a = SyncDocument(base.records + ("coach:a" to row("a", "phone")))
        val b = SyncDocument(mapOf("coach:b" to row("b", "desktop")))
        val result = SyncMerge.merge(base, a, b)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(setOf("coach:p", "coach:a", "coach:b"), result.document.records.keys)
    }

    @Test fun editsDuringNetworkArePreservedWithoutAdvancingCheckpoint() {
        val server = Server()
        val local = MemoryLocal(doc())
        val state = MemoryState()
        val engine = SyncEngine(local, state, server)
        engine.synchronize()
        val checkpoint = state.data
        local.interrupt = true
        server.state = server.state.copy(revision = 2, document = doc("remote edit"))
        engine.synchronize()
        assertEquals(doc(), local.data)
        assertEquals(checkpoint, state.data)
    }

    @Test fun compareAndSwapContentionIsRetriedAndBounded() {
        val server = Server()
        val local = MemoryLocal(doc())
        val state = MemoryState()
        server.collisions = 2
        SyncEngine(local, state, server).synchronize()
        assertEquals(doc(), state.data!!.document)
        local.data = doc("later")
        server.collisions = 5
        assertTrue(SyncEngine(local, state, server).synchronize().message.contains("连续保存"))
        assertEquals(doc("later"), local.data)
    }

    @Test fun unexpectedServerIdentityIsRejected() {
        val server = Server()
        val local = MemoryLocal(doc())
        val state = MemoryState()
        val engine = SyncEngine(local, state, server)
        engine.synchronize()
        server.state = server.state.copy(serverId = "22222222-2222-2222-2222-222222222222")
        assertThrows(IllegalArgumentException::class.java) { engine.synchronize() }
        assertEquals(doc(), local.data)
    }

    @Test fun formatRejectsUnknownFieldsAndCredentialContainers() {
        val valid = doc().encode()
        assertEquals(doc(), SyncDocument.decode(valid))
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.decode(JSONObject(valid).put("token", "forbidden").toString()) }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.decode(SyncDocument(mapOf("credentials:key" to "{}")).encode()) }
        val bad = JSONObject(row("p")).put("updatedAt", "100")
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.decode(SyncDocument(mapOf("coach:p" to bad.toString())).encode()) }
    }

    @Test fun httpsOnlyConnectionRejectsRedirectStyleCredentialsAndQuery() {
        val secret = "test-pairing-material-for-protocol-tests"
        listOf("http://localhost", "https://user:pass@example.com", "https://example.com?token=x", "https://example.com/#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { SyncConnection.create(it, secret) }
        }
        assertEquals("https://example.com/api/v1/study", SyncConnection.create("https://example.com/api", secret).endpoint().toString())
    }
}
