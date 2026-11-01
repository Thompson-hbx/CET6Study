package com.example.cet6.ui

import com.example.cet6.data.*
import com.example.cet6.ui.components.Tone

data class TaskStatus(val label: String, val tone: Tone, val spoken: String? = null)

fun readingStatus(entry: CoachPractice): TaskStatus {
    val questions = ExamCodec.passages(entry).flatMap { it.questions }
    val answered = ExamCodec.readingAnswered(entry)
    if (entry.submittedAt == 0L) {
        return if (answered == 0) {
            TaskStatus("未做", Tone.Muted)
        } else {
            TaskStatus("已录 $answered/${questions.size}", Tone.Better, "已录入 $answered 题，共 ${questions.size} 题")
        }
    }
    val known = questions.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() }
    val right = known.count { ReadingQuestions.selected(entry.answer, "${it.number}") == it.referenceAnswer }
    return if (known.isEmpty()) {
        TaskStatus("已核对", Tone.Ink)
    } else {
        TaskStatus("$right/${known.size}", Tone.Ink, "已核对，${known.size} 题中 $right 题与参考一致")
    }
}
