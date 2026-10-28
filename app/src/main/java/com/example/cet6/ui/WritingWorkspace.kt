package com.example.cet6.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.PassageText
import kotlinx.coroutines.launch

/** Writing desk: task, editor and the reviews of each submitted draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingWorkspace(vm: StudyViewModel, onBack: () -> Unit) {
    val state = vm.coach
    val talk = vm.talk
    val entry = state.entries[CoachKind.WRITING]
    if (entry == null) { LoadingScreen(onBack); return }
    val turns = rememberTurns(vm, entry.id)
    val meta = remember(entry.questions) { ExamCodec.metadata(entry) }
    val task = remember(entry.material) { WritingTask.of(entry.material) }
    val submitted = entry.submittedAt > 0
    val reference = meta?.optString("referenceWriting").orEmpty()
    var tab by rememberSaveable(entry.id) { mutableIntStateOf(if (submitted) 1 else 0) }
    var showPlan by rememberSaveable(entry.id) { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    val versions = remember(state.history, entry) { versionsOf(state.history, entry) }
    val bands = remember(vm.reviewTurns) { latestBands(vm.reviewTurns) }
    val words = remember(entry.answer) { WritingCoach.words(entry.answer) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val reviewTurn = CoachTurns.latest(turns, Anchor.REVIEW, TurnKind.WRITING_REVIEW)
    val pending = talk.pendingFor(entry.id, Anchor.REVIEW)
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    fun ask(question: String) {
        talk.send(entry, Anchor.REVIEW, TurnKind.CHAT, question) {
            CoachChat.prompt(entry, Anchor.REVIEW, CoachTurns.thread(turns, Anchor.REVIEW), question)
        }
        scope.launch { scroll.animateScrollTo(scroll.maxValue) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenTopBar(
                title = entry.title.removeSuffix(" · 作文").ifBlank { "自拟题目" },
                subtitle = listOfNotNull("短文写作", versions.indexOfFirst { it.id == entry.id }.takeIf { versions.size > 1 }?.let { "第 ${it + 1} 稿" },
                    if (submitted) "已提交" else null).joinToString(" · "),
                onBack = onBack,
                actions = {
                    WorkspaceMenu(vm, entry, listOfNotNull("构思提纲" to { showPlan = true },
                        if (reference.isNotBlank()) "参考范文" to { tab = 2 } else null,
                        if (versions.size > 1) "各稿" to { showVersions = true } else null))
                })
        },
        bottomBar = {
            when {
                tab == 0 -> BottomBar {
                    if (!submitted) ExamClock(entry.id)
                    Column(Modifier.weight(1f)) {
                        WordCount(words, task.minWords, task.maxWords)
                        Note(saveLabel(state.messages[CoachKind.WRITING]))
                    }
                    if (!submitted) Button(onClick = { confirmSubmit = true }, enabled = words > 0 && !state.working,
                        modifier = Modifier.height(48.dp)) { Text("提交") }
                    else Button(onClick = { state.revise(entry, entry.answer) }, enabled = !state.working,
                        modifier = Modifier.height(48.dp)) { Text("写修改稿") }
                }
                tab == 1 && submitted && pending?.kind != TurnKind.WRITING_REVIEW && (reviewTurn != null || pending != null) ->
                    ReviewFooter(vm, entry, "问问这篇作文…", placedReview(entry.answer, reviewTurn), canAsk = true, onAsk = ::ask)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DeskSteps("写作", tab, submitted, state.working, onTab = { tab = it }, onRevise = {
                state.revise(entry, placedReview(entry.answer, reviewTurn)?.let { applyAccepted(entry.answer, it) } ?: entry.answer)
            })
            Hairline()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 32.dp)) {
                when (tab) {
                    0 -> DraftPane(vm, entry, task, meta == null, submitted)
                    1 -> WritingReviewPane(vm, entry, turns, reviewTurn, scroll, onGoWrite = { tab = 0 }, onAsk = ::ask)
                    else -> ReferencePane(reference, meta?.optString("referenceWritingSource").orEmpty())
                }
            }
        }
    }
    if (showPlan) PlanSheet(vm, entry, turns) { showPlan = false }
    if (showVersions) VersionSheet(versions, entry.id, bands, onOpen = { showVersions = false; state.open(CoachKind.WRITING, it) }) { showVersions = false }
    if (confirmSubmit) SubmitDialog(words, task.minWords, onConfirm = {
        confirmSubmit = false
        state.submit(CoachKind.WRITING, false)
        tab = 1
    }) { confirmSubmit = false }
}

/** Band of each practice's newest review; follow-up replies on the same anchor are not reviews. */
fun latestBands(reviews: List<CoachTurn>): Map<String, Int> = reviews
    .filter { it.kind == TurnKind.WRITING_REVIEW || it.kind == TurnKind.TRANSLATION_REVIEW }
    .groupBy { it.practiceId }
    .mapNotNull { (id, items) -> CoachReview.parse(items.last().content).score?.band?.takeIf { it > 0 }?.let { id to it } }.toMap()

