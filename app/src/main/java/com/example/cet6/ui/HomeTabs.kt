package com.example.cet6.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
// import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.LocalMarks
import com.example.cet6.ui.theme.NumeralText
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
    .firstOrNull { paper -> vm.coach.history.none {
        ExamCodec.itemId(it)?.startsWith("${paper.stableId}/READING/") == true && ExamCodec.hasReadingProgress(it)
    } }

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
        item {
            Spacer(Modifier.height(12.dp))
            WeekSheet(week.size, listOf(
                "写作" to week.count { it.kind == CoachKind.WRITING.name },
                "翻译" to week.count { it.kind == CoachKind.TRANSLATION.name },
                "阅读" to week.count { it.kind == CoachKind.READING.name }))
        }
        if (draft != null) item {
            SectionLabel("继续")
            PaperCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(kindIcon(draft.kind), kindTile(draft.kind))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("${kindLabel(draft.kind)}草稿", style = MaterialTheme.typography.titleSmall)
                            Note("${shortTime(draft.updatedAt)} 保存")
                        }
                    }
                    Text(draft.title.ifBlank { "未命名练习" }, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    if (draft.kind != CoachKind.READING.name) {
                        val task = remember(draft.material) { WritingTask.of(draft.material) }
                        val words = WritingCoach.words(draft.answer)
                        val target = task.minWords
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (target != null && draft.kind == CoachKind.WRITING.name) LinearProgressIndicator(
                                progress = { (words.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh, drawStopIndicator = {})
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
            SectionLabel("今天的一页")
            RowCard {
                ListRow("单词复习", subtitle = if (vm.words.isEmpty()) "导入词库或在练习中加入生词" else "来自词库与练习原句",
                    trailing = if (vm.words.isEmpty()) null else "$due", trailingTone = if (due > 0) Tone.Ink else Tone.Muted,
                    trailingDescription = "待复习 $due 词", subtitleLines = 1,
                    leading = { IconTile(Icons.Outlined.Style, TileColors.Teal) }) { onRoute("me/words") }
                GroupDivider(TiledRowDividerStart)
                ListRow("错因重练", subtitle = if (reviews.isEmpty()) "批改后记下的错因会出现在这里" else
                        reviews.map { it.errorReason.substringBefore("；").take(16) }.distinct().take(2).joinToString(" · "),
                    trailing = "${reviews.size}", trailingTone = if (reviews.isEmpty()) Tone.Muted else Tone.Must,
                    trailingDescription = "${reviews.size} 条错因", subtitleLines = 1,
                    leading = { IconTile(Icons.Outlined.ErrorOutline, TileColors.Red) }) { onRoute("me/review") }
                if (next != null) {
                    GroupDivider(TiledRowDividerStart)
                    ListRow("下一套阅读",
                        subtitle = "${next.examDate.take(4)} 年 ${next.examDate.substring(5, 7).toInt()} 月 · 第 ${next.setCode} 套 · 仔细阅读",
                        enabled = !vm.coach.working, subtitleLines = 1,
                        leading = { IconTile(Icons.AutoMirrored.Outlined.MenuBook, TileColors.Orange) }) { onStart(next, CoachKind.READING) }
                }
                if (!vm.aiReady) {
                    GroupDivider(TiledRowDividerStart)
                    ListRow("连接 AI", subtitle = "配置后可以批改、讲解和追问", subtitleLines = 1,
                        leading = { IconTile(Icons.Outlined.Hub, TileColors.Blue) }) { onRoute("me/connection") }
                }
            }
        }
    }
}

/**
 * The ink sheet at the top of 今日: submissions in the last seven days, circled with the logo's vermilion ring,
 * and how they split across the three skills. Counts come from saved records only.
 */
@Composable
private fun WeekSheet(total: Int, perSkill: List<Pair<String, Int>>) {
    val ink = InkSheet
    val onInk = OnInkSheet
    val brand = LocalMarks.current.brand
    val ring = remember(brand) { BrandIcons.ring(brand) }
    Surface(color = ink, contentColor = onInk, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CardMargin)) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)/* .semantics(mergeDescendants = true) {} */,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("近 7 天提交", style = MaterialTheme.typography.bodySmall, color = onInk.copy(alpha = 0.72f))
                Row(verticalAlignment = Alignment.Bottom) {
                    // The ring stretches with the number, so two or three digits stay circled.
                    Box(contentAlignment = Alignment.Center) {
                        Image(ring, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
                        Text("$total", style = NumeralText.copy(fontSize = 56.sp, lineHeight = 62.sp), color = onInk,
                            modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp))
                    }
                    Text("次", style = MaterialTheme.typography.bodyLarge, color = onInk, modifier = Modifier.padding(start = 2.dp, bottom = 16.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                perSkill.forEach { (label, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = onInk.copy(alpha = 0.72f))
                        Text("$count", style = NumeralText.copy(fontSize = 20.sp, lineHeight = 24.sp), color = onInk,
                            modifier = Modifier.widthIn(min = 22.dp))
                    }
                }
            }
        }
    }
}

