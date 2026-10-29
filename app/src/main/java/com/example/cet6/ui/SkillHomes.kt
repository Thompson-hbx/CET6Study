package com.example.cet6.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*

/** Status of one skill on one paper, read from saved attempts; nothing is inferred from feedback text. */
data class TaskStatus(val label: String, val tone: Tone, val spoken: String? = null)

private fun latestAttempt(history: List<CoachPractice>, itemId: String) =
    history.filter { ExamCodec.itemId(it) == itemId }.maxByOrNull { it.updatedAt }

private fun writtenStatus(history: List<CoachPractice>, bands: Map<String, Int>, itemId: String): TaskStatus? {
    val attempts = history.filter { ExamCodec.itemId(it) == itemId && (it.answer.isNotBlank() || it.submittedAt > 0) }
    if (attempts.isEmpty()) return null
    attempts.filter { it.submittedAt > 0 }.sortedByDescending { it.submittedAt }.firstNotNullOfOrNull { bands[it.id] }
        ?.let { return TaskStatus("$it 分档", Tone.Ink) }
    if (attempts.any { it.submittedAt > 0 }) return TaskStatus("已提交", Tone.Muted)
    return TaskStatus("草稿 ${WritingCoach.words(attempts.maxBy { it.updatedAt }.answer)} 词", Tone.Better)
}

private fun sessionTitle(date: String) = "${date.take(4)} 年 ${date.substring(5, 7).toInt()} 月"

/** Skill home for writing or translation: one card per exam session, newest first. */
@Composable
fun TaskHome(vm: StudyViewModel, kind: CoachKind, padding: PaddingValues, onOpen: (CoachPractice) -> Unit,
    onStart: (ExamPaper) -> Unit, onCustom: () -> Unit, header: @Composable () -> Unit) {
    val history = vm.coach.history
    val bands = remember(vm.reviewTurns) { latestBands(vm.reviewTurns) }
    val papers = allPapers(vm)
    val bySession = papers.filter { !it.sample }.groupBy { it.examDate }.toSortedMap(compareByDescending { it })
    val samples = papers.filter { it.sample && it.issues(kind).isEmpty() }
    val draft = vm.coach.entries[kind]?.takeIf { it.submittedAt == 0L && it.answer.isNotBlank() }
    val mine = history.filter { it.kind == kind.name && it.answer.isNotBlank() }.take(3)
    var filter by rememberSaveable(kind) { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { header() }
        if (draft != null) item {
            SectionLabel("继续")
            RowCard {
                ListRow(draft.title.ifBlank { if (kind == CoachKind.WRITING) "自拟题目" else "自选段落" },
                    subtitle = "${WritingCoach.words(draft.answer)} 词 · ${shortTime(draft.updatedAt)} 保存",
                    trailing = "草稿", trailingTone = Tone.Better) { onOpen(draft) }
            }
        }
        val rows = bySession.mapValues { (_, sets) -> sets.sortedBy { it.setCode }.map { paper ->
            val available = paper.issues(kind).isEmpty()
            CatalogEntry(paper, available, if (available) writtenStatus(history, bands, "${paper.stableId}/${kind.name}/1") else null)
        } }
        item(key = "filters") { CatalogFilters(rows.values.flatten(), filter) { filter = it } }
        rows.forEach { (date, entries) ->
            val shown = entries.filter { it.matches(filter) }
            if (shown.isNotEmpty()) item(key = "session-$date") {
                SectionLabel(sessionTitle(date))
                shown.forEachIndexed { i, row ->
                    key("${kind.name}-${row.paper.stableId}") {
                        val itemId = "${row.paper.stableId}/${kind.name}/1"
                        CatalogRow("第 ${row.paper.setCode} 套", if (row.available) taskLine(row.paper, kind) else unavailable(row.paper),
                            row.status?.label, row.status?.tone ?: Tone.Muted, enabled = row.available && !vm.coach.working) {
                            latestAttempt(history, itemId)?.let(onOpen) ?: onStart(row.paper)
                        }
                    }
                    if (i < shown.lastIndex) CatalogDivider()
                }
            }
        }
        if (samples.isNotEmpty()) item(key = "samples") {
            SectionLabel("功能样本（原创，非真题）")
            RowCard {
                samples.forEachIndexed { i, paper ->
                    key("sample-${kind.name}-${paper.stableId}") {
                        val itemId = "${paper.stableId}/${kind.name}/1"
                        val status = writtenStatus(history, bands, itemId)
                        ListRow("原创样本", subtitle = taskLine(paper, kind), trailing = status?.label, trailingTone = status?.tone ?: Tone.Muted,
                            enabled = !vm.coach.working) { latestAttempt(history, itemId)?.let(onOpen) ?: onStart(paper) }
                    }
                    if (i < samples.lastIndex) RowDivider()
                }
            }
        }
        item {
            SectionLabel("自己的题目")
            RowCard {
                ListRow(if (kind == CoachKind.WRITING) "用自己的作文题练习" else "粘贴一段中文练习翻译",
                    subtitle = "批改、追问和修改稿与真题一样", enabled = !vm.coach.working, onClick = onCustom)
            }
        }
        if (mine.isNotEmpty()) item {
            SectionLabel(if (kind == CoachKind.WRITING) "最近写的" else "最近译的")
            RowCard {
                mine.forEachIndexed { i, practice ->
                    ListRow(practice.title.ifBlank { "未命名" },
                        subtitle = "${WritingCoach.words(practice.answer)} 词 · ${shortTime(practice.updatedAt)}",
                        trailing = bands[practice.id]?.let { "$it 分档" } ?: if (practice.submittedAt > 0) "已提交" else "草稿",
                        trailingTone = if (bands[practice.id] != null) Tone.Ink else Tone.Muted, enabled = !vm.coach.working) { onOpen(practice) }
                    if (i < mine.lastIndex) RowDivider()
                }
            }
        }
    }
}

