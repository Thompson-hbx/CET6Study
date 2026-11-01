package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.PassageText

/** Borderless manuscript editor set in Source Serif so drafting reads like a printed page. */
@Composable
fun Editor(text: String, readOnly: Boolean, placeholder: String, minHeight: Int = 320, onChange: (String) -> Unit) {
    BasicTextField(
        value = text,
        onValueChange = onChange,
        readOnly = readOnly,
        textStyle = PassageText.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, lineHeight = 30.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) {
                    Text(
                        placeholder,
                        style = PassageText.copy(fontSize = 18.sp, lineHeight = 30.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
fun FocusChooser(kind: CoachKind, enabled: Boolean, connected: Boolean, onPick: (WritingFocus) -> Unit) {
    SectionLabel("选择批改方式")
    val focuses = WritingFocus.forKind(kind)
    Column(Modifier.fillMaxWidth().padding(horizontal = CardMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        focuses.forEachIndexed { i, focus ->
            Surface(
                onClick = { onPick(focus) },
                enabled = enabled,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    if (i == 0) 1.dp else 0.5.dp,
                    if (i == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                focus.label + if (i == 0) "（推荐）" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            focus.summaryFor(kind),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill("开始批改", if (i == 0) Tone.Ink else Tone.Muted)
                }
            }
        }
    }
    if (!connected) {
        Note(
            "需要先在「我的 → AI 连接」配置 AI 服务并选择模型；原稿已保存在本机。",
            Modifier.padding(horizontal = PageGutter, vertical = 12.dp),
        )
    }
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
