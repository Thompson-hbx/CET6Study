package com.example.cet6

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.example.cet6.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.Executor
import org.json.JSONObject

/** Every file and database belongs to a unique cache folder, never the learner's stores. */
class CompleteRestoreChecks(private val context: Context) {
    private val paper = ExamPaper("sample-cet6-2026-06-13-1", "2026-06-13", "1", sourceName = "Original recovery test",
        sourceUrl = "local:test", licenseStatus = "original", sample = true)
    private val practice = CoachPractice(id = "recovery-original", title = "Original restore test", material = "Original prompt.",
        answer = "Original draft.", updatedAt = 1000)
    private val snapshot = StudyBackup(1000, emptyList(), emptyList(), emptyList(), listOf(DailyPlan("2026-10-24", 3, 5)),
        listOf(ReadingAttempt(1, "original-question", "B", true, 1000)),
        listOf(EssayEntity(1, "Original topic", "Original essay.", "Original feedback.", 1000)), listOf(practice))

    private class Fixture(context: Context,
        val folder: File = File(context.cacheDir, "complete-restore-${System.nanoTime()}").apply { check(mkdirs()) }) {
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = folder }
        val bank = File(folder, "exam-content-v1.json")
        val intent = File(folder, "restore-pending-v1.bin")
        fun open(blockBankOnInsert: Boolean = false): StudyDatabase {
            val builder = Room.databaseBuilder(isolated, StudyDatabase::class.java, File(folder, "study.db").absolutePath)
            if (blockBankOnInsert) builder.setQueryCallback({ sql, _ ->
                if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("essays") && !bank.exists()) {
                    check(bank.mkdir())
                    File(bank, "test-blocker").writeText("Original test fixture")
                }
            }, Executor { it.run() })
            return builder.build()
        }
        suspend fun useDatabase(blockBankOnInsert: Boolean = false, action: suspend (StudyDatabase) -> Unit) {
            val db = open(blockBankOnInsert)
            try { action(db) } finally { db.close() }
        }
        fun coordinator(db: StudyDatabase) = CompleteRestoreRepository(BackupRepository(db), ExamStore(isolated), RestoreJournal(isolated))
        fun unblock() {
            check(File(bank, "test-blocker").delete())
            check(bank.delete())
        }
    }

    suspend fun verify() {
        emptyAndLegacy()
        journalWriteFailure()
        databaseRollback()
        partialCommitAndRetry()
        conflictOnRetry()
        corruptIntent()
        verifyManifestRejection()
    }

    suspend fun verifyManifestRejection(): Int {
        val checks = CompleteManifestChecks()
        checks.verifyValid()
        val count = checks.verifyInvalid()
        val f = Fixture(context)
        f.useDatabase { db ->
            val local = practice.copy(id = "manifest-kept-local", answer = "Original local draft must remain.")
            db.dao().saveCoach(local)
            ExamStore(f.isolated).save(requireNotNull(checks.baseline().papers))
            val before = BackupRepository(db).snapshot().copy(createdAt = 0)
            val bankBefore = f.bank.readBytes()
            for ((name, bytes) in checks.invalidArchives()) {
                val failed = runCatching {
                    val decoded = CompleteBackupCodec.read(java.io.ByteArrayInputStream(bytes))
                    f.coordinator(db).restore(decoded, true)
                }.isFailure
                check(failed) { "Malformed manifest reached restore: $name" }
                check(BackupRepository(db).snapshot().copy(createdAt = 0) == before) { "Rejected archive changed study data" }
                check(f.bank.readBytes().contentEquals(bankBefore) && !RestoreJournal(f.isolated).hasPending()) {
                    "Rejected archive changed the bank or created a restore task"
                }
            }
        }
        return count
    }

    suspend fun prepareProcessRetry() {
        val f = Fixture(context)
        f.useDatabase(blockBankOnInsert = true) { db ->
            check(runCatching { f.coordinator(db).restore(CompleteBackup(snapshot, listOf(paper)), true) }.isFailure)
            check(db.dao().allEssays().size == 1 && RestoreJournal(f.isolated).hasPending())
        }
        f.unblock()
        File(context.cacheDir, "restore-process-fixture.json").writeText(JSONObject()
            .put("folder", f.folder.canonicalPath).put("pid", android.os.Process.myPid()).toString())
    }

    suspend fun resumeProcessRetry() {
        val marker = JSONObject(File(context.cacheDir, "restore-process-fixture.json").readText())
        check(marker.getInt("pid") != android.os.Process.myPid()) { "retry did not use a fresh process" }
        val folder = File(marker.getString("folder")).canonicalFile
        check(folder.parentFile == context.cacheDir.canonicalFile && folder.name.startsWith("complete-restore-"))
        val f = Fixture(context, folder)
        check(RestoreJournal(f.isolated).hasPending() && !f.bank.exists())
        f.useDatabase { db ->
            val result = requireNotNull(f.coordinator(db).resume())
            check(result.summary.newEssays == 0 && result.summary.newReading == 0 && result.summary.newCoach == 0)
            check(db.dao().allCoach() == listOf(practice) && db.dao().allEssays().size == 1 && db.dao().allReading().size == 1)
            check(ExamStore(f.isolated).load() == listOf(paper) && !RestoreJournal(f.isolated).hasPending())
        }
    }

    private suspend fun emptyAndLegacy() {
        val f = Fixture(context)
        f.useDatabase { db ->
            val repo = f.coordinator(db)
            val empty = CompleteBackup(snapshot, emptyList())
            check(repo.restore(empty, true).summary.newEssays == 1)
            check(!f.bank.exists() && !RestoreJournal(f.isolated).hasPending())
            val repeats = coroutineScope { (1..2).map { async { repo.restore(empty, true) } }.awaitAll() }
            check(repeats.all { it.summary.newEssays == 0 && it.summary.newReading == 0 && it.summary.newCoach == 0 })
            check(db.dao().allEssays().size == 1 && db.dao().allReading().size == 1)
            f.bank.writeText("invalid bank retained")
            check(repo.restore(CompleteBackup(snapshot, null), false).papers == null)
            check(f.bank.readText() == "invalid bank retained") { "legacy restore touched the personal bank" }
            check(repo.resume() == null)
        }
    }

    private suspend fun journalWriteFailure() {
        val f = Fixture(context)
        f.useDatabase { db ->
            val local = practice.copy(id = "journal-kept-local", answer = "Original local draft remains.")
            db.dao().saveCoach(local)
            ExamStore(f.isolated).save(listOf(paper))
            val before = BackupRepository(db).snapshot().copy(createdAt = 0)
            val bankBefore = f.bank.readBytes()
            // A nonempty directory at AtomicFile's staging path reproduces a real filesystem write failure.
            val blocked = File(f.intent.path + ".new")
            check(blocked.mkdir())
            val marker = File(blocked, "original-test-blocker").apply { writeText("original fixture") }
            check(runCatching { f.coordinator(db).restore(CompleteBackup(snapshot, listOf(paper)), true) }.isFailure)
            check(BackupRepository(db).snapshot().copy(createdAt = 0) == before) { "Journal failure changed learning data" }
            check(f.bank.readBytes().contentEquals(bankBefore)) { "Journal failure changed the bank" }
            check(!RestoreJournal(f.isolated).hasPending()) { "Incomplete task was reported durable" }
            check(marker.readText() == "original fixture")
            check(marker.delete() && blocked.delete())
            val result = f.coordinator(db).restore(CompleteBackup(snapshot, listOf(paper)), true)
            check(result.summary.newCoach == 1 && db.dao().allCoach().toSet() == setOf(local, practice))
            check(!RestoreJournal(f.isolated).hasPending())
        }
    }

    private suspend fun databaseRollback() {
        val f = Fixture(context)
        f.useDatabase { db ->
            db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_restore BEFORE INSERT ON essays BEGIN SELECT RAISE(ABORT, 'recovery test'); END")
            check(runCatching { f.coordinator(db).restore(CompleteBackup(snapshot, listOf(paper)), true) }.isFailure)
            val after = BackupRepository(db).snapshot()
            check(after.essays.isEmpty() && after.reading.isEmpty() && after.plans.isEmpty()) { "database restore did not roll back" }
            check(RestoreJournal(f.isolated).hasPending() && !f.bank.exists())
        }
        f.useDatabase { db ->
            val result = requireNotNull(f.coordinator(db).resume())
            check(result.summary.newEssays == 1 && db.dao().allCoach() == listOf(practice))
            check(ExamStore(f.isolated).load() == listOf(paper) && !RestoreJournal(f.isolated).hasPending())
        }
    }

    private suspend fun partialCommitAndRetry() {
        val f = Fixture(context)
        f.useDatabase(blockBankOnInsert = true) { db ->
            check(runCatching { f.coordinator(db).restore(CompleteBackup(snapshot, listOf(paper)), true) }.isFailure)
            check(db.dao().allEssays().size == 1 && db.dao().allReading().size == 1) { "fault did not follow database commit" }
            check(f.bank.isDirectory && RestoreJournal(f.isolated).hasPending())
        }
        f.unblock()
        f.useDatabase { db ->
            val result = requireNotNull(f.coordinator(db).resume())
            check(result.summary.newEssays == 0 && result.summary.newReading == 0 && result.summary.newCoach == 0)
            check(db.dao().allEssays().size == 1 && db.dao().allReading().size == 1)
            check(db.dao().dailyPlan("2026-10-24") == snapshot.plans.single()) { "restore choice was lost" }
            check(ExamStore(f.isolated).load() == listOf(paper) && !RestoreJournal(f.isolated).hasPending())
        }
    }

    private suspend fun conflictOnRetry() {
        val f = Fixture(context)
        val changed = practice.copy(answer = "New local draft.", updatedAt = 2000)
        f.useDatabase(blockBankOnInsert = true) { db ->
            check(runCatching { f.coordinator(db).restore(CompleteBackup(snapshot, listOf(paper)), false) }.isFailure)
            db.dao().saveCoach(changed)
        }
        f.unblock()
        f.useDatabase { db ->
            check(runCatching { f.coordinator(db).resume() }.isFailure)
            check(db.dao().allCoach() == listOf(changed) && db.dao().allEssays().size == 1)
            check(!f.bank.exists() && RestoreJournal(f.isolated).hasPending()) { "conflict discarded the pending task or wrote the bank" }
        }
    }

    private suspend fun corruptIntent() {
        val f = Fixture(context)
        f.intent.writeBytes(byteArrayOf(0x43, 0x36))
        f.useDatabase { db ->
            check(runCatching { f.coordinator(db).resume() }.isFailure)
            check(db.dao().allEssays().isEmpty() && db.dao().allCoach().isEmpty() && !f.bank.exists())
            check(f.intent.readBytes().contentEquals(byteArrayOf(0x43, 0x36))) { "corrupt task was discarded" }
        }
    }
}
