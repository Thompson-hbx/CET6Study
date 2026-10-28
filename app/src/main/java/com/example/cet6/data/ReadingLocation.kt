package com.example.cet6.data

/**
 * The AI's located quote, recomputed from the saved explanation each time, so older explanations work too and
 * nothing extra is stored. Only an exact quote in the question's snapshot counts, never a fuzzy match or an AI
 * paragraph number.
 */
object ReadingLocation {
    private fun passage(entry: CoachPractice, turn: CoachTurn): ExamPassage? {
        if (entry.id != turn.practiceId || entry.kind != CoachKind.READING.name ||
            turn.role != CoachTurn.ASSISTANT || turn.kind != TurnKind.READING_EXPLAIN) return null
        val number = turn.anchor.removePrefix("q:").toIntOrNull()
            ?.takeIf { turn.anchor == Anchor.question(it) } ?: return null
        return ExamCodec.passages(entry).singleOrNull { p -> p.questions.any { it.number == number } }
    }

    fun extract(entry: CoachPractice, turn: CoachTurn): String {
        val source = passage(entry, turn) ?: return ""
        val explanation = ReadingExplanation.parse(turn.content)
        return explanation.takeIf { it.structured }?.locate?.quote
            ?.takeIf { it.isNotBlank() && source.content.contains(it) }.orEmpty()
    }
}
