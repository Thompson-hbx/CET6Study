package com.example.cet6.ui

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingDiagnosisPanel(state: CoachState, entry: CoachPractice, passage: ExamPassage, question: ExamQuestion, networkBusy: Boolean) {
    val number = question.number
    val diagnosis = ReadingDiagnosis.saved(entry.feedback, number)
    val ownsPartial = state.partialEntryId == entry.id && state.partialReadingKey == "$number/diagnosis"
    var followUp by rememberSaveable(entry.id, number) { mutableStateOf("") }
    var showFollowUp by rememberSaveable(entry.id, number) { mutableStateOf(false) }
    val busy = state.working || networkBusy
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI 错因与原文定位", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (diagnosis.isBlank()) Text("结合本篇文章和你的选项，分析可能的误读，并给出原文定位依据。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { state.readingDiagnosis(number, followUp) }, enabled = !busy) {
            Text(if (diagnosis.isBlank()) "AI 分析我的作答" else "重新分析本题")
        }
        Text("点击后发送本篇文章、题目和作答，使用当前已连接模型。AI 判断与错因需自行核实。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ownsPartial && state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (diagnosis.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "同义替换" to "请把本题题干、原文证据和选项之间的同义替换逐项对应起来，引用原文，不补造句子。",
                "为什么选错" to "请具体说明我选的选项哪里超出了原文、偷换了概念或遗漏了条件；不能确定时请说明。",
                "拆解难句" to "请选出决定本题答案的原文长难句，拆解主干、修饰和逻辑关系，说明怎样理解。"
            ).forEach { (label, prompt) ->
                SuggestionChip(onClick = { followUp = prompt; showFollowUp = true },
                    label = { Text(label) }, enabled = !busy)
            }
        }
        TextButton(onClick = { showFollowUp = !showFollowUp }) {
            Text(if (showFollowUp) "收起补充" else if (diagnosis.isBlank()) "补充我当时的理解" else "继续追问")
        }
        if (showFollowUp) {
            OutlinedTextField(followUp, { followUp = it.take(2000) }, enabled = !busy,
                label = { Text("我为什么这样选，或哪里仍不明白") }, modifier = Modifier.fillMaxWidth())
            if (diagnosis.isNotBlank()) Button(onClick = { state.readingDiagnosis(number, followUp) }, enabled = !busy && followUp.isNotBlank()) {
                Text("发送本题追问")
            }
        }
        if (diagnosis.isNotBlank()) {
            MarkdownFeedback(diagnosis)
            val quotes = remember(passage.content, diagnosis) { ReadingEvidence.suggestions(passage.content, diagnosis) }
            val unmatched = remember(passage.content, diagnosis) {
                ReadingEvidence.quotations(diagnosis).any { ReadingEvidence.find(passage.content, it) == null }
            }
            if (unmatched) Text("部分引号内文本未在原文匹配，请核实或追问，不能直接当作原文证据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (quotes.isNotEmpty()) {
                Text("回原文核对引用", style = MaterialTheme.typography.labelLarge)
                Text("仅确认引文字面匹配，不代表 AI 的解释一定正确。", style = MaterialTheme.typography.bodySmall)
                val paragraphs = remember(passage.content) { ReadingDiagnosis.paragraphs(passage.content) }
                quotes.forEach { quote ->
                    val index = paragraphs.indexOfFirst { ReadingEvidence.find(it, quote) != null }
                    if (index >= 0) {
                        var expanded by rememberSaveable(entry.id, number, quote) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "收起" else "查看"}原文第 ${index + 1} 段") }
                        if (expanded) {
                            val paragraph = paragraphs[index]
                            val range = ReadingEvidence.find(paragraph, quote)!!
                            val highlighted = buildAnnotatedString {
                                append(paragraph)
                                addStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer), range.first, range.last + 1)
                            }
                            SelectionContainer { Text(highlighted, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
        if (ownsPartial && state.partial.isNotBlank()) {
            Text(if (state.working) "正在生成，以下文本尚未保存" else "响应未完成，已保留此前分析；可手动重试", style = MaterialTheme.typography.bodySmall)
            var expanded by rememberSaveable(entry.id, number) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起临时文本" else "查看临时文本") }
            if (expanded) Text(state.partial)
        }
        val status = state.messages[CoachKind.READING].orEmpty()
        if (status.isNotBlank() && status != "已保存到本机" && status != "正在保存……")
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
