package com.example.cet6

import android.content.Context
import com.example.cet6.data.*
import org.json.JSONObject
import java.io.File

/** Adds a fresh original attempt so manual accessibility checks cannot edit an existing draft. */
class ReadingAccessibilityFixture(private val context: Context) {
    private val marker = File(context.getExternalFilesDir(null), "reading-accessibility-fixture.json")

    suspend fun seed() {
        val input = File(context.getExternalFilesDir(null), "exam-sample.json")
        val paper = ExamCodec.decode(input.readText()).single()
        check(paper.sample && paper.licenseStatus == "original")
        val entry = ExamCodec.practice(paper, CoachKind.READING).copy(title = "原创答题卡无障碍检查")
        CoachRepository(StudyDatabase.get(context)).save(entry)
        marker.writeText(JSONObject().put("id", entry.id).toString())
    }

    suspend fun verify() {
        val id = JSONObject(marker.readText()).getString("id")
        val entry = requireNotNull(StudyDatabase.get(context).dao().coachById(id))
        check(entry.title == "原创答题卡无障碍检查" && entry.submittedAt > 0 && entry.feedback.isBlank())
        val passages = ExamCodec.passages(entry).sortedBy { it.code }
        check(passages.size == 2)
        check(SequentialAnswers.sequence(entry.answer, passages[0].questions) == "CADCB")
        check(SequentialAnswers.sequence(entry.answer, passages[1].questions) == "ADCBA")
    }
}