private fun kindIcon(kind: String) = when (kind) {
    CoachKind.WRITING.name -> Icons.Outlined.EditNote
    CoachKind.TRANSLATION.name -> Icons.Outlined.Translate
    else -> Icons.AutoMirrored.Outlined.MenuBook
}

private fun kindTile(kind: String) = when (kind) {
    CoachKind.WRITING.name -> TileColors.Blue
    CoachKind.TRANSLATION.name -> TileColors.Indigo
    else -> TileColors.Orange
}

/** 练习: large title and a three-way skill switch; the catalogue below carries the content. */
@Composable
fun PracticeHeader(kind: CoachKind, onKind: (CoachKind) -> Unit) {
    val kinds = listOf(CoachKind.WRITING, CoachKind.TRANSLATION, CoachKind.READING)
    Column {
        TabTitle("练习")
        DotTabs(listOf("写作", "翻译", "阅读"), kinds.indexOf(kind), { onKind(kinds[it]) },
            Modifier.padding(horizontal = CardMargin - 10.dp, vertical = 4.dp))
    }
}

/** 复盘: everything that looks back across skills — mistakes, trends, records and the word queue. */
@Composable
fun ReviewHome(vm: StudyViewModel, padding: PaddingValues, onRoute: (String) -> Unit) {
    val reviews = WeaknessReview.records(vm.coach.history)
    val due = vm.words.count { it.dueAt in 1..vm.now }
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { TabTitle("复盘") }
        item {
            Row(Modifier.fillMaxWidth().padding(start = CardMargin, end = CardMargin, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReviewCount("待回顾错因", reviews.size, LocalMarks.current.must.takeIf { reviews.isNotEmpty() }, Modifier.weight(1f))
                ReviewCount("已提交", vm.coach.history.count { it.submittedAt > 0 }, null, Modifier.weight(1f))
                ReviewCount("待复习词", due, null, Modifier.weight(1f))
            }
        }
        item {
            SectionLabel("错题与错因")
            RowCard {
                ListRow("错因回顾", subtitle = if (reviews.isEmpty()) "记录过错因的练习会出现在这里" else null,
                    trailing = if (reviews.isEmpty()) null else "${reviews.size} 条",
                    leading = { IconTile(Icons.Outlined.ErrorOutline, TileColors.Red) }, onClick = { onRoute("me/review") })
                GroupDivider(TiledRowDividerStart)
                ListRow("练习记录", leading = { IconTile(Icons.Outlined.History, TileColors.Gray) }, onClick = { onRoute("me/records") })
            }
        }
        item {
            SectionLabel("词汇")
            RowCard {
                ListRow("六级词汇", subtitle = if (vm.words.isEmpty()) "导入词库或在练习中加入生词" else "词库 ${vm.words.size} 词",
                    trailing = if (due > 0) "待复习 $due" else if (vm.words.isNotEmpty()) "已完成" else null,
                    trailingTone = if (due > 0) Tone.Better else Tone.Good,
                    leading = { IconTile(Icons.Outlined.Style, TileColors.Teal) }, onClick = { onRoute("me/words") })
            }
        }
        item {
            SectionLabel("趋势")
            RowCard {
                ListRow("学习趋势", subtitle = "按周查看阅读核对、修改稿与复习自评",
                    leading = { IconTile(Icons.AutoMirrored.Outlined.ShowChart, TileColors.Green) }, onClick = { onRoute("me/trends") })
            }
            GroupFooter("趋势只统计本机记录，含重练与样本难度差异，不代表成绩预测。", Modifier.padding(horizontal = CardMargin))
        }
    }
}

/** One count on a white sheet, set in the serif numerals; vermilion only when it asks for attention. */
@Composable
private fun ReviewCount(label: String, count: Int, tone: Color?, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)/* .semantics(mergeDescendants = true) {} */) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("$count", style = NumeralText, color = tone ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}
