package com.example.cet6.data

data class ReadingChoice(val label: String, val text: String)
data class ReadingQuestion(val number: String, val text: String, val choices: List<ReadingChoice>)

/** Optional view of the existing question text. Unrecognised formats remain editable as text. */
object ReadingQuestions {
    private val heading = Regex("(?m)^\\s*(\\d{1,3})[.)、]\\s*(?=\\S)")
    private val option = Regex("(?m)^\\s*([A-F])[.)、:]\\s*(?=\\S)")
    private val answerLine = Regex("^\\s*(\\d{1,3})[.)、:]\\s*([A-F])\\s*$")

    fun parse(text: String): List<ReadingQuestion> {
        val headings = heading.findAll(text).toList()
        if (headings.isEmpty() || text.substring(0, headings.first().range.first).isNotBlank()) return emptyList()
        val questions = headings.mapIndexed { index, match ->
            val body = text.substring(match.range.last + 1, headings.getOrNull(index + 1)?.range?.first ?: text.length).trim()
            val options = option.findAll(body).toList()
            if (options.size !in 2..6 || options.map { it.groupValues[1] }.distinct().size != options.size) return emptyList()
            val prompt = body.substring(0, options.first().range.first).trim()
            if (prompt.isBlank()) return emptyList()
            val choices = options.mapIndexed { i, item ->
                ReadingChoice(item.groupValues[1], body.substring(item.range.last + 1,
                    options.getOrNull(i + 1)?.range?.first ?: body.length).trim())
            }
            if (choices.any { it.text.isBlank() }) return emptyList()
            ReadingQuestion(match.groupValues[1], prompt, choices)
        }
        return questions.takeIf { it.map(ReadingQuestion::number).distinct().size == it.size }.orEmpty()
    }

    fun canSelect(answer: String): Boolean = answer.lineSequence().filter(String::isNotBlank).all { answerLine.matches(it) } &&
        answer.lineSequence().mapNotNull { answerLine.matchEntire(it)?.groupValues?.get(1) }.toList().let { it.distinct().size == it.size }

    fun selected(answer: String, number: String): String? = answer.lineSequence()
        .mapNotNull { answerLine.matchEntire(it) }.firstOrNull { it.groupValues[1] == number }?.groupValues?.get(2)

    fun choose(answer: String, number: String, choice: String): String {
        require(canSelect(answer) && number.matches(Regex("\\d{1,3}")) && choice.matches(Regex("[A-F]")))
        val lines = answer.lineSequence().filter(String::isNotBlank)
            .filterNot { answerLine.matchEntire(it)?.groupValues?.get(1) == number }.toList()
        return (lines + "$number. $choice").sortedBy { answerLine.matchEntire(it)!!.groupValues[1].toInt() }.joinToString("\n")
    }
}

object ReadingEvidence {
    fun find(material: String, quote: String): IntRange? {
        val words = quote.trim().split(Regex("\\s+"))
        if (quote.isBlank() || quote.length > 2000) return null
        return Regex(words.joinToString("\\s+") { Regex.escape(it) }).find(material)?.range
    }

    /** Only offer quotes which actually occur in the material; no semantic/fuzzy matching. */
    fun suggestions(material: String, feedback: String): List<String> =
        Regex("[“\"「]([^”\"」]{12,600})[”\"」]").findAll(feedback).map { it.groupValues[1] }
            .filter { find(material, it) != null }.distinct().take(8).toList()

    fun saved(text: String) = text.lineSequence().filter(String::isNotBlank).toList()
    fun add(material: String, savedText: String, quote: String): String {
        val range = find(material, quote) ?: throw IllegalArgumentException("未在原文找到这段引用，不能收藏为原文难句")
        val sentence = material.substring(range).replace(Regex("\\s+"), " ").trim()
        return (saved(savedText) + sentence).distinct().joinToString("\n").also {
            require(it.length <= 20_000) { "本篇收藏已满，请拆分练习" }
        }
    }
}
