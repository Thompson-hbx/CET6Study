package com.example.cet6.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.PassageText
import com.example.cet6.ui.theme.SourceText
import kotlinx.coroutines.launch

/** Translation desk: numbered source sentences, the learner's version, and a sentence-aligned review. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationWorkspace(vm: StudyViewModel, onBack: () -> Unit) {
    val state = vm.coach
    val talk = vm.talk
    val entry = state.entries[CoachKind.TRANSLATION]
    if (entry == null) { LoadingScreen(onBack); return }
    val turns = rememberTurns(vm, entry.id)
    val meta = remember(entry.questions) { ExamCodec.metadata(entry) }
    val submitted = entry.submittedAt > 0
    val sentences = remember(entry.material) { TranslationCoach.sentences(entry.material) }
    val reference = meta?.optString("referenceTranslation").orEmpty()
    var tab by rememberSaveable(entry.id) { mutableIntStateOf(if (submitted) 1 else 0) }
    var sentenceSheet by rememberSaveable(entry.id) { mutableStateOf<Int?>(null) }
    var showVersions by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var asking by rememberSaveable(entry.id) { mutableStateOf(false) }
    val versions = remember(state.history, entry) { versionsOf(state.history, entry) }
    val bands = remember(vm.reviewTurns) { latestBands(vm.reviewTurns) }
    val words = remember(entry.answer) { WritingCoach.words(entry.answer) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val reviewTurn = CoachTurns.latest(turns, Anchor.REVIEW, TurnKind.TRANSLATION_REVIEW)
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
                title = entry.title.removeSuffix(" · 翻译").ifBlank { "自选段落" },
                subtitle = listOfNotNull("段落翻译", versions.indexOfFirst { it.id == entry.id }.takeIf { versions.size > 1 }?.let { "第 ${it + 1} 稿" },
                    if (submitted) "已提交" else null).joinToString(" · "),
                onBack = onBack,
                actions = {
                    if (tab == 1 && submitted && reviewTurn != null && pending?.kind != TurnKind.TRANSLATION_REVIEW) AskAction { asking = true }
                    WorkspaceMenu(vm, entry, listOfNotNull(if (reference.isNotBlank()) "参考译文" to { tab = 2 } else null,
                        if (versions.size > 1) "各稿" to { showVersions = true } else null))
                })
        },
        bottomBar = {
            when {
                tab == 0 -> BottomBar {
                    if (!submitted) ExamClock(entry.id)
                    Column(Modifier.weight(1f)) {
                        WordCount(words, null, null)
                        Note(saveLabel(state.messages[CoachKind.TRANSLATION]))
                    }
                    if (!submitted) Button(onClick = { confirmSubmit = true }, enabled = words > 0 && !state.working,
                        modifier = Modifier.height(48.dp)) { Text("提交") }
                    else Button(onClick = { state.revise(entry, entry.answer) }, enabled = !state.working,
                        modifier = Modifier.height(48.dp)) { Text("写修改稿") }
                }
                tab == 1 && submitted && pending?.kind != TurnKind.TRANSLATION_REVIEW && (reviewTurn != null || pending != null) ->
                    ReviewFooter(vm, entry, "问问这段译文…", placedReview(entry.answer, reviewTurn), asking, { asking = it }, onAsk = ::ask)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DeskSteps("翻译", tab, submitted, state.working, onTab = { tab = it }, onRevise = {
                state.revise(entry, placedReview(entry.answer, reviewTurn)?.let { applyAccepted(entry.answer, it) } ?: entry.answer)
            })
            Hairline()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 32.dp)) {
                when (tab) {
                    0 -> TranslateDraftPane(vm, entry, sentences, turns, meta == null, submitted) { sentenceSheet = it }
                    1 -> TranslationReviewPane(vm, entry, sentences, turns, reviewTurn, scroll, onGoWrite = { tab = 0 }, onAsk = ::ask)
                    else -> TranslationReference(sentences, reference, meta?.optString("referenceSource").orEmpty())
                }
            }
        }
    }
    sentenceSheet?.let { index -> SentenceSheet(vm, entry, sentences, index, turns) { sentenceSheet = null } }
    if (showVersions) VersionSheet(versions, entry.id, bands, onOpen = { showVersions = false; state.open(CoachKind.TRANSLATION, it) }) { showVersions = false }
    if (confirmSubmit) SubmitDialog(words, null, onConfirm = {
        confirmSubmit = false
        state.submit(CoachKind.TRANSLATION, false)
        tab = 1
    }) { confirmSubmit = false }
}

@Composable
private fun TranslateDraftPane(vm: StudyViewModel, entry: CoachPractice, sentences: List<String>, turns: List<CoachTurn>,
    custom: Boolean, submitted: Boolean, onSentence: (Int) -> Unit) {
    val state = vm.coach
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (custom && !submitted && entry.sourcePracticeId.isBlank()) {
            PaperCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(entry.title, { v -> state.edit(CoachKind.TRANSLATION) { it.copy(title = v.take(200)) } },
                        label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(entry.material, { v -> state.edit(CoachKind.TRANSLATION) { it.copy(material = v) } },
                        label = { Text("要翻译的中文") }, minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (sentences.isNotEmpty()) {
            Note("点一句，可以看难词和句式提示", Modifier.padding(horizontal = PageGutter))
            RowCard {
                sentences.forEachIndexed { i, sentence ->
                    val helped = turns.any { it.anchor == Anchor.sentence(i + 1) }
                    SourceSentence(i + 1, sentence, helped) { onSentence(i + 1) }
                    if (i < sentences.lastIndex) RowDivider()
                }
            }
        }
        val source = entry.sourcePracticeId.takeIf(String::isNotBlank)?.let { id -> state.history.firstOrNull { it.id == id } }
        if (entry.practiceGoal.isNotBlank()) {
            Row(Modifier.padding(start = PageGutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Note("训练目标：${entry.practiceGoal}", Modifier.weight(1f), tone = Tone.Ink)
                if (source != null && source.material != entry.material) {
                    TextButton(onClick = { state.open(CoachKind.TRANSLATION, source) }) { Text("查看原练习") }
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
            Editor(entry.answer, readOnly = submitted, placeholder = "在这里写英文译文…", minHeight = 260) { text ->
                state.edit(CoachKind.TRANSLATION) { it.copy(answer = text) }
            }
        }
        if (submitted) Note("这一稿已提交，可以在「批改」里逐句对照；想继续改，点下方「写修改稿」。",
            Modifier.padding(horizontal = PageGutter))
    }
}

@Composable
private fun SourceSentence(number: Int, text: String, helped: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "看这一句的提示", onClick = onClick)
        .then(if (helped) Modifier.semantics { stateDescription = "已问过" } else Modifier)
        .padding(start = 12.dp, end = CardPadding, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(labelWidth(listOf("00"), MaterialTheme.typography.labelMedium)).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = SourceText, modifier = Modifier.weight(1f))
        if (helped) Box(Modifier.padding(top = 12.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

@Composable
private fun TranslationReviewPane(vm: StudyViewModel, entry: CoachPractice, sentences: List<String>, turns: List<CoachTurn>,
    reviewTurn: CoachTurn?, scroll: ScrollState, onGoWrite: () -> Unit, onAsk: (String) -> Unit) {
    val talk = vm.talk
    if (entry.submittedAt == 0L) {
        EmptyNote("提交这一稿后就可以批改。批改会按中文原文逐句对照你的译文。", "回到翻译", onGoWrite)
        return
    }
    val pending = talk.pendingFor(entry.id, Anchor.REVIEW)
    val reviewing = pending?.kind == TurnKind.TRANSLATION_REVIEW
    val text = if (reviewing) pending!!.text else reviewTurn?.content
    val review = remember(text) { text?.let(CoachReview::parse) }
    val decisions = remember(reviewTurn?.state) { ReviewDecisions.decode(reviewTurn?.state.orEmpty()) }
    var chooseAgain by rememberSaveable(entry.id) { mutableStateOf(false) }
    var selected by rememberSaveable(entry.id) { mutableStateOf<Int?>(null) }
    talk.failureFor(entry.id, Anchor.REVIEW, chat = false)?.let {
        FailureLine(it, talk::dismissFailure, Modifier.padding(horizontal = CardMargin, vertical = 8.dp))
    }
    if (review == null || (chooseAgain && !reviewing)) {
        FocusChooser(CoachKind.TRANSLATION, enabled = !talk.busy, connected = vm.aiReady && vm.chosenModel.isNotBlank()) { focus ->
            chooseAgain = false
            talk.send(entry, Anchor.REVIEW, TurnKind.TRANSLATION_REVIEW) { TranslationCoach.reviewPrompt(entry, focus) }
        }
        LegacyFeedback(entry.feedback)
        SkillErrorNote(vm, entry, CoachKind.TRANSLATION)
        return
    }
    if (reviewing) WorkingLine("正在逐句对照 · ${review.segments.size} / ${sentences.size} 句", talk::stop,
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
        if (review.segments.isNotEmpty()) {
            val counts = review.segments.groupingBy { it.verdict }.eachCount()
            Note(listOf("good" to "准确", "minor" to "基本准确", "error" to "有误", "missing" to "漏译")
                .mapNotNull { (key, label) -> counts[key]?.let { "$label $it 句" } }.joinToString(" · "),
                Modifier.padding(start = PageGutter, end = PageGutter, top = 10.dp))
        }
        val placed = remember(entry.answer, review, decisions) { placeIssues(entry.answer, review, decisions) }
        SectionLabel("逐句对照")
        RowCard {
            sentences.forEachIndexed { i, sentence ->
                val segment = review.segments.firstOrNull { it.index == i + 1 }
                SegmentBlock(i + 1, sentence, segment, placed.withIndex().filter { it.value.issue.segment == i + 1 && !it.value.dismissed },
                    selectedId = selected?.let { placed.getOrNull(it)?.issue?.id }) { selected = it }
                if (i < sentences.lastIndex) RowDivider()
            }
        }
        val loose = placed.withIndex().filter { it.value.issue.segment == null || it.value.issue.segment!! > sentences.size }
        if (loose.isNotEmpty()) {
            SectionLabel("其他修改")
            RowCard {
                loose.forEachIndexed { n, (i, p) ->
                    IssueRow(p, i + 1, onDecide = reviewTurn?.takeIf { !reviewing }?.let { turn ->
                        { issue: ReviewIssue, decision: ReviewDecision -> talk.updateDecision(turn, issue.id, decision) } }) { selected = i }
                    if (n < loose.lastIndex) RowDivider()
                }
            }
        }
        if (review.terms.isNotEmpty()) {
            SectionLabel("重点词语")
            PaperCard {
                BoxWithConstraints {
                    // The Chinese column fits its widest term but never takes more than a third of the card; longer terms wrap.
                    val zhWidth = minOf(labelWidth(review.terms.map { it.zh }, MaterialTheme.typography.bodyLarge), maxWidth * 0.34f)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        review.terms.forEach { term ->
                            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(term.zh, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(zhWidth))
                                Column(Modifier.weight(1f)) {
                                    Text(term.en, style = PassageText.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = MaterialTheme.colorScheme.primary)
                                    if (term.note.isNotBlank()) Note(term.note)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (review.tips.isNotEmpty()) {
            SectionLabel("建议")
            PaperCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    review.tips.forEach { Text("· $it", style = MaterialTheme.typography.bodyLarge) }
                }
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
        QuickAsks(CoachChat.suggestions(CoachKind.TRANSLATION, Anchor.REVIEW), enabled = !talk.busy, onPick = onAsk, edge = PageGutter)
        ThreadView(CoachTurns.thread(turns, Anchor.REVIEW), pending, talk::stop, Modifier.padding(start = PageGutter, end = PageGutter, top = 12.dp))
        SkillErrorNote(vm, entry, CoachKind.TRANSLATION)
    }
}

/** One source sentence with the learner's matching words, a verdict and, on request, a suggested version. */
@Composable
private fun SegmentBlock(number: Int, sentence: String, segment: ReviewSegment?, issues: List<IndexedValue<PlacedIssue>>,
    selectedId: Int?, onIssue: (Int) -> Unit) {
    var showSuggestion by rememberSaveable(number, segment?.suggestion) { mutableStateOf(false) }
    val numberWidth = labelWidth(listOf("00"), MaterialTheme.typography.labelMedium)
    val indent = numberWidth + 10.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = CardPadding, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(numberWidth).padding(top = 4.dp))
            Text(sentence, style = SourceText.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (segment == null) { Note("这一句还没有对照结果", Modifier.padding(start = indent)); return@Column }
        Column(Modifier.padding(start = indent), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (segment.mine.isNotBlank()) {
                val ranges = remember(segment.mine, issues) { TextMarks.place(segment.mine, issues.map { it.value.issue.quote }) }
                MarkedText(segment.mine, issues.mapIndexedNotNull { i, item -> ranges[i]?.let { TextMark(item.value.issue.id, it, item.value.tone) } },
                    selected = selectedId, style = PassageText, onMark = { id -> issues.firstOrNull { it.value.issue.id == id }?.let { onIssue(it.index) } })
            } else Text("（没有译出）", style = PassageText, color = Tone.Must.color())
            DotStatus(when (segment.verdict) { "good" -> "准确"; "minor" -> "基本准确"; "missing" -> "漏译"; else -> "有误" },
                when (segment.verdict) { "good" -> Tone.Good; "minor" -> Tone.Better; else -> Tone.Must })
            if (segment.note.isNotBlank()) Text(segment.note, style = MaterialTheme.typography.bodyMedium)
            issues.filter { it.value.range == null || segment.mine.isBlank() }.forEach { (i, p) ->
                Text("· ${p.issue.quote.ifBlank { "（漏译）" }} → ${p.issue.fix}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { onIssue(i) })
            }
            if (segment.suggestion.isNotBlank() && !segment.ok) {
                TextButton(onClick = { showSuggestion = !showSuggestion }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showSuggestion) "收起建议译法" else "看建议译法")
                }
                if (showSuggestion) SelectionContainer { Text(segment.suggestion, style = PassageText, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun TranslationReference(sentences: List<String>, reference: String, source: String) {
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            SelectionContainer { Text(sentences.joinToString(""), style = SourceText, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Hairline(Modifier.padding(vertical = 14.dp))
            SelectionContainer { Text(reference, style = PassageText) }
        }
        Column(Modifier.padding(horizontal = PageGutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (source.isNotBlank()) Note("来源：" + source.substringBefore(" (").substringBefore(";").take(120))
            Note("参考译文不是唯一答案，意思准确、表达地道的译法同样可取。")
        }
    }
}

/** Hints for one source sentence: difficult words or how to build the English sentence, never the full version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SentenceSheet(vm: StudyViewModel, entry: CoachPractice, sentences: List<String>, index: Int, turns: List<CoachTurn>, onDismiss: () -> Unit) {
    val talk = vm.talk
    val anchor = Anchor.sentence(index)
    val thread = CoachTurns.thread(turns, anchor)
    val pending = talk.pendingFor(entry.id, anchor)
    val kinds = setOf(TurnKind.CHAT, TurnKind.TRANSLATION_HINT)
    fun ask(question: String) = talk.send(entry, anchor, TurnKind.CHAT, question) { CoachChat.prompt(entry, anchor, thread, question) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("第 $index 句")
            Text(sentences.getOrNull(index - 1).orEmpty(), style = SourceText)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThreadView(thread, pending, talk::stop, kinds = kinds)
                QuickAsks(SentenceHelp.entries.map { it.label }, enabled = !talk.busy, onPick = { label ->
                    val help = SentenceHelp.entries.first { it.label == label }
                    talk.send(entry, anchor, TurnKind.TRANSLATION_HINT, help.label) { TranslationCoach.hintPrompt(entry, index, help) }
                })
                if (thread.isEmpty() && pending == null) Note("提示只讲难词和句子骨架，不会直接给出整句译文。")
                if (!vm.aiReady || vm.chosenModel.isBlank()) Note("需要先在「我的 → AI 连接」配置 AI 服务并选择模型。")
            }
            AskBar("关于这一句，想问什么…", busy = talk.busy, enabled = !talk.busy, onSend = ::ask, onStop = talk::stop, draftKey = entry.id + anchor,
                failure = talk.failureFor(entry.id, anchor), onDismissFailure = talk::dismissFailure)
        }
    }
}
