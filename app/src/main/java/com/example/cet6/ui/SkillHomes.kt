package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
// import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
// import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.cet6.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.LocalMarks
import com.example.cet6.ui.theme.NumeralText

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

/** Skill home for writing or translation: the one thing to do next, then every exam set as a cell of one matrix. */
@Composable
fun TaskHome(vm: StudyViewModel, kind: CoachKind, padding: PaddingValues, onOpen: (CoachPractice) -> Unit,
    onStart: (ExamPaper) -> Unit, onCustom: () -> Unit, header: @Composable () -> Unit) {
    val history = vm.coach.history
    val bands = remember(vm.reviewTurns) { latestBands(vm.reviewTurns) }
    val papers = remember(vm.examPapers) { allPapers(vm) }
    val bySession = remember(papers) { papers.filter { !it.sample }.groupBy { it.examDate }.toSortedMap(compareByDescending { it }) }
    val samples = remember(papers, kind) { papers.filter { it.sample && it.issues(kind).isEmpty() } }
    val draft = vm.coach.entries[kind]?.takeIf { it.submittedAt == 0L && it.answer.isNotBlank() }
    val mine = history.filter { it.kind == kind.name && it.answer.isNotBlank() }.take(3)
    val rows = remember(bySession, history, bands, kind) {
        bySession.mapValues { (_, sets) -> sets.sortedBy { it.setCode }.map { paper ->
            val available = paper.issues(kind).isEmpty()
            CatalogEntry(paper, available, if (available) writtenStatus(history, bands, "${paper.stableId}/${kind.name}/1") else null)
        } }
    }
    val next = rows.values.flatten().firstOrNull { it.available && it.status == null }?.paper
    val verb = if (kind == CoachKind.WRITING) "写" else "译"
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { header() }
        item(key = "next") {
            when {
                draft != null -> NextStep("继续草稿", draft.title.ifBlank { if (kind == CoachKind.WRITING) "自拟题目" else "自选段落" },
                    "${WritingCoach.words(draft.answer)} 词 · ${shortTime(draft.updatedAt)} 保存", "继续$verb", !vm.coach.working) { onOpen(draft) }
                next != null -> NextStep("下一套未做", next.label, taskMeta(next, kind), "开始$verb", !vm.coach.working) { onStart(next) }
            }
        }
        if (rows.isNotEmpty()) item(key = "matrix") {
            MatrixLabel(rows)
            SetMatrix(rows.map { (date, entries) -> date to entries.map { row ->
                val itemId = "${row.paper.stableId}/${kind.name}/1"
                matrixCell(row, if (row.available) null else unavailable(row.paper), !vm.coach.working) {
                    latestAttempt(history, itemId)?.let(onOpen) ?: onStart(row.paper)
                }
            } })
        }
        item(key = "more") {
            SectionLabel("更多练习")
            RowCard {
                ListRow(if (kind == CoachKind.WRITING) "自拟题目练习" else "自选段落练习", subtitle = "批改、追问和修改稿与真题一样",
                    trailing = "新建", trailingTone = Tone.Ink, enabled = !vm.coach.working) { onCustom() }
                samples.forEach { paper ->
                    key("sample-${kind.name}-${paper.stableId}") {
                        val itemId = "${paper.stableId}/${kind.name}/1"
                        val status = writtenStatus(history, bands, itemId)
                        GroupDivider()
                        ListRow("原创样本（非真题）", subtitle = taskLine(paper, kind), subtitleLines = 1,
                            trailing = status?.label, trailingTone = status?.tone ?: Tone.Muted, enabled = !vm.coach.working) {
                            latestAttempt(history, itemId)?.let(onOpen) ?: onStart(paper)
                        }
                    }
                }
            }
        }
        if (mine.isNotEmpty()) item(key = "mine") {
            SectionLabel(if (kind == CoachKind.WRITING) "最近写的" else "最近译的")
            RowCard {
                mine.forEachIndexed { i, practice ->
                    if (i > 0) GroupDivider()
                    ListRow(practice.title.ifBlank { "未命名" },
                        subtitle = "${WritingCoach.words(practice.answer)} 词 · ${shortTime(practice.updatedAt)}",
                        trailing = bands[practice.id]?.let { "$it 分档" } ?: if (practice.submittedAt > 0) "已提交" else "草稿",
                        trailingTone = if (bands[practice.id] != null) Tone.Ink else Tone.Muted,
                        enabled = !vm.coach.working) { onOpen(practice) }
                }
            }
        }
    }
}

