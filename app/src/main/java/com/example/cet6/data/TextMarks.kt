package com.example.cet6.data

/** Ties generated comments back to the learner's exact characters; nothing here guesses meaning. */
object TextMarks {
    private fun fold(c: Char): Char = when (c) {
        '’', '‘', '′', '`' -> '\''
        '“', '”', '″' -> '"'
        '–', '—' -> '-'
        else -> c.lowercaseChar()
    }

    /** Text with whitespace runs collapsed and quotes/case folded, plus each char's original offset. */
    private fun normalize(text: String): Pair<String, IntArray> {
        val out = StringBuilder(text.length)
        val map = IntArray(text.length)
        var n = 0
        var space = true
        for (i in text.indices) {
            val c = text[i]
            if (c.isWhitespace()) {
                if (!space) { out.append(' '); map[n++] = i; space = true }
            } else {
                out.append(fold(c)); map[n++] = i; space = false
            }
        }
        return out.toString() to map.copyOf(n)
    }

    /** Ranges where [quote] occurs: exact matches if any, otherwise whitespace, quote and case tolerant ones. */
    fun occurrences(text: String, quote: String): List<IntRange> {
        val q = quote.trim()
        if (q.isEmpty() || q.length > 2000) return emptyList()
        val exact = mutableListOf<IntRange>()
        var at = text.indexOf(q)
        while (at >= 0) { exact += at until at + q.length; at = text.indexOf(q, at + 1) }
        if (exact.isNotEmpty()) return exact
        val (folded, map) = normalize(text)
        val needle = normalize(q).first.trim()
        if (needle.isEmpty()) return emptyList()
        val loose = mutableListOf<IntRange>()
        at = folded.indexOf(needle)
        while (at >= 0) { loose += map[at]..map[at + needle.length - 1]; at = folded.indexOf(needle, at + 1) }
        return loose
    }

    fun locate(text: String, quote: String) = occurrences(text, quote).firstOrNull()

    /** Gives each quote its first occurrence that does not overlap one already placed; null if absent. */
    fun place(text: String, quotes: List<String>): List<IntRange?> {
        val taken = mutableListOf<IntRange>()
        return quotes.map { quote ->
            occurrences(text, quote).firstOrNull { r -> taken.none { it.first <= r.last && r.first <= it.last } }
                ?.also { taken += it }
        }
    }

    /** Applies replacements right to left; an edit overlapping one already applied is skipped. */
    fun apply(text: String, edits: List<Pair<IntRange, String>>): String {
        val builder = StringBuilder(text)
        var boundary = Int.MAX_VALUE
        for ((range, replacement) in edits.sortedByDescending { it.first.first }) {
            if (range.first < 0 || range.last >= text.length || range.last >= boundary) continue
            builder.replace(range.first, range.last + 1, replacement)
            boundary = range.first
        }
        return builder.toString()
    }
}

enum class DiffKind { SAME, ADDED, REMOVED }
data class DiffPiece(val text: String, val kind: DiffKind)

/** Word-level comparison for revisions; whitespace is kept so the result reads as running text. */
object WordDiff {
    private val token = Regex("\\s+|[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*|[^\\sA-Za-z0-9]")

    fun diff(before: String, after: String): List<DiffPiece> {
        val a = token.findAll(before).map { it.value }.toList()
        val b = token.findAll(after).map { it.value }.toList()
        if (a.size.toLong() * b.size > 4_000_000L) return listOf(DiffPiece(before, DiffKind.REMOVED), DiffPiece(after, DiffKind.ADDED))
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val pieces = mutableListOf<DiffPiece>()
        fun push(text: String, kind: DiffKind) {
            val last = pieces.lastOrNull()
            if (last != null && last.kind == kind) pieces[pieces.lastIndex] = last.copy(text = last.text + text)
            else pieces += DiffPiece(text, kind)
        }
        var i = 0; var j = 0
        while (i < a.size && j < b.size) when {
            a[i] == b[j] -> { push(a[i], DiffKind.SAME); i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { push(a[i], DiffKind.REMOVED); i++ }
            else -> { push(b[j], DiffKind.ADDED); j++ }
        }
        while (i < a.size) push(a[i++], DiffKind.REMOVED)
        while (j < b.size) push(b[j++], DiffKind.ADDED)
        return pieces
    }

    fun changed(pieces: List<DiffPiece>) = pieces.count { it.kind != DiffKind.SAME && it.text.isNotBlank() }
}
