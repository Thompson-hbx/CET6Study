package com.example.cet6.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Explicit whitelist of learning fields; this type has no reference to auth/session storage. */
object BackupCodec {
    private fun obj(vararg values: Pair<String, Any>) = JSONObject().apply { values.forEach { put(it.first, it.second) } }
    private fun <T> array(items: List<T>, encode: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(encode(it)) } }
    fun encode(data: StudyBackup): String = json(data).toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= BackupValidation.MAX_BYTES) { "备份大于 32 MiB，请使用分段完整备份" }
    }
    internal fun json(data: StudyBackup): JSONObject {
        BackupValidation.validate(data)
        return obj("format" to "CET6StudyBackup", "version" to 6, "databaseVersion" to 9, "createdAt" to data.createdAt,
            "coach" to array(data.coach) { obj("id" to it.id, "kind" to it.kind, "title" to it.title,
                "material" to it.material, "questions" to it.questions, "answer" to it.answer, "feedback" to it.feedback,
                "model" to it.model, "errorReason" to it.errorReason, "submittedAt" to it.submittedAt, "updatedAt" to it.updatedAt,
                "savedSentences" to it.savedSentences, "sourcePracticeId" to it.sourcePracticeId, "practiceGoal" to it.practiceGoal) },
            "turns" to array(data.turns) { obj("id" to it.id, "practiceId" to it.practiceId, "anchor" to it.anchor, "kind" to it.kind,
                "role" to it.role, "content" to it.content, "state" to it.state, "model" to it.model, "createdAt" to it.createdAt, "locatedQuote" to it.locatedQuote) },
            "words" to array(data.words) { obj("word" to it.word, "meaning" to it.meaning, "example" to it.example,
                "source" to it.source, "dueAt" to it.dueAt, "repetitions" to it.repetitions, "intervalDays" to it.intervalDays) },
            "reviews" to array(data.reviews) { obj("id" to it.id, "word" to it.word, "remembered" to it.remembered,
                "wasNew" to it.wasNew, "studiedOn" to it.studiedOn, "createdAt" to it.createdAt, "nextDueAt" to it.nextDueAt,
                "rating" to it.rating, "algorithm" to it.algorithm, "stability" to it.stability, "difficulty" to it.difficulty, "elapsedDays" to it.elapsedDays) },
            "schedules" to array(data.schedules) { obj("word" to it.word, "stability" to it.stability, "difficulty" to it.difficulty,
                "lastReviewedAt" to it.lastReviewedAt, "phase" to it.phase, "step" to it.step, "algorithm" to it.algorithm) },
            "plans" to array(data.plans) { obj("date" to it.date, "newGoal" to it.newGoal, "reviewGoal" to it.reviewGoal) },
            "reading" to array(data.reading) { obj("id" to it.id, "questionId" to it.questionId, "answer" to it.answer,
                "correct" to it.correct, "createdAt" to it.createdAt) },
            "essays" to array(data.essays) { obj("id" to it.id, "topic" to it.topic, "content" to it.content,
                "feedback" to it.feedback, "createdAt" to it.createdAt) })
    }
    fun read(input: InputStream): StudyBackup {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= BackupValidation.MAX_BYTES) { "备份文件大于 32 MiB" }
            bytes.write(buffer, 0, count)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        return decode(text)
    }
    fun decode(text: String): StudyBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= BackupValidation.MAX_BYTES) { "备份文件大于 32 MiB" }
        return decodeRoot(JSONObject(text.removePrefix("\uFEFF")))
    }
    internal fun decodeRoot(root: JSONObject): StudyBackup {
        val version = root.int("version")
        val fields = setOf("format", "version", "databaseVersion", "createdAt", "words", "reviews", "schedules", "plans", "reading", "essays")
        require(root.keys().asSequence().toSet() == when (version) {
            in 2..4 -> fields + "coach"
            5, 6 -> fields + "coach" + "turns"
            else -> fields
        }) { "备份字段不完整或不兼容" }
        require(root.string("format") == "CET6StudyBackup" &&
            ((version == 1 && root.int("databaseVersion") == 4) || (version == 2 && root.int("databaseVersion") == 5) ||
                (version == 3 && root.int("databaseVersion") == 6) || (version == 4 && root.int("databaseVersion") == 7) ||
                (version == 5 && root.int("databaseVersion") == 8) ||
                (version == 6 && root.int("databaseVersion") == 9))) { "不是兼容的 CET6Study 学习备份" }
        return StudyBackup(root.long("createdAt"),
            root.rows("words", 20_000) { WordEntity(it.string("word"), it.string("meaning"), it.string("example"),
                it.long("dueAt"), it.int("repetitions"), it.int("intervalDays"), it.string("source")) },
            root.rows("reviews", 100_000) { WordReview(it.string("id"), it.string("word"), it.bool("remembered"),
                it.bool("wasNew"), it.string("studiedOn"), it.long("createdAt"), it.long("nextDueAt"), it.int("rating"),
                it.string("algorithm"), it.number("stability"), it.number("difficulty"), it.int("elapsedDays")) },
            root.rows("schedules", 20_000) { WordSchedule(it.string("word"), it.number("stability"), it.number("difficulty"),
                it.long("lastReviewedAt"), it.int("phase"), it.int("step"), it.string("algorithm")) },
            root.rows("plans", 20_000) { DailyPlan(it.string("date"), it.int("newGoal"), it.int("reviewGoal")) },
            root.rows("reading", 100_000) { ReadingAttempt(it.long("id"), it.string("questionId"), it.string("answer"), it.bool("correct"), it.long("createdAt")) },
            root.rows("essays", 20_000) { EssayEntity(it.long("id"), it.string("topic"), it.string("content"), it.string("feedback"), it.long("createdAt")) },
            if (version == 1) emptyList() else root.rows("coach", 20_000) { CoachPractice(it.string("id"), it.string("kind"),
                it.string("title"), it.string("material"), it.string("questions"), it.string("answer"), it.string("feedback"),
                it.string("model"), it.string("errorReason"), it.long("submittedAt"), it.long("updatedAt"),
                if (version >= 3) it.string("savedSentences") else "",
                if (version >= 4) it.string("sourcePracticeId") else "", if (version >= 4) it.string("practiceGoal") else "") },
            if (version < 5) emptyList() else root.rows("turns", 200_000) { CoachTurn(it.string("id"), it.string("practiceId"),
                it.string("anchor"), it.string("kind"), it.string("role"), it.string("content"), it.string("state"),
                it.string("model"), it.long("createdAt"), if (version >= 6) it.string("locatedQuote") else "") }
        ).also(BackupValidation::validate)
    }
    internal fun JSONObject.string(key: String) = get(key).also { require(it is String) { "$key 必须是文本" } } as String
    private fun JSONObject.bool(key: String) = get(key).also { require(it is Boolean) { "$key 必须是布尔值" } } as Boolean
    internal fun JSONObject.long(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "$key 必须是整数" }
        return (value as Number).toLong()
    }
    internal fun JSONObject.int(key: String): Int = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$key 超出范围" } }.toInt()
    private fun JSONObject.number(key: String): Double {
        val value = get(key)
        require(value is Number && value.toDouble().isFinite()) { "$key 必须是有限数值" }
        return value.toDouble()
    }
    private fun <T> JSONObject.rows(key: String, maximum: Int, decode: (JSONObject) -> T): List<T> {
        val array = getJSONArray(key)
        require(array.length() <= maximum) { "$key 数量超限" }
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }
}
