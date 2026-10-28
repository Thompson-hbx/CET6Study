package com.example.cet6.data

import java.util.Locale

object PracticeVocabulary {
    fun word(entry: CoachPractice, word: String, meaning: String, context: String): WordEntity {
        val key = word.trim().lowercase(Locale.ROOT)
        val quote = context.trim()
        require(key.length in 1..100 && key.matches(Regex("[a-z]+(?:[ '-][a-z]+)*"))) { "请填写英文单词或短语" }
        require(meaning.trim().length in 1..1000 && quote.length in 1..2000) { "请填写释义和不超过 2000 字的来源句" }
        require(TextMarks.locate(entry.material, quote) != null || TextMarks.locate(entry.answer, quote) != null) { "来源句必须能在当前材料或作答中找到" }
        require(Regex("(?i)(?<![a-z])${Regex.escape(key)}(?![a-z])").containsMatchIn(quote)) { "来源句中未找到这个单词或短语" }
        require(entry.id.length in 1..100) { "练习标识无效" }
        return WordEntity(key, meaning.trim(), quote, source = "练习 ${entry.id}；用户确认释义与来源句")
    }

    /** Extracts source sentences from [entry.material] or [entry.answer] that contain [word] and pass provenance checks. */
    fun candidates(entry: CoachPractice, word: String, limit: Int = 4): List<String> {
        val key = word.trim().lowercase(Locale.ROOT)
        if (key.length !in 1..100 || !key.matches(Regex("[a-z]+(?:[ '-][a-z]+)*"))) return emptyList()
        val pattern = Regex("(?i)(?<![a-z])${Regex.escape(key)}(?![a-z])")
        return sequenceOf(entry.material, entry.answer)
            .flatMap { block -> block.lineSequence() }
            .flatMap { line -> line.split(Regex("(?<=[.!?])\\s+")).asSequence() }
            .map { it.trim() }
            .filter { it.length in 1..2000 && pattern.containsMatchIn(it) }
            .filter { TextMarks.locate(entry.material, it) != null || TextMarks.locate(entry.answer, it) != null }
            .distinct()
            .take(limit)
            .toList()
    }
}
