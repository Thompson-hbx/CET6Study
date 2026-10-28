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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    val talk = vm.talk
    val entry = state.entries[CoachKind.READING]
    if (entry == null) { LoadingScreen(onBack); return }
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
    var tab by rememberSaveable(entry.id) { mutableIntStateOf(savedPosition?.first ?: if (submitted) 1 else 0) }
    var selected by rememberSaveable(entry.id) { mutableIntStateOf(savedPosition?.second ?:
        questions.firstOrNull { submitted && ReadingGuidance.verdict(it, answers[it.number].orEmpty()) == "需要回看" }?.number ?: questions.first().number) }
    var invalid by rememberSaveable(entry.id) { mutableStateOf("") }
    var focus by remember(entry.id) { mutableStateOf<ReaderFocus?>(null) }
    var peekOpen by rememberSaveable(entry.id) { mutableStateOf(true) }
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { if (focus == null || tab != 2) scroll.scrollTo(0) }
    LaunchedEffect(entry.id, tab, selected) { vm.rememberReadingPosition(entry.id, tab, selected) }
    LaunchedEffect(submitted) {
        if (submitted && !initiallySubmitted) {
            tab = 1
            selected = questions.firstOrNull { ReadingGuidance.verdict(it, answers[it.number].orEmpty()) == "需要回看" }?.number ?: selected
        }
    }
    val anchor = Anchor.question(selected)
    val savedFocus = CoachTurns.latest(turns, anchor, TurnKind.READING_EXPLAIN)?.let { turn ->
        ReadingLocation.restored(entry, turn)?.let { quote ->
            passages.firstOrNull { p -> p.questions.any { it.number == selected } }?.let { ReaderFocus(it.code, quote) }
        }
    }
    fun ask(question: String) = talk.send(entry, anchor, TurnKind.CHAT, question) {
        CoachChat.prompt(entry, anchor, CoachTurns.thread(turns, anchor), question)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(entry.title, subtitle = "仔细阅读 · Section C" + if (submitted) " · 已核对" else "", onBack = onBack,
            actions = { WorkspaceMenu(vm, entry) }) },
        bottomBar = {
            when {
                tab == 0 -> BottomBar {
                    val answered = answers.count { it.value.isNotBlank() }
                    Column(Modifier.weight(1f)) {
                        Text("已录 $answered / ${questions.size}", style = MaterialTheme.typography.titleSmall)
                        Note(if (invalid.isNotBlank()) "有一篇输入格式不对" else saveLabel(state.messages[CoachKind.READING]),
                            tone = if (invalid.isNotBlank()) Tone.Must else Tone.Muted)
                    }
                    if (!submitted) Button(onClick = { state.submit(CoachKind.READING, false) },
                        enabled = answered > 0 && invalid.isBlank() && !state.working, modifier = Modifier.height(48.dp)) { Text("核对答案") }
                    else Button(onClick = { state.open(CoachKind.READING, entry, retry = true) }, enabled = !state.working,
                        modifier = Modifier.height(48.dp)) { Text("再练一次") }
                }
                tab == 1 && submitted -> BottomBar {
                    AskBar("问问这道题…", busy = talk.busy, enabled = !talk.busy, onSend = ::ask, onStop = talk::stop,
                        modifier = Modifier.weight(1f), draftKey = entry.id + anchor,
                        failure = talk.failureFor(entry.id, anchor, chat = true), onDismissFailure = talk::dismissFailure)
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("答题卡", "讲解", "原文").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { focus = null; tab = i }, text = { Text(label) })
                }
            }
            if (tab == 1 && submitted) {
                // Passage above, question below: checking an answer never means flipping to another tab and back.
                val q = questions.firstOrNull { it.number == selected } ?: questions.first()
                val passage = passages.first { p -> p.questions.any { it.number == q.number } }
                val savedQuote = CoachTurns.latest(turns, Anchor.question(q.number), TurnKind.READING_EXPLAIN)
                    ?.let { ReadingLocation.restored(entry, it) }
                if (savedQuote != null) Text("AI 定位 · 已匹配原文，待人工确认", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = PageGutter))
                PassagePeek(passage, q, focus?.takeIf { it.passage == passage.code }?.quote ?: savedQuote, peekOpen, onToggle = { peekOpen = !peekOpen },
                    onOpenFull = { focus = focus ?: savedFocus; tab = 2 }, modifier = if (peekOpen) Modifier.weight(0.42f) else Modifier)
                Hairline()
                Column(Modifier.weight(0.58f).fillMaxWidth().verticalScroll(scroll).padding(bottom = 32.dp)) {
                    ExplainPane(vm, entry, passages, questions, answers, turns, selected, onSelect = { focus = null; selected = it }, onAsk = ::ask) { code, quote ->
                        focus = ReaderFocus(code, quote); peekOpen = true
                    }
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 32.dp)) {
                when (tab) {
                    0 -> AnswerCardPane(vm, entry, passages, answers, submitted) { code, bad ->
                        invalid = if (bad) (invalid + code).toSet().joinToString("") else invalid.replace(code, "")
                    }
                    1 -> EmptyNote("在答题卡录入纸上的答案并核对后，就可以逐题讲解。", "去录入") { tab = 0 }
                    else -> PassagePane(vm, entry, passages, turns, focus ?: savedFocus)
                }
            }
        }
    }
}

