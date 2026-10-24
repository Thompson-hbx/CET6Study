package com.example.cet6.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cet6.PaperPdfActivity
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun rememberTurns(vm: StudyViewModel, practiceId: String): List<CoachTurn> {
    val flow = remember(practiceId) { vm.talk.turns(practiceId) }
    return flow.collectAsState(initial = emptyList()).value
}

/** All catalogue slots, with imported content where present, then original samples. */
fun allPapers(vm: StudyViewModel): List<ExamPaper> {
    val imported = vm.examPapers.associateBy { it.stableId }
    return ExamCatalog.papers().map { imported[it.stableId] ?: it } + vm.examPapers.filter { it.sample }
}

fun paperOf(vm: StudyViewModel, entry: CoachPractice): ExamPaper? {
    val item = ExamCodec.itemId(entry) ?: return null
    val id = item.substringBefore('/')
    return vm.examPapers.firstOrNull { it.stableId == id }
}

/** Every saved attempt at the same task, oldest first; drafts come after submitted versions. */
fun versionsOf(history: List<CoachPractice>, entry: CoachPractice): List<CoachPractice> {
    val item = ExamCodec.itemId(entry)
    val related = if (item != null) history.filter { ExamCodec.itemId(it) == item } else {
        val byId = history.associateBy { it.id }
        val chain = mutableSetOf(entry.id)
        var cursor: CoachPractice? = entry
        while (cursor != null && cursor.sourcePracticeId.isNotBlank() && chain.add(cursor.sourcePracticeId)) cursor = byId[cursor.sourcePracticeId]
        var grew = true
        while (grew) { grew = false; history.forEach { if (it.sourcePracticeId in chain && chain.add(it.id)) grew = true } }
        history.filter { it.id in chain }
    }
    val withCurrent = (related.filter { it.id != entry.id } + entry).filter { it.answer.isNotBlank() || it.id == entry.id }
    return withCurrent.sortedWith(compareBy({ it.submittedAt == 0L }, { it.submittedAt }, { it.updatedAt }))
}

fun shortTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

/** A 30-minute exam clock; it is a study aid and never locks the editor. */
@Composable
fun ExamClock(key: String, minutes: Int = 30) {
    var startedAt by rememberSaveable(key) { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (startedAt > 0) { now = System.currentTimeMillis(); delay(1000) } }
    val left = minutes * 60 - ((now - startedAt) / 1000).coerceAtLeast(0)
    // The face alone ("30 分钟", "12:04") does not say it is a timer or what tapping does.
    val spoken = when {
        startedAt == 0L -> "考试计时 $minutes 分钟，点按开始"
        left >= 0 -> "计时中，剩余 ${left / 60} 分 ${left % 60} 秒，点按停止"
        else -> "已超时 ${-left / 60} 分 ${-left % 60} 秒，点按停止"
    }
    TextButton(onClick = { startedAt = if (startedAt == 0L) System.currentTimeMillis().also { now = it } else 0L },
        modifier = Modifier.semantics { contentDescription = spoken }) {
        Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(when {
            startedAt == 0L -> "$minutes 分钟"
            left >= 0 -> "%d:%02d".format(left / 60, left % 60)
            else -> "超时 %d:%02d".format(-left / 60, -left % 60)
        }, style = MaterialTheme.typography.labelLarge, color = when {
            startedAt == 0L -> MaterialTheme.colorScheme.primary
            left < 0 -> Tone.Must.color()
            left < 300 -> Tone.Better.color()
            else -> MaterialTheme.colorScheme.primary
        })
    }
}

/** Overflow menu shared by workspaces: original PDFs and other secondary actions. */
@Composable
fun WorkspaceMenu(vm: StudyViewModel, entry: CoachPractice, extra: List<Pair<String, () -> Unit>> = emptyList()) {
    val context = LocalContext.current
    val bundled = remember(context) { context.assets.list("personal-papers").orEmpty().toSet() }
    val documents = paperOf(vm, entry)?.let { PaperDocuments.filenames(it).filter { name -> name in bundled } }.orEmpty()
    if (documents.isEmpty() && extra.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "更多") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            extra.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
            documents.forEach { name ->
                DropdownMenuItem(text = { Text(if (name.endsWith("_ans.pdf")) "参考答案 PDF" else "原卷 PDF") }, onClick = {
                    open = false
                    context.startActivity(Intent(context, PaperPdfActivity::class.java).putExtra("document", name))
                })
            }
        }
    }
}

/** Version list for one task; opening one replaces the current workspace entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionSheet(versions: List<CoachPractice>, currentId: String, bands: Map<String, Int>, onOpen: (CoachPractice) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("各稿", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp))
            versions.forEachIndexed { i, version ->
                val words = WritingCoach.words(version.answer)
                val status = when {
                    version.submittedAt == 0L -> "草稿"
                    bands[version.id] != null -> "${bands[version.id]} 分档"
                    else -> "已提交"
                }
                ListRow("第 ${i + 1} 稿" + if (version.id == currentId) " · 当前" else "",
                    subtitle = "$words 词 · ${shortTime(if (version.submittedAt > 0) version.submittedAt else version.updatedAt)}",
                    trailing = status, trailingTone = if (bands[version.id] != null) Tone.Ink else Tone.Muted,
                    enabled = version.id != currentId) { onOpen(version) }
            }
        }
    }
}

@Composable
fun SubmitDialog(words: Int, minWords: Int?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("提交这一稿？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("提交后这一稿会锁定，用来批改和对照；之后的修改会另存为修改稿。")
                if (minWords != null && words < minWords) Text("当前 $words 词，少于题目要求的 $minWords 词。", color = Tone.Better.color())
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("提交") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("再改改") } })
}

/** Count with a target, e.g. "126 词 · 目标 150–200"; colour only signals being outside the range. */
@Composable
fun WordCount(words: Int, min: Int?, max: Int?, modifier: Modifier = Modifier) {
    val tone = when {
        min == null || words == 0 -> Tone.Muted
        words < min -> Tone.Muted
        max != null && words > max -> Tone.Better
        else -> Tone.Good
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$words 词", style = MaterialTheme.typography.titleSmall, color = tone.color())
        if (min != null && max != null) Note("目标 $min–$max")
    }
}
