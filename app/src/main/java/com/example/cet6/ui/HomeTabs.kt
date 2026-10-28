package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

/** The draft most recently touched across the three skills; submitted work is not "continued". */
private fun latestDraft(vm: StudyViewModel): CoachPractice? = vm.coach.entries.values
    .filter { it.submittedAt == 0L && (it.answer.isNotBlank() || (it.kind == CoachKind.READING.name && ExamCodec.metadata(it) == null && it.material.isNotBlank())) }
    .maxByOrNull { it.updatedAt }

/** Newest catalogue set whose reading has not been started; shared reading sets point at their twin. */
private fun nextReading(vm: StudyViewModel): ExamPaper? = allPapers(vm)
    .filter { !it.sample && it.issues(CoachKind.READING).isEmpty() && PaperDocuments.sharedReadingPaperId(it) == null }
    .sortedWith(compareByDescending<ExamPaper> { it.examDate }.thenBy { it.setCode })
    .firstOrNull { paper -> vm.coach.history.none { ExamCodec.itemId(it)?.startsWith("${paper.stableId}/READING/") == true } }

private fun kindLabel(kind: String) = when (kind) {
    CoachKind.WRITING.name -> "写作"
    CoachKind.TRANSLATION.name -> "翻译"
    else -> "阅读"
}

/**
 * 今日: pick up where the learner stopped, then the few things worth doing today. Every number is counted
 * from saved records; nothing here is a goal or a prediction.
 */
@Composable
fun TodayHome(vm: StudyViewModel, padding: PaddingValues, onOpen: (CoachPractice) -> Unit, onStart: (ExamPaper, CoachKind) -> Unit,
    onRoute: (String) -> Unit) {
    val history = vm.coach.history
    val draft = latestDraft(vm)
    val due = vm.words.count { it.dueAt in 1..vm.now }
    val reviews = remember(history) { WeaknessReview.records(history) }
    val next = remember(history, vm.examPapers) { nextReading(vm) }
    val weekStart = vm.now - WEEK_MILLIS
    val week = history.filter { it.submittedAt >= weekStart }
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", Locale.CHINA))
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { TabTitle("今日", date) }
        if (draft != null) item {
            SectionLabel("继续")
            PaperCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Note("${kindLabel(draft.kind)}草稿 · ${shortTime(draft.updatedAt)} 保存", tone = Tone.Ink)
                    Text(draft.title.ifBlank { "未命名练习" }, style = MaterialTheme.typography.titleMedium)
                    if (draft.kind != CoachKind.READING.name) {
                        val task = remember(draft.material) { WritingTask.of(draft.material) }
                        val words = WritingCoach.words(draft.answer)
                        val target = task.minWords
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (target != null && draft.kind == CoachKind.WRITING.name) LinearProgressIndicator(
                                progress = { (words.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f),
                                drawStopIndicator = {})
                            Note(if (target != null && task.maxWords != null && draft.kind == CoachKind.WRITING.name)
                                "$words / ${target}–${task.maxWords} 词" else "$words 词")
                        }
                    } else Note(readingStatus(draft).label)
                    Button(onClick = { onOpen(draft) }, enabled = !vm.coach.working, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(when (draft.kind) { CoachKind.WRITING.name -> "继续写"; CoachKind.TRANSLATION.name -> "继续译"; else -> "继续做" })
                    }
                }
            }
        }
        item {
            SectionLabel("今天要做")
            RowCard {
                ListRow("单词复习", subtitle = if (vm.words.isEmpty()) "导入词库或在练习中加入生词" else "待复习 $due 词 · 来自词库与练习原句",
                    trailing = if (due > 0) "$due" else if (vm.words.isNotEmpty()) "已完成" else null,
                    trailingTone = if (due > 0) Tone.Better else Tone.Good) { onRoute("me/words") }
                RowDivider()
                ListRow("错因重练", subtitle = if (reviews.isEmpty()) "批改后记下的错因会出现在这里" else
                    reviews.map { it.errorReason.substringBefore('；').take(16) }.distinct().take(2).joinToString(" · "),
                    trailing = reviews.size.takeIf { it > 0 }?.toString(), trailingTone = Tone.Must) { onRoute("me/review") }
                if (next != null) {
                    RowDivider()
                    ListRow("下一套阅读", subtitle = "${next.examDate.take(4)} 年 ${next.examDate.substring(5, 7).toInt()} 月 · 第 ${next.setCode} 套 · 仔细阅读",
                        enabled = !vm.coach.working) { onStart(next, CoachKind.READING) }
                }
                if (!vm.aiReady) {
                    RowDivider()
                    ListRow("连接 AI", subtitle = "配置后可以批改、讲解和追问", trailing = "未连接", trailingTone = Tone.Muted) { onRoute("me/connection") }
                }
            }
        }
        item {
            SectionLabel("近 7 天")
            Row(Modifier.fillMaxWidth().padding(horizontal = CardMargin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    week.count { it.kind == CoachKind.WRITING.name } to "篇作文",
                    week.count { it.kind == CoachKind.TRANSLATION.name } to "篇翻译",
                    week.count { it.kind == CoachKind.READING.name } to "次阅读核对"
                ).forEach { (count, label) ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp).semantics(mergeDescendants = true) {}) {
                            Text("$count", style = MaterialTheme.typography.headlineSmall)
                            Note(label)
                        }
                    }
                }
            }
        }
    }
}