/** Answer sheet: five cells per passage plus a one-line entry such as CADCB, both kept in sync. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerCardPane(vm: StudyViewModel, entry: CoachPractice, passages: List<ExamPassage>, answers: Map<Int, String>,
    submitted: Boolean, onValidity: (String, Boolean) -> Unit) {
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
    fun focusAfter(index: Int) { focusTarget = if (index + 1 < passages.size) index + 1 else -1 }
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
                    typed[code] = chunk; errors.remove(code); onValidity(code, false)
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
    Spacer(Modifier.height(16.dp))
    passages.forEachIndexed { index, p -> key(p.code) {
        val ordered = p.questions.sortedBy { it.number }
        var picking by rememberSaveable(entry.id, p.code) { mutableIntStateOf(-1) }
        val error = errors[p.code].orEmpty()
        PaperCard(Modifier.padding(bottom = 12.dp)) {
            CardTitle("篇章 ${p.code} · 第 ${ordered.first().number}–${ordered.last().number} 题", Modifier.padding(bottom = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ordered.forEachIndexed { i, q ->
                    val mine = answers[q.number].orEmpty()
                    AnswerCell("${i + 1}", mine, picking == i, null, Modifier.weight(1f),
                        spoken = "篇章 ${p.code} 第 ${i + 1} 题，${mine.ifBlank { "未填" }}", enabled = !submitted && !state.working) {
                        if (!submitted) picking = if (picking == i) -1 else i
                    }
                }
            }
            if (picking in ordered.indices && !submitted) {
                val target = ordered[picking]
                // Wraps instead of squeezing 清除 off the card at large font sizes.
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Note("第 ${picking + 1} 题", Modifier.width(labelWidth(listOf("第 5 题"), MaterialTheme.typography.bodySmall))
                        .align(Alignment.CenterVertically))
                    listOf("A", "B", "C", "D").forEach { c ->
                        FilterChip(selected = answers[target.number] == c, onClick = {
                            state.edit(CoachKind.READING) { it.copy(answer = ReadingQuestions.choose(it.answer, "${target.number}", c)) }
                            typed.remove(p.code); errors.remove(p.code); onValidity(p.code, false)
                            picking = if (picking < ordered.lastIndex) picking + 1 else -1
                        }, label = { Text(c, fontWeight = FontWeight.SemiBold) }, enabled = !state.working)
                    }
                    if (answers[target.number].orEmpty().isNotBlank()) TextButton(onClick = {
                        state.edit(CoachKind.READING) { it.copy(answer = ReadingQuestions.choose(it.answer, "${target.number}", "")) }
                        typed.remove(p.code)
                    }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("清除") }
                }
            }
            val more = index < passages.lastIndex
            OutlinedTextField(
                value = shown(p),
                onValueChange = { onType(index, it) },
                label = { Text("按顺序连续输入") }, placeholder = { Text("如 CADCB，没做的写 ?") },
                supportingText = {
                    if (error.isNotBlank()) Text(error, color = Tone.Must.color())
                    else if (more && !submitted) Text("可以一次输入 ${passages.size * 5} 个，超出的自动接到下一篇")
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii,
                    imeAction = if (more) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(onNext = { focusAfter(index) }, onDone = { focusManager.clearFocus() }),
                textStyle = MaterialTheme.typography.titleMedium.copy(letterSpacing = 4.sp),
                singleLine = true, readOnly = submitted, enabled = !state.working, isError = error.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(requesters[index]))
        }
    } }
    val missingKey = passages.flatMap { it.questions }.count { it.referenceAnswer.isBlank() || it.answerSource.isBlank() }
    if (missingKey > 0) Note("$missingKey 题缺少有来源的参考答案，核对时只显示待核对。", Modifier.padding(horizontal = PageGutter, vertical = 4.dp))
    if (submitted) Note("这一次已经核对，答案已锁定；要重新做一遍，点下方「再练一次」。", Modifier.padding(horizontal = PageGutter, vertical = 4.dp))
}

/** One bubble of the answer sheet; the bottom stripe carries the check result once there is one. */
@Composable
private fun AnswerCell(number: String, value: String, selected: Boolean, tone: Tone?, modifier: Modifier = Modifier,
    spoken: String, enabled: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    val marked = tone == Tone.Good || tone == Tone.Must
    val stripe = if (marked) tone!!.color() else Color.Transparent
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(if (compact) 48.dp else 60.dp).semantics { contentDescription = spoken; if (selected) stateDescription = "已选中" },
        color = if (marked) tone!!.soft() else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Compact cells (the explain pane) drop the number row: position already says which question it is.
            if (!compact) Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
            Text(value.ifBlank { "·" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (marked) tone!!.color() else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).wrapContentHeight())
            Box(Modifier.fillMaxWidth().height(3.dp).background(stripe))
        }
    }
}

