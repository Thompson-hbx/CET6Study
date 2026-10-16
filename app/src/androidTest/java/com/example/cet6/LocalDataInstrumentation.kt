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

/** Framework runner: no new test dependency; never opens the user's actual database or credentials. */
class LocalDataInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            runBlocking { verifyMigrationAndTransactions(); verifyReleasedSchemas() }
            results.putString("stream", "PASS: v1→v4 preserves words/reading/essays; daily plans persist and roll over; duplicate review writes once; FSRS state/history survives reopen; CSV/JSON import preserves existing progress and sources.\n")
            finish(-1, results)
        } catch (e: Throwable) {
            results.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            finish(0, results)
        }
    }

    private suspend fun verifyReleasedSchemas() {
        for (version in 1..3) {
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
                if (version >= 2) {
                    old.execSQL("INSERT INTO daily_plans VALUES ('2026-10-16',7,15)")
                    old.execSQL("INSERT INTO word_reviews VALUES ('old-log','preserved',1,0,'2026-10-16',1000,1800000000000)")
                }
                old.version = version
            }
            val upgraded = Room.databaseBuilder(targetContext, StudyDatabase::class.java, file.absolutePath)
                .addMigrations(StudyDatabase.MIGRATION_1_2, StudyDatabase.MIGRATION_2_3, StudyDatabase.MIGRATION_3_4).build()
            try {
                val word = upgraded.dao().word("preserved")!!
                check(word.dueAt == 1_800_000_000_000L && word.repetitions == 5 && word.intervalDays == 30)
                check(word.source == if (version == 3) "用户来源" else "项目原创示例")
                check(upgraded.dao().schedule(word.word) == null) { "migration fabricated FSRS memory" }
                check(upgraded.dao().readingHistory().first().single().answer == "C")
                check(upgraded.dao().essays().first().single().content == "original draft")
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
            .addMigrations(StudyDatabase.MIGRATION_1_2, StudyDatabase.MIGRATION_2_3, StudyDatabase.MIGRATION_3_4).build()
        var db = open()
        try {
            check(db.dao().word("legacy") == legacyWord) { "legacy word changed" }
            check(db.dao().readingHistory().first().single().id == 7L)
            check(db.dao().essays().first().single().feedback == "Feedback")
            val repository = StudyRepository(db)
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
