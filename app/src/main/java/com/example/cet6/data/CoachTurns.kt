package com.example.cet6.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One saved exchange attached to a practice attempt. Assistant turns are stored only after a
 * complete response; streamed fragments never land here. The practice row itself is not touched,
 * so a slow request can never overwrite the learner's concurrent edits.
 */
@Entity(tableName = "coach_turns", indices = [Index("practiceId")])
data class CoachTurn(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val practiceId: String,
    /** Where the exchange belongs: the whole draft, one question, one paragraph or one sentence. */
    val anchor: String,
    /** Protocol of [content], e.g. a line-delimited review or free Markdown. */
    val kind: String,
    val role: String,
    val content: String,
    /** Small mutable JSON owned by the UI, such as accepted or dismissed suggestions. */
    val state: String = "",
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val fromUser get() = role == USER

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

data class TurnCount(val practiceId: String, val count: Int)

object TurnKind {
    const val WRITING_REVIEW = "write-review/1"
    const val WRITING_PLAN = "write-plan/1"
    const val TRANSLATION_REVIEW = "trans-review/1"
    const val TRANSLATION_HINT = "trans-hint/1"
    const val READING_EXPLAIN = "read-explain/1"
    const val READING_PARAGRAPH = "read-para/1"
    const val READING_OVERVIEW = "read-overview/1"
    const val CHAT = "chat/1"
    val all = setOf(WRITING_REVIEW, WRITING_PLAN, TRANSLATION_REVIEW, TRANSLATION_HINT,
        READING_EXPLAIN, READING_PARAGRAPH, READING_OVERVIEW, CHAT)
}

object Anchor {
    const val REVIEW = "review"
    const val PLAN = "plan"
    fun question(number: Int) = "q:$number"
    /** A reading practice can hold two passages, so paragraphs carry their passage: 2/3 → para:203. */
    fun paragraph(passage: Int, index: Int) = "para:${passage * 100 + index}"
    fun overview(passage: Int) = "over:$passage"
    fun sentence(index: Int) = "sent:$index"
    /** (passage, paragraph) of a paragraph anchor. */
    fun paragraphOf(anchor: String): Pair<Int, Int>? =
        anchor.takeIf { it.startsWith("para:") }?.removePrefix("para:")?.toIntOrNull()?.let { it / 100 to it % 100 }
    private val pattern = Regex("review|plan|(?:q|para|sent|over):\\d{1,4}")
    fun valid(anchor: String) = pattern.matches(anchor)
}

class CoachReplyTooLongException : IllegalArgumentException("AI 回复超过本机单条保存上限")

object CoachTurns {
    const val MAX_CONTENT = 60_000
    const val MAX_STATE = 8_000

    fun checkReplyLength(content: String) {
        if (content.length > MAX_CONTENT) throw CoachReplyTooLongException()
    }

    fun validate(turn: CoachTurn) {
        require(turn.id.length in 1..100 && turn.practiceId.length in 1..100) { "对话标识无效" }
        require(Anchor.valid(turn.anchor) && turn.kind in TurnKind.all) { "对话类型无效" }
        require(turn.role == CoachTurn.USER || turn.role == CoachTurn.ASSISTANT) { "对话角色无效" }
        require(turn.content.isNotBlank() && turn.content.length <= MAX_CONTENT && turn.state.length <= MAX_STATE &&
            turn.model.length <= 200) { "对话内容为空或过长" }
    }

    /** Turns of one anchor, oldest first, as stored. */
    fun thread(turns: List<CoachTurn>, anchor: String) = turns.filter { it.anchor == anchor }

    /** The newest complete assistant result of a protocol, ignoring chat replies on the same anchor. */
    fun latest(turns: List<CoachTurn>, anchor: String, kind: String) =
        turns.lastOrNull { it.anchor == anchor && it.kind == kind && it.role == CoachTurn.ASSISTANT }

    /** Plain transcript for prompts, newest last, trimmed from the front to fit the budget. */
    fun transcript(turns: List<CoachTurn>, budget: Int = 12_000): String {
        val lines = turns.map { (if (it.fromUser) "学生：" else "老师：") + it.content.trim() }
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (used + line.length > budget) break
            kept.addFirst(line); used += line.length
        }
        return kept.joinToString("\n\n")
    }
}
