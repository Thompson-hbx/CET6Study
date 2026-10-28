package com.example.cet6.ui.components

import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cet6.ui.theme.LocalMarks
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

/** Semantic tone for status text and marks; colours come from the theme, never ad-hoc hex. */
enum class Tone { Muted, Ink, Good, Better, Must }

@Composable
fun Tone.color(): Color = when (this) {
    Tone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Ink -> MaterialTheme.colorScheme.primary
    Tone.Good -> LocalMarks.current.good
    Tone.Better -> LocalMarks.current.better
    Tone.Must -> LocalMarks.current.must
}

@Composable
fun Tone.soft(): Color = when (this) {
    Tone.Muted -> MaterialTheme.colorScheme.surfaceVariant
    Tone.Ink -> MaterialTheme.colorScheme.primaryContainer
    Tone.Good -> LocalMarks.current.goodSoft
    Tone.Better -> LocalMarks.current.betterSoft
    Tone.Must -> LocalMarks.current.mustSoft
}

/** How a correction mark is named aloud, since its colour alone says must-fix, optional or adopted. */
fun Tone.markLabel(): String = when (this) {
    Tone.Must -> "必改"
    Tone.Better -> "可改"
    Tone.Good -> "已采纳"
    else -> "批注"
}

/** Text that is not inside a card lines up here. */
val PageGutter = 20.dp

/** Cards sit this far from the screen edge and keep their own inner padding. */
val CardMargin = 16.dp
val CardPadding = 16.dp

/**
 * Width of the widest [samples] set in [style], measured at the user's font scale, for label columns that must
 * line up across rows without wrapping or clipping. Measured rather than sized in sp because Android 14+ scales
 * large font sizes non-linearly, so a width in sp no longer tracks the text it holds.
 */
@Composable
fun labelWidth(samples: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(samples, style, measurer, density) {
        val widest = samples.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        with(density) { widest.toDp() } + 1.dp
    }
}

/** Large title at the top of a tab root. */
@Composable
fun TabTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 24.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        Hairline()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, start: Dp = 0.dp) {
    HorizontalDivider(modifier.padding(start = start), thickness = 0.8.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Divider between rows of one card, starting where the row text starts. */
@Composable
fun RowDivider() = Hairline(start = CardPadding)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = PageGutter, end = 8.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** A card on the desk: one group of rows or one block of related content. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(CardPadding),
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow, border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(color = color, shape = MaterialTheme.shapes.large, border = border,
        modifier = modifier.fillMaxWidth().padding(horizontal = CardMargin)) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** A card of [ListRow]s separated by inset dividers. */
@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    PaperCard(modifier, padding = PaddingValues(0.dp), content = content)

/** A tappable row inside a card: title, an optional quiet second line and a short status pill on the right. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    trailingTone: Tone = Tone.Muted,
    /** Spoken instead of [trailing] when the short status needs words, e.g. a bare score. */
    trailingDescription: String? = null,
    enabled: Boolean = true,
    subtitleLines: Int = 2,
    showChevron: Boolean = true,
    /** Extra one-line previews, each cut with its own ellipsis. */
    lines: List<String> = emptyList(),
    onClick: () -> Unit
) {
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp).padding(start = CardPadding, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Plain tinted words rather than a pill: long lists stay calm and the colour still carries the state.
        if (!trailing.isNullOrBlank()) Text(trailing, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = trailingTone.color(), maxLines = 1, modifier = Modifier.padding(start = 12.dp)
            .then(if (trailingDescription != null) Modifier.semantics { contentDescription = trailingDescription } else Modifier))
        if (showChevron && enabled) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        else Spacer(Modifier.width(8.dp))
    }
}

/** Fixed footer for the screen's main action; rides above the keyboard and system bar. */
@Composable
fun BottomBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column {
            Hairline()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Muted) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = tone.color())
}

/** Small label with a soft background, used sparingly for categories. */
@Composable
fun Tag(text: String, tone: Tone = Tone.Muted) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = tone.color(), maxLines = 1,
        modifier = Modifier.background(tone.soft(), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A short status such as 草稿 or 8 分档, as a tinted pill. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(text, style = PillText, color = tone.color(), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(tone.soft(), RoundedCornerShape(50)).padding(horizontal = PillPadding, vertical = 4.dp))
}