private fun verdictTone(verdict: String) = when (verdict) {
    "与参考一致" -> Tone.Good
    "需要回看" -> Tone.Must
    else -> Tone.Muted
}

private fun verdictLabel(verdict: String) = when (verdict) { "未录入" -> "未录入"; "暂无可核实答案" -> "待核对"; else -> verdict }

@Composable
private fun ExplainPane(vm: StudyViewModel, entry: CoachPractice, passages: List<ExamPassage>, questions: List<ExamQuestion>,
    answers: Map<Int, String>, turns: List<CoachTurn>, selected: Int, onSelect: (Int) -> Unit, onAsk: (String) -> Unit,
    onLocate: (String, String) -> Unit) {
    val known = questions.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() && answers[it.number].orEmpty().isNotBlank() }
    val matched = known.count { answers[it.number] == it.referenceAnswer }
    val unanswered = questions.count { answers[it.number].orEmpty().isBlank() }
    val unknown = questions.count { answers[it.number].orEmpty().isNotBlank() && (it.referenceAnswer.isBlank() || it.answerSource.isBlank()) }
    val passageLabel = labelWidth(passages.map { "篇章 ${it.code}" }, MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(16.dp))
    PaperCard {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$matched", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("/ ${known.size} 与参考一致", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        }
        Note(listOfNotNull("需要回看 ${known.size - matched}", if (unanswered > 0) "未录入 $unanswered" else null,
            if (unknown > 0) "待核对 $unknown" else null).joinToString(" · "))
        passages.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("篇章 ${p.code}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, modifier = Modifier.width(passageLabel))
                p.questions.sortedBy { it.number }.forEachIndexed { i, q ->
                    val mine = answers[q.number].orEmpty()
                    val verdict = ReadingGuidance.verdict(q, mine)
                    AnswerCell("${i + 1}", mine, q.number == selected, verdictTone(verdict), Modifier.weight(1f),
                        spoken = "篇章 ${p.code} 第 ${i + 1} 题，${mine.ifBlank { "未填" }}，${verdictLabel(verdict)}", compact = true) { onSelect(q.number) }
                }
            }
        }
    }
    val q = questions.firstOrNull { it.number == selected } ?: questions.first()
    val passage = passages.first { p -> p.questions.any { it.number == q.number } }
    key(q.number) { QuestionDetail(vm, entry, passage, q, answers[q.number].orEmpty(), turns, onAsk, onLocate) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionDetail(vm: StudyViewModel, entry: CoachPractice, passage: ExamPassage, q: ExamQuestion, mine: String,
    turns: List<CoachTurn>, onAsk: (String) -> Unit, onLocate: (String, String) -> Unit) {
    val talk = vm.talk
    val anchor = Anchor.question(q.number)
    val ordinal = passage.questions.sortedBy { it.number }.indexOfFirst { it.number == q.number } + 1
    val verdict = ReadingGuidance.verdict(q, mine)
    val explainTurn = CoachTurns.latest(turns, anchor, TurnKind.READING_EXPLAIN)
    val pending = talk.pendingFor(entry.id, anchor)
    val explaining = pending?.kind == TurnKind.READING_EXPLAIN
    val text = if (explaining) pending!!.text else explainTurn?.content
    val explanation = remember(text) { text?.let(ReadingExplanation::parse) }
    var reveal by rememberSaveable(entry.id, q.number) { mutableStateOf(false) }
    val keyKnown = q.referenceAnswer.isNotBlank() && q.answerSource.isNotBlank()
    val showKey = keyKnown && (reveal || explanation?.takeIf { it.structured }?.options?.isNotEmpty() == true)
    PaperCard(Modifier.padding(top = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle("篇章 ${passage.code} · 第 $ordinal 题", Modifier.weight(1f))
                DotStatus(verdictLabel(verdict), verdictTone(verdict))
            }
            SelectionContainer { Text(q.stem, style = PassageText) }
            val optionLabel = labelWidth(q.options.map { it.label }, MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                q.options.forEach { option ->
                    val note = explanation?.takeIf { it.structured }?.options?.firstOrNull { it.label == option.label }
                    val isKey = showKey && q.referenceAnswer == option.label
                    val isMine = mine == option.label
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(optionLabel),
                            color = when { isKey -> Tone.Good.color(); isMine && verdict == "需要回看" -> Tone.Must.color(); else -> MaterialTheme.colorScheme.onSurface })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectionContainer { Text(option.text, style = PassageText.copy(fontSize = 16.sp, lineHeight = 25.sp)) }
                            if (isMine || isKey) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isMine) Tag("我的选择", if (verdict == "需要回看") Tone.Must else if (verdict == "与参考一致") Tone.Good else Tone.Muted)
                                if (isKey) Tag("参考答案", Tone.Good)
                            }
                            if (note != null && note.note.isNotBlank()) Note(note.note, tone = if (note.correct) Tone.Good else Tone.Muted)
                        }
                    }
                }
            }
            if (keyKnown && !showKey) TextButton(onClick = { reveal = true }, contentPadding = PaddingValues(0.dp)) { Text("显示参考答案") }
            if (!keyKnown) Note("这题没有可核实的参考答案，讲解只能作为推断。")
            talk.failureFor(entry.id, anchor, chat = false)?.let { FailureLine(it, talk::dismissFailure) }
            when {
                explaining -> {
                    WorkingLine("正在讲解…", talk::stop)
                    explanation?.takeIf { it.structured }?.let { ExplanationView(it, null) { quote -> onLocate(passage.code, quote) } }
                }
                explanation == null -> {
                    Button(onClick = { talk.send(entry, anchor, TurnKind.READING_EXPLAIN) { ReadingCoach.explainPrompt(passage, entry.answer, q.number) } },
                        enabled = !talk.busy, modifier = Modifier.height(48.dp)) { Text("讲解这题") }
                    Note("回到原文讲清定位、同义替换和每个选项；不确定的地方会说明。")
                    if (!vm.aiReady || vm.chosenModel.isBlank()) Note("需要先在「我的 → AI 连接」配置 AI 服务并选择模型。")
                }
                !explanation.structured -> { Note("回复未完整通过结构检查，以下保留全部原文。"); MarkdownBody(explanation.raw); Provenance(explainTurn?.model.orEmpty()) }
                else -> ExplanationView(explanation, explainTurn?.model) { quote -> onLocate(passage.code, quote) }
            }
            ReadingDiagnosis.saved(entry.feedback, q.number).takeIf(String::isNotBlank)?.let { legacy ->
                var open by rememberSaveable(entry.id, q.number) { mutableStateOf(false) }
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "收起早期讲解" else "查看早期讲解") }
                if (open) MarkdownBody(legacy)
            }
        }
    }
    LocalHints(entry, passage, q)
    if (!explaining) {
        // Follow-up failures show above the composer in the bottom bar, not mid-page out of sight.
        SectionLabel("继续问")
        QuickAsks(CoachChat.suggestions(CoachKind.READING, anchor, mine.takeIf { verdict == "需要回看" }.orEmpty()),
            enabled = !talk.busy, onPick = onAsk, edge = PageGutter)
        ThreadView(CoachTurns.thread(turns, anchor), pending, talk::stop, Modifier.padding(start = PageGutter, end = PageGutter, top = 12.dp))
    }
    ErrorNote(vm, entry, passage, ordinal)
}

