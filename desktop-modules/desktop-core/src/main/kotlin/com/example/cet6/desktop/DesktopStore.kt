package com.example.cet6.desktop

import com.example.cet6.sync.*
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.util.UUID

class DesktopStore(val directory: File) : SyncLocalStore, Closeable {
    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock
    private val db: java.sql.Connection
    private var document: SyncDocument
    init {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建桌面学习目录" }
        channel = FileChannel.open(File(directory, "desktop.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = channel.tryLock() ?: error("朱批桌面端已在运行，请切换到已有窗口")
        db = DriverManager.getConnection("jdbc:sqlite:${File(directory, "study.db").absolutePath}")
        db.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("CREATE TABLE IF NOT EXISTS records (record_key TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        }
        val rows = sortedMapOf<String, String>()
        db.createStatement().use { s ->
            s.executeQuery("SELECT record_key,payload FROM records").use { r ->
                while (r.next()) rows[r.getString(1)] = r.getString(2)
            }
        }
        // A damaged store is rejected; an empty replacement is never written over it.
        document = SyncDocument.decode(SyncDocument(rows).encode())
    }

    @Synchronized override fun snapshot() = document

    @Synchronized override fun apply(expected: SyncDocument, updated: SyncDocument): Boolean {
        if (document != expected) return false
        val valid = SyncDocument.decode(updated.encode())
        require(valid.records.keys.containsAll(document.records.keys)) { "不能移除本机记录" }
        val changed = valid.records.filter { (key, value) -> document.records[key] != value }
        db.autoCommit = false
        try {
            db.prepareStatement("INSERT INTO records VALUES(?,?) ON CONFLICT(record_key) DO UPDATE SET payload=excluded.payload").use { s ->
                changed.forEach { (key, value) ->
                    s.setString(1, key)
                    s.setString(2, value)
                    s.addBatch()
                }
                s.executeBatch()
            }
            db.commit()
            document = valid
        } catch (e: Exception) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
        return true
    }

    @Synchronized fun edit(key: String, transform: (JSONObject) -> Unit) {
        val row = JSONObject(requireNotNull(document.records[key]))
        transform(row)
        check(apply(document, SyncDocument(document.records + (key to canonical(row)))))
    }

    @Synchronized fun create(kind: String, source: JSONObject? = null): String {
        val id = UUID.randomUUID().toString()
        val row = source?.let { JSONObject(it.toString()).put("sourcePracticeId", it.getString("id")) }
            ?: JSONObject().put("kind", kind).put("title", "").put("material", "").put("questions", "")
                .put("answer", "").put("savedSentences", "").put("sourcePracticeId", "").put("practiceGoal", "")
        row.put("id", id).put("feedback", "").put("model", "").put("errorReason", "")
            .put("submittedAt", 0).put("updatedAt", System.currentTimeMillis())
        val key = "coach:$id"
        check(apply(document, SyncDocument(document.records + (key to canonical(row)))))
        return key
    }

    @Synchronized override fun close() {
        db.close()
        lock.release()
        channel.close()
    }
}
