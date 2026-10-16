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

/** Framework runner: no new test dependency; never opens the user's actual database or credentials. */
class LocalDataInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            runBlocking { verifyMigrationAndTransactions() }
            results.putString("stream", "PASS: v1→v3 preserves words/reading/essays; daily plans persist and roll over; duplicate review writes once; history survives reopen; CSV/JSON import preserves existing progress and sources.\n")
            finish(-1, results)
        } catch (e: Throwable) {
            results.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
            finish(0, results)
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
            .addMigrations(StudyDatabase.MIGRATION_1_2, StudyDatabase.MIGRATION_2_3).build()
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
                listOf(async { repository.review("new", true, now) },
                    async { repository.review("new", false, now) }).awaitAll()
            }
            check(writes.count { it } == 1) { "double tap created multiple reviews" }
            check(db.dao().wordReviewHistory().first().single().wasNew)
            check(db.dao().dailyWordProgress().first().single().newCount == 1)
            check(!repository.review("missing", true, now))
            check(repository.review("legacy", false, now))
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
            check(db.dao().dailyPlan(date.toString())?.newGoal == 3)
            check(db.dao().word("imported")!!.source == "测试原创授权")
        } finally { db.close() }
    }
}
