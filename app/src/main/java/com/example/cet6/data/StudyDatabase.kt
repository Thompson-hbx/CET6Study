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

@Dao
interface StudyDao {
    @Query("SELECT * FROM words ORDER BY word") fun words(): Flow<List<WordEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addWords(words: List<WordEntity>)
    @Query("UPDATE words SET dueAt=:due, repetitions=:reps, intervalDays=:days WHERE word=:word")
    suspend fun recordReview(word: String, due: Long, reps: Int, days: Int)

    @Insert suspend fun addReadingAttempt(attempt: ReadingAttempt)
    @Query("SELECT * FROM reading_attempts ORDER BY createdAt DESC LIMIT 30")
    fun readingHistory(): Flow<List<ReadingAttempt>>

    @Insert suspend fun addEssay(essay: EssayEntity)
    @Query("SELECT * FROM essays ORDER BY createdAt DESC LIMIT 20")
    fun essays(): Flow<List<EssayEntity>>
}

@Database(entities = [WordEntity::class, ReadingAttempt::class, EssayEntity::class], version = 1)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun dao(): StudyDao
    companion object {
        @Volatile private var INSTANCE: StudyDatabase? = null
        fun get(context: Context): StudyDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, StudyDatabase::class.java,
                "cet6_study.db").build().also { INSTANCE = it }
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