private fun taskMeta(paper: ExamPaper, kind: CoachKind): String = when (kind) {
    CoachKind.WRITING -> {
        val t = WritingTask.of("${paper.writingPrompt}\n\n${paper.writingInstructions}")
        if (t.minWords != null && t.maxWords != null) "Part I 写作 · ${t.minWords}–${t.maxWords} 词" else "Part I 写作"
    }
    CoachKind.TRANSLATION -> "Part IV 翻译 · 共 ${TranslationCoach.sentences(paper.chineseText).size} 句原文"
    CoachKind.READING -> "Part III 仔细阅读 · ${paper.passages.size} 篇"
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

/** Reading home: the next set to do, then the same session × set matrix; each cell opens the two-passage answer card. */
@Composable
fun ReadingHome(vm: StudyViewModel, padding: PaddingValues, onOpen: (CoachPractice) -> Unit, onStart: (ExamPaper) -> Unit,
    onShared: (String) -> Unit, onCustom: () -> Unit, header: @Composable () -> Unit) {
    val history = vm.coach.history
    val papers = remember(vm.examPapers) { allPapers(vm) }
    val bySession = remember(papers) { papers.filter { !it.sample }.groupBy { it.examDate }.toSortedMap(compareByDescending { it }) }
    val samples = remember(papers) { papers.filter { it.sample && it.issues(CoachKind.READING).isEmpty() } }
    val attempts = remember(history, papers) { papers.associate { it.stableId to readingAttempt(history, it) } }
    val draft = vm.coach.entries[CoachKind.READING]?.takeIf {
        it.submittedAt == 0L && (it.answer.isNotBlank() || (ExamCodec.metadata(it) == null && it.material.isNotBlank()))
    }
    val mine = history.filter {
        it.kind == CoachKind.READING.name && (it.answer.isNotBlank() || (ExamCodec.metadata(it) == null && it.material.isNotBlank()))
    }.take(3)
    val rows = remember(bySession, attempts) {
        bySession.mapValues { (_, sets) -> sets.sortedBy { it.setCode }.map { paper ->
            val attempt = attempts[paper.stableId]
            CatalogEntry(paper, paper.issues(CoachKind.READING).isEmpty() || PaperDocuments.sharedReadingPaperId(paper) != null,
                attempt?.takeIf(ExamCodec::hasReadingProgress)?.let { readingStatus(it) },
                practiced = attempt?.let(ExamCodec::hasReadingProgress) == true)
        } }
    }
    val next = rows.values.flatten().firstOrNull {
        it.status == null && it.paper.issues(CoachKind.READING).isEmpty() && PaperDocuments.sharedReadingPaperId(it.paper) == null
    }?.paper
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { header() }
        item(key = "next") {
            when {
                draft != null -> NextStep("继续阅读", draft.title.ifBlank { "自己的文章" },
                    listOfNotNull(ExamCodec.metadata(draft)?.let { readingStatus(draft).label }, "${shortTime(draft.updatedAt)} 保存").joinToString(" · "),
                    "继续做", !vm.coach.working) { onOpen(draft) }
                next != null -> NextStep("下一套未做", next.label, taskMeta(next, CoachKind.READING), "开始做", !vm.coach.working) { onStart(next) }
            }
        }
        if (rows.isNotEmpty()) item(key = "matrix") {
            MatrixLabel(rows)
            SetMatrix(rows.map { (date, entries) -> date to entries.map { row ->
                val shared = PaperDocuments.sharedReadingPaperId(row.paper)
                val attempt = attempts[row.paper.stableId]
                when {
                    shared != null -> MatrixCell(row.paper.setCode, "同第 1 套", null, true, !vm.coach.working,
                        "阅读与第 1 套相同，按第 1 套练习", linked = true) { onShared(shared) }
                    else -> matrixCell(row, if (row.available) null else unavailable(row.paper), !vm.coach.working) {
                        if (attempt != null) onOpen(attempt) else onStart(row.paper)
                    }
                }
            } })
        }
        item(key = "more") {
            SectionLabel("更多练习")
            RowCard {
                ListRow("自选文章练习", subtitle = "粘贴英文文章，添加题目、独立作答后可请求讲解",
                    trailing = "新建", trailingTone = Tone.Ink, enabled = !vm.coach.working) { onCustom() }
                samples.forEach { paper ->
                    key("reading-sample-${paper.stableId}") {
                        val attempt = attempts[paper.stableId]
                        val status = attempt?.let { readingStatus(it) }
                        GroupDivider()
                        ListRow("原创样本（非真题）", subtitle = "${paper.passages.size} 篇 · Q46–Q55", subtitleLines = 1,
                            trailing = status?.label, trailingTone = status?.tone ?: Tone.Muted, trailingDescription = status?.spoken,
                            enabled = !vm.coach.working) { if (attempt != null) onOpen(attempt) else onStart(paper) }
                    }
                }
            }
        }
        if (mine.isNotEmpty()) item(key = "mine") {
            SectionLabel("最近读的")
            RowCard {
                mine.forEachIndexed { i, practice ->
                    val status = if (ExamCodec.metadata(practice) != null) readingStatus(practice)
                        else TaskStatus(if (practice.submittedAt > 0) "已提交" else "草稿", if (practice.submittedAt > 0) Tone.Ink else Tone.Better)
                    if (i > 0) GroupDivider()
                    ListRow(practice.title.ifBlank { "自己的文章" },
                        subtitle = listOfNotNull(
                            practice.practiceGoal.takeIf(String::isNotBlank)?.let { "目标：$it" },
                            shortTime(practice.updatedAt)
                        ).joinToString(" · "),
                        trailing = status.label, trailingTone = status.tone, trailingDescription = status.spoken,
                        enabled = !vm.coach.working) { onOpen(practice) }
                }
            }
        }
    }
}

