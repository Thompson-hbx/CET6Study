package com.example.cet6.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "words")
data class WordEntity(
    @PrimaryKey val word: String,
    val meaning: String,
    val example: String,
    val dueAt: Long = 0L,
    val repetitions: Int = 0,
    val intervalDays: Int = 0
)

@Entity(tableName = "reading_attempts")
data class ReadingAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: String,
    val answer: String,
    val correct: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "essays")
data class EssayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val content: String,
    val feedback: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "word_reviews")
data class WordReview(
    @PrimaryKey val id: String,
    val word: String,
    val remembered: Boolean,
    val wasNew: Boolean,
    val studiedOn: String,
    val createdAt: Long,
    val nextDueAt: Long
)

@Entity(tableName = "daily_plans")
data class DailyPlan(
    @PrimaryKey val date: String,
    val newGoal: Int = 10,
    val reviewGoal: Int = 20
)

data class DailyWordProgress(
    val date: String,
    val newCount: Int,
    val reviewCount: Int,
    val rememberedCount: Int,
    val totalCount: Int
)

@Dao
interface StudyDao {
    @Query("SELECT * FROM words ORDER BY word") fun words(): Flow<List<WordEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addWords(words: List<WordEntity>)
    @Query("UPDATE words SET dueAt=:due, repetitions=:reps, intervalDays=:days WHERE word=:word")
    suspend fun recordReview(word: String, due: Long, reps: Int, days: Int)
    @Query("SELECT * FROM words WHERE word=:word") suspend fun word(word: String): WordEntity?
    @Insert suspend fun addWordReview(review: WordReview)
    @Query("SELECT * FROM word_reviews ORDER BY createdAt DESC LIMIT 100")
    fun wordReviewHistory(): Flow<List<WordReview>>
    @Query("SELECT studiedOn AS date, SUM(CASE WHEN wasNew=1 THEN 1 ELSE 0 END) AS newCount, " +
        "SUM(CASE WHEN wasNew=0 THEN 1 ELSE 0 END) AS reviewCount, " +
        "SUM(CASE WHEN remembered=1 THEN 1 ELSE 0 END) AS rememberedCount, COUNT(*) AS totalCount " +
        "FROM word_reviews GROUP BY studiedOn ORDER BY studiedOn DESC")
    fun dailyWordProgress(): Flow<List<DailyWordProgress>>
    @Query("SELECT * FROM daily_plans WHERE date=:date") suspend fun dailyPlan(date: String): DailyPlan?
    @Query("SELECT * FROM daily_plans WHERE date<:date ORDER BY date DESC LIMIT 1")
    suspend fun previousPlan(date: String): DailyPlan?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDailyPlan(plan: DailyPlan)

    @Insert suspend fun addReadingAttempt(attempt: ReadingAttempt)
    @Query("SELECT * FROM reading_attempts ORDER BY createdAt DESC LIMIT 30")
    fun readingHistory(): Flow<List<ReadingAttempt>>

    @Insert suspend fun addEssay(essay: EssayEntity)
    @Query("SELECT * FROM essays ORDER BY createdAt DESC LIMIT 20")
    fun essays(): Flow<List<EssayEntity>>
}

@Database(entities = [WordEntity::class, ReadingAttempt::class, EssayEntity::class,
    WordReview::class, DailyPlan::class], version = 2)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun dao(): StudyDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS word_reviews (id TEXT NOT NULL, word TEXT NOT NULL, remembered INTEGER NOT NULL, wasNew INTEGER NOT NULL, studiedOn TEXT NOT NULL, createdAt INTEGER NOT NULL, nextDueAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS daily_plans (date TEXT NOT NULL, newGoal INTEGER NOT NULL, reviewGoal INTEGER NOT NULL, PRIMARY KEY(date))")
            }
        }
        @Volatile private var INSTANCE: StudyDatabase? = null
        fun get(context: Context): StudyDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, StudyDatabase::class.java,
                "cet6_study.db").addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}

/** Original demonstration words (not a copyrighted or licensed exam question bank). */
val starterWords = listOf(
    WordEntity("allocate", "分配；拨给", "The university allocated additional funds to research."),
    WordEntity("ambiguous", "模棱两可的", "The findings remain ambiguous without further evidence."),
    WordEntity("compelling", "有说服力的；引人入胜的", "The researcher presented a compelling argument."),
    WordEntity("diminish", "减少；削弱", "Regular practice can diminish test anxiety."),
    WordEntity("inevitable", "不可避免的", "Some degree of uncertainty is inevitable."),
    WordEntity("preliminary", "初步的；预备的", "These are only preliminary results."),
    WordEntity("substantial", "大量的；实质的", "They made substantial progress during the semester."),
    WordEntity("undermine", "削弱；破坏", "Lack of sleep can undermine concentration.")
)

/** A simple scheduling algorithm. Not a full FSRS/SM-2 implementation. */
fun nextReview(card: WordEntity, remembered: Boolean, now: Long): WordEntity {
    val days = if (!remembered) 1 else when (card.intervalDays) {
        0 -> 1; 1 -> 3; 3 -> 7; 7 -> 14; else -> (card.intervalDays * 2).coerceAtMost(90)
    }
    return card.copy(
        dueAt = now + days * 86_400_000L,
        repetitions = if (remembered) card.repetitions + 1 else 0,
        intervalDays = days
    )
}
