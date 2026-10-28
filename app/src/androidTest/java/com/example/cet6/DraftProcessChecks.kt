package com.example.cet6

import android.app.Instrumentation
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import com.example.cet6.data.CoachKind
import com.example.cet6.data.CoachPractice
import com.example.cet6.data.StudyDatabase
import com.example.cet6.ui.StudyViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Emulator-only process-death probes using labelled original drafts and the real Activity. */
class DraftProcessChecks(private val runner: Instrumentation, private val mode: String, private val runId: String) {
    private val context = runner.targetContext
    private val marker = AtomicFile(File(context.filesDir,
        if (mode == "low-memory") "draft-process-$mode-$runId.json" else "draft-process-$mode.json"))
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val db get() = StudyDatabase.get(context)

    init {
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) { "Draft process fixture requires emulator" }
        require(mode in setOf("foreground", "background", "screen-off", "pending", "low-memory"))
        if (mode == "low-memory") {
            if (Build.VERSION.SDK_INT < 30) error("Low-memory exit reports require Android 11+")
            check(ActivityManager.isLowMemoryKillReportSupported()) { "This emulator cannot identify low-memory exits" }
        }
        UUID.fromString(runId)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rowHash(row: CoachPractice) = hash(row.toString().toByteArray(Charsets.UTF_8))
    private fun bankHash(): String = File(context.filesDir, "exam-content-v1.json")
        .let { if (it.exists()) hash(it.readBytes()) else "absent" }
    private fun read() = JSONObject(marker.openRead().bufferedReader().use { it.readText() })
    private fun write(value: JSONObject) {
        val stream = marker.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); marker.finishWrite(stream) }
        catch (failure: Exception) { marker.failWrite(stream); throw failure }
    }

    private fun activity(): MainActivity {
        val monitor = runner.addMonitor(MainActivity::class.java.name, null, false)
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return monitor.waitForActivityWithTimeout(10_000) as? MainActivity ?: error("Draft fixture Activity did not start")
        } finally { runner.removeMonitor(monitor) }
    }

    private suspend fun ready(vm: StudyViewModel) = withTimeout(15_000) {
        while (withContext(Dispatchers.Main) { vm.coach.entries.size != 3 || vm.coach.working || vm.backupRecoveryRunning }) delay(20)
    }

    suspend fun prepare(): String {
        if (marker.baseFile.exists()) check(read().optString("phase") == "verified") { "Previous draft probe is unfinished" }
        val before = JSONObject()
        db.dao().allCoach().forEach { before.put(it.id, rowHash(it)) }
        val bank = bankHash()
        val activity = activity()
        lateinit var vm: StudyViewModel
        runner.runOnMainSync { vm = ViewModelProvider(activity)[StudyViewModel::class.java] }
        ready(vm)
        val items = JSONArray()
        for (kind in CoachKind.entries) {
            withContext(Dispatchers.Main) { vm.coach.startCustom(kind, "Original draft process $mode $kind $runId",
                "Original process recovery material for $kind.") }
            ready(vm)
            val baseline = "Committed original baseline $kind.\n原创基线。"
            withContext(Dispatchers.Main) { check(vm.coach.edit(kind) { it.copy(answer = baseline) }) }
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { vm.coach.messages[kind] != "已保存到本机" }) delay(10)
            }
            val entry = withContext(Dispatchers.Main) { vm.coach.entries.getValue(kind) }
            check(db.dao().coachById(entry.id)?.answer == baseline)
            items.put(JSONObject().put("kind", kind.name).put("id", entry.id).put("title", entry.title)
                .put("material", entry.material).put("baseline", baseline)
                .put("latest", "Latest original draft $kind revision 20.\n最后一行与符号 📚 must survive."))
        }
        val state = JSONObject().put("runId", runId).put("mode", mode).put("pid", Process.myPid())
            .put("before", before).put("bankHash", bank).put("items", items).put("phase", "prepared")
        write(state)
        val blockerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var blocker: Job? = null
        try {
            if (mode == "pending") {
                val entered = CompletableDeferred<Unit>()
                blocker = blockerScope.launch { db.withTransaction { entered.complete(Unit); awaitCancellation() } }
                withTimeout(10_000) { entered.await() }
            }
            withContext(Dispatchers.Main) {
                for (revision in 1..20) for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val kind = CoachKind.valueOf(item.getString("kind"))
                    check(vm.coach.edit(kind) { it.copy(answer = if (revision == 20) item.getString("latest")
                        else "Original transient draft $kind revision $revision.") })
                }
            }
            state.put("phase", "edited").put("editedElapsed", SystemClock.elapsedRealtime())
            write(state)
            if (mode == "background" || mode == "screen-off") withTimeout(20_000) {
                while (withContext(Dispatchers.Main) { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) } ||
                    (mode == "screen-off" && power.isInteractive)) delay(20)
            }
            if (mode != "pending") {
                withTimeout(10_000) {
                    while (withContext(Dispatchers.Main) {
                        vm.coach.working || CoachKind.entries.any { vm.coach.messages[it] != "已保存到本机" }
                    }) delay(10)
                }
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    check(db.dao().coachById(item.getString("id"))?.answer == item.getString("latest")) {
                        "Saved acknowledgement preceded latest database content"
                    }
                }
            } else {
                check(withContext(Dispatchers.Main) { CoachKind.entries.all { vm.coach.messages[it] == "正在保存……" } }) {
                    "Blocked writes were incorrectly acknowledged"
                }
            }
            state.put("phase", "ready").put("acknowledged", mode != "pending")
                .put("readyElapsed", SystemClock.elapsedRealtime()).put("readyWall", System.currentTimeMillis()).put("interactive", power.isInteractive)
                .put("stopped", withContext(Dispatchers.Main) { !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) })
            write(state)
            runner.sendStatus(1, android.os.Bundle().apply { putString("stream", "READY: original $mode drafts; waiting for external process termination.\n") })
            if (mode == "low-memory") return "PREPARED: acknowledged original drafts; instrumentation must finish before normal cached-app pressure.\n"
            awaitCancellation()
        } finally { blocker?.cancelAndJoin(); blockerScope.cancel() }
    }

    suspend fun verify(expectedKilledPid: Int = 0): String = verifyProcess(expectedKilledPid, dataOnly = false)

    suspend fun inspectData(): String {
        require(mode == "low-memory")
        return verifyProcess(0, dataOnly = true)
    }

    private suspend fun verifyProcess(expectedKilledPid: Int, dataOnly: Boolean): String {
        val state = read()
        check(state.getString("runId") == runId && state.getString("phase") == "ready")
        check(state.getInt("pid") != Process.myPid()) { "Draft check must use a fresh process" }
        if (mode == "low-memory" && !dataOnly) {
            if (Build.VERSION.SDK_INT < 30) error("Low-memory exit reports require Android 11+")
            check(expectedKilledPid > 0 && expectedKilledPid != Process.myPid())
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, expectedKilledPid, 8).firstOrNull {
                it.pid == expectedKilledPid && it.processName == context.packageName && it.timestamp >= state.getLong("readyWall")
            } ?: error("Prepared normal app process has no matching exit report")
            check(exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY) { "Exit reason ${exit.reason} is not low-memory reclamation" }
            state.put("lowMemoryExit", JSONObject().put("pid", exit.pid).put("reason", exit.reason)
                .put("status", exit.status).put("importance", exit.importance).put("timestamp", exit.timestamp)
                .put("pssKb", exit.pss).put("rssKb", exit.rss))
        }
        val before = state.getJSONObject("before")
        for (id in before.keys()) check(db.dao().coachById(id)?.let(::rowHash) == before.getString(id)) { "Existing practice changed" }
        check(bankHash() == state.getString("bankHash")) { "Question bank changed" }
        val items = state.getJSONArray("items")
        var pendingLost = 0
        val restored = mutableMapOf<CoachKind, CoachPractice>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val row = requireNotNull(db.dao().coachById(item.getString("id"))) { "Original fixture baseline missing" }
            check(row.title == item.getString("title") && row.material == item.getString("material") &&
                row.feedback.isBlank() && row.submittedAt == 0L)
            val latest = row.answer == item.getString("latest")
            if (state.getBoolean("acknowledged")) check(latest) { "Acknowledged draft did not survive process death" }
            else {
                check(latest || row.answer == item.getString("baseline")) { "Pending draft became partial/corrupt" }
                if (!latest) pendingLost++
            }
            restored[CoachKind.valueOf(row.kind)] = row
        }
        val activity = activity()
        lateinit var vm: StudyViewModel
        runner.runOnMainSync { vm = ViewModelProvider(activity)[StudyViewModel::class.java] }
        ready(vm)
        for ((kind, row) in restored) check(withContext(Dispatchers.Main) { vm.coach.entries[kind] == row }) {
            "Cold ViewModel did not restore the persisted draft"
        }
        state.put("phase", if (dataOnly) "data-inspected" else "verified").put("verifiedPid", Process.myPid()).put("drafts", restored.size)
            .put("lowMemoryVerified", mode == "low-memory" && !dataOnly)
            .put("unacknowledgedLatestMissing", pendingLost).put("previousPracticesUnchanged", before.length())
        write(state)
        if (dataOnly) return "DATA ONLY: drafts=${restored.size}; actual cold ViewModel, prior practices and bank preserved; low-memory exit NOT verified.\n"
        return "PASS: mode=$mode; fresh process and actual ViewModel; drafts=${restored.size}; unacknowledgedLatestMissing=$pendingLost; prior practices and bank preserved.\n"
    }
}
