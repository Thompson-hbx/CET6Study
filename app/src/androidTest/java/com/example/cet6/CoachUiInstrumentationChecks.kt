package com.example.cet6

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.example.cet6.data.CoachKind
import com.example.cet6.data.StudyDatabase
import com.example.cet6.ui.StudyViewModel
import kotlinx.coroutines.runBlocking

/** Optional emulator-only UI smoke test. Adds one named exercise; never clears or removes existing work. */
class CoachUiInstrumentationChecks(private val runner: Instrumentation) {
    private val automation get() = runner.uiAutomation
    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isVisibleToUser && predicate(root)) return root
        for (i in 0 until root.childCount) find(root.getChild(i), predicate)?.let { return it }
        return null
    }
    private val homeTabs = setOf("今日", "练习", "复盘", "我的")
    // The skill switch inside 练习 is a segmented button: Compose reports its choice as checked, not selected.
    private val skillSegments = setOf("写作", "翻译", "阅读")
    private fun actionTarget(node: AccessibilityNodeInfo, allowSelected: Boolean): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable && !(allowSelected && (target.isSelected || target.isChecked))) target = target.parent
        return target
    }
    private fun node(text: String, forClick: Boolean = false,
        root: AccessibilityNodeInfo? = automation.rootInActiveWindow) = find(root) {
        it.text?.toString() == text && (!forClick || actionTarget(it, text in homeTabs || text in skillSegments) != null)
    }
    private fun awaitState(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $label" }
            SystemClock.sleep(20)
        }
    }
    private fun scroll(forward: Boolean, viewport: AccessibilityNodeInfo? = null) {
        val target = viewport ?: find(automation.rootInActiveWindow) { it.isScrollable && !it.isEditable }
            ?: error("No scrollable viewport")
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        val from = bounds.top + bounds.height() * if (forward) 0.78f else 0.25f
        val to = bounds.top + bounds.height() * if (forward) 0.25f else 0.78f
        val x = bounds.right - 8
        val command = "input swipe $x ${from.toInt()} $x ${to.toInt()} 400"
        val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        check(output.isBlank()) { "Scroll input failed: $output" }
        automation.waitForIdle(200, 2000)
    }
    private fun top() { repeat(12) {
        if (node("阅读练习") != null) return
        scroll(false)
    } }
    private fun seek(text: String, forClick: Boolean = false): AccessibilityNodeInfo {
        repeat(24) {
            // Keep lookup and scrolling on one tree; a tab change can replace the viewport between reads.
            val root = automation.rootInActiveWindow
            val target = node(text, forClick, root)
            val viewport = find(root) { it.isScrollable && !it.isEditable }
            if (target == null && viewport == null) {
                awaitState("rendered content for $text") {
                    node(text, forClick) != null || find(automation.rootInActiveWindow) { it.isScrollable && !it.isEditable } != null
                }
                return@repeat
            }
            if (target != null) {
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                val clip = Rect().also { viewport?.getBoundsInScreen(it) }
                if (text in homeTabs || text in skillSegments || clip.isEmpty ||
                    (bounds.top >= clip.top + 6 && bounds.bottom <= clip.bottom - 6)) return target
                scroll(bounds.top >= clip.top, viewport)
            } else scroll(true, viewport)
        }
        error("UI element not found: $text")
    }
    private fun click(text: String) {
        if (text in listOf("资料作答", "AI 讲解", "错因难句", "历史")) top()
        automation.waitForIdle(200, 2000)
        val target = requireNotNull(actionTarget(seek(text, forClick = true), text in homeTabs || text in skillSegments)) { "Cannot click: $text" }
        awaitState("enabled $text") { target.refresh() && target.isEnabled }
        // Selected Compose tabs intentionally expose no click action.
        if (text in homeTabs && target.isSelected) return
        if (text in skillSegments && target.isChecked) return
        runner.sendStatus(0, Bundle().apply { putString("stream", "Activate $text\n") })
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Click rejected: $text" }
        if (text in homeTabs || text in listOf("资料作答", "AI 讲解", "错因难句", "历史")) {
            awaitState("selected tab $text") { target.refresh() && target.isSelected }
        }
        // A checked segment stops being clickable, so Compose replaces its node; look it up again by its label.
        if (text in skillSegments) awaitState("checked segment $text") { node(text)?.let { actionTarget(it, true) }?.isChecked == true }
        automation.waitForIdle(200, 2000)
    }
    private fun enter(label: String, value: String) {
        var target: AccessibilityNodeInfo? = seek(label)
        while (target != null && !target.isEditable) target = target.parent
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        check(target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true) { "Cannot enter: $label" }
        val field = requireNotNull(target)
        awaitState("entered $label") { field.refresh() && field.text?.toString() == value }
        automation.waitForIdle(200, 2000)
    }
    fun verify() {
        check(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic")) { "UI smoke test is restricted to an emulator" }
        val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: StudyViewModel
        runner.runOnMainSync { vm = ViewModelProvider(activity)[StudyViewModel::class.java] }
        fun currentId(): String? {
            var id: String? = null
            runner.runOnMainSync { id = vm.coach.entries[CoachKind.READING]?.id }
            return id
        }
        awaitState("initial reading draft") { currentId() != null }
        val before = currentId()
        automation.waitForIdle(500, 5000)
        click("练习")
        click("阅读")
        click("粘贴一篇英文文章练习")
        awaitState("a new reading draft") { currentId() != before }
        check(runBlocking { StudyDatabase.get(runner.targetContext).dao().coachDraft("READING")?.questions?.isBlank() == true }) {
            "New exercise was not opened; stopping before editing existing work"
        }
        top()
        val title = "模拟器UI阅读-${System.currentTimeMillis()}"
        val sentence = "Libraries need quiet rooms."
        enter("练习名称", title)
        enter("粘贴英文阅读文章", sentence)
        enter("新题目（含选项；可附参考答案）", "1. What do libraries need?\nA. Quiet rooms.\nB. Noise.")
        click("添加题目")
        awaitState("saved question") { runBlocking {
            StudyDatabase.get(runner.targetContext).dao().allCoach().any { it.title == title && it.questions.isNotBlank() }
        } }
        val added = runBlocking { StudyDatabase.get(runner.targetContext).dao().allCoach().single { it.title == title } }
        check(com.example.cet6.data.ReadingQuestions.parse(added.questions).isNotEmpty()) { "Question not added or not selectable: ${added.questions}" }
        click("A. Quiet rooms.")
        awaitState("selected answer persisted") { runBlocking { StudyDatabase.get(runner.targetContext).dao().coachById(added.id)?.answer == "1. A" } }
        click("提交")
        awaitState("offline submission persisted") { runBlocking { (StudyDatabase.get(runner.targetContext).dao().coachById(added.id)?.submittedAt ?: 0) > 0 } }
        click("错因难句")
        seek("原文证据与难句")
        enter("粘贴要核对的原文引用或难句", sentence)
        click("收藏这段原文")
        seek("本篇难句收藏")
        automation.takeScreenshot()?.let { bitmap ->
            java.io.File(runner.targetContext.getExternalFilesDir(null), "coach-ui-review.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        // Keep the immediate-background regression without assuming a particular write delay.
        click("逻辑推断")
        runner.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitState("background evidence and reason persisted") { runBlocking {
            StudyDatabase.get(runner.targetContext).dao().coachById(added.id)?.let {
                it.savedSentences == sentence && it.errorReason.contains("逻辑推断")
            } == true
        } }
        val saved = runBlocking { StudyDatabase.get(runner.targetContext).dao().allCoach().single { it.title == title } }
        check(saved.answer == "1. A" && saved.submittedAt > 0 && saved.feedback.isBlank())
        check(saved.savedSentences == sentence && saved.errorReason.contains("逻辑推断"))
        runner.targetContext.startActivity(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitState("foreground after background") { automation.rootInActiveWindow?.packageName?.toString() == "com.example.cet6" }
    }
}
