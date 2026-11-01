package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
// import androidx.compose.ui.semantics.semantics
// import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.NumeralText
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

// /** What a suggestion changes, in words, since strike-through and colour are silent to a screen reader. */
// private fun spokenChange(issue: ReviewIssue) = listOfNotNull(
// issue.quote.takeIf(String::isNotBlank)?.let { "原文：$it" },
// issue.fix.takeIf(String::isNotBlank)?.let { "改为：$it" }
// ).joinToString("，")

private fun levelTone(level: String) = when (level) {
    "好" -> Tone.Good
    "一般" -> Tone.Better
    "待改进" -> Tone.Must
    else -> Tone.Muted
}

@Composable
fun BandHeader(score: ReviewScore?, aspects: List<ReviewAspect>, modifier: Modifier = Modifier) {
    if (score == null && aspects.isEmpty()) return
    PaperCard(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        padding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (score != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // The band set like print on an ink tile, the summary beside it.
                    Surface(
                        color = InkSheet,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.widthIn(min = 76.dp),
                        // .semantics(mergeDescendants = true) {}
                    ) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (score.band == 0) "—" else "${score.band}",
                                style = NumeralText,
                                color = OnInkSheet,
                            )
                            Text(
                                if (score.band == 0) "未评档" else "分档 · 参考",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnInkSheet.copy(alpha = 0.72f),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "总评",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            score.summary.ifBlank { "参照六级评分标准的模拟档位，仅供参考" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            if (aspects.isNotEmpty()) {
                if (score != null) Hairline()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    aspects.chunked(2).forEach { rowAspects ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowAspects.forEach { aspect ->
                                val level = aspect.level.ifBlank { "—" }
                                val tone = levelTone(aspect.level)
                                Surface(
                                    color = MaterialTheme.colorScheme.background,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.weight(1f),
                                    // .semantics(mergeDescendants = true) {}
                                ) {
                                    Column(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                aspect.name,
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Tag(level, tone)
                                        }
                                        if (aspect.note.isNotBlank()) {
                                            Text(
                                                aspect.note,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            if (rowAspects.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Suggestions grouped by how much they matter, each rendered as a dedicated revision card. */
@Composable
fun IssueList(placed: List<PlacedIssue>, onOpen: (Int) -> Unit, onDecide: ((ReviewIssue, ReviewDecision) -> Unit)?) {
    if (placed.isEmpty()) return
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val must = placed.count { it.issue.must }
    val shown = placed.withIndex().filter { (_, p) ->
        when (filter) {
            1 -> p.issue.must
            2 -> !p.issue.must
            else -> true
        }
    }
    SectionLabel("逐条修改 · ${placed.size}")
    TrackSegments(
        listOf("全部 ${placed.size}", "必改 $must", "可改 ${placed.size - must}"),
        filter,
        { filter = it },
        Modifier.padding(start = PageGutter, end = PageGutter, bottom = 10.dp),
    )
    if (shown.isEmpty()) {
        Note("这一类没有修改。", Modifier.padding(horizontal = PageGutter))
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = CardMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { (i, p) ->
            IssueRow(p, i + 1, onDecide = onDecide) { onOpen(i) }
        }
    }
}

/**
 * One suggestion as a margin note: a numbered badge matching its underline, the struck words and the fix set in the
 * serif, the reason, then 采纳 in ink and 忽略 as quiet text.
 */
@Composable
fun IssueRow(placed: PlacedIssue, number: Int, onDecide: ((ReviewIssue, ReviewDecision) -> Unit)? = null, onClick: () -> Unit) {
    val issue = placed.issue
    val tone = if (issue.must) Tone.Must else Tone.Better
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        // .semantics { stateDescription = if (issue.must) "必改" else "可改" }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MarkBadge(number, if (placed.accepted) Tone.Good else tone)
                Text(
                    listOf(if (issue.must) "必改" else "润色", issue.category).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = tone.color(),
                    modifier = Modifier.weight(1f),
                )
                when {
                    placed.accepted -> Note("已采纳", tone = Tone.Good)
                    placed.dismissed -> Note("已忽略")
                    placed.range == null -> Note("未定位")
                }
            }
            Text(
                buildAnnotatedString {
                    if (issue.quote.isNotBlank()) {
                        withStyle(
                            SpanStyle(
                                textDecoration = TextDecoration.LineThrough,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { append(issue.quote) }
                    }
                    if (issue.fix.isNotBlank()) {
                        if (issue.quote.isNotBlank()) append("  ")
                        withStyle(SpanStyle(color = if (placed.accepted) Tone.Good.color() else tone.color(), fontWeight = FontWeight.SemiBold)) {
                            append(issue.fix)
                        }
                    }
                },
                style = PassageText.copy(fontSize = 17.sp, lineHeight = 26.sp),
                // modifier = Modifier.semantics { contentDescription = spokenChange(issue) }
            )
            if (issue.reason.isNotBlank()) {
                Text(
                    issue.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDecide != null && !placed.accepted && !placed.dismissed && placed.range != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDecide(issue, ReviewDecision.ACCEPT) },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) { Text("采纳", style = MaterialTheme.typography.labelLarge) }
                    TextButton(
                        onClick = { onDecide(issue, ReviewDecision.DISMISS) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) { Text("忽略", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

/** Detail of one suggestion, with stepping between suggestions like flipping marked pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueSheet(
    items: List<PlacedIssue>,
    index: Int,
    onIndex: (Int) -> Unit,
    onDismissSheet: () -> Unit,
    onAccept: (ReviewIssue) -> Unit,
    onReject: (ReviewIssue) -> Unit,
    onReset: (ReviewIssue) -> Unit,
    onAsk: ((ReviewIssue) -> Unit)?,
    askBusy: Boolean,
) {
    val placed = items.getOrNull(index) ?: return
    val issue = placed.issue
    val tone = if (issue.must) Tone.Must else Tone.Better
    ModalBottomSheet(onDismissRequest = onDismissSheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MarkBadge(index + 1, if (placed.accepted) Tone.Good else tone)
                Text(
                    listOf(if (issue.must) "必改" else "润色", issue.category).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${index + 1} / ${items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // modifier = Modifier.semantics { contentDescription = "第 ${index + 1} 处，共 ${items.size} 处" }
                StepButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, index > 0) { onIndex(index - 1) }
                // contentDescription = "上一处"
                StepButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, index < items.lastIndex) { onIndex(index + 1) }
                // contentDescription = "下一处"
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (issue.quote.isNotBlank()) {
                    Text(
                        issue.quote,
                        style = PassageText.copy(textDecoration = TextDecoration.LineThrough),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // modifier = Modifier.semantics { contentDescription = "原文：${issue.quote}" }
                if (issue.fix.isNotBlank()) {
                    Text(
                        issue.fix,
                        style = PassageText,
                        fontWeight = FontWeight.SemiBold,
                        color = if (placed.accepted) Tone.Good.color() else tone.color(),
                    )
                }
                // modifier = Modifier.semantics { contentDescription = "改为：${issue.fix}" }
            }
            if (issue.reason.isNotBlank()) Text(issue.reason, style = MaterialTheme.typography.bodyMedium)
            if (placed.range == null) Note("这条引用和原稿对不上，可能有出入，采纳不会改动原稿。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onAsk != null) {
                    OutlinedButton(
                        onClick = { onAsk(issue) },
                        enabled = !askBusy,
                        modifier = Modifier.height(48.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) { Text("追问") }
                }
                if (placed.accepted || placed.dismissed) {
                    Text(
                        if (placed.accepted) "已采纳" else "已忽略",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (placed.accepted) Tone.Good.color() else Tone.Muted.color(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onReset(issue) }) { Text("撤销") }
                } else {
                    // Deciding moves on to the next open suggestion, the way a marked page is worked through.
                    fun next() {
                        val following = items.indices.firstOrNull { it > index && !items[it].accepted && !items[it].dismissed }
                        if (following != null) onIndex(following) else onDismissSheet()
                    }
                    TextButton(
                        onClick = {
                            onReject(issue)
                            next()
                        },
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text("忽略") }
                    Button(onClick = {
                        onAccept(issue)
                        next()
                    }, enabled = placed.range != null, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text("采纳修改")
                    }
                }
            }
        }
    }
}

/** Round paper-coloured button that steps between suggestions. */
@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** Revision against its source: removed words struck through, added words tinted; spoken as 删去 / 新增. */
@Composable
fun DiffText(before: String, after: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = PassageText) {
    val pieces = remember(before, after) { WordDiff.diff(before, after) }
    // val spoken = remember(pieces) {
    // pieces.joinToString("") { piece ->
    // when (piece.kind) {
    // DiffKind.SAME -> piece.text
    // DiffKind.REMOVED -> " 删去「${piece.text.trim()}」 "
    // DiffKind.ADDED -> " 新增「${piece.text.trim()}」 "
    // }
    // }
    // }
    val must = Tone.Must.color()
    val good = Tone.Good.color()
    val goodSoft = Tone.Good.soft()
    Text(
        buildAnnotatedString {
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
        },
        style = style,
        modifier = modifier, /* .semantics { contentDescription = spoken } */
    )
}

/** Framed comparison with the previous draft, visually distinct from the live editor below it. */
@Composable
fun ComparePanel(before: String, after: String, modifier: Modifier = Modifier) {
    PaperCard(modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note("与上一稿对照 · 删去的划线，新增的着色")
            DiffText(
                before,
                after,
                style = PassageText.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                ),
            )
        }
    }
}
