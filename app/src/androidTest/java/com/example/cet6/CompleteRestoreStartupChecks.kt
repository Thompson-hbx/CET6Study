package com.example.cet6

import android.app.Instrumentation
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import com.example.cet6.data.*
import com.example.cet6.ui.StudyViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Emulator-only: adds labelled original practices, preserving existing records and question bank. */
class CompleteRestoreStartupChecks(private val runner: Instrumentation) {
    private val context = runner.targetContext
    private val marker = File(context.cacheDir, "restore-startup-fixture.json")
    private fun bankHash(): String {
        val bank = File(context.filesDir, "exam-content-v1.json")
        if (!bank.exists()) return "absent"
        return MessageDigest.getInstance("SHA-256").digest(bank.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun hash(entry: CoachPractice) = MessageDigest.getInstance("SHA-256").digest(entry.toString().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    suspend fun prepare(commitStudy: Boolean = true) {
        val journal = RestoreJournal(context)
        check(!journal.hasPending()) { "An existing restore task must not be replaced" }
        val existing = StudyDatabase.get(context).dao().allCoach()
        val stamp = maxOf(System.currentTimeMillis(), existing.maxOfOrNull { it.updatedAt } ?: 0) + 1
        val entries = (if (commitStudy) listOf(CoachKind.READING) else CoachKind.entries).map { kind ->
            CoachPractice(id = UUID.randomUUID().toString(), kind = kind.name,
                title = "Original startup restore check ${kind.name} 20261027",
                material = "Original recovery test prompt.", answer = "Original recovery test draft ${kind.name}.", updatedAt = stamp)
        }
        val study = StudyBackup(System.currentTimeMillis(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), entries)
        journal.write(RestoreIntent(CompleteBackup(study, emptyList()), false))
        if (commitStudy) BackupRepository(StudyDatabase.get(context)).restore(study, false)
        marker.writeText(JSONObject().put("pid", android.os.Process.myPid()).put("bankHash", bankHash())
            .put("commitStudy", commitStudy).put("stamp", stamp)
            .put("entries", JSONArray().apply { entries.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind)) } })
            .put("existing", JSONArray().apply { existing.forEach { put(JSONObject().put("id", it.id).put("sha256", hash(it))) } }).toString())
    }

    fun verify(shareReading: Boolean = false) {
        val saved = JSONObject(marker.readText())
        check(saved.getInt("pid") != android.os.Process.myPid()) { "startup must run in a fresh process" }
        check(RestoreJournal(context).hasPending()) { "prepared task was lost" }
        val shareSubject = "Original recovery sharing check"
        val shared = SharedReading.from("text/plain", "Original text shared during startup recovery.", shareSubject)
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (shareReading) launch.setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, shared.material).putExtra(Intent.EXTRA_SUBJECT, shareSubject)
        val activity = runner.startActivitySync(launch) as MainActivity
        lateinit var vm: StudyViewModel
        runner.runOnMainSync { vm = ViewModelProvider(activity)[StudyViewModel::class.java] }
        runBlocking {
            withTimeout(15_000) {
                while (withContext(Dispatchers.Main) {
                    vm.backupRecoveryRunning || vm.backupBusy || vm.coach.entries.size < CoachKind.entries.size ||
                        (shareReading && vm.coach.entries[CoachKind.READING]?.material != shared.material)
                }) delay(20)
            }
            check(withContext(Dispatchers.Main) { !vm.backupRestorePending && vm.backupStatus.startsWith("恢复完成") }) { "startup did not complete the retained task" }
            val expected = saved.getJSONArray("entries")
            for (i in 0 until expected.length()) {
                val item = expected.getJSONObject(i)
                val kind = CoachKind.valueOf(item.getString("kind"))
                val entry = requireNotNull(StudyDatabase.get(context).dao().coachById(item.getString("id")))
                check(entry.title == "Original startup restore check ${kind.name} 20261027" &&
                    entry.answer == "Original recovery test draft ${kind.name}." && entry.updatedAt == saved.getLong("stamp"))
                if (!saved.getBoolean("commitStudy") && !(shareReading && kind == CoachKind.READING)) {
                    check(withContext(Dispatchers.Main) { vm.coach.entries[kind] == entry }) {
                        "Restored ${kind.name} draft was not loaded into the editor"
                    }
                }
            }
            if (!saved.getBoolean("commitStudy")) {
                check(withContext(Dispatchers.Main) { vm.coach.flush() })
                val expectedIds = (0 until expected.length()).map { expected.getJSONObject(it).getString("id") }.toSet()
                val previous = saved.getJSONArray("existing")
                val previousIds = (0 until previous.length()).map { previous.getJSONObject(it).getString("id") }.toSet()
                val sharedIds = if (shareReading) setOf(withContext(Dispatchers.Main) {
                    val entry = vm.coach.entries.getValue(CoachKind.READING)
                    check(entry.title == shared.title && entry.material == shared.material && entry.answer.isEmpty())
                    entry.id
                }) else emptySet()
                check(StudyDatabase.get(context).dao().allCoach().map { it.id }.toSet() == previousIds + expectedIds + sharedIds) {
                    "Recovery created an unexpected blank practice"
                }
            }
            val existing = saved.getJSONArray("existing")
            for (i in 0 until existing.length()) {
                val item = existing.getJSONObject(i)
                val entry = requireNotNull(StudyDatabase.get(context).dao().coachById(item.getString("id")))
                check(hash(entry) == item.getString("sha256")) { "Startup changed an existing practice" }
            }
            check(!RestoreJournal(context).hasPending() && saved.getString("bankHash") == bankHash())
        }
    }
}
