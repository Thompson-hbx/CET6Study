package com.example.cet6

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import com.example.cet6.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

/** Default suite uses isolated databases. Explicit fixture mode only adds a new practice; never reads credentials. */
class LocalDataInstrumentation : Instrumentation() {
    private var uiScenario = false
    private var translationFixture = false
    private var longFeedbackFixture = false
    private var examFixture = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        uiScenario = arguments?.getString("scenario") == "ui"
        translationFixture = arguments?.getString("fixture") == "translation"
        longFeedbackFixture = arguments?.getString("fixture") == "long-feedback"
        examFixture = arguments?.getString("fixture") == "g4-sample"
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            if (examFixture) {
                check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) { "Exam sample fixture requires emulator" }
                val input = File(targetContext.getExternalFilesDir(null), "exam-sample.json")
                val papers = ExamCodec.decode(input.readText())
                check(papers.all { it.sample && it.licenseStatus == "original" })
                val store = ExamStore(targetContext)
                store.save(store.merge(store.load(), papers))
                results.putString("stream", "SEEDED: labelled original workflow sample, not genuine exam content.\n")
                finish(-1, results)
                return
            }
            if (longFeedbackFixture) {
                check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) {
                    "Long layout fixture is restricted to an emulator"
                }
                val feedback = "# 模拟长反馈布局检查\n\n这是原创布局测试文本，不是 AI 请求结果或官方解析。\n\n" +
                    (1..100).joinToString("\n\n") { index ->
                        "## 第 $index 段：证据核对\n\n> Libraries provide both books and quiet rooms.\n\n" +
                            "- **原文证据**：both 表明两者都包含。\n- **练习提示**：请核对选项是否缩小范围。\n\n" +
                            "| 原表达 | 建议 |\n| --- | --- |\n| Libraries provides books. | Libraries provide books. |"
                    } + "\n\n## 布局测试结束标记\n\nLONG-FEEDBACK-END-20261018"
                val entry = CoachPractice(title = "模拟器长反馈布局检查 20261018", material = "Libraries provide both books and quiet rooms.",
                    questions = "1. What do libraries provide?", answer = "Books and quiet rooms.", feedback = feedback,
                    model = "layout-fixture (非 AI)", submittedAt = System.currentTimeMillis())
                runBlocking { CoachRepository(StudyDatabase.get(targetContext)).save(entry) }
                results.putString("stream", "SEEDED emulator-only labelled layout fixture: ${feedback.length} characters; ${entry.id}\n")
                finish(-1, results)
                return
            }
            if (translationFixture) {
                val entry = CoachPractice(kind = CoachKind.TRANSLATION.name,
                    title = "Device translation check 20261018-${System.currentTimeMillis()}",
                    material = "大学图书馆不仅提供书籍，也为学生创造安静的学习空间。随着数字资源日益丰富，图书馆仍然发挥着重要作用。")
                runBlocking { CoachRepository(StudyDatabase.get(targetContext)).save(entry) }
                results.putString("stream", "SEEDED original translation material only: ${entry.title}\n")
                finish(-1, results)
                return
            }
            if (uiScenario) {
                CoachUiInstrumentationChecks(this).verify()
                results.putString("stream", "PASS: emulator UI article entry, add question, option selection, offline submission, evidence lookup/save, error reason and background persistence.\n")
                finish(-1, results)
                return
            }
            runBlocking { ExamInstrumentationChecks(targetContext).verify(); verifyMigrationAndTransactions(); verifyReleasedSchemas(); BackupInstrumentationChecks(targetContext).verify(); CoachInstrumentationChecks(targetContext).verify(); MicroPracticeInstrumentationChecks(targetContext).verify(); ShareInstrumentationChecks(targetContext).verify(); CoachFailureInstrumentationChecks(targetContext).verify() }
            results.putString("stream", "PASS: v1-v6 to v7 preserves learning data; drafts/history/evidence survive reopen; generation failure and offline mode preserve source; generated drafts retain provenance and independent answers; shared text preserves drafts and running AI; timeout/429/refresh/stream failures preserve saved feedback and manual retry succeeds; backup v4 roundtrip and v1-v3 imports; existing backup and scheduler checks.\n")
            finish(-1, results)
        } catch (e: Throwable) {
            if (uiScenario) {
                val screenshot = uiAutomation.takeScreenshot()
                if (screenshot != null) java.io.File(targetContext.getExternalFilesDir(null), "g3-ui-failure.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            results.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            finish(0, results)
        }
    }

    private suspend fun verifyReleasedSchemas() {
        for (version in 1..6) {
            val file = File(targetContext.cacheDir, "released-$version-${System.nanoTime()}.db")
            val schema = context.assets.open("com.example.cet6.data.StudyDatabase/$version.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val table = entities.getJSONObject(i)
                    old.execSQL(table.getString("createSql").replace("\u0024{TABLE_NAME}", table.getString("tableName")))
                }
                val sourceColumn = if (version >= 3) ",source" else ""
                val sourceValue = if (version >= 3) ",'用户来源'" else ""
                old.execSQL("INSERT INTO words (word,meaning,example,dueAt,repetitions,intervalDays$sourceColumn) VALUES ('preserved','原释义','Original sentence.',1800000000000,5,30$sourceValue)")
                old.execSQL("INSERT INTO reading_attempts VALUES (10,'q1','C',0,1000)")
                old.execSQL("INSERT INTO essays VALUES (11,'topic','original draft','original feedback',1001)")
                if (version >= 5) old.execSQL("INSERT INTO coach_practices (id,kind,title,material,questions,answer,feedback,model,errorReason,submittedAt,updatedAt) VALUES ('preserved-coach','READING','title','Original material.','question','A','Original feedback.','test-model','逻辑推断',100,200)")
                if (version >= 6) old.execSQL("UPDATE coach_practices SET savedSentences='Original material.'")
                if (version >= 2) {
                    old.execSQL("INSERT INTO daily_plans VALUES ('2026-10-16',7,15)")
                    old.execSQL("INSERT INTO word_reviews (id,word,remembered,wasNew,studiedOn,createdAt,nextDueAt) VALUES ('old-log','preserved',1,0,'2026-10-16',1000,1800000000000)")
                }
                old.version = version
            }
            val upgraded = Room.databaseBuilder(targetContext, StudyDatabase::class.java, file.absolutePath)
                .addMigrations(StudyDatabase.MIGRATION_1_2, StudyDatabase.MIGRATION_2_3, StudyDatabase.MIGRATION_3_4, StudyDatabase.MIGRATION_4_5, StudyDatabase.MIGRATION_5_6, StudyDatabase.MIGRATION_6_7).build()
            try {
                val word = upgraded.dao().word("preserved")!!
                check(word.dueAt == 1_800_000_000_000L && word.repetitions == 5 && word.intervalDays == 30)
                check(word.source == if (version >= 3) "用户来源" else "项目原创示例")
                check(upgraded.dao().schedule(word.word) == null) { "migration fabricated FSRS memory" }
                check(upgraded.dao().readingHistory().first().single().answer == "C")
                check(upgraded.dao().essays().first().single().content == "original draft")
                if (version >= 5) {
                    val preserved = upgraded.dao().allCoach().single()
                    check(preserved.answer == "A" && preserved.feedback == "Original feedback.")
                    check(preserved.savedSentences == if (version >= 6) "Original material." else "")
                    check(preserved.sourcePracticeId.isBlank() && preserved.practiceGoal.isBlank())
                }
                if (version >= 2) {
                    val log = upgraded.dao().wordReviewHistory().first().single()
                    check(log.id == "old-log" && log.rating == 0 && log.algorithm == "legacy")
                    check(upgraded.dao().dailyPlan("2026-10-16")?.newGoal == 7)
                }
            } finally { upgraded.close() }
        }
    }

    private suspend fun verifyMigrationAndTransactions() {
        val file = File(targetContext.cacheDir, "migration-${System.nanoTime()}.db")
        val legacyWord = WordEntity("legacy", "保留释义", "Original example.", 123456789L, 4, 14)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE words (word TEXT NOT NULL PRIMARY KEY, meaning TEXT NOT NULL, example TEXT NOT NULL, dueAt INTEGER NOT NULL, repetitions INTEGER NOT NULL, intervalDays INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE reading_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, questionId TEXT NOT NULL, answer TEXT NOT NULL, correct INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE essays (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, topic TEXT NOT NULL, content TEXT NOT NULL, feedback TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("INSERT INTO words VALUES ('legacy','保留释义','Original example.',123456789,4,14)")
            old.execSQL("INSERT INTO reading_attempts VALUES (7,'reading_demo_1','B',1,123)")
            old.execSQL("INSERT INTO essays VALUES (8,'Topic','Draft','Feedback',456)")
            old.version = 1
        }
        fun open() = Room.databaseBuilder(targetContext, StudyDatabase::class.java, file.absolutePath)
            .addMigrations(StudyDatabase.MIGRATION_1_2, StudyDatabase.MIGRATION_2_3, StudyDatabase.MIGRATION_3_4, StudyDatabase.MIGRATION_4_5, StudyDatabase.MIGRATION_5_6, StudyDatabase.MIGRATION_6_7).build()
        var db = open()
        try {
            check(db.dao().word("legacy") == legacyWord) { "legacy word changed" }
            check(db.dao().readingHistory().first().single().id == 7L)
            check(db.dao().essays().first().single().feedback == "Feedback")
            var repository = StudyRepository(db)
            val date = LocalDate.of(2026, 10, 16)
            check(repository.plan(date) == DailyPlan(date.toString()))
            repository.savePlan(DailyPlan(date.toString(), 3, 5))
            check(repository.plan(date.plusDays(1)) == DailyPlan(date.plusDays(1).toString(), 3, 5))
            check(runCatching { repository.savePlan(DailyPlan(date.toString(), 0, 5)) }.isFailure)
            db.dao().addWords(listOf(WordEntity("new", "新词", "New word.")))
            val now = 1_800_000_000_000L
            val writes = coroutineScope {
                listOf(async { repository.review("new", WordRating.GOOD, now) },
                    async { repository.review("new", WordRating.AGAIN, now) }).awaitAll()
            }
            check(writes.count { it } == 1) { "double tap created multiple reviews" }
            check(db.dao().wordReviewHistory().first().single().wasNew)
            check(db.dao().dailyWordProgress().first().single().newCount == 1)
            check(!repository.review("missing", WordRating.GOOD, now))
            check(repository.review("legacy", WordRating.AGAIN, now))
            check(db.dao().word("legacy")?.repetitions == 0)
            check(db.dao().dailyWordProgress().first().single().reviewCount == 1)
            val scheduled = db.dao().word("new")!!
            val imported = VocabularyImport.parse("""[{"word":"new","meaning":"不要覆盖","example":"changed"},{"word":"imported","meaning":"导入词","example":"Original example."}]""", "测试原创授权")
            check(repository.importWords(imported) == 1)
            check(db.dao().word("new") == scheduled)
            check(db.dao().word("imported")!!.source == "测试原创授权")
            check(repository.importWords(imported) == 0)
            val bundled = targetContext.assets.open("academic_words.csv").use {
                VocabularyImport.parse(VocabularyImport.read(it), "项目原创测试")
            }
            check(bundled.size == 72)
            check(repository.importWords(bundled) == 72)
            check(repository.importWords(bundled) == 0)
            db.close()
            db = open()
            repository = StudyRepository(db)
            check(db.dao().wordReviewHistory().first().size == 2)
            check(db.dao().word("new")!!.dueAt > now)
            check(db.dao().schedule("new")!!.algorithm == WordScheduler.ALGORITHM)
            val due = db.dao().word("new")!!.dueAt
            val before = db.dao().schedule("new")!!
            check(repository.review("new", WordRating.GOOD, due))
            check(db.dao().schedule("new")!!.lastReviewedAt == due)
            check(db.dao().schedule("new")!!.stability >= before.stability)
            check(db.dao().dailyWordProgress().first().single().newCount == 1)
            check(db.dao().dailyWordProgress().first().single().reviewCount == 1)
            check(db.dao().dailyPlan(date.toString())?.newGoal == 3)
            check(db.dao().word("imported")!!.source == "测试原创授权")
        } finally { db.close() }
    }
}
