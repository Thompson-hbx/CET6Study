package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
// import androidx.compose.ui.semantics.contentDescription
// import androidx.compose.ui.semantics.semantics
// import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import com.example.cet6.ui.theme.PassageText

private data class ReaderFocus(val passage: String, val quote: String)

/**
 * Paper-first reading: enter the answers marked on paper, check them against sourced keys, then work
 * through each question with the passage at hand. Paragraph numbers come from [PassageLayout] everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingWorkspace(vm: StudyViewModel, onBack: () -> Unit) {
    val state = vm.coach
    val hazeState = remember { HazeState() }
    val talk = vm.talk
    val entry = state.entries[CoachKind.READING]
    if (entry == null) {
        LoadingScreen(onBack)
        return
    }
    val passages = remember(entry.questions) { ExamCodec.passages(entry) }
    if (passages.isEmpty()) {
        if (ExamCodec.metadata(entry) == null) {
            SimpleScreen(entry.title.ifBlank { "自己的文章" }, onBack, actions = { WorkspaceMenu(vm, entry) }) { scroll ->
                CoachScreen(vm, CoachKind.READING, scroll)
            }
        } else {
            SimpleScreen(entry.title, onBack) { Note("题目快照无法读取，请保留记录并重新核对内容包。") }
        }
        return
    }
    val questions = remember(passages) { passages.flatMap { it.questions }.sortedBy { it.number } }
    val turns = rememberTurns(vm, entry.id)
    val submitted = entry.submittedAt > 0
    val answers = questions.associate { it.number to ReadingQuestions.selected(entry.answer, "${it.number}").orEmpty() }
    val savedPosition = remember(entry.id) { vm.readingPosition(entry.id)?.takeIf { position -> questions.any { it.number == position.second } } }
    val initiallySubmitted = remember(entry.id) { submitted }
    // Always default to the unified Passage + Question split workbench (tab = 1) whether answering or reviewing.
    var tab by rememberSaveable(entry.id) { mutableIntStateOf(1) }
    var selected by rememberSaveable(entry.id) {
        mutableIntStateOf(
            savedPosition?.second
                ?: questions.firstOrNull { submitted && ReadingGuidance.verdict(it, answers[it.number].orEmpty()) == "需要回看" }?.number ?: questions.first().number,
        )
    }
    var invalid by rememberSaveable(entry.id) { mutableStateOf("") }
    var focus by remember(entry.id) { mutableStateOf<ReaderFocus?>(null) }
    var asking by rememberSaveable(entry.id) { mutableStateOf(false) }
    var tallPanel by rememberSaveable(entry.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(tab, selected) { if (focus == null || tab != 2) scroll.scrollTo(0) }
    LaunchedEffect(entry.id, tab, selected) { vm.rememberReadingPosition(entry.id, tab, selected) }
    LaunchedEffect(submitted) {
        if (submitted && !initiallySubmitted) {
            tab = 1
            selected = questions.firstOrNull { ReadingGuidance.verdict(it, answers[it.number].orEmpty()) == "需要回看" }?.number ?: selected
        }
    }
    val anchor = Anchor.question(selected)
    // The selected question's saved explanation names its quote; recomputed from that reply, never stored apart.
    val savedFocus = remember(turns, selected, passages) {
        CoachTurns.latest(turns, anchor, TurnKind.READING_EXPLAIN)?.let { turn ->
            ReadingLocation.extract(entry, turn).takeIf(String::isNotBlank)?.let { quote ->
                passages.firstOrNull { p -> p.questions.any { it.number == selected } }?.let { ReaderFocus(it.code, quote) }
            }
        }
    }
    fun ask(question: String) = talk.send(entry, anchor, TurnKind.CHAT, question) {
        CoachChat.prompt(entry, anchor, CoachTurns.thread(turns, anchor), question)
    }
    val desk = tab == 1
    val current = passages.firstOrNull { p -> p.questions.any { it.number == selected } } ?: passages.first()
    val answeredCount = answers.count { it.value.isNotBlank() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            WorkspaceTopBar(
                "阅读",
                context = entry.title.removeSuffix(" · 阅读"),
                detail = if (submitted) "已核对" else "已答 $answeredCount/${questions.size}",
                compact = desk,
                onBack = onBack,
                actions = {
                    if (desk && submitted) AskAction { asking = true }
                    TextButton(
                        onClick = { tab = if (tab == 0) 1 else 0 },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.heightIn(min = 32.dp),
                    ) {
                        Text(
                            if (tab == 0) (if (submitted) "同屏精析" else "同屏做题") else "答题卡",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    WorkspaceMenu(
                        vm,
                        entry,
                        listOf(
                            (if (submitted) "同屏精析" else "同屏做题") to { tab = 1 },
                            "全文与逐段讲解" to {
                                focus = focus ?: savedFocus
                                tab = 2
                            },
                            "快录答题卡" to { tab = 0 },
                        ),
                    )
                },
            )
        },
        bottomBar = {
            when {
                !submitted -> BottomBar(hazeState = hazeState) {
                    Column(Modifier.weight(1f)) {
                        Text("已答 $answeredCount / ${questions.size}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Note(
                            if (invalid.isNotBlank()) "有一篇输入格式不对" else saveLabel(state.messages[CoachKind.READING]),
                            tone = if (invalid.isNotBlank()) Tone.Must else Tone.Muted,
                        )
                    }
                    OutlinedButton(
                        onClick = { tab = if (tab == 0) 1 else 0 },
                        modifier = Modifier.height(42.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(if (tab == 0) "同屏做题" else "纸上快录")
                    }
                    Button(
                        onClick = { state.submit(CoachKind.READING, false) },
                        enabled = answeredCount > 0 && invalid.isBlank() && !state.working,
                        modifier = Modifier.height(42.dp),
                    ) { Text("核对答案") }
                }
                tab == 0 -> BottomBar(hazeState = hazeState) {
                    Column(Modifier.weight(1f)) {
                        Text("已核对 $answeredCount / ${questions.size} 题", style = MaterialTheme.typography.titleSmall)
                        Note(saveLabel(state.messages[CoachKind.READING]))
                    }
                    OutlinedButton(
                        onClick = { tab = 1 },
                        modifier = Modifier.height(42.dp),
                    ) { Text("回到精析") }
                    Button(
                        onClick = { state.open(CoachKind.READING, entry, retry = true) },
                        enabled = !state.working,
                        modifier = Modifier.height(42.dp),
                    ) { Text("再练一次") }
                }
                desk && (
                    asking || talk.failureFor(entry.id, anchor, chat = true) != null ||
                        talk.pendingFor(entry.id, anchor)?.kind == TurnKind.CHAT
                    ) -> BottomBar(hazeState = hazeState) {
                    AskBar(
                        "问问这道题…", busy = talk.busy, enabled = !talk.busy, onSend = ::ask, onStop = talk::stop,
                        modifier = Modifier.weight(1f), draftKey = entry.id + anchor,
                        failure = talk.failureFor(entry.id, anchor, chat = true), onDismissFailure = talk::dismissFailure,
                    )
                    if (!talk.busy) {
                        TextButton(onClick = {
                            asking = false
                            talk.failureFor(entry.id, anchor, chat = true)?.let { talk.dismissFailure() }
                        }) { Text("收起") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).haze(hazeState).fillMaxSize()) {
            if (!desk) {
                TrackSegments(
                    listOf("答题卡", if (submitted) "同屏精析" else "同屏做题", "全文精读"),
                    tab,
                    {
                        focus = null
                        tab = it
                    },
                    Modifier.padding(horizontal = CardMargin, vertical = 8.dp),
                )
            }
            if (desk) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val q = questions.firstOrNull { it.number == selected } ?: questions.first()
                    val savedQuote = savedFocus?.takeIf { it.passage == current.code }?.quote
                    val panelMax = maxHeight * if (tallPanel) 0.72f else 0.54f
                    Column(Modifier.fillMaxSize()) {
                        if (savedQuote != null) {
                            Text(
                                "AI 定位 · 已匹配原文，待人工确认",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = PageGutter, vertical = 2.dp),
                            )
                        }
                        PassagePeek(
                            passages = passages,
                            passage = current,
                            q = q,
                            quote = focus?.takeIf { it.passage == current.code }?.quote ?: savedQuote,
                            tallPanel = tallPanel,
                            onToggleRatio = { tallPanel = !tallPanel },
                            onOpenFull = {
                                focus = focus ?: savedFocus
                                tab = 2
                            },
                            onSelectPassage = { targetPassage ->
                                focus = null
                                selected = targetPassage.questions.minOf { item -> item.number }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuestionPanel(
                            vm = vm,
                            entry = entry,
                            passage = current,
                            allQuestions = questions,
                            answers = answers,
                            submitted = submitted,
                            turns = turns,
                            selected = selected,
                            maxHeight = panelMax,
                            scroll = scroll,
                            bottomPadding = padding.calculateBottomPadding(),
                            onSelect = {
                                focus = null
                                selected = it
                            },
                            onChooseOption = { qNum, opt ->
                                state.edit(CoachKind.READING) { it.copy(answer = ReadingQuestions.choose(it.answer, "$qNum", opt)) }
                            },
                            onAsk = ::ask,
                        ) { code, quote -> focus = ReaderFocus(code, quote) }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = padding.calculateBottomPadding() + 32.dp)) {
                    when (tab) {
                        0 -> AnswerCardPane(vm, entry, passages, answers, submitted) { code, bad ->
                            invalid = if (bad) (invalid + code).toSet().joinToString("") else invalid.replace(code, "")
                        }
                        else -> PassagePane(vm, entry, passages, turns, focus ?: savedFocus)
                    }
                }
            }
        }
    }
}

/** Answer sheet: five cells per passage plus a one-line entry such as CADCB, both kept in sync. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerCardPane(
    vm: StudyViewModel,
    entry: CoachPractice,
    passages: List<ExamPassage>,
    answers: Map<Int, String>,
    submitted: Boolean,
    onValidity: (String, Boolean) -> Unit,
) {
    val state = vm.coach
    val focusManager = LocalFocusManager.current
    val requesters = remember(entry.id, passages.size) { List(passages.size) { FocusRequester() } }
    // Text being typed per passage (absent: show the saved answers). Held here so one field can fill the next.
    val typed = remember(entry.id) { mutableStateMapOf<String, String>() }
    val errors = remember(entry.id) { mutableStateMapOf<String, String>() }
    // Focus moves wait for the next composition: a field that loses focus re-sends the text it last composed,
    // which would wipe a pasted or quickly typed run that has not been composed yet. -1 clears focus.
    var focusTarget by remember(entry.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        if (target in requesters.indices) requesters[target].requestFocus() else focusManager.clearFocus()
        focusTarget = null
    }
    fun shown(p: ExamPassage) = typed[p.code] ?: SequentialAnswers.sequence(entry.answer, p.questions)
    fun focusAfter(index: Int) {
        focusTarget = if (index + 1 < passages.size) index + 1 else -1
    }

    // Answers as saved by the latest edit, which may not have been composed into [entry] yet.
    fun latestAnswer() = state.entries[CoachKind.READING]?.answer ?: entry.answer
    fun onType(index: Int, raw: String) {
        val p = passages[index]
        val before = runCatching { SequentialAnswers.normalize(shown(p)) }.getOrDefault("")
        // Answers copied off paper usually arrive as one run; letters past the fifth continue in the next passage.
        val chunks = runCatching { SequentialAnswers.normalize(raw) }.getOrNull()
            ?.takeIf { it.length > 5 && index < passages.lastIndex }
            ?.let { runCatching { SequentialAnswers.split(it, passages.size - index) }.getOrNull() }
        if (chunks != null) {
            val touched = chunks.withIndex().filter { it.index == 0 || it.value.isNotEmpty() }
            val updated = runCatching {
                touched.fold(latestAnswer()) { answer, (offset, chunk) -> SequentialAnswers.apply(answer, chunk, passages[index + offset].questions) }
            }.getOrNull()
            if (updated != null && state.edit(CoachKind.READING) { it.copy(answer = updated) }) {
                touched.forEach { (offset, chunk) ->
                    val code = passages[index + offset].code
                    typed[code] = chunk
                    errors.remove(code)
                    onValidity(code, false)
                }
                val last = touched.last()
                if (last.value.length == 5) focusAfter(index + last.index) else focusTarget = index + last.index
                return
            }
        }
        typed[p.code] = raw
        val result = runCatching { SequentialAnswers.apply(latestAnswer(), raw, p.questions) }
        errors[p.code] = result.exceptionOrNull()?.message.orEmpty()
        onValidity(p.code, result.isFailure)
        result.getOrNull()?.let { next ->
            if (state.edit(CoachKind.READING) { it.copy(answer = next) }) {
                val now = SequentialAnswers.normalize(raw)
                typed[p.code] = now
                if (now.length == 5 && before.length < 5) focusAfter(index)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    passages.forEachIndexed { index, p ->
        key(p.code) {
            val ordered = p.questions.sortedBy { it.number }
            var picking by rememberSaveable(entry.id, p.code) { mutableIntStateOf(-1) }
            val error = errors[p.code].orEmpty()
            val filledCount = ordered.count { answers[it.number].orEmpty().isNotBlank() }
            PaperCard(
                modifier = Modifier.padding(bottom = 14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                padding = PaddingValues(0.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tag("篇章 ${p.code}", Tone.Ink)
                    CardTitle("第 ${ordered.first().number}–${ordered.last().number} 题", Modifier.weight(1f))
                    StatusPill(
                        "$filledCount / ${ordered.size} 已填",
                        if (filledCount == ordered.size) Tone.Good else if (filledCount > 0) Tone.Ink else Tone.Muted,
                    )
                }
                Hairline()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ordered.forEachIndexed { i, q ->
                            val mine = answers[q.number].orEmpty()
                            val verdict = if (submitted) ReadingGuidance.verdict(q, mine) else ""
                            AnswerCell(
                                number = "${i + 1}",
                                questionNo = "${q.number}",
                                value = mine,
                                selected = picking == i,
                                tone = if (submitted) verdictTone(verdict) else null,
                                modifier = Modifier.weight(1f),
                                spoken = "篇章 ${p.code} 第 ${i + 1} 题，${mine.ifBlank { "未填" }}",
                                enabled = !submitted && !state.working,
                            ) {
                                if (!submitted) picking = if (picking == i) -1 else i
                            }
                        }
                    }
                    if (picking in ordered.indices && !submitted) {
                        val target = ordered[picking]
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Wraps instead of squeezing 清除 off the card at large font sizes.
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Note(
                                    "第 ${picking + 1} 题",
                                    Modifier.width(labelWidth(listOf("第 5 题"), MaterialTheme.typography.bodySmall))
                                        .align(Alignment.CenterVertically),
                                    tone = Tone.Ink,
                                )
                                listOf("A", "B", "C", "D").forEach { c ->
                                    FilterChip(
                                        selected = answers[target.number] == c,
                                        onClick = {
                                            state.edit(CoachKind.READING) { it.copy(answer = ReadingQuestions.choose(it.answer, "${target.number}", c)) }
                                            typed.remove(p.code)
                                            errors.remove(p.code)
                                            onValidity(p.code, false)
                                            picking = if (picking < ordered.lastIndex) picking + 1 else -1
                                        },
                                        label = { Text(c, fontWeight = FontWeight.SemiBold) },
                                        enabled = !state.working,
                                        shape = RoundedCornerShape(10.dp),
                                        border = null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                    )
                                }
                                if (answers[target.number].orEmpty().isNotBlank()) {
                                    TextButton(onClick = {
                                        state.edit(CoachKind.READING) { it.copy(answer = ReadingQuestions.choose(it.answer, "${target.number}", "")) }
                                        typed.remove(p.code)
                                    }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("清除") }
                                }
                            }
                        }
                    }
                    val more = index < passages.lastIndex
                    TextField(
                        value = shown(p),
                        onValueChange = { onType(index, it) },
                        label = { Text("按顺序连续输入") },
                        placeholder = { Text("如 CADCB，没做的写 ?") },
                        supportingText = {
                            if (error.isNotBlank()) {
                                Text(error, color = Tone.Must.color())
                            } else if (more && !submitted) Text("可以一次输入 ${passages.size * 5} 个，超出的自动接到下一篇")
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = if (more) ImeAction.Next else ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onNext = { focusAfter(index) }, onDone = { focusManager.clearFocus() }),
                        textStyle = MaterialTheme.typography.titleMedium.copy(letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            errorContainerColor = MaterialTheme.colorScheme.errorContainer,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        readOnly = submitted,
                        enabled = !state.working,
                        isError = error.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().focusRequester(requesters[index]),
                    )
                }
            }
        }
    }
    val missingKey = passages.flatMap { it.questions }.count { it.referenceAnswer.isBlank() || it.answerSource.isBlank() }
    if (missingKey > 0) Note("$missingKey 题缺少有来源的参考答案，核对时只显示待核对。", Modifier.padding(horizontal = PageGutter, vertical = 4.dp))
    if (submitted) Note("这一次已经核对，答案已锁定；要重新做一遍，点下方「再练一次」。", Modifier.padding(horizontal = PageGutter, vertical = 4.dp))
}

/** One bubble of the answer sheet; the bottom stripe carries the check result once there is one. */
@Composable
private fun AnswerCell(
    number: String,
    questionNo: String,
    value: String,
    selected: Boolean,
    tone: Tone?,
    modifier: Modifier = Modifier,
    spoken: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val marked = tone == Tone.Good || tone == Tone.Must
    val filled = value.isNotBlank()
    val stripe = when {
        marked -> tone!!.color()
        selected -> MaterialTheme.colorScheme.primary
        filled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(64.dp)/* .semantics { contentDescription = spoken; if (selected) stateDescription = "已选中" } */,
        color = when {
            marked -> tone!!.soft()
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            filled -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            if (selected) 1.5.dp else 0.5.dp,
            when {
                selected -> MaterialTheme.colorScheme.primary
                marked -> tone!!.color().copy(alpha = 0.4f)
                filled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$number · Q$questionNo",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                value.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    marked -> tone!!.color()
                    filled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.weight(1f).wrapContentHeight(),
            )
            Box(Modifier.fillMaxWidth().height(3.dp).background(stripe))
        }
    }
}