private fun taskLine(paper: ExamPaper, kind: CoachKind): String = when (kind) {
    CoachKind.WRITING -> WritingTask.of("${paper.writingPrompt}\n\n${paper.writingInstructions}").opening
        ?: paper.writingPrompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    CoachKind.TRANSLATION -> TranslationCoach.sentences(paper.chineseText).firstOrNull().orEmpty()
    CoachKind.READING -> paper.passages.joinToString(" · ") { p -> p.content.trim().take(40) + "…" }
}

private fun unavailable(paper: ExamPaper) = when (paper.status) {
    "待导入" -> "未导入，可在「我的 → 题库管理」导入"
    else -> "资料待校对"
}

/** Reading home: papers by session; each opens the two-passage answer card. */
@Composable
fun ReadingHome(vm: StudyViewModel, padding: PaddingValues, onOpen: (CoachPractice) -> Unit, onStart: (ExamPaper) -> Unit,
    onShared: (String) -> Unit, onCustom: () -> Unit, header: @Composable () -> Unit) {
    val history = vm.coach.history
    val papers = allPapers(vm)
    val bySession = papers.filter { !it.sample }.groupBy { it.examDate }.toSortedMap(compareByDescending { it })
    val samples = papers.filter { it.sample && it.issues(CoachKind.READING).isEmpty() }
    val draft = vm.coach.entries[CoachKind.READING]?.takeIf {
        it.submittedAt == 0L && (it.answer.isNotBlank() || (ExamCodec.metadata(it) == null && it.material.isNotBlank()))
    }
    val mine = history.filter {
        it.kind == CoachKind.READING.name && (it.answer.isNotBlank() || (ExamCodec.metadata(it) == null && it.material.isNotBlank()))
    }.take(3)
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { header() }
        if (draft != null) item {
            val status = if (ExamCodec.metadata(draft) != null) readingStatus(draft) else TaskStatus("草稿", Tone.Better)
            SectionLabel("继续")
            RowCard {
                ListRow(draft.title.ifBlank { "自己的文章" },
                    subtitle = "${shortTime(draft.updatedAt)} 保存",
                    trailing = status.label, trailingTone = status.tone, trailingDescription = status.spoken) { onOpen(draft) }
            }
        }
        val rows = bySession.mapValues { (_, sets) -> sets.sortedBy { it.setCode }.map { paper ->
            val attempt = readingAttempt(history, paper)
            CatalogEntry(paper, paper.issues(CoachKind.READING).isEmpty() || PaperDocuments.sharedReadingPaperId(paper) != null,
                attempt?.let { readingStatus(it) }, inProgress = attempt != null && attempt.submittedAt == 0L)
        } }
        item(key = "filters") { CatalogFilters(rows.values.flatten(), filter) { filter = it } }
        rows.forEach { (date, entries) ->
            val shown = entries.filter { it.matches(filter) }
            if (shown.isNotEmpty()) item(key = "session-$date") {
                SectionLabel(sessionTitle(date))
                shown.forEachIndexed { i, row ->
                    key("reading-${row.paper.stableId}") { ReadingPaperRow(vm, history, row.paper, onOpen, onStart, onShared) }
                    if (i < shown.lastIndex) CatalogDivider()
                }
            }
        }
        if (samples.isNotEmpty()) item(key = "samples") {
            SectionLabel("功能样本（原创，非真题）")
            RowCard {
                samples.forEachIndexed { i, paper ->
                    key("reading-sample-${paper.stableId}") { ReadingPaperRow(vm, history, paper, onOpen, onStart, onShared) }
                    if (i < samples.lastIndex) RowDivider()
                }
            }
        }
        item {
            SectionLabel("自己的文章")
            RowCard {
                ListRow("粘贴一篇英文文章练习", subtitle = "添加题目、独立作答后可请求讲解", enabled = !vm.coach.working, onClick = onCustom)
            }
        }
        if (mine.isNotEmpty()) item {
            SectionLabel("最近读的")
            RowCard {
                mine.forEachIndexed { i, practice ->
                    val status = if (ExamCodec.metadata(practice) != null) readingStatus(practice)
                        else TaskStatus(if (practice.submittedAt > 0) "已提交" else "草稿", if (practice.submittedAt > 0) Tone.Ink else Tone.Better)
                    ListRow(practice.title.ifBlank { "自己的文章" },
                        subtitle = listOfNotNull(
                            practice.practiceGoal.takeIf(String::isNotBlank)?.let { "目标：$it" },
                            shortTime(practice.updatedAt)
                        ).joinToString(" · "),
                        trailing = status.label, trailingTone = status.tone, trailingDescription = status.spoken,
                        enabled = !vm.coach.working) { onOpen(practice) }
                    if (i < mine.lastIndex) RowDivider()
                }
            }
        }
    }
}

