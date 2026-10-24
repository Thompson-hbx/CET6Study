package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.cet6.data.CoachTurn
import com.example.cet6.data.TurnKind
import com.example.cet6.ui.components.*

/** A thin working line with an honest stop control; no spinner theatre. */
@Composable
fun WorkingLine(label: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("停止") }
        }
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A request that did not go through, as a tinted strip that screen readers announce when it appears. */
@Composable
fun FailureLine(failure: CoachTalk.Failure, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tone = if (failure.stopped) Tone.Muted else Tone.Must
    Surface(color = tone.soft(), shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (failure.question.isNotBlank()) Text("问题：${failure.question.take(120)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(failure.message, style = MaterialTheme.typography.bodyMedium, color = tone.color())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                failure.retry?.let { retry -> TextButton(onClick = { onDismiss(); retry() }) { Text(if (failure.stopped) "重新生成" else "重试") } }
                TextButton(onClick = onDismiss) { Text("收起") }
            }
        }
    }
}

/**
 * Quick questions; tapping sends exactly what is shown, nothing is silently rewritten. With [edge] the row
 * runs to the screen edges and starts at that inset, so chips scroll out of view instead of being cut at the margin.
 */
@Composable
fun QuickAsks(items: List<String>, enabled: Boolean, onPick: (String) -> Unit, modifier: Modifier = Modifier, edge: Dp = 0.dp) {
    if (items.isEmpty()) return
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = edge, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Surface(onClick = { onPick(item) }, enabled = enabled, shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Text(item, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Follow-up thread of one anchor: the learner's questions on the right, replies as plain reading text. */
@Composable
fun ThreadView(turns: List<CoachTurn>, pending: CoachTalk.Pending?, onStop: () -> Unit, modifier: Modifier = Modifier,
    kinds: Set<String> = setOf(TurnKind.CHAT)) {
    val chat = turns.filter { it.kind in kinds }
    if (chat.isEmpty() && pending?.kind !in kinds) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        chat.forEach { turn ->
            if (turn.fromUser) Question(turn.content)
            else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MarkdownBody(turn.content)
                ReplyActions(turn)
            }
        }
        if (pending != null && pending.kind in kinds) {
            if (pending.question.isNotBlank()) Question(pending.question)
            if (pending.text.isNotBlank()) SelectionContainer { Text(pending.text, style = MaterialTheme.typography.bodyLarge) }
            WorkingLine(if (pending.text.isBlank()) "等待 AI 回复…" else "正在生成 · ${pending.text.length} 字符", onStop)
        }
    }
}

@Composable
private fun ReplyActions(turn: CoachTurn) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(turn.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Provenance(turn.model)
            Text("已保存 · ${shortTime(turn.createdAt)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(turn.content)); copied = true }) {
            Text(if (copied) "已复制" else "复制回复")
        }
    }
}

@Composable
private fun Question(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp), color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 300.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/**
 * Single-line composer that grows with its text; the send key doubles as stop while a reply streams.
 * A [failure] of what was just asked shows right above it, where the learner is looking.
 */
@Composable
fun AskBar(placeholder: String, busy: Boolean, enabled: Boolean, onSend: (String) -> Unit, onStop: () -> Unit,
    modifier: Modifier = Modifier, draftKey: String = "", failure: CoachTalk.Failure? = null, onDismissFailure: () -> Unit = {}) {
    var text by rememberSaveable(draftKey) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun send() {
        if (text.isBlank() || !enabled || busy) return
        onSend(text.trim()); text = ""; focus.clearFocus()
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        failure?.let {
            FailureLine(it, onDismissFailure)
            if (it.question.isNotBlank()) TextButton(onClick = { text = it.question; onDismissFailure() },
                enabled = text.isBlank() || text == it.question) { Text("编辑问题后再发") }
        }
        if (busy) Text("AI 正在处理，可随时停止", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                BasicTextField(text, { text = it.take(2000) }, enabled = enabled, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth(),
                    // The placeholder lives inside the field so screen readers announce it as the field's label.
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    })
            }
            if (busy) FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Stop, contentDescription = "停止回复")
            } else FilledIconButton(onClick = ::send, enabled = enabled && text.isNotBlank(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
            }
        }
    }
}
