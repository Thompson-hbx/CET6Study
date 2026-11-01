package com.example.cet6.desktop

import com.example.cet6.data.CoachReview
import org.json.JSONObject

/** One correction as the reviewer saved it: the quoted words, the suggested fix and why; [must] is false for polish. */
data class MarginNote(val quote: String, val fix: String, val reason: String, val category: String, val must: Boolean)

/** Both clients reject the same malformed reviews and retain the original text for display. */
fun marginNotes(text: String): List<MarginNote> {
    val review = CoachReview.parse(text)
    if (!review.structured) return emptyList()
    return review.issues.map { MarginNote(it.quote, it.fix, it.reason, it.category, it.must) }
}

/** Display saved protocol fields without changing, accepting or inventing any correction. */
fun readableFeedback(text: String): String = text.lineSequence().joinToString("\n") { line ->
    val row = try {
        JSONObject(line)
    } catch (_: org.json.JSONException) {
        return@joinToString line
    }
    fun s(key: String) = row.opt(key) as? String ?: ""
    when (s("type")) {
        "score" -> "AI 模拟档位：${row.opt("band")}（仅供参考）\n${s("summary")}\n"
        "aspect" -> "${s("name")} · ${s("level")}\n${s("note")}\n"
        "issue" -> "原句：${s("quote")}\n建议：${s("fix")}\n${s("reason")}\n"
        "segment" -> "第 ${row.opt("index")} 句\n原译：${s("mine")}\n${s("note")}\n建议：${s("suggestion")}\n"
        "term" -> "${s("zh")} → ${s("en")}\n${s("note")}\n"
        "tip", "rewrite", "misread", "summary" -> "${s("text")}\n"
        "locate" -> "原文定位 · 第 ${row.opt("paragraph")} 段\n${s("quote")}\n${s("note")}\n"
        "paraphrase" -> "题干：${s("stem")}\n原文：${s("passage")}\n选项：${s("option")}\n"
        "option" -> "${s("label")} · ${when (s("verdict")) {
            "correct" -> "参考正确项"
            "wrong" -> "干扰项"
            else -> s("verdict")
        }}\n${s("note")}\n"
        "check" -> "自测：${s("question")}\n参考：${s("answer")}\n"
        else -> line
    }
}
