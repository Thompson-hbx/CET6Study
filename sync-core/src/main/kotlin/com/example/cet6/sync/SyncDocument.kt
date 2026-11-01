package com.example.cet6.sync

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/** Only learning fields enter this format. Credentials, preferences and files have no record type. */
data class SyncDocument(val records: Map<String, String> = emptyMap()) {
    fun json(): JSONObject = JSONObject().put("format", "XiyeSync").put("version", 1)
        .put("records", JSONObject().apply { records.toSortedMap().forEach { (key, value) -> put(key, JSONObject(value)) } })

    fun encode(): String = canonical(json()).also { require(it.toByteArray().size <= MAX_BYTES) { "同步数据超过 32 MiB" } }

    fun backup(): String {
        val root = JSONObject().put("format", "CET6StudyBackup").put("version", 5)
            .put("databaseVersion", 8).put("createdAt", 0)
        val arrays = (listOf("coach", "turns", "plans", "reading", "essays", "words", "reviews", "schedules"))
            .associateWith { JSONArray() }
        records.toSortedMap().forEach { (key, value) ->
            val row = JSONObject(value)
            when (val type = key.substringBefore(':')) {
                "vocabulary" -> {
                    arrays.getValue("words").put(row.getJSONObject("word"))
                    if (!row.isNull("schedule")) arrays.getValue("schedules").put(row.getJSONObject("schedule"))
                    val reviews = row.getJSONArray("reviews")
                    for (i in 0 until reviews.length()) arrays.getValue("reviews").put(reviews.getJSONObject(i))
                }
                else -> arrays.getValue(type).put(if (type in setOf("reading", "essays"))
                    row.put("id", arrays.getValue(type).length() + 1) else row)
            }
        }
        arrays.forEach { (name, rows) -> root.put(name, rows) }
        return root.toString()
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_RECORDS = 300_000
        private val schemas = mapOf(
            "coach" to "id kind title material questions answer feedback model errorReason submittedAt updatedAt savedSentences sourcePracticeId practiceGoal",
            "turns" to "id practiceId anchor kind role content state model createdAt",
            "plans" to "date newGoal reviewGoal",
            "reading" to "questionId answer correct createdAt",
            "essays" to "topic content feedback createdAt",
            "words" to "word meaning example dueAt repetitions intervalDays source",
            "reviews" to "id word remembered wasNew studiedOn createdAt nextDueAt rating algorithm stability difficulty elapsedDays",
            "schedules" to "word stability difficulty lastReviewedAt phase step algorithm"
        ).mapValues { it.value.split(' ').toSet() }

        fun decode(text: String): SyncDocument {
            require(text.toByteArray().size <= MAX_BYTES) { "同步数据超过 32 MiB" }
            val root = JSONObject(text)
            fields(root, setOf("format", "version", "records"))
            require(root.get("format") == "XiyeSync" && integer(root, "version") == 1L) { "同步协议版本不兼容" }
            val rows = root.getJSONObject("records")
            require(rows.length() <= MAX_RECORDS) { "同步记录数量超限" }
            val records = rows.keys().asSequence().associateWith { key ->
                require(key.length in 3..300 && ':' in key) { "同步标识无效" }
                val row = rows.getJSONObject(key)
                val type = key.substringBefore(':')
                if (type == "vocabulary") {
                    fields(row, setOf("word", "schedule", "reviews"))
                    validateRow("words", row.getJSONObject("word"))
                    val word = row.getJSONObject("word").getString("word")
                    require(key == "vocabulary:$word") { "词条标识不一致" }
                    if (!row.isNull("schedule")) {
                        validateRow("schedules", row.getJSONObject("schedule"))
                        require(row.getJSONObject("schedule").getString("word") == word)
                    }
                    val reviews = row.getJSONArray("reviews")
                    require(reviews.length() <= 100_000)
                    val ids = mutableSetOf<String>()
                    for (i in 0 until reviews.length()) {
                        val review = reviews.getJSONObject(i)
                        validateRow("reviews", review)
                        require(review.getString("word") == word && ids.add(review.getString("id"))) { "学习日志重复或引用无效" }
                    }
                    if (!row.isNull("schedule")) {
                        val schedule = row.getJSONObject("schedule")
                        require((0 until reviews.length()).any { i -> reviews.getJSONObject(i).let { review ->
                            review.get("createdAt") == schedule.get("lastReviewedAt") && review.get("algorithm") == schedule.get("algorithm") &&
                                (review.get("stability") as Number).toDouble() == (schedule.get("stability") as Number).toDouble() &&
                                (review.get("difficulty") as Number).toDouble() == (schedule.get("difficulty") as Number).toDouble() &&
                                integer(review, "nextDueAt") == integer(row.getJSONObject("word"), "dueAt")
                        } }) { "单词进度、日志与复习状态不一致" }
                    }
                } else {
                    require(type in setOf("coach", "turns", "plans", "reading", "essays")) { "未知同步类型" }
                    validateRow(type, row)
                    if (type in setOf("coach", "turns")) require(key == "$type:${row.getString("id")}")
                    if (type == "plans") require(key == "plans:${row.getString("date")}")
                }
                canonical(row)
            }
            records.filterKeys { it.startsWith("turns:") }.values.forEach {
                require("coach:${JSONObject(it).getString("practiceId")}" in records) { "对话对应的练习不存在" }
            }
            return SyncDocument(records.toSortedMap())
        }

        fun fromBackup(text: String): SyncDocument {
            require(text.toByteArray().size <= MAX_BYTES)
            val root = JSONObject(text)
            fields(root, setOf("format", "version", "databaseVersion", "createdAt", "coach", "turns", "words", "reviews", "schedules", "plans", "reading", "essays"))
            require(root.get("format") == "CET6StudyBackup" && integer(root, "version") == 5L && integer(root, "databaseVersion") == 8L)
            val records = sortedMapOf<String, String>()
            fun rows(name: String): List<JSONObject> = root.getJSONArray(name).let { a -> (0 until a.length()).map(a::getJSONObject) }
            for (type in listOf("coach", "turns", "plans", "reading", "essays")) {
                val counts = mutableMapOf<String, Int>()
                rows(type).forEach { row ->
                    val id = when (type) {
                        "plans" -> row.getString("date")
                        "reading", "essays" -> {
                            row.remove("id")
                            val hash = digest(canonical(row))
                            val occurrence = (counts[hash] ?: 0) + 1
                            counts[hash] = occurrence
                            "$hash/$occurrence"
                        }
                        else -> row.getString("id")
                    }
                    require(records.put("$type:$id", canonical(row)) == null) { "同步记录标识重复" }
                }
            }
            val reviews = rows("reviews").groupBy { it.getString("word") }
            val schedules = rows("schedules").associateBy { it.getString("word") }
            rows("words").forEach { word ->
                val id = word.getString("word")
                val row = JSONObject().put("word", word).put("schedule", schedules[id] ?: JSONObject.NULL)
                    .put("reviews", JSONArray(reviews[id].orEmpty().sortedBy { it.getString("id") }))
                require(records.put("vocabulary:$id", canonical(row)) == null)
            }
            return decode(SyncDocument(records).encode())
        }

        private fun validateRow(type: String, row: JSONObject) {
            fields(row, schemas.getValue(type))
            val integers = setOf("submittedAt", "updatedAt", "createdAt", "dueAt", "repetitions", "intervalDays", "nextDueAt", "rating", "elapsedDays", "lastReviewedAt", "phase", "step", "newGoal", "reviewGoal")
            val booleans = setOf("correct", "remembered", "wasNew")
            val numbers = setOf("stability", "difficulty")
            row.keys().forEach { field ->
                when (field) {
                    in integers -> require(integer(row, field) in 0..if (field in setOf("repetitions", "intervalDays", "rating", "elapsedDays", "phase", "step", "newGoal", "reviewGoal"))
                        Int.MAX_VALUE.toLong() else 253_402_300_799_999L) { "记录数值无效" }
                    in booleans -> require(row.get(field) is Boolean) { "记录布尔值无效" }
                    in numbers -> require(row.get(field) is Number && (row.get(field) as Number).toDouble().isFinite())
                    else -> require(row.get(field) is String && row.getString(field).length <= when (field) {
                        "feedback" -> 200_000; "content" -> 100_000; "material" -> 60_000; "answer" -> 30_000
                        "questions", "savedSentences", "state" -> 20_000; else -> 4000
                    }) { "记录文本无效或过长" }
                }
            }
            if (type == "coach") {
                require(row.getString("id").length in 1..100 && row.getString("sourcePracticeId").length <= 100 &&
                    row.getString("sourcePracticeId") != row.getString("id"))
                require(row.getString("title").length <= 1000 && row.getString("model").length <= 200)
                require(row.getString("kind") in setOf("READING", "WRITING", "TRANSLATION"))
                require(integer(row, "submittedAt") <= integer(row, "updatedAt"))
            }
            if (type == "turns") {
                require(row.getString("id").length in 1..100 && row.getString("practiceId").length in 1..100)
                require(row.getString("role") in setOf("user", "assistant"))
                require(row.getString("content").isNotBlank() && row.getString("content").length <= 60_000 &&
                    row.getString("state").length <= 8000 && row.getString("model").length <= 200)
                require(row.getString("kind") in setOf("write-review/1", "write-plan/1", "trans-review/1", "trans-hint/1", "read-explain/1", "read-para/1", "read-overview/1", "chat/1"))
                val anchor = row.getString("anchor")
                require(anchor in setOf("review", "plan") || anchor.matches(Regex("(?:q|para|over|sent):[0-9]{1,4}"))) { "对话锚点无效" }
            }
            if (type == "plans") {
                require(LocalDate.parse(row.getString("date")).toString() == row.getString("date"))
                require(integer(row, "newGoal") in 1..100 && integer(row, "reviewGoal") in 1..200)
            }
            if (type == "words") {
                require(row.getString("word").length in 1..100 && row.getString("word").matches(Regex("[a-z]+(?:[ '-][a-z]+)*")))
                require(row.getString("meaning").length in 1..1000 && row.getString("example").length <= 2000 && row.getString("source").length in 1..300)
                require(integer(row, "intervalDays") in 0..36_500)
            }
            if (type == "reading") require(row.getString("questionId").length in 1..200 && row.getString("answer").length in 1..1000)
            if (type == "essays") require(row.getString("topic").length <= 1000 && row.getString("content").isNotEmpty())
            if (type == "reviews") {
                require(row.getString("id").length in 1..100 && integer(row, "nextDueAt") > integer(row, "createdAt"))
                require(LocalDate.parse(row.getString("studiedOn")).toString() == row.getString("studiedOn"))
                if (row.getString("algorithm") == "legacy") require(integer(row, "rating") == 0L &&
                    row.getDouble("stability") == 0.0 && row.getDouble("difficulty") == 0.0)
                else {
                    require(row.getString("algorithm") == FSRS_ALGORITHM) { "复习算法不兼容" }
                    require(integer(row, "rating") in 1..3 && row.getBoolean("remembered") == (integer(row, "rating") == 3L))
                    require(row.getDouble("stability") > 0 && row.getDouble("difficulty") in 1.0..10.0)
                }
            }
            if (type == "schedules") {
                require(integer(row, "phase") in 1..3 && integer(row, "step") in 0..1)
                require(integer(row, "phase") == 1L || integer(row, "step") == 0L)
                require(row.getString("algorithm") == FSRS_ALGORITHM) { "复习算法不兼容" }
                require(row.getDouble("stability") > 0 && row.getDouble("difficulty") in 1.0..10.0)
            }
        }
        private const val FSRS_ALGORITHM = "fsrs6-py6.3.1-bee98f3ed75-fix1-r0.9-steps1,10-relearn1-v1"
    }
}

fun fields(value: JSONObject, expected: Set<String>) {
    require(value.keys().asSequence().toSet() == expected) { "同步字段不完整或包含未知字段" }
}

fun integer(value: JSONObject, key: String): Long {
    val raw = value.get(key)
    require(raw is Int || raw is Long) { "同步版本或数值必须是整数" }
    return (raw as Number).toLong()
}

fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> error("不支持的同步数据类型")
}

fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
