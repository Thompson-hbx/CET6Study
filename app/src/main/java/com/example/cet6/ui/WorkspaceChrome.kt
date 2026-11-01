package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cet6.ui.components.*

/**
 * Compact studio header for writing, translation and reading workspaces: keeps navigation, paper context
 * and draft status in one focused bar so the screen belongs to the manuscript or passage rather than chrome.
 */
@Composable
fun WorkspaceTopBar(
    title: String,
    context: String,
    onBack: () -> Unit,
    detail: String? = null,
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val cleanContext = Regex("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月").replace(
        context
            .removeSuffix(" · 仔细阅读")
            .removeSuffix(" · 阅读")
            .removeSuffix(" · 作文")
            .removeSuffix(" · 写作")
            .removeSuffix(" · 翻译")
    ) { m -> "${m.groupValues[1]}.${m.groupValues[2].padStart(2, '0')}" }
        .replace("第 ", "第")
        .replace(" 套", "套")
    // Round back button, the set's name in bold with the skill and status beneath, actions on the right.
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onBack)
                Column(
                    Modifier.weight(1f).padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        cleanContext,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                        // modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        listOfNotNull(title, detail?.takeIf { !compact && it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(Modifier.widthIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    }
}

/**
 * Manuscript sheet for essays and translations: a structured header bar with live word-target progress
 * and paragraph count above a clean serif writing sheet.
 */
@Composable
fun WorkspaceDocument(
    title: String,
    text: String,
    readOnly: Boolean,
    words: Int,
    minWords: Int? = null,
    maxWords: Int? = null,
    minHeight: Int = 280,
    placeholder: String,
    headerAction: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit
) {
    val paragraphs = if (text.isBlank()) 0 else text.trim().split(Regex("\\n\\s*\\n|\\n")).count { it.isNotBlank() }
    val targetFraction = when {
        minWords == null || minWords <= 0 -> 0f
        else -> (words.toFloat() / minWords.toFloat()).coerceIn(0f, 1f)
    }
    val progressTone = when {
        minWords == null || words == 0 -> Tone.Muted
        words < minWords -> Tone.Ink
        maxWords != null && words > maxWords -> Tone.Better
        else -> Tone.Good
    }
    // A white sheet on the paper ground: word count and target above, the serif writing area below.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CardMargin)
    ) {
        Column {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                        // modifier = Modifier.semantics { heading() }
                    )
                    if (paragraphs > 0) {
                        Tag("$paragraphs 段", Tone.Muted)
                    }
                    Spacer(Modifier.weight(1f))
                    if (headerAction != null) headerAction()
                    WordCount(words, minWords, maxWords, showTarget = true)
                }
                if (minWords != null) {
                    LinearProgressIndicator(
                        progress = { targetFraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                        color = if (progressTone == Tone.Ink) MaterialTheme.colorScheme.primary else progressTone.color(),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        drawStopIndicator = {}
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 22.dp)) {
                Editor(text, readOnly, placeholder, minHeight, onChange)
            }
        }
    }
}
