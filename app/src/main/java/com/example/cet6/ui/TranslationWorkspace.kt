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
// import androidx.compose.ui.semantics.semantics
// import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import com.example.cet6.ui.theme.PassageText
import com.example.cet6.ui.theme.SourceText
import kotlinx.coroutines.launch

/** Translation desk: numbered source sentences, the learner's version, and a sentence-aligned review. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranslationWorkspace(vm: StudyViewModel, onBack: () -> Unit) {
    val state = vm.coach
    val hazeState = remember { HazeState() }
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
    var sourceOpen by rememberSaveable(entry.id) { mutableStateOf(true) }
    val editingSource = meta == null && !submitted && entry.sourcePracticeId.isBlank()
    val split = tab == 0 && sentences.isNotEmpty() && !editingSource
    // While the keyboard is up the step bar and footer step aside, so the source and the draft share what is left.
    val typing = split && WindowInsets.isImeVisible
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
            WorkspaceTopBar(
                title = "翻译",
                context = entry.title.removeSuffix(" · 翻译").ifBlank { "自选段落" },
                detail = listOfNotNull(versions.indexOfFirst { it.id == entry.id }.takeIf { versions.size > 1 }?.let { "第 ${it + 1} 稿" },
                    if (submitted) "已提交" else "草稿").joinToString(" · "),
                compact = typing,
                onBack = onBack,
                actions = {
                    if (tab == 1 && submitted && reviewTurn != null && pending?.kind != TurnKind.TRANSLATION_REVIEW) AskAction { asking = true }
                    WorkspaceMenu(vm, entry, listOfNotNull(if (reference.isNotBlank()) "参考译文" to { tab = 2 } else null,
                        if (versions.size > 1) "各稿" to { showVersions = true } else null))
                })
        },
        bottomBar = {
            when {
                tab == 0 && !typing -> BottomBar(hazeState = hazeState) {
                    if (!submitted) ExamClock(entry.id)
                    Note(saveLabel(state.messages[CoachKind.TRANSLATION]), Modifier.weight(1f))
                    if (!submitted) Button(onClick = { confirmSubmit = true }, enabled = words > 0 && !state.working,
                        modifier = Modifier.height(48.dp)) { Text("提交") }
                    else Button(onClick = { state.revise(entry, entry.answer) }, enabled = !state.working,
                        modifier = Modifier.height(48.dp)) { Text("写修改稿") }
                }
                tab == 1 && submitted && pending?.kind != TurnKind.TRANSLATION_REVIEW && (reviewTurn != null || pending != null) ->
                    ReviewFooter(vm, entry, "问问这段译文…", placedReview(entry.answer, reviewTurn), asking, { asking = it }, hazeState, onAsk = ::ask)
            }
        }
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).haze(hazeState).then(if (typing) Modifier.consumeWindowInsets(padding).imePadding() else Modifier).fillMaxSize()) {
            if (!typing) {
                DeskSteps("翻译", tab, submitted, state.working, onTab = { tab = it }, onRevise = {
                    state.revise(entry, placedReview(entry.answer, reviewTurn)?.let { applyAccepted(entry.answer, it) } ?: entry.answer)
                })
            }
            // While translating, the Chinese stays on screen above the editor instead of scrolling away with it.
            if (split) BoxWithConstraints(Modifier.fillMaxSize()) {
                val sourceMax = maxHeight * if (typing) 0.6f else 0.42f
                Column(Modifier.fillMaxSize()) {
                    SourcePane(sentences, turns, sourceOpen, { sourceOpen = !sourceOpen }, sourceMax) { sentenceSheet = it }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(bottom = padding.calculateBottomPadding() + 32.dp)) {
                        TranslateDraftPane(vm, entry, sentences, turns, meta == null, submitted, includeSource = false) { sentenceSheet = it }
                    }
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = padding.calculateBottomPadding() + 32.dp)) {
                when (tab) {
                    0 -> TranslateDraftPane(vm, entry, sentences, turns, meta == null, submitted, includeSource = true) { sentenceSheet = it }
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
    custom: Boolean, submitted: Boolean, includeSource: Boolean, onSentence: (Int) -> Unit) {
    val state = vm.coach
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (custom && !submitted && entry.sourcePracticeId.isBlank()) {
            PaperCard(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                padding = PaddingValues(0.dp)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag("自选翻译段落", Tone.Ink)
                    FieldRow("标题", entry.title, { v -> state.edit(CoachKind.TRANSLATION) { it.copy(title = v.take(200)) } },
                        placeholder = "输入练习标题")
                }
                Hairline()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    CardTitle("要翻译的中文", Modifier.padding(bottom = 4.dp))
                    FieldRow(null, entry.material, { v -> state.edit(CoachKind.TRANSLATION) { it.copy(material = v) } },
                        placeholder = "粘贴或输入要翻译的中文段落", minLines = 3, accessibilityLabel = "要翻译的中文", maxLines = 10)
                }
            }
        }
        if (includeSource && sentences.isNotEmpty()) {
            SourceCardList(sentences, turns, onSentence)
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
        WorkspaceDocument(
            title = if (submitted) "已提交的译文" else "英文译文",
            text = entry.answer,
            readOnly = submitted,
            words = WritingCoach.words(entry.answer),
            minHeight = 220,
            placeholder = "在这里动笔写英文译文…",
            headerAction = if (sentences.isNotEmpty()) {
                { Note("共 ${sentences.size} 句原文") }
            } else null
        ) { text ->
            state.edit(CoachKind.TRANSLATION) { it.copy(answer = text) }
        }
        if (submitted) Note("这一稿已提交，可以在「批改」里逐句对照；想继续改，点下方「写修改稿」。",
            Modifier.padding(horizontal = PageGutter))
    }
}

/** The Chinese source pinned above the editor: supports 1-sentence focus mode (default) and full-paragraph expansion. */
@Composable
private fun SourcePane(sentences: List<String>, turns: List<CoachTurn>, open: Boolean, onToggle: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp, onSentence: (Int) -> Unit) {
    var activeIdx by rememberSaveable(sentences.size) { mutableIntStateOf(0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val safeIdx = activeIdx.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = CardMargin, vertical = 4.dp).heightIn(max = maxHeight)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Tag("原文对照", Tone.Ink)
                if (open && !showAll && sentences.size > 1) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        sentences.indices.forEach { i ->
                            val selected = i == safeIdx
                            val helped = turns.any { it.anchor == Anchor.sentence(i + 1) }
                            Surface(
                                onClick = { activeIdx = i },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                color = when {
                                    selected -> MaterialTheme.colorScheme.primary
                                    helped -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier.size(width = 26.dp, height = 24.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${i + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Note("共 ${sentences.size} 句", Modifier.weight(1f))
                }
                if (open && sentences.size > 1) {
                    TextButton(
                        onClick = { showAll = !showAll },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.heightIn(min = 30.dp)
                    ) { Text(if (showAll) "逐句聚焦" else "整段总览", style = MaterialTheme.typography.labelMedium) }
                }
                TextButton(
                    onClick = onToggle,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.heightIn(min = 30.dp)
                ) { Text(if (open) "收起" else "展开原文", style = MaterialTheme.typography.labelMedium) }
            }
            if (open) {
                Hairline()
                if (showAll) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sentences.forEachIndexed { i, sentence ->
                            val helped = turns.any { it.anchor == Anchor.sentence(i + 1) }
                            SourceSentence(i + 1, sentence, helped) { onSentence(i + 1) }
                        }
                    }
                } else if (sentences.isNotEmpty()) {
                    val currentSentence = sentences[safeIdx]
                    val helped = turns.any { it.anchor == Anchor.sentence(safeIdx + 1) }
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SelectionContainer {
                            Text(currentSentence, style = SourceText)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { onSentence(safeIdx + 1) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 32.dp)
                            ) {
                                Text(if (helped) "已看词汇与句式提示" else "看本句词汇与句式提示", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.weight(1f))
                            if (safeIdx > 0) {
                                TextButton(
                                    onClick = { activeIdx = safeIdx - 1 },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.heightIn(min = 30.dp)
                                ) { Text("‹ 上一句", style = MaterialTheme.typography.labelMedium) }
                            }
                            if (safeIdx < sentences.lastIndex) {
                                TextButton(
                                    onClick = { activeIdx = safeIdx + 1 },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.heightIn(min = 30.dp)
                                ) { Text("下一句 ›", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCardList(sentences: List<String>, turns: List<CoachTurn>, onSentence: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = CardMargin), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Tag("中文原文", Tone.Ink)
            Note("共 ${sentences.size} 句 · 点一句看难词和句式提示")
        }
        sentences.forEachIndexed { i, sentence ->
            val helped = turns.any { it.anchor == Anchor.sentence(i + 1) }
            SourceSentence(i + 1, sentence, helped) { onSentence(i + 1) }
        }
    }
}

@Composable
private fun SourceSentence(number: Int, text: String, helped: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (helped) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
            .clickable(/* onClickLabel = "看这一句的提示", */ onClick = onClick)
            .then(if (helped) Modifier/* .semantics { stateDescription = "已问过" } */ else Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = if (helped) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
                modifier = Modifier.padding(top = 4.dp).size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "$number",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (helped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(text, style = SourceText, modifier = Modifier.weight(1f))
            Tag(if (helped) "已看提示" else "提示", if (helped) Tone.Ink else Tone.Muted)
        }
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
        Column(Modifier.fillMaxWidth().padding(horizontal = CardMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sentences.forEachIndexed { i, sentence ->
                val segment = review.segments.firstOrNull { it.index == i + 1 }
                SegmentBlock(
                    number = i + 1,
                    sentence = sentence,
                    segment = segment,
                    issues = placed.withIndex().filter { it.value.issue.segment == i + 1 && !it.value.dismissed },
                    selectedId = selected?.let { placed.getOrNull(it)?.issue?.id },
                    onIssue = { selected = it }
                )
            }
        }
        val loose = placed.withIndex().filter { it.value.issue.segment == null || it.value.issue.segment!! > sentences.size }
        if (loose.isNotEmpty()) {
            SectionLabel("其他修改")
            Column(Modifier.fillMaxWidth().padding(horizontal = CardMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                loose.forEach { (i, p) ->
                    IssueRow(p, i + 1, onDecide = reviewTurn?.takeIf { !reviewing }?.let { turn ->
                        { issue: ReviewIssue, decision: ReviewDecision -> talk.updateDecision(turn, issue.id, decision) } }) { selected = i }
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
                            Row(Modifier/* .semantics(mergeDescendants = true) {} */, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    val verdictTone = when (segment?.verdict) {
        "good" -> Tone.Good
        "minor" -> Tone.Better
        null -> Tone.Muted
        else -> Tone.Must
    }
    // The sentence's verdict sits on its numbered badge, the same marker the margin notes use.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (segment != null && verdictTone != Tone.Muted) MarkBadge(number, verdictTone, Modifier.padding(top = 3.dp))
                    else Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = CircleShape,
                        modifier = Modifier.padding(top = 3.dp).size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        sentence,
                        style = SourceText.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (segment != null) {
                        DotStatus(
                            when (segment.verdict) { "good" -> "准确"; "minor" -> "基本准确"; "missing" -> "漏译"; else -> "有误" },
                            verdictTone
                        )
                    }
                }
                if (segment == null) {
                    Note("这一句还没有对照结果")
                    return@Column
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        if (segment.mine.isNotBlank()) {
                            val ranges = remember(segment.mine, issues) { TextMarks.place(segment.mine, issues.map { it.value.issue.quote }) }
                            MarkedText(
                                segment.mine,
                                issues.mapIndexedNotNull { i, item -> ranges[i]?.let { TextMark(item.value.issue.id, it, item.value.tone) } },
                                selected = selectedId,
                                style = PassageText,
                                onMark = { id -> issues.firstOrNull { it.value.issue.id == id }?.let { onIssue(it.index) } }
                            )
                        } else {
                            Text("（没有译出）", style = PassageText, color = Tone.Must.color())
                        }
                    }
                }
                if (segment.note.isNotBlank()) {
                    Text(segment.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                issues.filter { it.value.range == null || segment.mine.isBlank() }.forEach { (i, p) ->
                    Text(
                        "· ${p.issue.quote.ifBlank { "（漏译）" }} → ${p.issue.fix}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onIssue(i) }
                    )
                }
                if (segment.suggestion.isNotBlank() && !segment.ok) {
                    TextButton(onClick = { showSuggestion = !showSuggestion }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showSuggestion) "收起建议译法" else "看建议译法")
                    }
                    if (showSuggestion) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(
                                    segment.suggestion,
                                    style = PassageText,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
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
