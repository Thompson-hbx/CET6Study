package com.example.cet6.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ExamCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    fun decode(text: String): List<ExamPaper> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "内容包超过 4 MiB" }
        val root = JSONObject(text)
        require(root.getString("format") == "CET6ExamPack" && root.getInt("version") == 1) { "不支持的题库格式" }
        val papers = strings(root.getJSONArray("papers")).map(::paper)
        require(papers.isNotEmpty() && papers.size <= 40 && papers.map { it.stableId }.distinct().size == papers.size) { "空内容包、重复套卷或套卷过多" }
        papers.forEach { p ->
            p.identityIssues()
            require(p.sourceName.length <= 500 && p.sourceUrl.length <= 1000 && p.referenceSource.length <= 1000) { "来源记录过长" }
            require(encode(listOf(p)).length <= 200_000) { "单套内容过长" }
            p.passages.forEach { require(it.content.length <= 60_000 && passageJson(it).toString().length <= 19_000) { "篇章或题目过长" } }
            require(p.writingPrompt.length + p.writingInstructions.length <= 60_000 && p.chineseText.length <= 60_000 && p.referenceTranslation.length <= 15_000) { "写译内容过长" }
            if (p.issues(CoachKind.READING).isEmpty()) {
                p.passages.forEach { practice(p, CoachKind.READING, it) }
                practice(p, CoachKind.READING)
            }
            if (p.issues(CoachKind.WRITING).isEmpty()) practice(p, CoachKind.WRITING)
            if (p.issues(CoachKind.TRANSLATION).isEmpty()) practice(p, CoachKind.TRANSLATION)
        }
        return papers
    }
    private fun paper(o: JSONObject) = ExamPaper(
        o.getString("stableId"), o.getString("examDate"), o.getString("setCode"), o.getInt("revision"),
        o.getString("sourceName"), o.getString("sourceUrl"), o.getString("licenseStatus"), o.optBoolean("reviewed"), o.optBoolean("sample"),
        strings(o.optJSONArray("passages") ?: JSONArray()).map(::passage), o.optString("writingPrompt"), o.optString("writingInstructions"),
        o.optString("chineseText"), o.optString("referenceTranslation"), o.optString("referenceSource"))
    fun passage(o: JSONObject) = ExamPassage(o.getString("code"), o.optString("title"), o.getString("content"),
        strings(o.getJSONArray("questions")).map { q -> ExamQuestion(q.getInt("number"), q.getString("stem"),
            strings(q.getJSONArray("options")).map { ExamOption(it.getString("label"), it.getString("text")) },
            q.optString("referenceAnswer"), q.optString("answerSource"), q.optString("evidence")) })
    private fun obj(vararg fields: Pair<String, Any>) = JSONObject().apply { fields.forEach { put(it.first, it.second) } }
    fun passageJson(p: ExamPassage): JSONObject = obj("code" to p.code, "title" to p.title, "content" to p.content,
        "questions" to JSONArray(p.questions.map { q -> obj("number" to q.number, "stem" to q.stem,
            "options" to JSONArray(q.options.map { obj("label" to it.label, "text" to it.text) }),
            "referenceAnswer" to q.referenceAnswer, "answerSource" to q.answerSource, "evidence" to q.evidence) }))
    fun encode(papers: List<ExamPaper>): String = obj("format" to "CET6ExamPack", "version" to 1,
        "papers" to JSONArray(papers.map { p -> obj("stableId" to p.stableId, "examDate" to p.examDate, "setCode" to p.setCode,
            "revision" to p.revision, "sourceName" to p.sourceName, "sourceUrl" to p.sourceUrl, "licenseStatus" to p.licenseStatus,
            "reviewed" to p.reviewed, "sample" to p.sample, "passages" to JSONArray(p.passages.map(::passageJson)),
            "writingPrompt" to p.writingPrompt, "writingInstructions" to p.writingInstructions, "chineseText" to p.chineseText,
            "referenceTranslation" to p.referenceTranslation, "referenceSource" to p.referenceSource) })).toString(2)

    fun practice(p: ExamPaper, kind: CoachKind, passage: ExamPassage? = null): CoachPractice {
        require(p.issues(kind).isEmpty()) { "请先校对本题型内容" }
        val key = "${p.stableId}/${kind.name}/${passage?.code ?: if (kind == CoachKind.READING) "all" else "1"}"
        val meta = obj("format" to "CET6ExamAttempt", "itemId" to key, "revision" to p.revision,
            "sourceName" to p.sourceName, "sourceUrl" to p.sourceUrl, "sample" to p.sample,
            "referenceTranslation" to if (kind == CoachKind.TRANSLATION) p.referenceTranslation else "",
            "referenceSource" to if (kind == CoachKind.TRANSLATION) p.referenceSource else "")
        if (passage != null) meta.put("passage", passageJson(passage))
        if (kind == CoachKind.READING && passage == null) meta.put("passages", JSONArray(p.passages.map(::passageJson)))
        return CoachPractice(kind = kind.name, title = p.label + if (passage != null) " · 篇章 ${passage.code}" else " · ${kind.label}",
            material = when (kind) { CoachKind.READING -> passage?.content ?: p.passages.joinToString("\n\n") { "Passage ${it.code}\n${it.content}" }; CoachKind.WRITING -> "${p.writingPrompt}\n\n${p.writingInstructions}"; CoachKind.TRANSLATION -> p.chineseText },
            questions = meta.toString()) .also(CoachPrompts::validate)
    }
    fun metadata(entry: CoachPractice): JSONObject? = runCatching { JSONObject(entry.questions).takeIf { it.optString("format") == "CET6ExamAttempt" } }.getOrNull()
    fun itemId(entry: CoachPractice) = metadata(entry)?.optString("itemId")
    fun passages(entry: CoachPractice): List<ExamPassage> {
        val meta = metadata(entry) ?: return emptyList()
        meta.optJSONObject("passage")?.let { return listOf(passage(it)) }
        val all = meta.optJSONArray("passages") ?: return emptyList()
        return (0 until all.length()).map { passage(all.getJSONObject(it)) }
    }
}

/** One atomic, versioned local content file. Revisions never alter snapshots already stored in Room. */
class ExamStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "exam-content-v1.json"))
    fun load(): List<ExamPaper> = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyList()
        else file.openRead().bufferedReader(Charsets.UTF_8).use { ExamCodec.decode(it.readText()) }
    fun merge(existing: List<ExamPaper>, incoming: List<ExamPaper>): List<ExamPaper> {
        incoming.forEach { p -> existing.find { it.stableId == p.stableId }?.let { old ->
            require(p.revision >= old.revision) { "拒绝旧版本：${p.label}" }
            require(p.revision != old.revision || p == old) { "同版本内容冲突：请核对来源并提高 revision" }
        } }
        return (existing.associateBy { it.stableId } + incoming.associateBy { it.stableId }).values.toList()
    }
    fun save(papers: List<ExamPaper>) {
        val text = ExamCodec.encode(papers)
        ExamCodec.decode(text)
        val output = file.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Throwable) { file.failWrite(output); throw e }
    }
}
