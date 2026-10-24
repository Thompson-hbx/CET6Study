package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

class BackupInstrumentationChecks(private val context: Context) {
    private fun file(name: String) = File(context.cacheDir, "backup-$name-${System.nanoTime()}.db")
    private fun open(file: File) = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
    suspend fun verify() {
        val source = open(file("source"))
        val targetFile = file("target")
        var target = open(targetFile)
        val aborting = open(file("rollback"))
        try {
            val at = 1_800_000_000_000L
            val logs = (0 until 125).map { i -> WordReview("old-$i", "source", true, false, "2027-01-15",
                at + i * 1000, at + i * 1000 + WordScheduler.DAY) }
            source.dao().addWords(listOf(WordEntity("source", "原始词", "Original example.", logs.last().nextDueAt, 5, 30),
                WordEntity("fsrs", "算法", "Original example.")))
            source.dao().addWordReviews(logs)
            check(StudyRepository(source).review("fsrs", WordRating.GOOD, at))
            source.dao().saveDailyPlan(DailyPlan("2026-10-16", 3, 5))
            source.dao().addReadingAttempts((0 until 40).map { ReadingAttempt(questionId = "question-$it", answer = "B", correct = true, createdAt = at + it) })
            source.dao().addEssays((0 until 25).map { EssayEntity(topic = if (it == 0) "" else "topic-$it", content = "draft-$it\n中文与引号\"",
                feedback = "feedback-$it", createdAt = at + it) })
            check(source.dao().wordReviewHistory().first().size == 100)
            check(source.dao().readingHistory().first().size == 30 && source.dao().essays().first().size == 20)
            val snapshot = BackupRepository(source).snapshot()
            val encoded = BackupCodec.encode(snapshot)
            val restored = BackupCodec.read(ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)))
            check(snapshot == restored) { "codec roundtrip lost learning fields" }
            check(restored.reviews.size == 126 && restored.reading.size == 40 && restored.essays.size == 25)
            val root = JSONObject(encoded)
            check(root.keys().asSequence().toSet() == setOf("format", "version", "databaseVersion", "createdAt", "words", "reviews", "schedules", "plans", "reading", "essays", "coach", "turns"))
            val invalid = listOf(encoded.dropLast(1), JSONObject(encoded).put("version", 99).toString(),
                JSONObject(encoded).put("accessToken", "not-a-credential").toString(),
                JSONObject(encoded).apply { getJSONArray("words").getJSONObject(0).put("repetitions", "5") }.toString(),
                JSONObject(encoded).apply { getJSONArray("reviews").put(getJSONArray("reviews").getJSONObject(0)) }.toString(),
                JSONObject(encoded).apply { getJSONArray("words").getJSONObject(0).put("dueAt", 123) }.toString())
            invalid.forEach { check(runCatching { BackupCodec.decode(it) }.isFailure) { "invalid backup accepted" } }
            check(runCatching { BackupCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }.isFailure)
            target.dao().addWords(listOf(WordEntity("untouched", "保留本机", "Local example."), WordEntity("fsrs", "本机释义", "Local sentence.")))
            target.dao().saveDailyPlan(DailyPlan("2026-10-16", 1, 2))
            target.dao().addReadingAttempt(ReadingAttempt(questionId = "local", answer = "A", correct = false, createdAt = at))
            target.dao().addEssay(EssayEntity(topic = "local", content = "local draft", feedback = "local feedback", createdAt = at))
            val repository = BackupRepository(target)
            val summary = repository.restore(restored, false)
            check(summary.newWords == 1 && summary.updatedProgress == 1 && summary.newReviews == 126)
            check(target.dao().allWords().size == 3 && target.dao().word("fsrs")!!.meaning == "本机释义")
            check(target.dao().allReading().size == 41 && target.dao().allEssays().size == 26)
            check(target.dao().dailyPlan("2026-10-16")?.newGoal == 1)
            val again = repository.restore(restored, false)
            check(again.newReviews == 0 && again.newReading == 0 && again.newEssays == 0 && again.updatedProgress == 0)
            check(repository.restore(restored, true).writtenPlans == 1)
            val beforeConflict = repository.snapshot().copy(createdAt = 0)
            val conflict = restored.copy(reviews = restored.reviews.mapIndexed { i, review -> if (i == 0) review.copy(wasNew = !review.wasNew) else review })
            check(runCatching { repository.restore(conflict, true) }.isFailure)
            check(repository.snapshot().copy(createdAt = 0) == beforeConflict)
            val originalState = target.dao().schedule("fsrs")!!
            target.close()
            target = open(targetFile)
            check(target.dao().schedule("fsrs") == originalState)
            val due = target.dao().word("fsrs")!!.dueAt
            check(StudyRepository(target).review("fsrs", WordRating.AGAIN, due))
            val latest = target.dao().schedule("fsrs")!!
            BackupRepository(target).restore(restored, false)
            check(target.dao().schedule("fsrs") == latest) { "restore rewound newer learning" }
            BackupCodec.encode(BackupRepository(target).snapshot()) // merged result remains exportable
            aborting.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_essay BEFORE INSERT ON essays BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
            check(runCatching { BackupRepository(aborting).restore(restored, true) }.isFailure)
            val afterAbort = BackupRepository(aborting).snapshot()
            check(afterAbort.words.isEmpty() && afterAbort.schedules.isEmpty() && afterAbort.reviews.isEmpty() &&
                afterAbort.plans.isEmpty() && afterAbort.reading.isEmpty() && afterAbort.essays.isEmpty()) { "partial restore survived rollback" }
        } finally { source.close(); target.close(); aborting.close() }
    }
}
