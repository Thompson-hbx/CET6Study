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
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*

@Composable
fun ReadingChoices(entry: CoachPractice, editable: Boolean, onAnswer: (String) -> Unit) {
    val questions = remember(entry.questions) { ReadingQuestions.parse(entry.questions) }
    if (questions.isEmpty()) {
        Text("可直接按题号作答。题目按 1. / 2. 编号、选项 A. 至 F. 各占一行时，会显示选项按钮。",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val selectable = ReadingQuestions.canSelect(entry.answer)
    if (!selectable && editable) Text("当前作答包含说明文字，请在下方继续编辑；选项按钮仅适用于「1. A」格式。")
    questions.forEach { question ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${question.number}. ${question.text}", style = MaterialTheme.typography.titleMedium)
                question.choices.forEach { option ->
                    val select = { onAnswer(ReadingQuestions.choose(entry.answer, question.number, option.label)) }
                    Row(Modifier.fillMaxWidth().clickable(enabled = editable && selectable, onClick = select)) {
                        RadioButton(selected = ReadingQuestions.selected(entry.answer, question.number) == option.label,
                            onClick = select, enabled = editable && selectable)
                        Text("${option.label}. ${option.text}", Modifier.padding(top = 12.dp))
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
                    "已在原文找到（第 ${range.first + 1} 字符起）。文字匹配不代表 AI 解读一定正确。")
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
                        message = "已加入本篇难句收藏；保存状态见上方。"
                    } catch (e: IllegalArgumentException) { message = e.message.orEmpty() }
                }, enabled = !state.working) { Text("收藏这段原文") }
            }
            if (message.isNotBlank()) Text(message)
            val saved = ReadingEvidence.saved(entry.savedSentences)
            if (saved.isNotEmpty()) {
                Text("本篇难句收藏（离线可看）")
                saved.forEach { sentence -> TextButton(onClick = { quote = sentence }) { Text(sentence) } }
            }
        }
    }
}
