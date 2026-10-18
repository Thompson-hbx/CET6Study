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
    fun encode(data: StudyBackup): String {
        BackupValidation.validate(data)
        val root = obj("format" to "CET6StudyBackup", "version" to 4, "databaseVersion" to 7, "createdAt" to data.createdAt,
            "coach" to array(data.coach) { obj("id" to it.id, "kind" to it.kind, "title" to it.title,
                "material" to it.material, "questions" to it.questions, "answer" to it.answer, "feedback" to it.feedback,
                "model" to it.model, "errorReason" to it.errorReason, "submittedAt" to it.submittedAt, "updatedAt" to it.updatedAt,
                "savedSentences" to it.savedSentences, "sourcePracticeId" to it.sourcePracticeId, "practiceGoal" to it.practiceGoal) },
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
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= BackupValidation.MAX_BYTES) { "备份大于 32 MiB，未导出；请保留本机数据并联系维护者" } }
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
        val root = JSONObject(text.removePrefix("\uFEFF"))
        val version = root.int("version")
        val fields = setOf("format", "version", "databaseVersion", "createdAt", "words", "reviews", "schedules", "plans", "reading", "essays")
        require(root.keys().asSequence().toSet() == if (version in 2..4) fields + "coach" else fields) { "备份字段不完整或不兼容" }
        require(root.string("format") == "CET6StudyBackup" &&
            ((version == 1 && root.int("databaseVersion") == 4) || (version == 2 && root.int("databaseVersion") == 5) ||
                (version == 3 && root.int("databaseVersion") == 6) || (version == 4 && root.int("databaseVersion") == 7))) { "不是兼容的 CET6Study 学习备份" }
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
                if (version >= 4) it.string("sourcePracticeId") else "", if (version >= 4) it.string("practiceGoal") else "") }
        ).also(BackupValidation::validate)
    }
    private fun JSONObject.string(key: String) = get(key).also { require(it is String) { "$key 必须是文本" } } as String
    private fun JSONObject.bool(key: String) = get(key).also { require(it is Boolean) { "$key 必须是布尔值" } } as Boolean
    private fun JSONObject.long(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "$key 必须是整数" }
        return (value as Number).toLong()
    }
    private fun JSONObject.int(key: String): Int = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$key 超出范围" } }.toInt()
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
