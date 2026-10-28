package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.PassageText

/** Where each suggestion sits in the learner's text, after their own accept/dismiss choices. */
data class PlacedIssue(val issue: ReviewIssue, val range: IntRange?, val accepted: Boolean, val dismissed: Boolean) {
    val tone get() = when {
        accepted -> Tone.Good
        issue.must -> Tone.Must
        else -> Tone.Better
    }
}

fun placeIssues(text: String, review: CoachReview, decisions: ReviewDecisions): List<PlacedIssue> {
    val ranges = TextMarks.place(text, review.issues.map { it.quote })
    return review.issues.mapIndexed { i, issue ->
        PlacedIssue(issue, ranges[i], issue.id in decisions.accepted, issue.id in decisions.dismissed)
    }
}

/** The learner's text with accepted fixes applied; untouched where a fix could not be located. */
fun applyAccepted(text: String, placed: List<PlacedIssue>): String =
    TextMarks.apply(text, placed.filter { it.accepted && it.range != null }.map { it.range!! to it.issue.fix })

/** What a suggestion changes, in words, since strike-through and colour are silent to a screen reader. */
private fun spokenChange(issue: ReviewIssue) = listOfNotNull(
    issue.quote.takeIf(String::isNotBlank)?.let { "原文：$it" },
    issue.fix.takeIf(String::isNotBlank)?.let { "改为：$it" }
).joinToString("，")

private fun levelTone(level: String) = when (level) { "好" -> Tone.Good; "一般" -> Tone.Better; "待改进" -> Tone.Must; else -> Tone.Muted }

