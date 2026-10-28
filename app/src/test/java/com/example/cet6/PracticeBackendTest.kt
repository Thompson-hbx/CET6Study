package com.example.cet6

import com.example.cet6.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PracticeBackendTest {
    private val goal = "衔接：说明原因与结果的联系"
    private fun source(kind: CoachKind) = CoachPractice(kind = kind.name,
        material = "用3–5句英文说明图书馆如何帮助学生。", questions = "", answer = "Libraries helps students.",
        errorReason = goal, submittedAt = 100, updatedAt = 200)
    private fun targeted(kind: CoachKind) = source(kind).copy(sourcePracticeId = "original-task", practiceGoal = goal)

    @Test fun generatedTaskCannotReuseItsSourceMaterial() {
        for (kind in CoachKind.entries) {
            val original = source(kind)
            val questions = if (kind == CoachKind.READING)
                "1. What is needed?\nA. Books.\nB. Rooms.\nC. Staff.\nD. Time." else ""
            val response = JSONObject().put("title", "重复材料").put("material", original.material)
                .put("questions", questions).toString()
            assertTrue("Repeated material accepted for $kind", runCatching { MicroPractice.parse(response, original) }.isFailure)
        }
    }

    @Test fun targetedWritingReviewKeepsGoalAndShortTaskLength() {
        val entry = targeted(CoachKind.WRITING)
        val review = WritingCoach.reviewPrompt(entry, WritingFocus.COMPLETE)
        assertTrue("Training goal missing from review", review.contains(goal))
        assertFalse("Short task expanded into a full essay", review.contains("150–200 词"))
        val plan = WritingCoach.planPrompt(entry)
        assertTrue("Training goal missing from plan", plan.contains(goal))
        assertFalse("Short task forced into three paragraphs", plan.contains("### 三段式提纲"))
    }

    @Test fun customWritingReviewUsesActualWordRange() {
        val entry = source(CoachKind.WRITING).copy(material = "Write at least 50 words but no more than 70 words.")
        val prompt = WritingCoach.reviewPrompt(entry, WritingFocus.COMPLETE)
        assertTrue(prompt.contains("50–70 词"))
        assertFalse("Rewrite overrides the task range", prompt.contains("150–200 词"))
    }

    @Test fun targetedTranslationHintsReviewsAndFollowUpsKeepGoal() {
        val entry = targeted(CoachKind.TRANSLATION).copy(material = "图书馆支持学习。学生可以在那里讨论。")
        assertTrue(TranslationCoach.reviewPrompt(entry, WritingFocus.COMPLETE).contains(goal))
        assertTrue(TranslationCoach.hintPrompt(entry, 2, SentenceHelp.STRUCTURE).contains(goal))
        assertTrue(CoachChat.prompt(entry, Anchor.sentence(2), emptyList(), "怎样连接这两句？").contains(goal))
    }

    @Test fun generatedWritingAndTranslationRemainIndependentUnsolvedDrafts() {
        for (kind in listOf(CoachKind.WRITING, CoachKind.TRANSLATION)) {
            val original = source(kind)
            val response = JSONObject().put("title", "原创微练习").put("material", "请说明校园步道的两个用途。")
                .put("questions", "").toString()
            val generated = MicroPractice.parse(response, original)
            assertEquals(original.id, generated.sourcePracticeId)
            assertEquals(goal, generated.practiceGoal)
            assertNotEquals(original.material, generated.material)
            assertTrue(generated.answer.isBlank() && generated.feedback.isBlank() && generated.errorReason.isBlank())
            assertEquals(0L, generated.submittedAt)
            assertEquals(original.id, original.retryCopy().sourcePracticeId)
        }
    }

    @Test fun ordinaryTranslationPromptHasNoInventedTrainingGoal() {
        val entry = source(CoachKind.TRANSLATION)
        assertFalse(TranslationCoach.reviewPrompt(entry, WritingFocus.COMPLETE).contains(goal))
        assertTrue(TranslationCoach.hintPrompt(entry, 1, SentenceHelp.TERMS).contains(entry.material))
    }
}