private fun readingAttempt(history: List<CoachPractice>, paper: ExamPaper) =
    history.filter { ExamCodec.itemId(it)?.startsWith("${paper.stableId}/READING/") == true }.maxByOrNull { it.updatedAt }

/**
 * One catalogue set with the status of its latest attempt; null status means not started. [practiced] excludes
 * attempts that were only opened, so the 已练 count reflects sets with answers entered or checked.
 */
private data class CatalogEntry(val paper: ExamPaper, val available: Boolean, val status: TaskStatus?,
    val practiced: Boolean = status != null)

/** One matrix cell; a null [tone] means not started (or not available). [linked] marks a set that reuses another's. */
private data class MatrixCell(val setCode: String, val text: String, val tone: Tone?, val available: Boolean,
    val enabled: Boolean, val spoken: String, val linked: Boolean = false, val onClick: () -> Unit)

/** Drafts show just 草稿 in the cell, which is too narrow for a word count; the full label is still spoken. */
private fun matrixCell(row: CatalogEntry, unavailableReason: String?, idle: Boolean, onClick: () -> Unit): MatrixCell {
    val status = row.status
    return when {
        unavailableReason != null -> MatrixCell(row.paper.setCode, if (row.paper.status == "待导入") "未导入" else "待校对",
            null, false, false, unavailableReason, onClick = onClick)
        status == null -> MatrixCell(row.paper.setCode, "未做", null, true, idle, "未做", onClick = onClick)
        else -> MatrixCell(row.paper.setCode, if (status.label.startsWith("草稿")) "草稿" else status.label, status.tone, true, idle,
            status.spoken ?: status.label, onClick = onClick)
    }
}

@Composable
private fun MatrixLabel(rows: Map<String, List<CatalogEntry>>) {
    val all = rows.values.flatten()
    SectionLabel("历年真题 · 已练 ${all.count { it.practiced }}/${all.count { it.available }} 套")
}

/** The single primary action of a skill home: continue the open draft, or start the newest set not yet done. */
@Composable
private fun NextStep(tag: String, title: String, note: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    PaperCard(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) {
        Note(tag, tone = Tone.Ink)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        Note(note, Modifier.padding(top = 2.dp))
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) {
            Text(action)
        }
    }
}

