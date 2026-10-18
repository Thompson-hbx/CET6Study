package com.example.cet6.data

import java.time.LocalDate

/** Content is independent of Room learning history. Set numbers are editorial labels, not official IDs. */
data class ExamSession(val date: String, val sourceUrl: String, val note: String = "") {
    val year get() = LocalDate.parse(date).year
    val month get() = LocalDate.parse(date).monthValue
}

object ExamCatalog {
    val sessions = listOf(
        ExamSession("2023-03-12", "https://jwc.cuit.edu.cn/info/1174/2438.htm", "2022 下半年加考；按实际举行日期归档"),
        ExamSession("2023-06-17", "https://www.zbu.edu.cn/jwc/2023/0613/c848a88440/page.htm"),
        ExamSession("2023-12-16", "https://www.neea.edu.cn/html1/report/2309/7-1.htm"),
        ExamSession("2024-06-15", "https://www.neea.edu.cn/html1/report/2403/75-1.htm"),
        ExamSession("2024-12-14", "https://www.neea.edu.cn/html1/report/2409/8-1.htm"),
        ExamSession("2025-06-14", "https://www.neea.edu.cn/html1/report/2503/19-1.htm"),
        ExamSession("2025-12-13", "https://www.neea.edu.cn/html1/report/2509/26-1.htm"),
        ExamSession("2026-06-13", "https://www.neea.edu.cn/html1/report/2603/2-1.htm")
    )
    const val SET_SOURCE = "https://www.wehuster.com/cet6"
    fun paperId(date: String, set: String) = "cet6-$date-$set"
    fun papers() = sessions.flatMap { session -> (1..3).map { set ->
        ExamPaper(paperId(session.date, "$set"), session.date, "$set", sourceName = "目录核对：教育考试机构 / WeHUSTER",
            sourceUrl = session.sourceUrl)
    } }
}

data class ExamOption(val label: String, val text: String)
data class ExamQuestion(val number: Int, val stem: String, val options: List<ExamOption>,
    val referenceAnswer: String = "", val answerSource: String = "", val evidence: String = "")
data class ExamPassage(val code: String, val title: String, val content: String, val questions: List<ExamQuestion>)
data class ExamPaper(
    val stableId: String, val examDate: String, val setCode: String, val revision: Int = 1,
    val sourceName: String = "", val sourceUrl: String = "", val licenseStatus: String = "unknown",
    val reviewed: Boolean = false, val sample: Boolean = false,
    val passages: List<ExamPassage> = emptyList(), val writingPrompt: String = "", val writingInstructions: String = "",
    val chineseText: String = "", val referenceTranslation: String = "", val referenceSource: String = ""
) {
    val label get() = if (sample) "原创功能样本 · 非真题" else "${examDate.take(4)} 年 ${examDate.substring(5, 7).toInt()} 月 · 第 $setCode 套"
    fun issues(kind: CoachKind? = null): List<String> = buildList {
        if (licenseStatus !in setOf("personal-use", "redistributable", "original")) add("使用权未确认")
        if (!reviewed) add("尚未完成人工校对（含跨套卷与来源核对）")
        if (kind == null || kind == CoachKind.READING) {
        if (passages.size != 2 || passages.map { it.code }.toSet() != setOf("1", "2")) add("仔细阅读应包含篇章 1、2")
        passages.forEach { p ->
            if (p.content.isBlank()) add("篇章 ${p.code} 缺失正文")
            val expected = if (p.code == "1") (46..50).toSet() else (51..55).toSet()
            if (p.questions.size != 5 || p.questions.map { it.number }.toSet() != expected) add("篇章 ${p.code} 题号不完整或重复")
            p.questions.forEach { q ->
                if (q.stem.isBlank() || q.options.size != 4 || q.options.map { it.label }.toSet() != setOf("A", "B", "C", "D") || q.options.any { it.text.isBlank() }) add("${q.number} 题缺少题干或 A/B/C/D 选项")
                if (q.referenceAnswer !in setOf("", "A", "B", "C", "D")) add("${q.number} 题答案冲突或无效")
                if (q.referenceAnswer.isNotBlank() && q.answerSource.isBlank()) add("${q.number} 题答案缺少来源")
                if (q.evidence.isNotBlank() && !p.content.contains(q.evidence)) add("${q.number} 题证据不在原文")
            }
        }
        }
        if ((kind == null || kind == CoachKind.WRITING) && (writingPrompt.isBlank() || writingInstructions.isBlank())) add("写作任务或要求缺失")
        if ((kind == null || kind == CoachKind.TRANSLATION) && chineseText.isBlank()) add("翻译中文原文缺失")
        if ((kind == null || kind == CoachKind.TRANSLATION) && referenceTranslation.isNotBlank() && referenceSource.isBlank()) add("参考译文缺少来源")
    }
    val status get() = if (licenseStatus == "unknown" && passages.isEmpty()) "待导入" else if (issues().isEmpty()) "已导入" else "待校对"
    fun identityIssues() {
        require(revision > 0 && setCode in setOf("1", "2", "3")) { "版本或套次无效" }
        require(ExamCatalog.sessions.any { it.date == examDate }) { "考次不在已核实的已举行目录中" }
        require(stableId == (if (sample) "sample-" else "") + ExamCatalog.paperId(examDate, setCode)) { "stableId 与年份、日期、套次不一致" }
        require(sourceName.isNotBlank() && sourceUrl.isNotBlank()) { "缺少材料来源记录" }
        require(passages.map { it.code }.distinct().size == passages.size && passages.all { it.code in setOf("1", "2") }) { "篇章标识重复或无效" }
    }
}