/** Structured explanation: where the answer is, how the wording maps, and a self-check. */
@Composable
private fun ExplanationView(explanation: ReadingExplanation, model: String?, onLocate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        explanation.locate?.let { locate ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("定位" + (locate.paragraph?.let { " · 第 $it 段" } ?: ""), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (locate.quote.isNotBlank()) TextButton(onClick = { onLocate(locate.quote) }) { Text("在原文中看") }
                }
                if (locate.quote.isNotBlank()) Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
                    SelectionContainer {
                        Text(locate.quote, style = PassageText.copy(fontSize = 16.sp, lineHeight = 26.sp), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (locate.note.isNotBlank()) Text(locate.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (explanation.paraphrases.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rowLabel = labelWidth(listOf("题干", "原文", "选项"), MaterialTheme.typography.bodySmall)
            Text("同义替换", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            explanation.paraphrases.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("题干" to row.stem, "原文" to row.passage, "选项" to row.option).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                        Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Note(label, Modifier.width(rowLabel).padding(top = 3.dp))
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (explanation.misread.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("可能的误读", style = MaterialTheme.typography.labelLarge, color = Tone.Must.color())
            Text(explanation.misread, style = MaterialTheme.typography.bodyMedium)
        }
        if (explanation.checkQuestion.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var show by remember(explanation.checkQuestion) { mutableStateOf(false) }
            Text("自测", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(explanation.checkQuestion, style = MaterialTheme.typography.bodyMedium)
            if (show) Text(explanation.checkAnswer, style = MaterialTheme.typography.bodyMedium, color = Tone.Good.color())
            else TextButton(onClick = { show = true }, contentPadding = PaddingValues(0.dp)) { Text("想好了，看答案") }
        }
        if (explanation.summary.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("这类题怎么做", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(explanation.summary, style = MaterialTheme.typography.bodyMedium)
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
            Text("${level} / 4 · ${steps[level - 1]}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(when (level) {
                1 -> "圈出题干里的主体、时间和限定词，想清楚要找的是什么信息。"
                2 -> ReadingGuidance.paragraph(passage, q)?.let { "回到篇章 ${passage.code} 第 $it 段，找题干说法的同义表达。" }
                    ?: "这份资料没有记录证据位置。从题干关键词出发逐段找同义表达，不要把猜测当作确定位置。"
                3 -> if (q.verifiedEvidence(passage) != null) "证据句：“${q.evidence}”。来源：${q.evidenceSource}。核对主语、转折、因果和范围。"
                    else "资料没有经过原文匹配的证据句，可以请求讲解，再自己回原文核实。"
                else -> "参考答案：${q.referenceAnswer.ifBlank { "暂无可核实答案" }}" + if (q.answerSource.isNotBlank()) "（来源已记录）" else ""
            }, style = MaterialTheme.typography.bodyLarge)
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
                    SuggestionChip(onClick = { state.edit(CoachKind.READING) {
                        val note = "$reason：篇章 ${passage.code} 第 $ordinal 题"
                        it.copy(errorReason = if (it.errorReason.split('；').contains(note)) it.errorReason
                            else listOf(it.errorReason, note).filter(String::isNotBlank).joinToString("；"))
                    } }, label = { Text(reason) }, enabled = !state.working)
                }
            }
            OutlinedTextField(entry.errorReason, { value -> state.edit(CoachKind.READING) { it.copy(errorReason = value) } },
                label = { Text("我的错因与定位笔记") }, enabled = !state.working, modifier = Modifier.fillMaxWidth())
            if (entry.errorReason.isNotBlank()) OutlinedButton(onClick = { state.generate(CoachKind.READING) }, enabled = !state.working && !vm.busy) {
                Text("按错因出一道练习")
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
private fun PassagePeek(passage: ExamPassage, q: ExamQuestion, quote: String?, open: Boolean, onToggle: () -> Unit,
    onOpenFull: () -> Unit, modifier: Modifier = Modifier) {
    val paragraphs = remember(passage.content) { PassageLayout.paragraphs(passage.content) }
    val evidence = quote?.takeIf(String::isNotBlank) ?: q.verifiedEvidence(passage)
    val marks = remember(paragraphs, evidence) { paragraphs.map { para -> evidence?.let { TextMarks.locate(para, it) } } }
    val target = marks.indexOfFirst { it != null }.takeIf { it >= 0 } ?: ReadingGuidance.paragraph(passage, q)?.minus(1)
    val list = rememberLazyListState()
    LaunchedEffect(passage.code, target, open) { if (open) list.scrollToItem(target?.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) ?: 0) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = PageGutter, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Note("原文 · 篇章 ${passage.code}" + (target?.let { " · 依据在第 ${it + 1} 段" } ?: ""), Modifier.weight(1f))
            TextButton(onClick = onOpenFull) { Text("全文") }
            TextButton(onClick = onToggle) { Text(if (open) "收起原文" else "展开原文") }
        }
        if (open) {
            val numberWidth = labelWidth(listOf("${paragraphs.size}"), MaterialTheme.typography.labelMedium)
            val soft = Tone.Better.soft()
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list,
                contentPadding = PaddingValues(start = 12.dp, end = PageGutter, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(paragraphs) { i, paragraph ->
                    Row {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(numberWidth).padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Spacer(Modifier.width(10.dp))
                        SelectionContainer {
                            Text(buildAnnotatedString {
                                append(paragraph)
                                marks[i]?.let { addStyle(SpanStyle(background = soft), it.first, it.last + 1) }
                            }, style = PassageText.copy(fontSize = 16.sp, lineHeight = 26.sp))
                        }
                    }
                }
            }
        }
    }
}

/** The passages as a book page: numbered paragraphs; tap one for help with it. */
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
        SectionLabel("篇章 ${p.code} · 约 $words 词 · ${paragraphs.size} 段",
            action = if (overviewTurn == null && pending == null) "梳理结构" else null,
            onAction = { talk.send(entry, overviewAnchor, TurnKind.READING_OVERVIEW) { ReadingCoach.overviewPrompt(p) } })
        talk.failureFor(entry.id, overviewAnchor)?.let { FailureLine(it, talk::dismissFailure, Modifier.padding(start = CardMargin, end = CardMargin, bottom = 8.dp)) }
        if (pending?.kind == TurnKind.READING_OVERVIEW) WorkingLine("正在梳理…", talk::stop, Modifier.padding(horizontal = PageGutter))
        overview?.takeIf { it.main.isNotBlank() || it.structure.isNotBlank() }?.let {
            Column(Modifier.padding(start = PageGutter, end = PageGutter, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (it.main.isNotBlank()) Text("主旨：${it.main}", style = MaterialTheme.typography.bodyMedium)
                if (it.structure.isNotBlank()) Note("结构：${it.structure}")
            }
        }
        val numberWidth = labelWidth(listOf("${paragraphs.size}"), MaterialTheme.typography.labelMedium)
        RowCard {
            paragraphs.forEachIndexed { i, paragraph ->
                val index = i + 1
                val anchor = Anchor.paragraph(p.code.toIntOrNull() ?: 0, index)
                val requester = requesters.getOrPut("${p.code}/$index") { BringIntoViewRequester() }
                val highlight = focus?.takeIf { it.passage == p.code }?.let { TextMarks.locate(paragraph, it.quote) }
                val gist = overview?.paragraphs?.firstOrNull { it.index == index }
                Row(Modifier.fillMaxWidth().bringIntoViewRequester(requester).clickable(onClickLabel = "翻译、拆解长句或看段意") { sheet = anchor }
                    .padding(start = 12.dp, end = CardPadding, top = 10.dp, bottom = 10.dp)) {
                    Text("$index", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(numberWidth).padding(top = 5.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (gist != null) Note(listOf(gist.role, gist.gist).filter(String::isNotBlank).joinToString(" · "), tone = Tone.Ink)
                        val soft = Tone.Better.soft()
                        Text(buildAnnotatedString {
                            append(paragraph)
                            highlight?.let { addStyle(SpanStyle(background = soft), it.first, it.last + 1) }
                        }, style = PassageText)
                        if (turns.any { it.anchor == anchor }) Note("已有讲解 · 点开查看", tone = Tone.Ink)
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
private fun ParagraphSheet(vm: StudyViewModel, entry: CoachPractice, passage: ExamPassage, index: Int, anchor: String,
    turns: List<CoachTurn>, onDismiss: () -> Unit) {
    val talk = vm.talk
    val thread = CoachTurns.thread(turns, anchor)
    val pending = talk.pendingFor(entry.id, anchor)
    val paragraph = PassageLayout.paragraphs(passage.content).getOrNull(index - 1).orEmpty()
    fun ask(question: String) = talk.send(entry, anchor, TurnKind.CHAT, question) { CoachChat.prompt(entry, anchor, thread, question) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("篇章 ${passage.code} · 第 $index 段")
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(paragraph, style = PassageText.copy(fontSize = 16.sp, lineHeight = 26.sp)) }
                ThreadView(thread, pending, talk::stop, kinds = setOf(TurnKind.CHAT, TurnKind.READING_PARAGRAPH))
                QuickAsks(ParagraphHelp.entries.map { it.label } + CoachChat.suggestions(CoachKind.READING, anchor).takeIf { thread.isNotEmpty() }.orEmpty(),
                    enabled = !talk.busy, onPick = { label ->
                        val help = ParagraphHelp.entries.firstOrNull { it.label == label }
                        if (help != null) talk.send(entry, anchor, TurnKind.READING_PARAGRAPH, help.label) { ReadingCoach.paragraphPrompt(passage, index, help) }
                        else ask(label)
                    })
                if (!vm.aiReady || vm.chosenModel.isBlank()) Note("需要先在「我的 → AI 连接」配置 AI 服务并选择模型。")
            }
            AskBar("关于这一段，想问什么…", busy = talk.busy, enabled = !talk.busy, onSend = ::ask, onStop = talk::stop, draftKey = entry.id + anchor,
                failure = talk.failureFor(entry.id, anchor), onDismissFailure = talk::dismissFailure)
        }
    }
}
