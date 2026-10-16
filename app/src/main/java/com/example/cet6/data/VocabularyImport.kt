package com.example.cet6.data

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

object VocabularyImport {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_WORDS = 10_000

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "词库文件不能超过 2 MiB" }
            bytes.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun parse(text: String, source: String): List<WordEntity> {
        require(source.trim().length in 2..300) { "请填写 2–300 字的合法来源与使用授权说明" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "词库文件不能超过 2 MiB" }
        val content = text.removePrefix("\uFEFF").trim()
        val rows: List<List<String>> = if (content.startsWith("[")) {
            val array = JSONArray(content)
            require(array.length() in 1..MAX_WORDS) { "词库需包含 1–10000 条记录" }
            (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                listOf("word", "meaning", "example").map { key ->
                    val value = row.get(key)
                    require(value is String) { "第 ${i + 1} 条的 $key 必须为文本" }
                    value
                }
            }
        } else {
            val csv = parseCsv(content)
            require(csv.firstOrNull()?.map { it.trim() } == listOf("word", "meaning", "example")) {
                "CSV 表头必须是 word,meaning,example"
            }
            csv.drop(1)
        }
        require(rows.size in 1..MAX_WORDS) { "词库需包含 1–10000 条记录" }
        val keys = HashSet<String>()
        return rows.mapIndexed { index, row ->
            require(row.size == 3) { "第 ${index + 1} 条必须有 word、meaning、example 三列" }
            val word = row[0].trim().lowercase(Locale.ROOT)
            val meaning = row[1].trim()
            val example = row[2].trim()
            require(word.matches(Regex("[a-z]+(?:[ '-][a-z]+)*")) && word.length <= 100) {
                "第 ${index + 1} 条单词格式错误（只支持英文单词或短语）"
            }
            require(meaning.length in 1..1000 && example.length <= 2000) { "第 ${index + 1} 条释义为空或内容过长" }
            require(keys.add(word)) { "文件内有重复单词：$word，请先合并再导入" }
            WordEntity(word, meaning, example, source = source.trim())
        }
    }

    /** RFC-style quoting, commas/newlines in quotes, CRLF and escaped double quotes. */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var index = 0
        fun endField() { row += field.toString(); field.setLength(0); closed = false }
        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows += row.toList()
            row.clear()
            require(rows.size <= MAX_WORDS + 1) { "词库最多 10000 条记录" }
        }
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') { field.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else field.append(char)
            } else when (char) {
                '"' -> { require(field.isEmpty() && !closed) { "CSV 引号位置错误" }; quoted = true }
                ',' -> endField()
                '\n', '\r' -> { if (char == '\r' && index < text.length && text[index] == '\n') index++; endRow() }
                else -> { require(!closed) { "CSV 引号后只能是逗号或换行" }; field.append(char) }
            }
        }
        require(!quoted) { "CSV 存在未闭合的引号" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) endRow()
        return rows
    }
}