@Composable
fun BandHeader(score: ReviewScore?, aspects: List<ReviewAspect>, modifier: Modifier = Modifier) {
    if (score == null && aspects.isEmpty()) return
    PaperCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (score != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(if (score.band == 0) "—" else "${score.band}", style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Note(if (score.band == 0) "未评档" else "分档 · 参考")
                }
                Text(score.summary.ifBlank { "参照六级评分标准的模拟档位，仅供参考" }, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(top = 4.dp))
            }
            if (aspects.isNotEmpty()) {
                if (score != null) Hairline()
                // Columns are as wide as their widest entry, so names and levels never wrap or clip at large font sizes.
                val levels = aspects.map { it.level.ifBlank { "—" } }
                val nameWidth = labelWidth(aspects.map { it.name }, MaterialTheme.typography.labelLarge)
                val levelWidth = labelWidth(levels, MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    aspects.forEachIndexed { i, aspect ->
                        Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(aspect.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(nameWidth))
                            Text(levels[i], style = MaterialTheme.typography.labelLarge, color = levelTone(aspect.level).color(),
                                modifier = Modifier.width(levelWidth))
                            Text(aspect.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(top = 1.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Suggestions grouped by how much they matter, each decidable in place; tapping a row opens its detail. */
@Composable
fun IssueList(placed: List<PlacedIssue>, onOpen: (Int) -> Unit, onDecide: ((ReviewIssue, ReviewDecision) -> Unit)?) {
    if (placed.isEmpty()) return
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val must = placed.count { it.issue.must }
    val shown = placed.withIndex().filter { (_, p) -> when (filter) { 1 -> p.issue.must; 2 -> !p.issue.must; else -> true } }
    SectionLabel("逐条修改 · ${placed.size}")
    Row(Modifier.padding(start = CardMargin, end = CardMargin, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("全部 ${placed.size}", "必改 $must", "可改 ${placed.size - must}").forEachIndexed { i, label ->
            FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
        }
    }
    if (shown.isEmpty()) { Note("这一类没有修改。", Modifier.padding(horizontal = PageGutter)); return }
    RowCard {
        shown.forEachIndexed { n, (i, p) ->
            IssueRow(p, i + 1, onDecide = onDecide) { onOpen(i) }
            if (n < shown.lastIndex) RowDivider()
        }
    }
}

/** One suggestion as a list row: what was written, what to write, and the learner's decision. */
@Composable
fun IssueRow(placed: PlacedIssue, number: Int, onDecide: ((ReviewIssue, ReviewDecision) -> Unit)? = null, onClick: () -> Unit) {
    val issue = placed.issue
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { stateDescription = if (issue.must) "必改" else "可改" }
        .padding(horizontal = CardPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$number", style = MaterialTheme.typography.labelLarge, color = placed.tone.color(),
            modifier = Modifier.width(labelWidth(listOf("00"), MaterialTheme.typography.labelLarge)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(buildAnnotatedString {
                if (issue.quote.isNotBlank()) withStyle(SpanStyle(textDecoration = if (placed.accepted) TextDecoration.LineThrough else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(issue.quote) }
                if (issue.fix.isNotBlank()) {
                    append("  →  ")
                    withStyle(SpanStyle(color = Tone.Good.color(), fontWeight = FontWeight.Medium)) { append(issue.fix) }
                }
            }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { contentDescription = spokenChange(issue) })
            if (issue.reason.isNotBlank()) Text(issue.reason, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Text buttons keep each row short; the hit area still meets the 40 dp minimum.
            if (onDecide != null && !placed.accepted && !placed.dismissed && placed.range != null) Row(Modifier.offset(x = (-12).dp)) {
                TextButton(onClick = { onDecide(issue, ReviewDecision.ACCEPT) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("采纳") }
                TextButton(onClick = { onDecide(issue, ReviewDecision.DISMISS) }, modifier = Modifier.heightIn(min = 40.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("忽略") }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Note(issue.category, tone = if (issue.must) Tone.Must else Tone.Better)
            when {
                placed.accepted -> Note("已采纳", tone = Tone.Good)
                placed.dismissed -> Note("已忽略")
                placed.range == null -> Note("未定位")
            }
        }
    }
}

/** Detail of one suggestion, with stepping between suggestions like flipping marked pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueSheet(
    items: List<PlacedIssue>, index: Int, onIndex: (Int) -> Unit, onDismissSheet: () -> Unit,
    onAccept: (ReviewIssue) -> Unit, onReject: (ReviewIssue) -> Unit, onReset: (ReviewIssue) -> Unit,
    onAsk: ((ReviewIssue) -> Unit)?, askBusy: Boolean
) {
    val placed = items.getOrNull(index) ?: return
    val issue = placed.issue
    ModalBottomSheet(onDismissRequest = onDismissSheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(if (issue.must) "必改" else "可改", if (issue.must) Tone.Must else Tone.Better)
                Tag(issue.category)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onIndex(index - 1) }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上一处")
                }
                Text("${index + 1} / ${items.size}", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { contentDescription = "第 ${index + 1} 处，共 ${items.size} 处" })
                IconButton(onClick = { onIndex(index + 1) }, enabled = index < items.lastIndex) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下一处")
                }
            }
            if (issue.quote.isNotBlank()) Text(issue.quote, style = PassageText.copy(textDecoration = TextDecoration.LineThrough),
                color = Tone.Must.color(), modifier = Modifier.semantics { contentDescription = "原文：${issue.quote}" })
            if (issue.fix.isNotBlank()) Text(issue.fix, style = PassageText, color = Tone.Good.color(),
                modifier = Modifier.semantics { contentDescription = "改为：${issue.fix}" })
            if (issue.reason.isNotBlank()) Text(issue.reason, style = MaterialTheme.typography.bodyLarge)
            if (placed.range == null) Note("这条引用和原稿对不上，可能有出入，采纳不会改动原稿。")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (placed.accepted || placed.dismissed) {
                    Text(if (placed.accepted) "已采纳" else "已忽略", style = MaterialTheme.typography.labelLarge,
                        color = if (placed.accepted) Tone.Good.color() else Tone.Muted.color())
                    TextButton(onClick = { onReset(issue) }) { Text("撤销") }
                } else {
                    // Deciding moves on to the next open suggestion, the way a marked page is worked through.
                    fun next() {
                        val following = items.indices.firstOrNull { it > index && !items[it].accepted && !items[it].dismissed }
                        if (following != null) onIndex(following) else onDismissSheet()
                    }
                    OutlinedButton(onClick = { onReject(issue); next() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("忽略") }
                    Button(onClick = { onAccept(issue); next() }, enabled = placed.range != null, modifier = Modifier.weight(1f).height(48.dp)) { Text("采纳") }
                }
            }
            if (onAsk != null) TextButton(onClick = { onAsk(issue) }, enabled = !askBusy) { Text("这处为什么要改？问一问") }
        }
    }
}

/** Revision against its source: removed words struck through, added words tinted; spoken as 删去 / 新增. */
@Composable
fun DiffText(before: String, after: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = PassageText) {
    val pieces = remember(before, after) { WordDiff.diff(before, after) }
    val spoken = remember(pieces) {
        pieces.joinToString("") { piece ->
            when (piece.kind) {
                DiffKind.SAME -> piece.text
                DiffKind.REMOVED -> " 删去「${piece.text.trim()}」 "
                DiffKind.ADDED -> " 新增「${piece.text.trim()}」 "
            }
        }
    }
    val must = Tone.Must.color(); val good = Tone.Good.color(); val goodSoft = Tone.Good.soft()
    Text(buildAnnotatedString {
        pieces.forEachIndexed { i, piece ->
            when (piece.kind) {
                DiffKind.SAME -> append(piece.text)
                DiffKind.REMOVED -> {
                    withStyle(SpanStyle(color = must, textDecoration = TextDecoration.LineThrough)) { append(piece.text) }
                    // A hair space keeps "provide|provides" legible when a replacement follows directly.
                    if (pieces.getOrNull(i + 1)?.kind == DiffKind.ADDED) append(' ')
                }
                DiffKind.ADDED -> withStyle(SpanStyle(color = good, background = goodSoft)) { append(piece.text) }
            }
        }
    }, style = style, modifier = modifier.semantics { contentDescription = spoken })
}

/** Framed comparison with the previous draft, visually distinct from the live editor below it. */
@Composable
fun ComparePanel(before: String, after: String, modifier: Modifier = Modifier) {
    PaperCard(modifier, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note("与上一稿对照 · 删去的划线，新增的着色")
            DiffText(before, after, style = PassageText.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight))
        }
    }
}