@Composable
private fun ReadingPaperRow(vm: StudyViewModel, history: List<CoachPractice>, paper: ExamPaper, onOpen: (CoachPractice) -> Unit,
    onStart: (ExamPaper) -> Unit, onShared: (String) -> Unit) {
    val shared = PaperDocuments.sharedReadingPaperId(paper)
    val available = paper.issues(CoachKind.READING).isEmpty()
    val attempt = readingAttempt(history, paper)
    val status = attempt?.let { readingStatus(it) }
    CatalogRow(if (paper.sample) "样本" else "第 ${paper.setCode} 套",
        when {
            shared != null -> "阅读共用第 1 套，按第 1 套题序练习"
            available -> paper.passages.minByOrNull { it.code }?.let { openingLine(it.content) }.orEmpty()
            else -> unavailable(paper)
        },
        status?.label, status?.tone ?: Tone.Muted, statusDescription = status?.spoken,
        enabled = (available || shared != null) && !vm.coach.working) {
        when {
            shared != null -> onShared(shared)
            attempt != null -> onOpen(attempt)
            else -> onStart(paper)
        }
    }
}

private fun readingAttempt(history: List<CoachPractice>, paper: ExamPaper) =
    history.filter { ExamCodec.itemId(it)?.startsWith("${paper.stableId}/READING/") == true }.maxByOrNull { it.updatedAt }

