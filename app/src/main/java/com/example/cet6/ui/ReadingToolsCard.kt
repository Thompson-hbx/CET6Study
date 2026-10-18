package com.example.cet6.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*

@Composable
fun ReadingChoices(entry: CoachPractice, editable: Boolean, onAnswer: (String) -> Unit) {
    val questions = remember(entry.questions) { ReadingQuestions.parse(entry.questions) }
    if (questions.isEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "按题号填写答案；题目和选项各占一行可显示选项按钮。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
        return
    }
    val selectable = ReadingQuestions.canSelect(entry.answer)
    if (!selectable && editable) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "当前答案格式不支持选项按钮，请在下方编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
    questions.forEach { question ->
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${question.number}. ${question.text}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                question.choices.forEach { option ->
                    val isSelected = ReadingQuestions.selected(entry.answer, question.number) == option.label
                    val select = { onAnswer(ReadingQuestions.choose(entry.answer, question.number, option.label)) }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = editable && selectable, onClick = select)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = select,
                                enabled = editable && selectable
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${option.label}. ${option.text}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReadingEvidenceCard(state: CoachState, entry: CoachPractice) {
    var quote by rememberSaveable(entry.id) { mutableStateOf("") }
    var message by rememberSaveable(entry.id) { mutableStateOf("") }
    val range = remember(entry.material, quote) { ReadingEvidence.find(entry.material, quote) }
    val suggestions = remember(entry.material, entry.feedback) { ReadingEvidence.suggestions(entry.material, entry.feedback) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原文证据与难句", style = MaterialTheme.typography.titleMedium)
            if (suggestions.isNotEmpty()) {
                Text("AI 引用中可在原文找到的片段：")
                suggestions.forEach { candidate ->
                    TextButton(onClick = { quote = candidate; message = "" }) { Text(candidate) }
                }
            }
            OutlinedTextField(quote, { quote = it.take(2000); message = "" }, label = { Text("粘贴要核对的原文引用或难句") },
                modifier = Modifier.fillMaxWidth(), maxLines = 4)
            if (quote.isNotBlank()) {
                Text(if (range == null) "原文未找到相同文字；请检查 AI 是否改写或省略了引用。" else
                    "已匹配原文 · 第 ${range.first + 1} 字符起")
            }
            if (range != null) {
                // Include context but keep long articles usable on a phone.
                val start = maxOf(0, range.first - 180)
                val end = minOf(entry.material.length, range.last + 181)
                val colors = MaterialTheme.colorScheme
                val text = buildAnnotatedString {
                    append(entry.material.substring(start, end))
                    addStyle(SpanStyle(background = colors.tertiaryContainer,
                        color = colors.onTertiaryContainer), range.first - start, range.last + 1 - start)
                }
                SelectionContainer { Text(text) }
                OutlinedButton(onClick = {
                    try {
                        val saved = ReadingEvidence.add(entry.material, entry.savedSentences, quote)
                        state.edit(CoachKind.READING) { it.copy(savedSentences = saved) }
                        state.save(CoachKind.READING)
                        message = "已加入收藏，请确认保存状态。"
                    } catch (e: IllegalArgumentException) { message = e.message.orEmpty() }
                }, enabled = !state.working) { Text("收藏这段原文") }
            }
            if (message.isNotBlank()) Text(message)
            val saved = ReadingEvidence.saved(entry.savedSentences)
            if (saved.isNotEmpty()) {
                Text("本篇难句收藏")
                saved.forEach { sentence -> TextButton(onClick = { quote = sentence }) { Text(sentence) } }
            }
        }
    }
}
