package com.example.cet6

import android.app.Instrumentation
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import com.example.cet6.data.*
import com.example.cet6.ui.StudyViewModel
import kotlinx.coroutines.*

/** Emulator-only exercise of the actual Activity/ViewModel lifecycle, with a new labelled draft. */
class CoachLifecycleChecks(private val runner: Instrumentation) {
    fun verify() {
        check(android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.contains("generic")) { "Lifecycle fixture requires emulator" }
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: StudyViewModel
        runner.runOnMainSync { vm = ViewModelProvider(activity)[StudyViewModel::class.java] }
        runBlocking {
            suspend fun idle() = withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { vm.coach.entries.size < 3 || vm.coach.working }) delay(20)
            }
            try { idle() } catch (e: TimeoutCancellationException) {
                error(withContext(Dispatchers.Main) { "Startup stayed busy: working=${vm.coach.working}, kinds=${vm.coach.entries.keys}, messages=${vm.coach.messages}" })
            }
            withContext(Dispatchers.Main) { vm.coach.startCustom(CoachKind.WRITING,
                "Lifecycle sample 20261024", "Original lifecycle test material.") }
            idle()
            withContext(Dispatchers.Main) {
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Original lifecycle sharing sentence.")
                    .putExtra(Intent.EXTRA_SUBJECT, "Lifecycle shared sample 20261024")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) {
                    vm.coach.entries.getValue(CoachKind.READING).title != SharedReading.from("text/plain", "Original lifecycle sharing sentence.", "Lifecycle shared sample 20261024").title
                }) delay(20)
            }
            idle()
            val id = withContext(Dispatchers.Main) {
                check(vm.coach.entries.getValue(CoachKind.WRITING).title == "Lifecycle sample 20261024")
                check(vm.coach.edit(CoachKind.WRITING) { it.copy(answer = "Latest edit before background 20261024.") })
                vm.coach.entries.getValue(CoachKind.WRITING).id
            }
            runner.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            idle()
            val persisted = StudyDatabase.get(runner.targetContext).dao().coachById(id)!!
            check(persisted.answer == "Latest edit before background 20261024.")
        }
    }
}
