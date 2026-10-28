package com.example.cet6.data

import org.json.JSONObject

/** Validate known fields without coercion; unknown fields/types remain forward compatible. */
internal object CoachOutputSchema {
    private val issueLevels = setOf("must", "better")
    private val aspectLevels = setOf("好", "一般", "待改进")
    private val segmentVerdicts = setOf("good", "minor", "error", "missing")
    private val optionVerdicts = setOf("correct", "wrong")

    fun review(rows: List<JSONObject>): Boolean = singletons(rows, setOf("score", "rewrite")) &&
        uniqueIndexes(rows, "segment") && rows.all { o ->
            when (o.optString("type")) {
                "score" -> o.integer("band")?.let { it in 0..15 } == true && o.strings("summary")
                "aspect" -> o.nonEmpty("name") && o.strings("level", "note") && o.choiceOrAbsent("level", aspectLevels)
                "issue" -> o.strings("quote", "fix", "reason", "category", "level") &&
                    (o.nonEmpty("quote") || o.nonEmpty("fix")) && o.positiveOrAbsent("index") && o.choiceOrAbsent("level", issueLevels)
                "segment" -> o.positive("index") && o.strings("mine", "verdict", "note", "suggestion") &&
                    o.choiceOrAbsent("verdict", segmentVerdicts)
                "term" -> o.nonEmpty("zh") && o.nonEmpty("en") && o.strings("note")
                "tip", "rewrite" -> o.opt("text") is String
                else -> true
            }
        }

    fun reading(rows: List<JSONObject>): Boolean = singletons(rows, setOf("locate", "misread", "check", "summary")) &&
        rows.filter { it.optString("type") == "option" }.map { (it.opt("label") as? String)?.trim()?.uppercase() }
            .let { it.distinct().size == it.size } && rows.all { o ->
            when (o.optString("type")) {
                "locate" -> o.opt("quote") is String && o.strings("note") && o.positiveOrAbsent("paragraph")
                "paraphrase" -> o.nonEmpty("passage") && o.strings("stem", "option")
                "option" -> (o.opt("label") as? String)?.trim()?.uppercase()?.let { it in setOf("A", "B", "C", "D") } == true &&
                    (o.opt("verdict") as? String)?.let { it in optionVerdicts } == true && o.strings("note")
                "check" -> o.nonEmpty("question") && o.nonEmpty("answer")
                "misread", "summary" -> o.opt("text") is String
                else -> true
            }
        }

    fun overview(rows: List<JSONObject>, paragraphCount: Int): Boolean = singletons(rows, setOf("main", "structure")) &&
        uniqueIndexes(rows, "para") && rows.all { o ->
            when (o.optString("type")) {
                "main", "structure" -> o.opt("text") is String
                "para" -> o.integer("index")?.let { it in 1..paragraphCount } == true && o.nonEmpty("gist") && o.strings("role")
                else -> true
            }
        }

    private fun singletons(rows: List<JSONObject>, types: Set<String>): Boolean {
        val seen = mutableSetOf<String>()
        return rows.all { val type = it.optString("type"); type !in types || seen.add(type) }
    }

    private fun uniqueIndexes(rows: List<JSONObject>, type: String): Boolean = rows.filter { it.optString("type") == type }
        .map { it.integer("index") }.let { it.distinct().size == it.size }

    private fun JSONObject.strings(vararg keys: String): Boolean = keys.all { !has(it) || opt(it) is String }
    private fun JSONObject.nonEmpty(key: String): Boolean = (opt(key) as? String)?.isNotBlank() == true
    private fun JSONObject.positive(key: String): Boolean = integer(key)?.let { it > 0 } == true
    private fun JSONObject.positiveOrAbsent(key: String): Boolean = !has(key) || positive(key)
    private fun JSONObject.choiceOrAbsent(key: String, values: Set<String>): Boolean = !has(key) ||
        (opt(key) as? String)?.let { it in values } == true

    // Both Android's JSON parser and the JVM test implementation produce Int/Long for integer literals.
    private fun JSONObject.integer(key: String): Int? = when (val value = opt(key)) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }
}
