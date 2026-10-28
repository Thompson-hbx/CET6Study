package com.example.cet6.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
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
    val intervalDays: Int = 0,
    @ColumnInfo(defaultValue = "'项目原创示例'") val source: String = "项目原创示例"
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
    val nextDueAt: Long,
    @ColumnInfo(defaultValue = "0") val rating: Int = 0,
    @ColumnInfo(defaultValue = "'legacy'") val algorithm: String = "legacy",
    @ColumnInfo(defaultValue = "0.0") val stability: Double = 0.0,
    @ColumnInfo(defaultValue = "0.0") val difficulty: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val elapsedDays: Int = 0
)

@Entity(tableName = "word_schedules")
data class WordSchedule(
    @PrimaryKey val word: String,
    val stability: Double,
    val difficulty: Double,
    val lastReviewedAt: Long,
    val phase: Int,
    val step: Int,
    val algorithm: String
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
    @Upsert suspend fun saveCoach(entry: CoachPractice)
    @Query("SELECT * FROM coach_practices WHERE id=:id") suspend fun coachById(id: String): CoachPractice?
    @Query("SELECT * FROM coach_practices ORDER BY updatedAt DESC")
    fun coachHistory(): Flow<List<CoachPractice>>
    @Query("SELECT * FROM coach_practices WHERE kind=:kind AND submittedAt=0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun coachDraft(kind: String): CoachPractice?
    @Query("SELECT * FROM coach_practices ORDER BY id") suspend fun allCoach(): List<CoachPractice>
    @Query("SELECT * FROM coach_turns WHERE practiceId=:practiceId ORDER BY createdAt, id")
    fun turns(practiceId: String): Flow<List<CoachTurn>>
    @Query("SELECT * FROM coach_turns WHERE role='assistant' AND anchor='review' AND kind IN ('write-review/1','trans-review/1') ORDER BY createdAt, id")
    fun reviewTurns(): Flow<List<CoachTurn>>
    @Query("SELECT practiceId, COUNT(*) AS count FROM coach_turns WHERE role='assistant' GROUP BY practiceId")
    fun turnCounts(): Flow<List<TurnCount>>
    @Upsert suspend fun saveTurn(turn: CoachTurn)
    @Query("SELECT * FROM coach_turns WHERE id=:id") suspend fun turnById(id: String): CoachTurn?
    @Query("UPDATE coach_turns SET state=:state WHERE id=:id") suspend fun updateTurnState(id: String, state: String): Int
    @Query("SELECT * FROM coach_turns ORDER BY createdAt, id") suspend fun allTurns(): List<CoachTurn>
    @Insert suspend fun addTurns(turns: List<CoachTurn>)
    @Query("SELECT * FROM words ORDER BY word") fun words(): Flow<List<WordEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addWords(words: List<WordEntity>): List<Long>
    @Query("UPDATE words SET dueAt=:due, repetitions=:reps, intervalDays=:days WHERE word=:word")
    suspend fun recordReview(word: String, due: Long, reps: Int, days: Int)
    @Query("SELECT * FROM words WHERE word=:word") suspend fun word(word: String): WordEntity?
    @Query("SELECT * FROM word_schedules") fun wordSchedules(): Flow<List<WordSchedule>>
    @Query("SELECT * FROM word_schedules WHERE word=:word") suspend fun schedule(word: String): WordSchedule?
    @Upsert suspend fun saveSchedule(schedule: WordSchedule)
    @Query("SELECT EXISTS(SELECT 1 FROM word_reviews WHERE word=:word AND studiedOn=:date AND wasNew=1)")
    suspend fun wasIntroducedOn(word: String, date: String): Boolean
    @Insert suspend fun addWordReview(review: WordReview)
    @Query("SELECT * FROM word_reviews ORDER BY createdAt DESC LIMIT 100")
    fun wordReviewHistory(): Flow<List<WordReview>>
    @Query("SELECT studiedOn AS date, COUNT(DISTINCT CASE WHEN wasNew=1 THEN word END) AS newCount, " +
        "COUNT(DISTINCT CASE WHEN wasNew=0 THEN word END) AS reviewCount, " +
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

    // Full snapshots must never use the UI's recent-history limits.
    @Query("SELECT * FROM words ORDER BY word") suspend fun allWords(): List<WordEntity>
    @Query("SELECT * FROM word_reviews ORDER BY createdAt, id") suspend fun allWordReviews(): List<WordReview>
    @Query("SELECT * FROM word_schedules ORDER BY word") suspend fun allSchedules(): List<WordSchedule>
    @Query("SELECT * FROM daily_plans ORDER BY date") suspend fun allPlans(): List<DailyPlan>
    @Query("SELECT * FROM reading_attempts ORDER BY id") suspend fun allReading(): List<ReadingAttempt>
    @Query("SELECT * FROM essays ORDER BY id") suspend fun allEssays(): List<EssayEntity>
    @Upsert suspend fun saveWords(words: List<WordEntity>)
    @Insert suspend fun addWordReviews(reviews: List<WordReview>)
    @Insert suspend fun addReadingAttempts(attempts: List<ReadingAttempt>)
    @Insert suspend fun addEssays(essays: List<EssayEntity>)
    @Query("DELETE FROM word_schedules WHERE word=:word") suspend fun clearSchedule(word: String)
}

@Database(entities = [WordEntity::class, ReadingAttempt::class, EssayEntity::class,
    WordReview::class, DailyPlan::class, WordSchedule::class, CoachPractice::class, CoachTurn::class], version = 9)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun dao(): StudyDao
    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE coach_turns ADD COLUMN locatedQuote TEXT NOT NULL DEFAULT ''")
            }
        }
        /** Additive only: conversations live beside practices, existing rows are untouched. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `coach_turns` (`id` TEXT NOT NULL, `practiceId` TEXT NOT NULL, `anchor` TEXT NOT NULL, `kind` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `state` TEXT NOT NULL, `model` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_coach_turns_practiceId` ON `coach_turns` (`practiceId`)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE coach_practices ADD COLUMN sourcePracticeId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE coach_practices ADD COLUMN practiceGoal TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE coach_practices ADD COLUMN savedSentences TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS coach_practices (id TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, material TEXT NOT NULL, questions TEXT NOT NULL, answer TEXT NOT NULL, feedback TEXT NOT NULL, model TEXT NOT NULL, errorReason TEXT NOT NULL, submittedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS word_reviews (id TEXT NOT NULL, word TEXT NOT NULL, remembered INTEGER NOT NULL, wasNew INTEGER NOT NULL, studiedOn TEXT NOT NULL, createdAt INTEGER NOT NULL, nextDueAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS daily_plans (date TEXT NOT NULL, newGoal INTEGER NOT NULL, reviewGoal INTEGER NOT NULL, PRIMARY KEY(date))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN source TEXT NOT NULL DEFAULT '项目原创示例'")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS word_schedules (word TEXT NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL, lastReviewedAt INTEGER NOT NULL, phase INTEGER NOT NULL, step INTEGER NOT NULL, algorithm TEXT NOT NULL, PRIMARY KEY(word))")
                db.execSQL("ALTER TABLE word_reviews ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_reviews ADD COLUMN algorithm TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE word_reviews ADD COLUMN stability REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE word_reviews ADD COLUMN difficulty REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE word_reviews ADD COLUMN elapsedDays INTEGER NOT NULL DEFAULT 0")
            }
        }
        val ALL_MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
        @Volatile private var INSTANCE: StudyDatabase? = null
        fun get(context: Context): StudyDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, StudyDatabase::class.java,
                "cet6_study.db").addMigrations(*ALL_MIGRATIONS).build().also { INSTANCE = it }
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
