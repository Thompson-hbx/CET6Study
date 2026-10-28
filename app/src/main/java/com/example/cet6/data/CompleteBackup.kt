package com.example.cet6.data

import com.example.cet6.data.BackupCodec.int
import com.example.cet6.data.BackupCodec.long
import com.example.cet6.data.BackupCodec.string

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Null papers means a legacy learning-only JSON, not an empty personal library. */
data class CompleteBackup(val study: StudyBackup, val papers: List<ExamPaper>?)

/** Chunked ZIP: bounded entries, explicit manifest and checksums; never extracts paths to disk. */
object CompleteBackupCodec {
    private const val MAX_ENTRY = 2 * 1024 * 1024
    private const val MAX_TOTAL = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val CHUNK_BYTES = 512 * 1024
    private val tables = listOf("words", "reviews", "schedules", "plans", "reading", "essays", "coach", "turns")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun write(backup: CompleteBackup, output: OutputStream) {
        val papers = requireNotNull(backup.papers) { "完整备份必须包含题库状态" }
        val root = BackupCodec.json(backup.study)
        val descriptors = JSONArray()
        var total = 0L
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                total += bytes.size
                require(bytes.size <= MAX_ENTRY && total <= MAX_TOTAL && descriptors.length() < MAX_ENTRIES - 1) {
                    "完整备份超过安全读取范围，请保留本机数据"
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                descriptors.put(JSONObject().put("name", name).put("bytes", bytes.size).put("sha256", hash(bytes)))
            }
            for (table in tables) {
                val rows = root.getJSONArray(table)
                var chunk = JSONArray()
                var size = 2
                var index = 0
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val length = row.toString().toByteArray(Charsets.UTF_8).size + 1
                    if (chunk.length() > 0 && size + length > CHUNK_BYTES) {
                        entry("study/$table-${index++}.json", chunk.toString())
                        chunk = JSONArray(); size = 2
                    }
                    chunk.put(row); size += length
                }
                entry("study/$table-${index}.json", chunk.toString())
            }
            val examText = ExamCodec.encode(papers)
            if (papers.isNotEmpty()) ExamCodec.decode(examText)
            // Each paper is separately bounded by ExamCodec; splitting also preserves empty libraries.
            papers.forEachIndexed { index, paper -> entry("papers/$index.json", ExamCodec.encode(listOf(paper))) }
            val manifest = JSONObject().put("format", "CET6CompleteBackup").put("version", 1)
                .put("createdAt", backup.study.createdAt).put("paperCount", papers.size)
                .put("entries", descriptors).put("counts", JSONObject().apply {
                    tables.forEach { put(it, root.getJSONArray(it).length()) }
                })
            val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
            require(manifestBytes.size <= MAX_ENTRY && total + manifestBytes.size <= MAX_TOTAL) { "备份清单或总容量超限" }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
        }
    }

    fun read(input: InputStream): CompleteBackup {
        val buffered = PushbackInputStream(input.buffered(), 2)
        val first = buffered.read(); val second = buffered.read()
        if (second >= 0) buffered.unread(second)
        if (first >= 0) buffered.unread(first)
        if (first != 'P'.code || second != 'K'.code) return CompleteBackup(BackupCodec.read(buffered), null)
        val rows = tables.associateWith { JSONArray() }
        val papers = mutableListOf<ExamPaper>()
        val actual = linkedMapOf<String, Pair<Int, String>>()
        val chunkIndexes = tables.associateWith { mutableSetOf<Int>() }
        val paperIndexes = mutableSetOf<Int>()
        var manifest: JSONObject? = null
        var total = 0L
        ZipInputStream(buffered).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && actual.size < MAX_ENTRIES && entry.name !in actual) { "备份条目重复或过多" }
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(bytes.size() + count <= MAX_ENTRY && total <= MAX_TOTAL) { "备份解压内容超限" }
                    bytes.write(buffer, 0, count)
                }
                val value = bytes.toByteArray()
                actual[entry.name] = value.size to hash(value)
                val text = utf8(value)
                val study = Regex("study/([a-z]+)-([0-9]+)\\.json").matchEntire(entry.name)
                val paper = Regex("papers/([0-9]+)\\.json").matchEntire(entry.name)
                when {
                    entry.name == "manifest.json" -> manifest = JSONObject(text)
                    study != null -> {
                        val table = study.groupValues[1]
                        val target = requireNotNull(rows[table]) { "未知学习数据表" }
                        require(chunkIndexes.getValue(table).add(study.groupValues[2].toInt()))
                        val chunk = JSONArray(text)
                        require(target.length() + chunk.length() <= 200_000) { "备份记录数量超限" }
                        for (i in 0 until chunk.length()) target.put(chunk.getJSONObject(i))
                    }
                    paper != null -> {
                        require(paperIndexes.add(paper.groupValues[1].toInt()) && paperIndexes.size <= 40)
                        papers += ExamCodec.decode(text).single()
                    }
                    else -> throw IllegalArgumentException("未知备份条目")
                }
                zip.closeEntry()
            }
        }
        val meta = requireNotNull(manifest) { "完整备份缺少清单" }
        require(meta.string("format") == "CET6CompleteBackup" && meta.int("version") == 1) { "不兼容的完整备份" }
        val declared = meta.getJSONArray("entries")
        val names = mutableSetOf<String>()
        for (i in 0 until declared.length()) {
            val item = declared.getJSONObject(i)
            val name = item.string("name")
            require(name != "manifest.json" && names.add(name) &&
                actual[name] == (item.int("bytes") to item.string("sha256"))) { "备份完整性校验失败" }
        }
        require(names == actual.keys - "manifest.json") { "备份清单与内容不一致" }
        require(meta.int("paperCount") == papers.size && paperIndexes == papers.indices.toSet()) { "题库条目缺失" }
        require(papers.map { it.stableId }.toSet().size == papers.size) { "题库重复" }
        val root = JSONObject().put("format", "CET6StudyBackup").put("version", 5).put("databaseVersion", 8)
            .put("createdAt", meta.long("createdAt"))
        for (table in tables) {
            val indexes = chunkIndexes.getValue(table)
            require(indexes.isNotEmpty() && indexes == (0 until indexes.size).toSet() &&
                meta.getJSONObject("counts").int(table) == rows.getValue(table).length()) { "学习记录条目缺失" }
            root.put(table, rows.getValue(table))
        }
        return CompleteBackup(BackupCodec.decodeRoot(root), papers)
    }
}
