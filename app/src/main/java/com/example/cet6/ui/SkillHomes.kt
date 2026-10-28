package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    onStart: (ExamPaper) -> Unit, onCustom: () -> Unit) {
    val history = vm.coach.history
    val bands = remember(vm.reviewTurns) { latestBands(vm.reviewTurns) }
    val papers = allPapers(vm)
    val bySession = papers.filter { !it.sample }.groupBy { it.examDate }.toSortedMap(compareByDescending { it })
    val samples = papers.filter { it.sample && it.issues(kind).isEmpty() }
    val draft = vm.coach.entries[kind]?.takeIf { it.submittedAt == 0L && it.answer.isNotBlank() }
    val mine = history.filter { it.kind == kind.name && it.answer.isNotBlank() }.take(3)
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item {
            if (kind == CoachKind.WRITING) TabTitle("写作", "六级 Part I · 30 分钟 · 150–200 词")
            else TabTitle("翻译", "六级 Part IV · 30 分钟 · 段落汉译英")
        }
        if (draft != null) item {
            SectionLabel("继续")
            RowCard {
                ListRow(draft.title.ifBlank { if (kind == CoachKind.WRITING) "自拟题目" else "自选段落" },
                    subtitle = "${WritingCoach.words(draft.answer)} 词 · ${shortTime(draft.updatedAt)} 保存",
                    trailing = "草稿", trailingTone = Tone.Better) { onOpen(draft) }
            }
        }
        bySession.forEach { (date, sets) ->
            item(key = "session-$date") {
                SectionLabel(sessionTitle(date))
                RowCard {
                    val ordered = sets.sortedBy { it.setCode }
                    ordered.forEachIndexed { i, paper ->
                        key("${kind.name}-${paper.stableId}") {
                            val available = paper.issues(kind).isEmpty()
                            val itemId = "${paper.stableId}/${kind.name}/1"
                            val status = if (available) writtenStatus(history, bands, itemId) else null
                            ListRow("第 ${paper.setCode} 套", subtitle = if (available) taskLine(paper, kind) else unavailable(paper),
                                trailing = status?.label, trailingTone = status?.tone ?: Tone.Muted, enabled = available && !vm.coach.working) {
                                latestAttempt(history, itemId)?.let(onOpen) ?: onStart(paper)
                            }
                        }
                        if (i < ordered.lastIndex) RowDivider()
                    }
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
    onShared: (String) -> Unit, onCustom: () -> Unit) {
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
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { TabTitle("阅读", "仔细阅读 · 纸上做完，再来核对和弄懂") }
        if (draft != null) item {
            val status = if (ExamCodec.metadata(draft) != null) readingStatus(draft) else TaskStatus("草稿", Tone.Better)
            SectionLabel("继续")
            RowCard {
                ListRow(draft.title.ifBlank { "自己的文章" },
                    subtitle = "${shortTime(draft.updatedAt)} 保存",
                    trailing = status.label, trailingTone = status.tone, trailingDescription = status.spoken) { onOpen(draft) }
            }
        }
        bySession.forEach { (date, sets) ->
            item(key = "session-$date") {
                SectionLabel(sessionTitle(date))
                RowCard {
                    val ordered = sets.sortedBy { it.setCode }
                    ordered.forEachIndexed { i, paper ->
                        key("reading-${paper.stableId}") { ReadingPaperRow(vm, history, paper, onOpen, onStart, onShared) }
                        if (i < ordered.lastIndex) RowDivider()
                    }
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
    val attempt = history.filter { ExamCodec.itemId(it)?.startsWith("${paper.stableId}/READING/") == true }.maxByOrNull { it.updatedAt }
    val status = attempt?.let { readingStatus(it) }
    ListRow(if (paper.sample) "原创样本" else "第 ${paper.setCode} 套",
        subtitle = when {
            shared != null -> "阅读与第 1 套相同，在第 1 套练习"
            available -> null
            else -> unavailable(paper)
        },
        lines = if (shared == null && available) paper.passages.sortedBy { it.code }.map { openingLine(it.content) } else emptyList(),
        trailing = status?.label, trailingTone = status?.tone ?: Tone.Muted, trailingDescription = status?.spoken,
        enabled = (available || shared != null) && !vm.coach.working) {
        when {
            shared != null -> onShared(shared)
            attempt != null -> onOpen(attempt)
            else -> onStart(paper)
        }
    }
}

/** Opening of a passage, enough to recognise it; the row cuts it to one line. */
private fun openingLine(content: String) = PassageLayout.paragraphs(content).firstOrNull().orEmpty().take(160)

private fun readingStatus(entry: CoachPractice): TaskStatus {
    val questions = ExamCodec.passages(entry).flatMap { it.questions }
    val answered = questions.count { ReadingQuestions.selected(entry.answer, "${it.number}").orEmpty().isNotBlank() }
    if (entry.submittedAt == 0L) return if (answered == 0) TaskStatus("未录入", Tone.Better)
        else TaskStatus("已录 $answered/${questions.size}", Tone.Better, "已录入 $answered 题，共 ${questions.size} 题")
    val known = questions.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() }
    val right = known.count { ReadingQuestions.selected(entry.answer, "${it.number}") == it.referenceAnswer }
    return if (known.isEmpty()) TaskStatus("已核对", Tone.Ink)
        else TaskStatus("$right/${known.size}", Tone.Ink, "已核对，${known.size} 题中 $right 题与参考一致")
}

/** Sections of the 我的 tab; each row opens a full screen. */
@Composable
fun MeHome(vm: StudyViewModel, padding: PaddingValues, onRoute: (String) -> Unit) {
    val reviews = WeaknessReview.records(vm.coach.history)
    LazyColumn(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { TabTitle("我的") }
        item {
            SectionLabel("AI 连接")
            RowCard {
                ListRow(vm.provider.label, subtitle = when {
                    !vm.aiReady -> "未连接 · 配置后可以批改、讲解和追问"
                    vm.chosenModel.isBlank() -> "已连接 · 还没有选择模型"
                    else -> "已连接 · ${vm.models.firstOrNull { it.slug == vm.chosenModel }?.displayName ?: vm.chosenModel}"
                }, trailing = if (vm.aiReady && vm.chosenModel.isNotBlank()) "可用" else null, trailingTone = Tone.Good) { onRoute("me/connection") }
            }
        }
        item {
            SectionLabel("复盘")
            RowCard {
                ListRow("错因回顾", subtitle = if (reviews.isEmpty()) "记录过错因的练习会出现在这里" else "${reviews.size} 条记录",
                    onClick = { onRoute("me/review") })
                RowDivider()
                ListRow("学习趋势", subtitle = "按周查看阅读核对、修改稿与复习自评", onClick = { onRoute("me/trends") })
                RowDivider()
                ListRow("练习记录", subtitle = "全部阅读、写作和翻译练习", onClick = { onRoute("me/records") })
            }
        }
        item {
            val dueCount = vm.words.count { it.dueAt in 1..vm.now }
            val totalCount = vm.words.size
            SectionLabel("词汇")
            RowCard {
                ListRow(
                    "六级词汇",
                    subtitle = if (totalCount == 0) "导入词库或在练习中加入生词" else "词库 $totalCount 词 · 今日待复习 $dueCount 词",
                    trailing = if (dueCount > 0) "待复习 $dueCount" else if (totalCount > 0) "已完成" else null,
                    trailingTone = if (dueCount > 0) Tone.Better else Tone.Good,
                    onClick = { onRoute("me/words") }
                )
            }
        }
        item {
            SectionLabel("数据")
            RowCard {
                ListRow("备份与恢复", subtitle = "完整导出学习记录与个人题库，不含凭证", onClick = { onRoute("me/backup") })
                RowDivider()
                ListRow("题库管理", subtitle = "导入或导出个人题库", onClick = { onRoute("me/exams") })
            }
        }
        item {
            SectionLabel("其他")
            RowCard {
                ListRow("单词与旧版功能", subtitle = "FSRS 单词复习、学习计划", onClick = { onRoute("me/legacy") })
                RowDivider()
                ListRow("旧版作文记录", subtitle = "早期版本保存的作文与批改", onClick = { onRoute("me/essays") })
            }
        }
    }
}
