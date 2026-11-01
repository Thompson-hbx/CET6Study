package com.example.cet6.desktop

import com.example.cet6.sync.SyncConnection
import com.example.cet6.sync.SyncDocument
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun draftsPersistAndStaleSyncCannotOverwriteNewTyping() {
        val directory = temporary.newFolder()
        var key = ""
        DesktopStore(directory).use { store ->
            key = store.create("WRITING")
            val initial = store.snapshot()
            store.edit(key) { it.put("answer", "written offline") }
            assertFalse(store.apply(initial, initial))
            assertThrows(IllegalArgumentException::class.java) { store.apply(store.snapshot(), SyncDocument()) }
        }
        DesktopStore(directory).use { store ->
            val row = JSONObject(store.snapshot().records.getValue(key))
            assertEquals("written offline", row.getString("answer"))
            val revision = store.create("WRITING", row)
            assertEquals(row.getString("id"), JSONObject(store.snapshot().records.getValue(revision)).getString("sourcePracticeId"))
            assertEquals(2, store.snapshot().records.size)
        }
    }

    @Test fun pairingIsProtectedByWindowsUserEncryption() {
        val directory = temporary.newFolder()
        val fixture = "test-only-pairing-material-for-dpapi-roundtrip"
        val secrets = DesktopSecrets(directory)
        secrets.save(SyncConnection.create("https://example.com/", fixture))
        assertTrue(secrets.load()!!.secret == fixture)
        assertFalse(directory.resolve("connection.protected").readText().contains(fixture))
    }
}
