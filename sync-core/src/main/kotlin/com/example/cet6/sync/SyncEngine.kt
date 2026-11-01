package com.example.cet6.sync

import org.json.JSONObject

data class RemoteSnapshot(val serverId: String, val revision: Long, val document: SyncDocument) {
    fun json(): JSONObject = JSONObject().put("serverId", serverId).put("revision", revision).put("document", document.json())
    companion object {
        fun parse(json: JSONObject): RemoteSnapshot {
            fields(json, setOf("serverId", "revision", "document"))
            require(json.get("serverId") is String && json.getString("serverId").matches(Regex("[a-f0-9-]{36}")))
            val revision = integer(json, "revision").also { require(it >= 0) }
            return RemoteSnapshot(json.getString("serverId"), revision, SyncDocument.decode(json.getJSONObject("document").toString()))
        }
    }
}

data class SyncConflict(val key: String, val local: String, val remote: String)
data class ConflictChoice(val conflict: SyncConflict, val useRemote: Boolean)
data class MergeResult(val document: SyncDocument, val conflicts: List<SyncConflict>)

object SyncMerge {
    /** Absence never means deletion. Concurrent edits of one record require an explicit choice. */
    fun merge(base: SyncDocument, local: SyncDocument, remote: SyncDocument, choices: List<ConflictChoice> = emptyList()): MergeResult {
        val conflicts = mutableListOf<SyncConflict>()
        val merged = (base.records.keys + local.records.keys + remote.records.keys).associateWith { key ->
            val b = base.records[key]
            val l = local.records[key]
            val r = remote.records[key]
            val choice = choices.firstOrNull { it.conflict == SyncConflict(key, l.orEmpty(), r.orEmpty()) }
            val selected = when {
                l == null -> requireNotNull(r ?: b)
                r == null -> l
                l == r -> l
                l == b -> r
                r == b -> l
                choice != null -> if (choice.useRemote) r else l
                else -> { conflicts += SyncConflict(key, l, r); l }
            }
            if (key.startsWith("vocabulary:") && l != null && r != null) {
                val events = sortedMapOf<String, String>()
                for (text in listOf(l, r)) {
                    val reviews = JSONObject(text).getJSONArray("reviews")
                    for (i in 0 until reviews.length()) {
                        val review = reviews.getJSONObject(i)
                        val id = review.getString("id")
                        val encoded = canonical(review)
                        require(events[id]?.let { it == encoded } != false) { "同标识学习日志发生冲突，未覆盖任何版本" }
                        events[id] = encoded
                    }
                }
                canonical(JSONObject(selected).put("reviews", org.json.JSONArray(events.values.map(::JSONObject))))
            } else selected
        }
        return MergeResult(SyncDocument(merged.toSortedMap()), conflicts)
    }
}

interface SyncTransport {
    fun fetch(known: RemoteSnapshot?): RemoteSnapshot
    /** Returns null when another device changed the revision. */
    fun push(expected: RemoteSnapshot, document: SyncDocument): RemoteSnapshot?
}

interface SyncLocalStore {
    fun snapshot(): SyncDocument
    /** Must compare and write under the same local transaction/lock. */
    fun apply(expected: SyncDocument, updated: SyncDocument): Boolean
}

interface SyncStateStore {
    fun load(): RemoteSnapshot?
    fun save(snapshot: RemoteSnapshot)
    /** Durable copies must exist before the user can choose between conflicting versions. */
    fun archive(local: SyncDocument, remote: RemoteSnapshot)
}

data class SyncResult(val message: String, val conflicts: List<SyncConflict> = emptyList())

class SyncEngine(private val local: SyncLocalStore, private val state: SyncStateStore, private val transport: SyncTransport) {
    fun synchronize(choices: List<ConflictChoice> = emptyList()): SyncResult {
        val checkpoint = state.load()
        val initial = local.snapshot()
        var remote = transport.fetch(checkpoint)
        require(checkpoint == null || checkpoint.serverId == remote.serverId) { "服务器身份已变化，请核对服务器并重新配置连接" }
        repeat(3) {
            val merge = SyncMerge.merge(checkpoint?.document ?: SyncDocument(), initial, remote.document, choices)
            if (merge.conflicts.isNotEmpty()) {
                state.archive(initial, remote)
                return SyncResult("有 ${merge.conflicts.size} 条记录在两端都被修改，请选择保留的版本。两端副本已保存。", merge.conflicts)
            }
            val document = SyncDocument.decode(merge.document.encode())
            val accepted = if (document == remote.document) remote else transport.push(remote, document)
            if (accepted == null) {
                remote = transport.fetch(null)
            } else {
                if (document != initial) state.archive(initial, remote)
                if (!local.apply(initial, document)) return SyncResult("同步期间本机有新编辑，已保留；下次同步将重新核对。")
                state.save(accepted)
                return SyncResult("同步完成，共 ${document.records.size} 条学习记录。")
            }
        }
        return SyncResult("另一设备正在连续保存，本机记录已保留，请稍后同步。")
    }
}