/** 练习: one catalogue with the three skills behind a segmented switch instead of three tabs. */
@Composable
fun PracticeHeader(kind: CoachKind, onKind: (CoachKind) -> Unit) {
    TabTitle("练习")
    val kinds = listOf(CoachKind.WRITING, CoachKind.TRANSLATION, CoachKind.READING)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = CardMargin, vertical = 8.dp)) {
        kinds.forEachIndexed { i, k ->
            SegmentedButton(selected = kind == k, onClick = { onKind(k) }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                icon = {}) { Text(kindLabel(k.name)) }
        }
    }
    Note(when (kind) {
        CoachKind.WRITING -> "六级 Part I · 30 分钟 · 150–200 词"
        CoachKind.TRANSLATION -> "六级 Part IV · 30 分钟 · 段落汉译英"
        CoachKind.READING -> "仔细阅读 · 纸上做完，再来核对和弄懂"
    }, Modifier.padding(horizontal = PageGutter))
}

/** 复盘: everything that looks back across skills — mistakes, trends, records and the word queue. */
@Composable
fun ReviewHome(vm: StudyViewModel, padding: PaddingValues, onRoute: (String) -> Unit) {
    val reviews = WeaknessReview.records(vm.coach.history)
    val due = vm.words.count { it.dueAt in 1..vm.now }
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { TabTitle("复盘") }
        item {
            SectionLabel("错题与错因")
            RowCard {
                ListRow("错因回顾", subtitle = if (reviews.isEmpty()) "记录过错因的练习会出现在这里" else "${reviews.size} 条记录",
                    onClick = { onRoute("me/review") })
                RowDivider()
                ListRow("练习记录", subtitle = "全部阅读、写作和翻译练习", onClick = { onRoute("me/records") })
            }
        }
        item {
            SectionLabel("词汇")
            RowCard {
                ListRow("六级词汇", subtitle = if (vm.words.isEmpty()) "导入词库或在练习中加入生词" else "词库 ${vm.words.size} 词 · 今日待复习 $due 词",
                    trailing = if (due > 0) "待复习 $due" else if (vm.words.isNotEmpty()) "已完成" else null,
                    trailingTone = if (due > 0) Tone.Better else Tone.Good, onClick = { onRoute("me/words") })
            }
        }
        item {
            SectionLabel("趋势")
            RowCard {
                ListRow("学习趋势", subtitle = "按周查看阅读核对、修改稿与复习自评", onClick = { onRoute("me/trends") })
            }
        }
        item {
            Text("趋势只统计本机记录，含重练与样本难度差异，不代表成绩预测。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PageGutter, vertical = 16.dp))
        }
    }
}