private fun verdictTone(verdict: String) = when (verdict) {
    "与参考一致" -> Tone.Good
    "需要回看" -> Tone.Must
    else -> Tone.Muted
}

private fun verdictLabel(verdict: String) = when (verdict) {
    "未录入" -> "未录入"
    "暂无可核实答案" -> "待核对"
    else -> verdict
}

/** The question sheet under the passage: a single-line strip of this passage's 5 questions, then the selected question in detail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionPanel(
    vm: StudyViewModel,
    entry: CoachPractice,
    passage: ExamPassage,
    allQuestions: List<ExamQuestion>,
    answers: Map<Int, String>,
    submitted: Boolean,
    turns: List<CoachTurn>,
    selected: Int,
    maxHeight: androidx.compose.ui.unit.Dp,
    scroll: androidx.compose.foundation.ScrollState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onSelect: (Int) -> Unit,
    onChooseOption: (Int, String) -> Unit,
    onAsk: (String) -> Unit,
    onLocate: (String, String) -> Unit,
) {
    val ordered = passage.questions.sortedBy { it.number }
    val known = ordered.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() && answers[it.number].orEmpty().isNotBlank() }
    val matched = known.count { answers[it.number] == it.referenceAnswer }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
    ) {
        Column {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                    .padding(top = 6.dp, bottom = 8.dp),
            ) {
                Box(
                    Modifier.size(width = 36.dp, height = 4.dp).align(Alignment.CenterHorizontally)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ordered.forEachIndexed { i, q ->
                        val mine = answers[q.number].orEmpty()
                        val verdict = if (submitted) ReadingGuidance.verdict(q, mine) else ""
                        AnswerCircle(
                            label = mine.ifBlank { "—" },
                            questionNo = "${q.number}",
                            answered = mine.isNotBlank(),
                            selected = q.number == selected,
                            tone = if (submitted) verdictTone(verdict) else if (mine.isNotBlank()) Tone.Ink else Tone.Muted,
                            spoken = "篇章 ${passage.code} 第 ${i + 1} 题，${mine.ifBlank { "未填" }}" +
                                if (submitted) "，${verdictLabel(verdict)}" else "",
                            modifier = Modifier.weight(1f),
                        ) { onSelect(q.number) }
                    }
                }
            }
            Hairline()
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = bottomPadding + 24.dp)) {
                val q = ordered.firstOrNull { it.number == selected } ?: ordered.first()
                val qIndex = allQuestions.indexOfFirst { it.number == q.number }
                val prevNo = allQuestions.getOrNull(qIndex - 1)?.number
                val nextNo = allQuestions.getOrNull(qIndex + 1)?.number
                key(q.number) {
                    QuestionDetail(
                        vm = vm,
                        entry = entry,
                        passage = passage,
                        q = q,
                        mine = answers[q.number].orEmpty(),
                        submitted = submitted,
                        matchedSummary = if (submitted && known.isNotEmpty()) "$matched/${known.size} 一致" else null,
                        allMatched = matched == known.size && known.isNotEmpty(),
                        prevNo = prevNo,
                        nextNo = nextNo,
                        onSelectQuestion = onSelect,
                        onChooseOption = { opt -> onChooseOption(q.number, opt) },
                        turns = turns,
                        onAsk = onAsk,
                        onLocate = onLocate,
                    )
                }
            }
        }
    }
}

/** One answer on the sheet's strip: question number + chosen letter, tinted by the check result. */
@Composable
private fun AnswerCircle(
    label: String,
    questionNo: String,
    answered: Boolean,
    selected: Boolean,
    tone: Tone,
    spoken: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val marked = tone == Tone.Good || tone == Tone.Must
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = when {
            selected -> MaterialTheme.colorScheme.primary
            marked -> tone.soft()
            answered -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceContainerLowest
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(
                0.5.dp,
                when {
                    marked -> tone.color().copy(alpha = 0.4f)
                    answered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
            )
        },
        modifier = modifier.height(42.dp),
        /* .semantics { contentDescription = spoken; if (selected) stateDescription = "已选中" } */
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Q$questionNo",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (answered) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    marked -> tone.color()
                    answered -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionDetail(
    vm: StudyViewModel,
    entry: CoachPractice,
    passage: ExamPassage,
    q: ExamQuestion,
    mine: String,
    submitted: Boolean,
    matchedSummary: String?,
    allMatched: Boolean,
    prevNo: Int?,
    nextNo: Int?,
    onSelectQuestion: (Int) -> Unit,
    onChooseOption: (String) -> Unit,
    turns: List<CoachTurn>,
    onAsk: (String) -> Unit,
    onLocate: (String, String) -> Unit,
) {
    val talk = vm.talk
    val anchor = Anchor.question(q.number)
    val ordinal = passage.questions.sortedBy { it.number }.indexOfFirst { it.number == q.number } + 1
    val verdict = if (submitted) ReadingGuidance.verdict(q, mine) else ""
    val explainTurn = CoachTurns.latest(turns, anchor, TurnKind.READING_EXPLAIN)
    val pending = talk.pendingFor(entry.id, anchor)
    val explaining = pending?.kind == TurnKind.READING_EXPLAIN
    val text = if (explaining) pending!!.text else explainTurn?.content
    val explanation = remember(text) { text?.let(ReadingExplanation::parse) }
    var reveal by rememberSaveable(entry.id, q.number) { mutableStateOf(false) }
    val keyKnown = q.referenceAnswer.isNotBlank() && q.answerSource.isNotBlank()
    val showKey = submitted && keyKnown && (reveal || explanation?.takeIf { it.structured }?.options?.isNotEmpty() == true)
    Column(Modifier.padding(horizontal = PageGutter, vertical = 10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("篇章 ${passage.code} · 第 $ordinal 题", Tone.Ink)
                if (matchedSummary != null) {
                    StatusPill(matchedSummary, if (allMatched) Tone.Good else Tone.Ink)
                }
                Spacer(Modifier.weight(1f))
                if (submitted) {
                    DotStatus(verdictLabel(verdict), verdictTone(verdict))
                } else {
                    if (prevNo != null) {
                        TextButton(
                            onClick = { onSelectQuestion(prevNo) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.heightIn(min = 28.dp),
                        ) { Text("‹ Q$prevNo", style = MaterialTheme.typography.labelMedium) }
                    }
                    if (nextNo != null) {
                        TextButton(
                            onClick = { onSelectQuestion(nextNo) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.heightIn(min = 28.dp),
                        ) { Text("Q$nextNo ›", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            SelectionContainer {
                Text(q.stem, style = MaterialTheme.typography.titleMedium.copy(lineHeight = 24.sp), fontWeight = FontWeight.SemiBold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                q.options.forEach { option ->
                    val note = if (submitted) explanation?.takeIf { it.structured }?.options?.firstOrNull { it.label == option.label } else null
                    val isKey = showKey && q.referenceAnswer == option.label
                    val isMine = mine == option.label
                    val optionTone = when {
                        !submitted && isMine -> Tone.Ink
                        isKey -> Tone.Good
                        isMine && verdict == "需要回看" -> Tone.Must
                        isMine -> Tone.Good
                        else -> Tone.Muted
                    }
                    Surface(
                        onClick = {
                            if (!submitted && !vm.coach.working) {
                                onChooseOption(if (isMine) "" else option.label)
                            }
                        },
                        enabled = !submitted && !vm.coach.working,
                        color = when {
                            !submitted && isMine -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            isKey -> Tone.Good.soft()
                            isMine -> verdictTone(verdict).soft()
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            if (isKey || isMine) 1.2.dp else 0.5.dp,
                            when {
                                !submitted && isMine -> MaterialTheme.colorScheme.primary
                                isKey || isMine -> optionTone.color().copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Surface(
                                color = when {
                                    !submitted && isMine -> MaterialTheme.colorScheme.primary
                                    isKey || isMine -> optionTone.color()
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(top = 2.dp).size(26.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isKey || isMine) Color.White else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    option.text,
                                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (submitted && (isMine || isKey)) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (isMine) Tag("我的选择", if (verdict == "需要回看") Tone.Must else if (verdict == "与参考一致") Tone.Good else Tone.Muted)
                                        if (isKey) Tag("参考答案", Tone.Good)
                                    }
                                }
                                if (note != null && note.note.isNotBlank()) Note(note.note, tone = if (note.correct) Tone.Good else Tone.Muted)
                            }
                        }
                    }
                }
            }
            if (submitted) {
                if (keyKnown && !showKey) TextButton(onClick = { reveal = true }, contentPadding = PaddingValues(0.dp)) { Text("显示参考答案") }
                if (!keyKnown) Note("这题没有可核实的参考答案，讲解只能作为推断。")
                talk.failureFor(entry.id, anchor, chat = false)?.let { FailureLine(it, talk::dismissFailure) }
                when {
                    explaining -> {
                        WorkingLine("正在讲解…", talk::stop)
                        explanation?.takeIf { it.structured }?.let { ExplanationView(it, null) { quote -> onLocate(passage.code, quote) } }
                    }
                    explanation == null -> {
                        Button(
                            onClick = { talk.send(entry, anchor, TurnKind.READING_EXPLAIN) { ReadingCoach.explainPrompt(passage, entry.answer, q.number) } },
                            enabled = !talk.busy,
                            modifier = Modifier.height(48.dp),
                        ) { Text("讲解这题") }
                        Note("回到原文讲清定位、同义替换和每个选项；不确定的地方会说明。")
                        if (!vm.aiReady || vm.chosenModel.isBlank()) Note("需要先在「我的 → AI 连接」配置 AI 服务并选择模型。")
                    }
                    !explanation.structured -> {
                        Note("回复未完整通过结构检查，以下保留全部原文。")
                        MarkdownBody(explanation.raw)
                        Provenance(explainTurn?.model.orEmpty())
                    }
                    else -> ExplanationView(explanation, explainTurn?.model) { quote -> onLocate(passage.code, quote) }
                }
                ReadingDiagnosis.saved(entry.feedback, q.number).takeIf(String::isNotBlank)?.let { legacy ->
                    var open by rememberSaveable(entry.id, q.number) { mutableStateOf(false) }
                    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "收起早期讲解" else "查看早期讲解") }
                    if (open) MarkdownBody(legacy)
                }
            }
        }
    }
    if (submitted) {
        LocalHints(entry, passage, q)
        if (!explaining) {
            SectionLabel("继续问")
            QuickAsks(
                CoachChat.suggestions(CoachKind.READING, anchor, mine.takeIf { verdict == "需要回看" }.orEmpty()),
                enabled = !talk.busy,
                onPick = onAsk,
                edge = PageGutter,
            )
            ThreadView(CoachTurns.thread(turns, anchor), pending, talk::stop, Modifier.padding(start = PageGutter, end = PageGutter, top = 12.dp))
        }
        ErrorNote(vm, entry, passage, ordinal)
    }
}

/** Structured explanation: where the answer is, how the wording maps, and a self-check, rendered in insight cards. */
@Composable
private fun ExplanationView(explanation: ReadingExplanation, model: String?, onLocate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        explanation.locate?.let { locate ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag("定位" + (locate.paragraph?.let { " · 第 $it 段" } ?: ""), Tone.Ink)
                        Spacer(Modifier.weight(1f))
                        if (locate.quote.isNotBlank()) {
                            TextButton(
                                onClick = { onLocate(locate.quote) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.heightIn(min = 28.dp),
                            ) { Text("在原文中看", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    // The located sentence quoted on a paper-coloured slip, like a cutting from the passage.
                    if (locate.quote.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    locate.quote,
                                    style = PassageText.copy(fontSize = 16.sp, lineHeight = 25.sp),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    if (locate.note.isNotBlank()) Text(locate.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (explanation.paraphrases.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val rowLabel = labelWidth(listOf("题干", "原文", "选项"), MaterialTheme.typography.bodySmall)
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tag("同义替换对照", Tone.Ink)
                    explanation.paraphrases.forEachIndexed { idx, row ->
                        if (idx > 0) Hairline()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("题干" to row.stem, "原文" to row.passage, "选项" to row.option).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                                Row(Modifier/* .semantics(mergeDescendants = true) {} */, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Note(label, Modifier.width(rowLabel).padding(top = 3.dp), tone = Tone.Ink)
                                    Text(value, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (explanation.misread.isNotBlank()) {
            Surface(
                color = Tone.Must.soft().copy(alpha = 0.65f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("可能的误读", Tone.Must)
                    Text(explanation.misread, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (explanation.checkQuestion.isNotBlank()) {
            var show by remember(explanation.checkQuestion) { mutableStateOf(false) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("自测检验", Tone.Ink)
                    Text(explanation.checkQuestion, style = MaterialTheme.typography.bodyMedium)
                    if (show) {
                        Text(explanation.checkAnswer, style = MaterialTheme.typography.bodyMedium, color = Tone.Good.color())
                    } else {
                        TextButton(onClick = { show = true }, contentPadding = PaddingValues(0.dp)) { Text("想好了，看答案") }
                    }
                }
            }
        }
        if (explanation.summary.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("这类题怎么做", Tone.Ink)
                    Text(explanation.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (model != null) Provenance(model)
    }
}

/** Offline, sourced hints in four steps; the last one shows the key with its source. */
@Composable
private fun LocalHints(entry: CoachPractice, passage: ExamPassage, q: ExamQuestion) {
    var level by rememberSaveable(entry.id, q.number) { mutableIntStateOf(0) }
    SectionLabel("自己先想", action = if (level == 0) "看提示" else "收起", onAction = { level = if (level == 0) 1 else 0 })
    if (level == 0) return
    PaperCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val steps = listOf("圈出题干线索", "回到原文定位", "读懂证据关系", "对照选项与参考")
            Text("$level / 4 · ${steps[level - 1]}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                when (level) {
                    1 -> "圈出题干里的主体、时间和限定词，想清楚要找的是什么信息。"
                    2 -> ReadingGuidance.paragraph(passage, q)?.let { "回到篇章 ${passage.code} 第 $it 段，找题干说法的同义表达。" }
                        ?: "这份资料没有记录证据位置。从题干关键词出发逐段找同义表达，不要把猜测当作确定位置。"
                    3 -> if (q.verifiedEvidence(passage) != null) {
                        "证据句：“${q.evidence}”。来源：${q.evidenceSource}。核对主语、转折、因果和范围。"
                    } else {
                        "资料没有经过原文匹配的证据句，可以请求讲解，再自己回原文核实。"
                    }
                    else -> "参考答案：${q.referenceAnswer.ifBlank { "暂无可核实答案" }}" + if (q.answerSource.isNotBlank()) "（来源已记录）" else ""
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (level > 1) OutlinedButton(onClick = { level-- }) { Text("上一步") }
                if (level < 4) Button(onClick = { level++ }) { Text("下一步") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorNote(vm: StudyViewModel, entry: CoachPractice, passage: ExamPassage, ordinal: Int) {
    val state = vm.coach
    var open by rememberSaveable(entry.id) { mutableStateOf(entry.errorReason.isNotBlank()) }
    SectionLabel("错因笔记", action = if (open) "收起" else "记一笔", onAction = { open = !open })
    if (!open) return
    PaperCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("词汇理解", "长难句", "逻辑推断", "选项陷阱", "录入更正").forEach { reason ->
                    SuggestionChip(
                        onClick = {
                            state.edit(CoachKind.READING) {
                                val note = "$reason：篇章 ${passage.code} 第 $ordinal 题"
                                it.copy(
                                    errorReason = if (it.errorReason.split('；').contains(note)) {
                                        it.errorReason
                                    } else {
                                        listOf(it.errorReason, note).filter(String::isNotBlank).joinToString("；")
                                    },
                                )
                            }
                        },
                        label = { Text(reason) },
                        enabled = !state.working,
                        border = null,
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }
            Hairline()
            FieldRow(
                null,
                entry.errorReason,
                { value -> state.edit(CoachKind.READING) { it.copy(errorReason = value) } },
                placeholder = "我的错因与定位笔记",
                enabled = !state.working,
                minLines = 3,
            )
            if (entry.errorReason.isNotBlank()) {
                TextButton(onClick = { state.generate(CoachKind.READING) }, enabled = !state.working && !vm.busy) {
                    Text("按错因出一道练习")
                }
            }
            state.messages[CoachKind.READING]?.takeIf { it.isNotBlank() && it != "已保存到本机" && it != "正在保存……" }?.let { Note(it) }
        }
    }
}

/**
 * The selected question's passage beside its explanation. The evidence (a located quote, else the sourced
 * evidence sentence) is tinted and scrolled into view; without either, the passage simply starts at the top.
 */
@Composable
private fun PassagePeek(
    passages: List<ExamPassage>,
    passage: ExamPassage,
    q: ExamQuestion,
    quote: String?,
    tallPanel: Boolean,
    onToggleRatio: () -> Unit,
    onOpenFull: () -> Unit,
    onSelectPassage: (ExamPassage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paragraphs = remember(passage.content) { PassageLayout.paragraphs(passage.content) }
    val evidence = quote?.takeIf(String::isNotBlank) ?: q.verifiedEvidence(passage)
    val marks = remember(paragraphs, evidence) { paragraphs.map { para -> evidence?.let { TextMarks.locate(para, it) } } }
    val target = marks.indexOfFirst { it != null }.takeIf { it >= 0 } ?: ReadingGuidance.paragraph(passage, q)?.minus(1)
    val list = rememberLazyListState()
    LaunchedEffect(passage.code, target) { list.scrollToItem(target?.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) ?: 0) }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f))
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            passages.forEach { p ->
                val active = p.code == passage.code
                Surface(
                    onClick = { if (!active) onSelectPassage(p) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = if (active) null else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        "篇章 ${p.code}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Note(target?.let { "依据在第 ${it + 1} 段" } ?: "", Modifier.weight(1f))
            TextButton(
                onClick = onToggleRatio,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 30.dp),
            ) { Text(if (tallPanel) "扩大原文" else "扩大题目", style = MaterialTheme.typography.labelMedium) }
            TextButton(
                onClick = onOpenFull,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 30.dp),
            ) { Text("逐段讲解", style = MaterialTheme.typography.labelMedium) }
        }
        Hairline()
        val numberWidth = labelWidth(listOf("${paragraphs.size}"), MaterialTheme.typography.labelMedium)
        val soft = Tone.Better.soft()
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = list,
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(paragraphs) { i, paragraph ->
                Row {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (marks[i] != null) Tone.Better.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (marks[i] != null) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.width(numberWidth).padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                    Spacer(Modifier.width(10.dp))
                    SelectionContainer {
                        Text(
                            buildAnnotatedString {
                                append(paragraph)
                                marks[i]?.let { addStyle(SpanStyle(background = soft), it.first, it.last + 1) }
                            },
                            style = PassageText.copy(fontSize = 16.sp, lineHeight = 25.sp),
                        )
                    }
                }
            }
        }
    }
}

/** The passages as an editorial book page: numbered paragraphs; tap one for translation, sentence breakdown or gist. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PassagePane(vm: StudyViewModel, entry: CoachPractice, passages: List<ExamPassage>, turns: List<CoachTurn>, focus: ReaderFocus?) {
    val talk = vm.talk
    var sheet by rememberSaveable(entry.id) { mutableStateOf<String?>(null) }
    val requesters = remember(entry.id) { mutableMapOf<String, BringIntoViewRequester>() }
    passages.forEach { p ->
        val paragraphs = remember(p.content) { PassageLayout.paragraphs(p.content) }
        val overviewAnchor = Anchor.overview(p.code.toIntOrNull() ?: 0)
        val overviewTurn = CoachTurns.latest(turns, overviewAnchor, TurnKind.READING_OVERVIEW)
        val pending = talk.pendingFor(entry.id, overviewAnchor)
        val overview = remember(overviewTurn?.content, pending?.text) {
            (pending?.text?.takeIf { pending.kind == TurnKind.READING_OVERVIEW } ?: overviewTurn?.content)?.let { PassageOverview.parse(it, paragraphs.size) }
        }
        val words = remember(p.content) { WritingCoach.words(p.content) }
        SectionLabel(
            "篇章 ${p.code} · 约 $words 词 · ${paragraphs.size} 段",
            action = if (overviewTurn == null && pending == null) "梳理结构" else null,
            onAction = { talk.send(entry, overviewAnchor, TurnKind.READING_OVERVIEW) { ReadingCoach.overviewPrompt(p) } },
        )
        talk.failureFor(entry.id, overviewAnchor)?.let {
            FailureLine(it, talk::dismissFailure, Modifier.padding(start = CardMargin, end = CardMargin, bottom = 8.dp))
        }
        if (pending?.kind == TurnKind.READING_OVERVIEW) WorkingLine("正在梳理…", talk::stop, Modifier.padding(horizontal = PageGutter))
        overview?.takeIf { it.main.isNotBlank() || it.structure.isNotBlank() }?.let {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(start = CardMargin, end = CardMargin, bottom = 10.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag("篇章导读", Tone.Ink)
                    if (it.main.isNotBlank()) Text("主旨：${it.main}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (it.structure.isNotBlank()) Note("结构：${it.structure}")
                }
            }
        }
        val numberWidth = labelWidth(listOf("${paragraphs.size}"), MaterialTheme.typography.labelMedium)
        PaperCard(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            padding = PaddingValues(vertical = 8.dp),
        ) {
            paragraphs.forEachIndexed { i, paragraph ->
                val index = i + 1
                val anchor = Anchor.paragraph(p.code.toIntOrNull() ?: 0, index)
                val requester = requesters.getOrPut("${p.code}/$index") { BringIntoViewRequester() }
                val highlight = focus?.takeIf { it.passage == p.code }?.let { TextMarks.locate(paragraph, it.quote) }
                val gist = overview?.paragraphs?.firstOrNull { it.index == index }
                val hasNotes = turns.any { it.anchor == anchor }
                Row(
                    Modifier.fillMaxWidth()
                        .bringIntoViewRequester(requester)
                        .clickable(/* onClickLabel = "翻译、拆解长句或看段意" */) { sheet = anchor }
                        .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    Text(
                        "$index",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasNotes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (hasNotes) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.width(numberWidth).padding(top = 5.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (gist != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Note(
                                    listOf(gist.role, gist.gist).filter(String::isNotBlank).joinToString(" · "),
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    tone = Tone.Ink,
                                )
                            }
                        }
                        val soft = Tone.Better.soft()
                        Text(
                            buildAnnotatedString {
                                append(paragraph)
                                highlight?.let { addStyle(SpanStyle(background = soft), it.first, it.last + 1) }
                            },
                            style = PassageText,
                        )
                        if (hasNotes) Tag("已有讲解 · 点开查看", Tone.Ink)
                    }
                }
                if (highlight != null) LaunchedEffect(focus) { requester.bringIntoView() }
            }
        }
    }
    Note("点任意一段，可以翻译、拆解长句或看段意。", Modifier.padding(horizontal = PageGutter, vertical = 12.dp))
    sheet?.let { anchor ->
        val (code, index) = Anchor.paragraphOf(anchor) ?: return@let
        val passage = passages.firstOrNull { it.code == "$code" } ?: return@let
        ParagraphSheet(vm, entry, passage, index, anchor, turns) { sheet = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParagraphSheet(
    vm: StudyViewModel,
    entry: CoachPractice,
    passage: ExamPassage,
    index: Int,
    anchor: String,
    turns: List<CoachTurn>,
    onDismiss: () -> Unit,
) {
    val talk = vm.talk
    val thread = CoachTurns.thread(turns, anchor)
    val pending = talk.pendingFor(entry.id, anchor)
    val paragraph = PassageLayout.paragraphs(passage.content).getOrNull(index - 1).orEmpty()
    fun ask(question: String) = talk.send(entry, anchor, TurnKind.CHAT, question) { CoachChat.prompt(entry, anchor, thread, question) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("篇章 ${passage.code} · 第 $index 段")
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(paragraph, style = PassageText.copy(fontSize = 16.sp, lineHeight = 26.sp)) }
                ThreadView(thread, pending, talk::stop, kinds = setOf(TurnKind.CHAT, TurnKind.READING_PARAGRAPH))
                QuickAsks(
                    ParagraphHelp.entries.map { it.label } + CoachChat.suggestions(CoachKind.READING, anchor).takeIf { thread.isNotEmpty() }.orEmpty(),
                    enabled = !talk.busy,
                    onPick = { label ->
                        val help = ParagraphHelp.entries.firstOrNull { it.label == label }
                        if (help != null) {
                            talk.send(entry, anchor, TurnKind.READING_PARAGRAPH, help.label) { ReadingCoach.paragraphPrompt(passage, index, help) }
                        } else {
                            ask(label)
                        }
                    },
                )
                if (!vm.aiReady || vm.chosenModel.isBlank()) Note("需要先在「我的 → AI 连接」配置 AI 服务并选择模型。")
            }
            AskBar(
                "关于这一段，想问什么…",
                busy = talk.busy,
                enabled = !talk.busy,
                onSend = ::ask,
                onStop = talk::stop,
                draftKey = entry.id + anchor,
                failure = talk.failureFor(entry.id, anchor),
                onDismissFailure = talk::dismissFailure,
            )
        }
    }
}
