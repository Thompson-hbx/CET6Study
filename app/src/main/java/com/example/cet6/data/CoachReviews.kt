package com.example.cet6.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ReviewScore(val band: Int, val summary: String)
data class ReviewAspect(val name: String, val level: String, val note: String)
data class ReviewIssue(val id: Int, val quote: String, val fix: String, val reason: String,
    val category: String, val must: Boolean, val segment: Int?)
data class ReviewSegment(val index: Int, val mine: String, val verdict: String, val note: String, val suggestion: String) {
    val ok get() = verdict == "good"
}
data class ReviewTerm(val zh: String, val en: String, val note: String)

/**
 * A writing or translation review read from line-delimited JSON. When the model ignored the
 * protocol, [structured] is false and the screen falls back to the raw text instead of guessing.
 */
data class CoachReview(
    val score: ReviewScore?, val aspects: List<ReviewAspect>, val issues: List<ReviewIssue>,
    val segments: List<ReviewSegment>, val terms: List<ReviewTerm>, val tips: List<String>,
    val rewrite: String, val raw: String
) {
    val structured get() = !JsonLines.hasMalformedObjects(raw) &&
        segments.map { it.index }.distinct().size == segments.size &&
        (score != null || aspects.isNotEmpty() || issues.isNotEmpty() || segments.isNotEmpty())

    companion object {
        private val types = setOf("score", "aspect", "issue", "segment", "term", "tip", "rewrite")
        val bands = listOf(2, 5, 8, 11, 14)

        /** Bands are the five public descriptor levels; a raw 0–15 score is shown as its band. */
        fun band(value: Int): Int? = when (value) {
            0 -> 0
            in 1..15 -> bands.minBy { kotlin.math.abs(it - value) }
            else -> null
        }

        fun parse(text: String): CoachReview {
            var score: ReviewScore? = null
            val aspects = mutableListOf<ReviewAspect>()
            val issues = mutableListOf<ReviewIssue>()
            val segments = mutableListOf<ReviewSegment>()
            val terms = mutableListOf<ReviewTerm>()
            val tips = mutableListOf<String>()
            var rewrite = ""
            val rows = JsonLines.typed(text, types)
            if (JsonLines.hasMalformedObjects(text) || !CoachOutputSchema.review(rows)) {
                return CoachReview(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", text)
            }
            rows.forEach { o ->
                when (o.optString("type")) {
                    "score" -> band(o.optInt("band", -1))?.let { score = ReviewScore(it, o.text("summary")) }
                    "aspect" -> if (o.text("name").isNotBlank()) aspects += ReviewAspect(o.text("name"), o.text("level"), o.text("note"))
                    "issue" -> {
                        val quote = o.text("quote")
                        if (quote.isNotBlank() || o.text("fix").isNotBlank()) issues += ReviewIssue(issues.size, quote, o.text("fix"),
                            o.text("reason"), o.text("category").ifBlank { "表达" }, o.optString("level") != "better",
                            o.optInt("index", 0).takeIf { it > 0 })
                    }
                    "segment" -> o.optInt("index", 0).takeIf { it > 0 }?.let { index ->
                        segments += ReviewSegment(index, o.text("mine"), o.text("verdict").ifBlank { "minor" },
                            o.text("note"), o.text("suggestion"))
                    }
                    "term" -> if (o.text("zh").isNotBlank() && o.text("en").isNotBlank()) terms += ReviewTerm(o.text("zh"), o.text("en"), o.text("note"))
                    "tip" -> o.text("text").takeIf(String::isNotBlank)?.let(tips::add)
                    "rewrite" -> rewrite = o.text("text")
                }
            }
            return CoachReview(score, aspects, issues, segments.sortedBy { it.index },
                terms, tips, rewrite, text)
        }
    }
}

enum class ReviewDecision { ACCEPT, DISMISS, RESET }

/** The learner's verdict on individual suggestions; stored on the review turn, never inferred. */
data class ReviewDecisions(val accepted: Set<Int> = emptySet(), val dismissed: Set<Int> = emptySet()) {
    fun accept(id: Int) = copy(accepted = accepted + id, dismissed = dismissed - id)
    fun dismiss(id: Int) = copy(dismissed = dismissed + id, accepted = accepted - id)
    fun reset(id: Int) = copy(accepted = accepted - id, dismissed = dismissed - id)
    fun encode(): String = JSONObject().put("accepted", JSONArray(accepted.sorted())).put("dismissed", JSONArray(dismissed.sorted())).toString()

    companion object {
        /** A failed read must never become an empty state during a database write. */
        internal fun decodeForUpdate(state: String, issueIds: Set<Int>): ReviewDecisions {
            require(state.length <= CoachTurns.MAX_STATE) { "建议状态过长，原状态已保留" }
            if (state.isBlank()) return ReviewDecisions()
            val input = JSONTokener(state)
            require(input.nextClean() == '{') { "建议状态格式无效，原状态已保留" }
            val fields = mutableMapOf<String, Set<Int>>()
            if (input.nextClean() != '}') {
                input.back()
                while (true) {
                    require(input.nextClean() == '\"') { "建议状态字段无效，原状态已保留" }
                    val key = input.nextString('\"')
                    require(key in setOf("accepted", "dismissed") && key !in fields) { "建议状态字段未知或重复，原状态已保留" }
                    require(input.nextClean() == ':') { "建议状态格式无效，原状态已保留" }
                    val array = requireNotNull(input.nextValue() as? JSONArray) { "建议状态必须是编号列表，原状态已保留" }
                    val ids = mutableSetOf<Int>()
                    for (i in 0 until array.length()) {
                        val id = when (val value = array.opt(i)) {
                            is Int -> value
                            is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                            else -> null
                        }
                        require(id != null && id in issueIds && ids.add(id)) { "建议状态编号无效或重复，原状态已保留" }
                    }
                    fields[key] = ids
                    val next = input.nextClean()
                    if (next == '}') break
                    require(next == ',') { "建议状态格式无效，原状态已保留" }
                }
            }
            require(input.nextClean().code == 0) { "建议状态存在多余内容，原状态已保留" }
            val accepted = fields["accepted"].orEmpty()
            val dismissed = fields["dismissed"].orEmpty()
            require(accepted.intersect(dismissed).isEmpty()) { "建议状态互相冲突，原状态已保留" }
            return ReviewDecisions(accepted, dismissed)
        }

        fun decode(state: String): ReviewDecisions = runCatching {
            val o = JSONObject(state)
            fun ints(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
            ReviewDecisions(ints("accepted"), ints("dismissed"))
        }.getOrDefault(ReviewDecisions())
    }
}

data class ExplainLocate(val paragraph: Int?, val quote: String, val note: String)
data class ExplainParaphrase(val stem: String, val passage: String, val option: String)
data class ExplainOption(val label: String, val correct: Boolean, val note: String)

/** One reading question explained step by step; same fallback rule as [CoachReview]. */
data class ReadingExplanation(
    val locate: ExplainLocate?, val paraphrases: List<ExplainParaphrase>, val options: List<ExplainOption>,
    val misread: String, val checkQuestion: String, val checkAnswer: String, val summary: String, val raw: String
) {
    val structured get() = !JsonLines.hasMalformedObjects(raw) &&
        options.map { it.label }.distinct().size == options.size &&
        (locate != null || paraphrases.isNotEmpty() || options.isNotEmpty())

    companion object {
        private val types = setOf("locate", "paraphrase", "option", "misread", "check", "summary")
        fun parse(text: String): ReadingExplanation {
            var locate: ExplainLocate? = null
            val paraphrases = mutableListOf<ExplainParaphrase>()
            val options = mutableListOf<ExplainOption>()
            var misread = ""; var question = ""; var answer = ""; var summary = ""
            val rows = JsonLines.typed(text, types)
            if (JsonLines.hasMalformedObjects(text) || !CoachOutputSchema.reading(rows)) {
                return ReadingExplanation(null, emptyList(), emptyList(), "", "", "", "", text)
            }
            rows.forEach { o ->
                when (o.optString("type")) {
                    "locate" -> locate = ExplainLocate(o.optInt("paragraph", 0).takeIf { it > 0 }, o.text("quote"), o.text("note"))
                    "paraphrase" -> if (o.text("passage").isNotBlank()) paraphrases += ExplainParaphrase(o.text("stem"), o.text("passage"), o.text("option"))
                    "option" -> o.text("label").uppercase().takeIf { it in setOf("A", "B", "C", "D") }?.let {
                        options += ExplainOption(it, o.optString("verdict") == "correct", o.text("note"))
                    }
                    "misread" -> misread = o.text("text")
                    "check" -> { question = o.text("question"); answer = o.text("answer") }
                    "summary" -> summary = o.text("text")
                }
            }
            return ReadingExplanation(locate, paraphrases, options.sortedBy { it.label },
                misread, question, answer, summary, text)
        }
    }
}

data class ParagraphGist(val index: Int, val gist: String, val role: String)

/** Paragraph map of a passage; only paragraphs that exist locally are kept. */
data class PassageOverview(val main: String, val paragraphs: List<ParagraphGist>, val structure: String, val raw: String) {
    val structured get() = !JsonLines.hasMalformedObjects(raw) &&
        paragraphs.map { it.index }.distinct().size == paragraphs.size &&
        (main.isNotBlank() || paragraphs.isNotEmpty())

    companion object {
        fun parse(text: String, paragraphCount: Int): PassageOverview {
            var main = ""; var structure = ""
            val gists = mutableListOf<ParagraphGist>()
            val rows = JsonLines.typed(text, setOf("main", "para", "structure"))
            if (JsonLines.hasMalformedObjects(text) || !CoachOutputSchema.overview(rows, paragraphCount)) {
                return PassageOverview("", emptyList(), "", text)
            }
            rows.forEach { o ->
                when (o.optString("type")) {
                    "main" -> main = o.text("text")
                    "structure" -> structure = o.text("text")
                    "para" -> o.optInt("index", 0).takeIf { it in 1..paragraphCount }?.let {
                        gists += ParagraphGist(it, o.text("gist"), o.text("role"))
                    }
                }
            }
            return PassageOverview(main, gists.sortedBy { it.index }, structure, text)
        }
    }
}

private fun JSONObject.text(key: String): String = (opt(key) as? String)?.trim().orEmpty()
