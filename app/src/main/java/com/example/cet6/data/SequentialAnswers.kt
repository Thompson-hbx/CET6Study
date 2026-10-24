package com.example.cet6.data

/** Users enter 1–5 in passage order; original numbers remain only in stored answer mappings. */
object SequentialAnswers {
    fun normalize(text: String): String {
        require(text.length <= 100) { "每篇只需输入 5 个选项" }
        return text.uppercase().replace(Regex("[\\s,，;；]"), "").replace('？', '?')
    }

    fun sequence(answer: String, questions: List<ExamQuestion>): String = questions.sortedBy { it.number }
        .joinToString("") { ReadingQuestions.selected(answer, "${it.number}").orEmpty().ifBlank { "?" } }.trimEnd('?')

    /** A run typed past one passage's fifth answer continues into the following passages, as on a paper answer sheet. */
    fun split(text: String, passages: Int): List<String> {
        val sequence = normalize(text)
        require(passages >= 1 && sequence.length <= passages * 5 && sequence.matches(Regex("[ABCD?]*"))) {
            "请按顺序输入最多 ${passages * 5} 个 A/B/C/D；未答用 ? 占位"
        }
        return (0 until passages).map { sequence.drop(it * 5).take(5) }
    }

    fun apply(existing: String, text: String, questions: List<ExamQuestion>): String {
        val ordered = questions.sortedBy { it.number }
        require(ordered.size == 5 && ordered.map { it.number }.distinct().size == 5) { "本篇题目不完整，请先核对资料" }
        val sequence = normalize(text)
        require(sequence.length <= 5 && sequence.matches(Regex("[ABCD?]*"))) { "请按 1—5 顺序输入最多 5 个 A/B/C/D；未答用 ? 占位" }
        require(ReadingQuestions.canSelect(existing)) { "原作答格式无法读取，请保留记录并核对" }
        var answer = existing.lineSequence().filter(String::isNotBlank).filterNot { line ->
            ordered.any { ReadingQuestions.selected(line, "${it.number}") != null }
        }.joinToString("\n")
        sequence.forEachIndexed { i, c -> if (c != '?') answer = ReadingQuestions.choose(answer, "${ordered[i].number}", "$c") }
        return answer
    }
}
