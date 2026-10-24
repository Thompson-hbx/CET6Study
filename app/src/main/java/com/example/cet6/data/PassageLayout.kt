package com.example.cet6.data

/**
 * Paragraphs of an imported passage. Text extracted from PDFs keeps the printed line breaks, so
 * inside a block a newline after a sentence end starts a new paragraph and any other newline is
 * re-flowed into a space. Blank lines always separate paragraphs. The stored content is unchanged.
 */
object PassageLayout {
    private val sentenceEnd = Regex("[.!?。！？][\"”’)）]?\\s*$")
    private val blankLine = Regex("\\n\\s*\\n")
    private val spaces = Regex("[ \\t\\u00A0]{2,}")

    fun paragraphs(content: String): List<String> {
        val blocks = content.replace("\r\n", "\n").replace('\r', '\n').trim().split(blankLine)
        return blocks.flatMap { block ->
            val lines = block.split('\n').filter(String::isNotBlank)
            val paragraphs = mutableListOf<String>()
            val current = StringBuilder()
            lines.forEach { line ->
                if (current.isNotEmpty()) current.append(' ')
                current.append(line.trim())
                if (sentenceEnd.containsMatchIn(line)) { paragraphs += current.toString(); current.clear() }
            }
            if (current.isNotBlank()) paragraphs += current.toString()
            paragraphs
        }.map { it.replace(spaces, " ").trim() }.filter(String::isNotBlank)
    }

    /** 1-based paragraph containing [quote], or null when it is not literally in the passage. */
    fun paragraphOf(content: String, quote: String): Int? {
        if (quote.isBlank()) return null
        val all = paragraphs(content)
        val whole = all.indexOfFirst { TextMarks.locate(it, quote) != null }
        if (whole >= 0) return whole + 1
        // A quote may straddle a re-flowed break; fall back to where its opening words are.
        val opening = quote.trim().split(Regex("\\s+")).take(6).joinToString(" ")
        if (opening.length < 12) return null
        return all.indexOfFirst { TextMarks.locate(it, opening) != null }.takeIf { it >= 0 }?.plus(1)
    }

    fun numbered(content: String) = paragraphs(content).mapIndexed { i, text -> "[${i + 1}] $text" }.joinToString("\n\n")
}
