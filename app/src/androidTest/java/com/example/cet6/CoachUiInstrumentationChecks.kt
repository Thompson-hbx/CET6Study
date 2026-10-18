package com.example.cet6

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import com.example.cet6.data.StudyDatabase
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
    private fun node(text: String) = find(automation.rootInActiveWindow) { it.text?.toString() == text }
    private fun input(command: String) {
        val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input $command")).use { it.readBytes().toString(Charsets.UTF_8) }
        check(output.isBlank()) { "Input command failed: $output" }
    }
    private fun scroll(forward: Boolean) {
        val root = automation.rootInActiveWindow ?: return
        val target = find(root) { it.isScrollable && !it.isEditable } ?: root
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        val from = bounds.top + bounds.height() * if (forward) 0.78f else 0.25f
        val to = bounds.top + bounds.height() * if (forward) 0.25f else 0.78f
        val x = bounds.right - 8
        input("swipe $x ${from.toInt()} $x ${to.toInt()} 400")
        automation.waitForIdle(350, 3000)
    }
    private fun top() { repeat(12) {
        if (node("阅读练习") != null) return
        scroll(false)
    } }
    private fun seek(text: String): AccessibilityNodeInfo {
        repeat(24) {
            val target = node(text)
            val viewport = find(automation.rootInActiveWindow) { it.isScrollable && !it.isEditable }
            if (target != null) {
                val bounds = Rect().also { target.getBoundsInScreen(it) }
                val clip = Rect().also { viewport?.getBoundsInScreen(it) }
                if (text in listOf("阅读", "写译", "连接", "今日") || clip.isEmpty ||
                    (bounds.top >= clip.top + 6 && bounds.bottom <= clip.bottom - 6)) return target
                scroll(bounds.top >= clip.top)
            } else scroll(true)
            SystemClock.sleep(150)
        }
        error("UI element not found: $text")
    }
    private fun click(text: String) {
        if (text in listOf("资料作答", "AI 讲解", "错因难句", "历史")) top()
        automation.waitForIdle(200, 2000)
        val target = seek(text)
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        check(!bounds.isEmpty && target.isEnabled) { "Cannot click: $text" }
        runner.sendStatus(0, Bundle().apply { putString("stream", "Tap $text at $bounds\n") })
        input("tap ${bounds.centerX()} ${bounds.centerY()}")
        SystemClock.sleep(200)
    }
    private fun enter(label: String, value: String) {
        var target: AccessibilityNodeInfo? = seek(label)
        while (target != null && !target.isEditable) target = target.parent
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        check(target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true) { "Cannot enter: $label" }
        SystemClock.sleep(100)
    }
    fun verify() {
        check(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic")) { "UI smoke test is restricted to an emulator" }
        runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        automation.waitForIdle(500, 5000)
        click("阅读")
        click("新建练习")
        SystemClock.sleep(400)
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
        SystemClock.sleep(400)
        val added = runBlocking { StudyDatabase.get(runner.targetContext).dao().allCoach().single { it.title == title } }
        check(com.example.cet6.data.ReadingQuestions.parse(added.questions).isNotEmpty()) { "Question not added or not selectable: ${added.questions}" }
        click("A. Quiet rooms.")
        click("提交")
        click("错因难句")
        seek("原文证据与难句")
        enter("粘贴要核对的原文引用或难句", sentence)
        click("收藏这段原文")
        seek("本篇难句收藏")
        click("逻辑推断")
        // Exercise the background-save hook while its debounce write is still pending.
        runner.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(700)
        val saved = runBlocking { StudyDatabase.get(runner.targetContext).dao().allCoach().single { it.title == title } }
        check(saved.answer == "1. A" && saved.submittedAt > 0 && saved.feedback.isBlank())
        check(saved.savedSentences == sentence && saved.errorReason.contains("逻辑推断"))
        runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
