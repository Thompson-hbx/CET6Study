package com.example.cet6.server

import com.example.cet6.sync.*
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.sql.DriverManager
import java.util.UUID

/** A single private workspace. Every accepted revision is retained for recovery. */
class StudyStore(file: File) : Closeable {
    private val db = run {
        check(file.absoluteFile.parentFile.isDirectory || file.absoluteFile.parentFile.mkdirs())
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    }
    init {
        db.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA busy_timeout=5000")
            it.execute("CREATE TABLE IF NOT EXISTS meta (singleton INTEGER PRIMARY KEY CHECK(singleton=1), server_id TEXT NOT NULL, revision INTEGER NOT NULL)")
            it.execute("CREATE TABLE IF NOT EXISTS records (record_key TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            it.execute("CREATE TABLE IF NOT EXISTS history (revision INTEGER NOT NULL, record_key TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(revision,record_key))")
        }
        db.prepareStatement("INSERT OR IGNORE INTO meta VALUES(1,?,0)").use {
            it.setString(1, UUID.randomUUID().toString()); it.executeUpdate()
        }
    }

    @Synchronized fun snapshot(): RemoteSnapshot {
        val (id, revision) = db.createStatement().use { s -> s.executeQuery("SELECT server_id,revision FROM meta").use { r ->
            check(r.next()); r.getString(1) to r.getLong(2)
        } }
        val records = sortedMapOf<String, String>()
        db.createStatement().use { s -> s.executeQuery("SELECT record_key,payload FROM records").use { r ->
            while (r.next()) records[r.getString(1)] = r.getString(2)
        } }
        return RemoteSnapshot(id, revision, SyncDocument(records))
    }

    @Synchronized fun update(serverId: String, revision: Long, changes: Map<String, String>): RemoteSnapshot? {
        db.autoCommit = false
        try {
            val current = snapshot()
            if (current.serverId != serverId || current.revision != revision) { db.rollback(); return null }
            val actual = changes.filter { (key, value) -> current.document.records[key] != value }
            val document = SyncDocument.decode(SyncDocument(current.document.records + actual).encode())
            actual.filterKeys { it.startsWith("vocabulary:") }.forEach { (key, value) ->
                val previous = current.document.records[key]?.let(::JSONObject)?.getJSONArray("reviews")
                val nextReviews = org.json.JSONObject(value).getJSONArray("reviews")
                val nextById = (0 until nextReviews.length()).associate { i -> nextReviews.getJSONObject(i).let { it.getString("id") to canonical(it) } }
                if (previous != null) for (i in 0 until previous.length()) {
                    val event = previous.getJSONObject(i)
                    require(nextById[event.getString("id")] == canonical(event)) { "同步不能移除或改写学习日志" }
                }
            }
            if (actual.isEmpty()) { db.commit(); return current }
            val next = Math.addExact(revision, 1)
            db.prepareStatement("INSERT INTO history VALUES(?,?,?)").use { statement ->
                actual.forEach { (key, value) ->
                    statement.setLong(1, next); statement.setString(2, key); statement.setString(3, value); statement.addBatch()
                }
                statement.executeBatch()
            }
            db.prepareStatement("INSERT INTO records VALUES(?,?) ON CONFLICT(record_key) DO UPDATE SET payload=excluded.payload").use { statement ->
                actual.forEach { (key, value) -> statement.setString(1, key); statement.setString(2, value); statement.addBatch() }
                statement.executeBatch()
            }
            db.prepareStatement("UPDATE meta SET revision=? WHERE singleton=1").use { it.setLong(1, next); it.executeUpdate() }
            db.commit()
            return RemoteSnapshot(serverId, next, document)
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally { db.autoCommit = true }
    }

    @Synchronized override fun close() { db.close() }
}
