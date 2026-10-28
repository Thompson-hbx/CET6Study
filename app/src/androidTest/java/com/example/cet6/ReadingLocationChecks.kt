package com.example.cet6

import android.content.Context
import androidx.room.Room
import com.example.cet6.data.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Isolated cache databases and original text; no learner records or AI requests. */
class ReadingLocationChecks(private val context: Context) {
    suspend fun verify() {
        val file = File(context.cacheDir, "reading-location-${System.nanoTime()}.db")
        fun open() = Room.databaseBuilder(context, StudyDatabase::class.java, file.absolutePath).build()
        var db = open()
        val target = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        try {
            val quote = "Libraries offer quiet rooms."
            val passage = ExamPassage("1", "Original test", "Intro.\n\n$quote", listOf(ExamQuestion(46, "What?", emptyList())))
            val entry = CoachPractice(id = "location-practice", kind = CoachKind.READING.name, material = passage.content,
                questions = JSONObject().put("format", "CET6ExamAttempt").put("passage", ExamCodec.passageJson(passage)).toString(), updatedAt = 1000)
            val reply = CoachTurn(id = "location-reply", practiceId = entry.id, anchor = Anchor.question(46),
                kind = TurnKind.READING_EXPLAIN, role = CoachTurn.ASSISTANT,
                content = JSONObject().put("type", "locate").put("quote", quote).put("paragraph", 99).toString(), createdAt = 1000)
            val repository = CoachRepository(db)
            repository.save(entry)
            repository.saveExchange(null, reply)
            db.close()
            db = open()
            val saved = db.dao().turnById(reply.id)!!
            check(saved.locatedQuote == quote && ReadingLocation.restored(db.dao().coachById(entry.id)!!, saved) == quote)
            check(PassageLayout.paragraphOf(passage.content, saved.locatedQuote) == 2)
            CoachRepository(db).saveExchange(null, reply.copy(id = "invalid", content = reply.content.replace(quote, "Invented quote.")))
            check(db.dao().turnById("invalid")!!.locatedQuote.isEmpty())
            val snapshot = BackupRepository(db).snapshot()
            val bytes = ByteArrayOutputStream().also { CompleteBackupCodec.write(CompleteBackup(snapshot, emptyList()), it) }.toByteArray()
            BackupRepository(target).restore(CompleteBackupCodec.read(ByteArrayInputStream(bytes)).study, false)
            check(target.dao().allTurns() == db.dao().allTurns())
            check(ReadingLocation.restored(target.dao().coachById(entry.id)!!, target.dao().turnById(reply.id)!!) == quote)
        } finally { db.close(); target.close() }
    }
}