private val PillPadding = 10.dp
private val PillText @Composable get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)

/** Width that fits the widest of [labels] as a [StatusPill], so pills in one column line up. */
@Composable
fun pillWidth(labels: List<String>): Dp = labelWidth(labels, PillText) + PillPadding * 2

/** Heading of a card's own content, e.g. 篇章 1 · 第 46–50 题. */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() })
}

/** Honest, quiet provenance under generated text. */
@Composable
fun Provenance(model: String, modifier: Modifier = Modifier) {
    Note(if (model.isBlank()) "生成内容，仅供参考" else "$model 生成，仅供参考", modifier)
}

/** Markdown rendered with the app's text colour and size; text stays selectable. */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.builder(context).usePlugin(TablePlugin.create(context)).build() }
    val rendered = remember(markwon, text) { markwon.toMarkdown(text) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val size = style.fontSize.value
    AndroidView(modifier = modifier.fillMaxWidth(), factory = { ctx ->
        TextView(ctx).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.4f) }
    }, update = { view ->
        view.setTextColor(color)
        view.setLinkTextColor(link)
        view.textSize = size
        markwon.setParsedMarkdown(view, rendered)
    })
}

data class TextMark(val id: Int, val range: IntRange, val tone: Tone)

/**
 * Text with correction marks drawn under the exact characters, the way a teacher underlines in red.
 * Tapping a mark reports its id; the selected mark also gets a soft background. Screen readers reach
 * each mark through the text's custom actions, named by tone and the marked words.
 */
@Composable
fun MarkedText(
    text: String,
    marks: List<TextMark>,
    selected: Int?,
    style: TextStyle,
    onMark: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val colors = marks.associate { it.id to it.tone.color() }
    val softs = marks.associate { it.id to it.tone.soft() }
    val valid = marks.filter { it.range.first >= 0 && it.range.last < text.length && !it.range.isEmpty() }
    val annotated = buildAnnotatedString {
        append(text)
        valid.firstOrNull { it.id == selected }?.let { addStyle(SpanStyle(background = softs.getValue(it.id)), it.range.first, it.range.last + 1) }
    }
    val actions = valid.sortedBy { it.range.first }.map { mark ->
        CustomAccessibilityAction("${mark.tone.markLabel()}：${text.substring(mark.range).take(40)}") { onMark(mark.id); true }
    }
    Text(annotated, style = style.copy(color = MaterialTheme.colorScheme.onSurface), onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth()
            .semantics { if (actions.isNotEmpty()) customActions = actions }
            .drawBehind {
                val result = layout ?: return@drawBehind
                valid.forEach { mark ->
                    val end = mark.range.last + 1
                    for (line in result.getLineForOffset(mark.range.first)..result.getLineForOffset(mark.range.last)) {
                        val from = maxOf(mark.range.first, result.getLineStart(line))
                        val to = minOf(end, result.getLineEnd(line, visibleEnd = true))
                        if (to <= from) continue
                        val y = result.getLineBaseline(line) + 3.dp.toPx()
                        drawLine(colors.getValue(mark.id), Offset(result.getBoundingBox(from).left, y),
                            Offset(result.getBoundingBox(to - 1).right, y), strokeWidth = if (mark.id == selected) 2.5.dp.toPx() else 1.6.dp.toPx())
                    }
                }
            }
            .pointerInput(valid) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    (valid.firstOrNull { offset in it.range }
                        ?: valid.minByOrNull { minOf(kotlin.math.abs(it.range.first - offset), kotlin.math.abs(it.range.last - offset)) }
                            ?.takeIf { minOf(kotlin.math.abs(it.range.first - offset), kotlin.math.abs(it.range.last - offset)) <= 2 })
                        ?.let { onMark(it.id) }
                }
            })
}

/** Two words of status with a coloured dot, for dense lists where a badge would be too loud. */
@Composable
fun DotStatus(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(tone.color(), RoundedCornerShape(50)))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tone.color(), fontWeight = FontWeight.Medium)
    }
}