/** Sessions down, set numbers across: every exam set fits on about one screen and its state reads at a glance. */
@Composable
private fun SetMatrix(sessions: List<Pair<String, List<MatrixCell>>>) {
    val codes = remember(sessions) { sessions.flatMap { (_, cells) -> cells.map { it.setCode } }.distinct().sorted() }
    val dateStyle = NumeralText.copy(fontSize = 15.sp, lineHeight = 20.sp)
    val dateWidth = labelWidth(listOf("2025.12"), dateStyle)
    PaperCard(padding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier/* .clearAndSetSemantics {} */, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.width(dateWidth))
                codes.forEach { code ->
                    Text("第 $code 套", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
            sessions.forEach { (date, cells) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${date.take(4)}.${date.substring(5, 7)}", style = dateStyle,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                        modifier = Modifier.width(dateWidth)/* .clearAndSetSemantics {} */)
                    codes.forEach { code ->
                        val cell = cells.firstOrNull { it.setCode == code }
                        if (cell == null) Spacer(Modifier.weight(1f))
                        else SetCell(cell, sessionTitle(date), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * A set's state by fill: finished work is an ink tile, a draft or partial entry a dashed ochre outline, an open set
 * a white tile with a hairline. A linked or unavailable set has nothing of its own to do, so it is plain text.
 */
@Composable
private fun SetCell(cell: MatrixCell, session: String, modifier: Modifier) {
    val tone = cell.tone
    val inProgress = tone == Tone.Better || tone == Tone.Must
    val done = tone != null && !inProgress
    val open = tone == null && cell.available && !cell.linked
    val shape = RoundedCornerShape(12.dp)
    val ochre = LocalMarks.current.better
    Surface(
        onClick = cell.onClick,
        enabled = cell.enabled,
        shape = shape,
        color = when {
            done -> MaterialTheme.colorScheme.primary
            inProgress -> LocalMarks.current.betterSoft
            open -> MaterialTheme.colorScheme.surfaceContainerLowest
            else -> Color.Transparent
        },
        border = if (open) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = modifier.heightIn(min = 44.dp)
            .then(if (inProgress) Modifier.drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(ochre, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(12.dp.toPx() - stroke / 2),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            } else Modifier)
            // .semantics { contentDescription = "$session 第 ${cell.setCode} 套，${cell.spoken}" }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(cell.text,
                style = if (done) NumeralText.copy(fontSize = 15.sp, lineHeight = 20.sp) else MaterialTheme.typography.labelMedium,
                fontWeight = if (tone != null) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    done -> MaterialTheme.colorScheme.onPrimary
                    inProgress -> ochre
                    open -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.outline
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)/* .clearAndSetSemantics {} */)
        }
    }
}

fun readingStatus(entry: CoachPractice): TaskStatus {
    val questions = ExamCodec.passages(entry).flatMap { it.questions }
    val answered = ExamCodec.readingAnswered(entry)
    if (entry.submittedAt == 0L) return if (answered == 0) TaskStatus("未做", Tone.Muted)
        else TaskStatus("已录 $answered/${questions.size}", Tone.Better, "已录入 $answered 题，共 ${questions.size} 题")
    val known = questions.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() }
    val right = known.count { ReadingQuestions.selected(entry.answer, "${it.number}") == it.referenceAnswer }
    return if (known.isEmpty()) TaskStatus("已核对", Tone.Ink)
        else TaskStatus("$right/${known.size}", Tone.Ink, "已核对，${known.size} 题中 $right 题与参考一致")
}

/** The 我的 tab as a settings list: an overview cell, then connection, data and older screens. */
@Composable
fun MeHome(vm: StudyViewModel, padding: PaddingValues, onRoute: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { TabTitle("我的") }
        item {
            Spacer(Modifier.height(12.dp))
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_study), contentDescription = null, modifier = Modifier.size(56.dp))
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("朱批", style = MaterialTheme.typography.titleLarge)
                        Text("${vm.coach.history.count { it.submittedAt > 0 }} 次提交 · ${if (vm.deviceSync.enabled) "设备同步已开启" else "学习数据保存在本机"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            SectionLabel("外观")
            RowCard {
                ListRow("界面风格", trailing = vm.uiStyle.label,
                    leading = { IconTile(Icons.Outlined.Palette, TileColors.Indigo) }) { onRoute("me/style") }
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
                ListRow("设备同步", subtitle = if (vm.deviceSync.enabled) "已开启自动同步" else "手机与电脑接着学",
                    leading = { IconTile(Icons.Outlined.Hub, TileColors.Blue) }, onClick = { onRoute("me/sync") })
                GroupDivider(TiledRowDividerStart)
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