/** One catalogue set with its status; [inProgress] marks a started but unfinished attempt. */
private data class CatalogEntry(val paper: ExamPaper, val available: Boolean, val status: TaskStatus?,
    val inProgress: Boolean = status != null && (status.tone == Tone.Better || status.label == "已提交")) {
    fun matches(filter: Int) = when (filter) {
        1 -> available && status == null
        2 -> inProgress
        else -> true
    }
}

/** 全部 / 未做 / 进行中, counted over the sets on this list. */
@Composable
private fun CatalogFilters(entries: List<CatalogEntry>, selected: Int, onSelect: (Int) -> Unit) {
    FilterPills(listOf("全部 ${entries.count { it.available }}", "未做 ${entries.count { it.matches(1) }}", "进行中 ${entries.count { it.matches(2) }}"),
        selected, onSelect, Modifier.padding(start = PageGutter, end = PageGutter, top = 14.dp))
}

/** Opening of a passage, enough to recognise it; the row cuts it to one line. */
private fun openingLine(content: String) = PassageLayout.paragraphs(content).firstOrNull().orEmpty().take(160)

fun readingStatus(entry: CoachPractice): TaskStatus {
    val questions = ExamCodec.passages(entry).flatMap { it.questions }
    val answered = questions.count { ReadingQuestions.selected(entry.answer, "${it.number}").orEmpty().isNotBlank() }
    if (entry.submittedAt == 0L) return if (answered == 0) TaskStatus("未录入", Tone.Better)
        else TaskStatus("已录 $answered/${questions.size}", Tone.Better, "已录入 $answered 题，共 ${questions.size} 题")
    val known = questions.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() }
    val right = known.count { ReadingQuestions.selected(entry.answer, "${it.number}") == it.referenceAnswer }
    return if (known.isEmpty()) TaskStatus("已核对", Tone.Ink)
        else TaskStatus("$right/${known.size}", Tone.Ink, "已核对，${known.size} 题中 $right 题与参考一致")
}

/** The 我的 tab as an iOS settings list: an overview cell, then connection, data and older screens. */
@Composable
fun MeHome(vm: StudyViewModel, padding: PaddingValues, onRoute: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { TabTitle("我的") }
        item {
            Spacer(Modifier.height(12.dp))
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).background(TileColors.Blue, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.School, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("六级研习室", style = MaterialTheme.typography.titleLarge)
                        Text("${vm.coach.history.count { it.submittedAt > 0 }} 次提交 · 学习数据只保存在本机",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            SectionLabel("AI 连接")
            RowCard {
                ListRow("AI 服务", subtitle = when {
                    !vm.aiReady -> "未连接"
                    vm.chosenModel.isBlank() -> "已连接 · 未选择模型"
                    else -> "已连接 · ${vm.models.firstOrNull { it.slug == vm.chosenModel }?.displayName ?: vm.chosenModel}"
                }, trailing = vm.provider.label, leading = { IconTile(Icons.Outlined.Hub, TileColors.Blue) }) { onRoute("me/connection") }
            }
        }
        item {
            SectionLabel("数据")
            RowCard {
                ListRow("备份与恢复", leading = { IconTile(Icons.Outlined.Backup, TileColors.Green) }, onClick = { onRoute("me/backup") })
                GroupDivider(TiledRowDividerStart)
                ListRow("题库管理", trailing = "${vm.examPapers.size} 套",
                    leading = { IconTile(Icons.AutoMirrored.Outlined.LibraryBooks, TileColors.Orange) }, onClick = { onRoute("me/exams") })
            }
            GroupFooter("完整备份包含学习记录与个人题库，不含登录凭证或 API Key。", Modifier.padding(horizontal = CardMargin))
        }
        item {
            SectionLabel("其他")
            RowCard {
                ListRow("单词与旧版功能", leading = { IconTile(Icons.AutoMirrored.Outlined.MenuBook, TileColors.Indigo) }, onClick = { onRoute("me/legacy") })
                GroupDivider(TiledRowDividerStart)
                ListRow("旧版作文记录", leading = { IconTile(Icons.Outlined.Description, TileColors.Gray) }, onClick = { onRoute("me/essays") })
            }
        }
    }
}