fun saveLabel(message: String?): String = when (message) {
    null, "" -> "自动保存在本机"
    "已保存到本机" -> "已保存"
    "正在保存……" -> "保存中…"
    else -> message
}

@Composable
fun LoadingScreen(onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { ScreenTopBar("正在打开…", onBack = onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun DraftPane(vm: StudyViewModel, entry: CoachPractice, task: WritingTask, custom: Boolean, submitted: Boolean) {
    val state = vm.coach
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (custom && !submitted && entry.sourcePracticeId.isBlank()) {
            PaperCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(entry.title, { v -> state.edit(CoachKind.WRITING) { it.copy(title = v.take(200)) } },
                        label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(entry.material, { v -> state.edit(CoachKind.WRITING) { it.copy(material = v) } },
                        label = { Text("题目与要求") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            // The task folds to one line so the first screen belongs to the essay; the opening sentence stays in view.
            PaperCard(padding = PaddingValues(start = CardPadding, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "收起题目" else "展开题目") { expanded = !expanded }
                    .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(taskSummary(task), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                }
                Column(Modifier.padding(end = CardPadding - 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    task.opening?.let { opening -> SelectionContainer { Text(opening, style = PassageText.copy(fontSize = 16.sp, lineHeight = 25.sp)) } }
                    if (expanded || task.opening == null) Text(entry.material, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    if (!submitted && entry.answer.isBlank() && task.opening != null) TextButton(
                        onClick = { state.edit(CoachKind.WRITING) { it.copy(answer = task.opening + " ") } },
                        contentPadding = PaddingValues(0.dp)) { Text("插入开头句") }
                }
            }
        }
        val source = entry.sourcePracticeId.takeIf(String::isNotBlank)?.let { id -> state.history.firstOrNull { it.id == id } }
        if (entry.practiceGoal.isNotBlank()) {
            Row(Modifier.padding(start = PageGutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Note("训练目标：${entry.practiceGoal}", Modifier.weight(1f), tone = Tone.Ink)
                if (source != null && source.material != entry.material) {
                    TextButton(onClick = { state.open(CoachKind.WRITING, source) }) { Text("查看原练习") }
                }
            }
        }
        if (source != null && source.answer.isNotBlank() && source.material == entry.material) {
            var compare by rememberSaveable(entry.id) { mutableStateOf(false) }
            Row(Modifier.padding(start = PageGutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Note("修改自 ${shortTime(if (source.submittedAt > 0) source.submittedAt else source.updatedAt)} 的一稿", Modifier.weight(1f))
                TextButton(onClick = { compare = !compare }) { Text(if (compare) "收起对照" else "对照上一稿") }
            }
            if (compare) ComparePanel(source.answer, entry.answer)
        }
        PaperCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Editor(entry.answer, readOnly = submitted, placeholder = "在这里写作…") { text ->
                state.edit(CoachKind.WRITING) { it.copy(answer = text) }
            }
        }
        if (submitted) Note("这一稿已提交，可以在「批改」里查看批注；想继续改，点下方「写修改稿」。", Modifier.padding(horizontal = PageGutter))
    }
}

/** One line naming what the task asks, e.g. 以给定句开头 · 150–200 词. */
private fun taskSummary(task: WritingTask): String = listOfNotNull(
    if (task.opening != null) "以给定句开头" else "题目与要求",
    if (task.minWords != null && task.maxWords != null) "${task.minWords}–${task.maxWords} 词" else task.minWords?.let { "至少 $it 词" }
).joinToString(" · ")

/** Borderless editor that reads like the page it will be printed on. */
@Composable
fun Editor(text: String, readOnly: Boolean, placeholder: String, minHeight: Int = 320, onChange: (String) -> Unit) {
    BasicTextField(text, onChange, readOnly = readOnly, textStyle = PassageText.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text(placeholder, style = PassageText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            }
        })
}

@Composable
private fun WritingReviewPane(vm: StudyViewModel, entry: CoachPractice, turns: List<CoachTurn>, reviewTurn: CoachTurn?,
    scroll: ScrollState, onGoWrite: () -> Unit, onAsk: (String) -> Unit) {
    val talk = vm.talk
    if (entry.submittedAt == 0L) {
        EmptyNote("提交这一稿后就可以批改。批改针对提交时的原稿，之后的修改会另存为修改稿。", "回到写作", onGoWrite)
        return
    }
    val pending = talk.pendingFor(entry.id, Anchor.REVIEW)
    val reviewing = pending?.kind == TurnKind.WRITING_REVIEW
    val text = if (reviewing) pending!!.text else reviewTurn?.content
    val review = remember(text) { text?.let(CoachReview::parse) }
    val decisions = remember(reviewTurn?.state) { ReviewDecisions.decode(reviewTurn?.state.orEmpty()) }
    var chooseAgain by rememberSaveable(entry.id) { mutableStateOf(false) }
    var selected by rememberSaveable(entry.id) { mutableStateOf<Int?>(null) }
    var showDiff by rememberSaveable(entry.id) { mutableStateOf(false) }
    talk.failureFor(entry.id, Anchor.REVIEW, chat = false)?.let {
        FailureLine(it, talk::dismissFailure, Modifier.padding(horizontal = CardMargin, vertical = 8.dp))
    }
    if (review == null || (chooseAgain && !reviewing)) {
        FocusChooser(CoachKind.WRITING, enabled = !talk.busy, connected = vm.aiReady && vm.chosenModel.isNotBlank()) { focus ->
            chooseAgain = false
            talk.send(entry, Anchor.REVIEW, TurnKind.WRITING_REVIEW) { WritingCoach.reviewPrompt(entry, focus) }
        }
        LegacyFeedback(entry.feedback)
        SkillErrorNote(vm, entry, CoachKind.WRITING)
        return
    }
    if (reviewing) WorkingLine("正在批改 · 已标出 ${review.issues.size} 处", talk::stop,
        Modifier.padding(horizontal = PageGutter, vertical = 8.dp))
    if (!review.structured && !reviewing) {
        Spacer(Modifier.height(16.dp))
        PaperCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note("回复未完整通过结构检查，以下保留全部原文；请核对后使用。")
                MarkdownBody(review.raw)
                Provenance(reviewTurn?.model.orEmpty())
            }
        }
    } else {
        Spacer(Modifier.height(16.dp))
        BandHeader(review.score, review.aspects)
        val placed = remember(entry.answer, review, decisions) { placeIssues(entry.answer, review, decisions) }
        IssueList(placed, onOpen = { selected = it }, onDecide = reviewTurn?.takeIf { !reviewing }?.let { turn ->
            { issue: ReviewIssue, decision: ReviewDecision -> talk.updateDecision(turn, issue.id, decision) } })
        SectionLabel("原稿批注")
        PaperCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            MarkedText(entry.answer, placed.filter { !it.dismissed }.mapNotNull { p -> p.range?.let { TextMark(p.issue.id, it, p.tone) } },
                selected = selected?.let { placed.getOrNull(it)?.issue?.id }, style = PassageText,
                onMark = { id -> selected = placed.indexOfFirst { it.issue.id == id } })
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DotStatus("必改 ${placed.count { it.issue.must && !it.accepted && !it.dismissed }}", Tone.Must)
                DotStatus("可改 ${placed.count { !it.issue.must && !it.accepted && !it.dismissed }}", Tone.Better)
                DotStatus("已采纳 ${placed.count { it.accepted }}", Tone.Good)
            }
        }
        if (!reviewing && reviewTurn != null) Note("点画线处或上面的条目，逐条决定采纳还是忽略；采纳的会带进修改稿。",
            Modifier.padding(horizontal = PageGutter, vertical = 8.dp))
        if (review.tips.isNotEmpty()) {
            SectionLabel("写作建议")
            PaperCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    review.tips.forEach { Text("· $it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        if (review.rewrite.isNotBlank()) {
            SectionLabel("修改后全文（参考）", action = if (showDiff) "看全文" else "对照原稿", onAction = { showDiff = !showDiff })
            PaperCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                if (showDiff) DiffText(entry.answer, review.rewrite) else SelectionContainer { Text(review.rewrite, style = PassageText) }
            }
        }
        if (!reviewing && reviewTurn != null) Row(Modifier.padding(start = PageGutter, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Provenance(reviewTurn.model, Modifier.weight(1f))
            TextButton(onClick = { chooseAgain = true }, enabled = !talk.busy) { Text("换一种方式批改") }
        }
        if (selected != null && reviewTurn != null) IssueSheet(placed, selected!!, onIndex = { selected = it.coerceIn(0, placed.lastIndex) },
            onDismissSheet = { selected = null },
            onAccept = { talk.updateDecision(reviewTurn, it.id, ReviewDecision.ACCEPT) },
            onReject = { talk.updateDecision(reviewTurn, it.id, ReviewDecision.DISMISS) },
            onReset = { talk.updateDecision(reviewTurn, it.id, ReviewDecision.RESET) },
            onAsk = { issue -> selected = null; onAsk(CoachChat.aboutIssue(issue)) }, askBusy = talk.busy)
    }
    if (!reviewing) {
        // Follow-up failures show above the composer in the bottom bar, not down here out of sight.
        SectionLabel("继续问")
        QuickAsks(CoachChat.suggestions(CoachKind.WRITING, Anchor.REVIEW), enabled = !talk.busy, onPick = onAsk, edge = PageGutter)
        ThreadView(CoachTurns.thread(turns, Anchor.REVIEW), pending, talk::stop, Modifier.padding(start = PageGutter, end = PageGutter, top = 12.dp))
        SkillErrorNote(vm, entry, CoachKind.WRITING)
    }
}

@Composable
fun FocusChooser(kind: CoachKind, enabled: Boolean, connected: Boolean, onPick: (WritingFocus) -> Unit) {
    SectionLabel("选择批改方式")
    val focuses = WritingFocus.forKind(kind)
    RowCard {
        focuses.forEachIndexed { i, focus ->
            ListRow(focus.label + if (i == 0) "（推荐）" else "", subtitle = focus.summaryFor(kind), enabled = enabled) { onPick(focus) }
            if (i < focuses.lastIndex) RowDivider()
        }
    }
    if (!connected) Note("需要先在「我的 → AI 连接」配置 AI 服务并选择模型；原稿已保存在本机。",
        Modifier.padding(horizontal = PageGutter, vertical = 12.dp))
}

@Composable
fun LegacyFeedback(feedback: String) {
    if (feedback.isBlank()) return
    var open by rememberSaveable { mutableStateOf(false) }
    SectionLabel("早期批改记录", action = if (open) "收起" else "展开", onAction = { open = !open })
    if (open) PaperCard { MarkdownBody(feedback) }
}

@Composable
fun EmptyNote(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ReferencePane(reference: String, source: String) {
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) { SelectionContainer { Text(reference, style = PassageText) } }
        Column(Modifier.padding(horizontal = PageGutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (source.isNotBlank()) Note("来源：" + source.substringBefore(" (").substringBefore(";").take(120))
            Note("参考范文只是一种写法，不是唯一答案。")
        }
    }
}

/** Planning help before writing; it suggests angles and an outline but never writes the essay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSheet(vm: StudyViewModel, entry: CoachPractice, turns: List<CoachTurn>, onDismiss: () -> Unit) {
    val talk = vm.talk
    val plan = CoachTurns.latest(turns, Anchor.PLAN, TurnKind.WRITING_PLAN)
    val pending = talk.pendingFor(entry.id, Anchor.PLAN)
    val thread = CoachTurns.thread(turns, Anchor.PLAN)
    fun ask(question: String) = talk.send(entry, Anchor.PLAN, TurnKind.CHAT, question) {
        CoachChat.prompt(entry, Anchor.PLAN, thread, question)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("构思提纲", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Before there is a plan the failure belongs to 帮我审题; afterwards it shows above the composer.
                if (plan == null) talk.failureFor(entry.id, Anchor.PLAN)?.let { FailureLine(it, talk::dismissFailure) }
                when {
                    pending?.kind == TurnKind.WRITING_PLAN -> {
                        WorkingLine("正在审题…", talk::stop)
                        if (pending.text.isNotBlank()) MarkdownBody(pending.text)
                    }
                    plan != null -> { MarkdownBody(plan.content); Provenance(plan.model) }
                    else -> {
                        Text("先弄清题目要什么、选哪个角度、三段怎么展开，再自己动笔。这里不会替你写作文。",
                            style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { talk.send(entry, Anchor.PLAN, TurnKind.WRITING_PLAN) { WritingCoach.planPrompt(entry) } },
                            enabled = !talk.busy && entry.material.isNotBlank()) { Text("帮我审题") }
                        if (!vm.aiReady || vm.chosenModel.isBlank()) Note("需要先在「我的 → AI 连接」配置 AI 服务并选择模型。")
                    }
                }
                if (plan != null) {
                    QuickAsks(CoachChat.suggestions(CoachKind.WRITING, Anchor.PLAN), enabled = !talk.busy, onPick = ::ask)
                    ThreadView(thread, pending, talk::stop)
                }
            }
            if (plan != null) AskBar("说说你的想法，或问一个问题…", busy = talk.busy, enabled = !talk.busy, onSend = ::ask,
                onStop = talk::stop, draftKey = entry.id + "/plan",
                failure = talk.failureFor(entry.id, Anchor.PLAN), onDismissFailure = talk::dismissFailure)
        }
    }
}
