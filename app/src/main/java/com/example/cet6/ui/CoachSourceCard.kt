package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cet6.data.CoachPractice

@Composable
fun CoachSourceCard(state: CoachState, entry: CoachPractice) {
    if (entry.sourcePracticeId.isBlank()) return
    val source = state.history.firstOrNull { it.id == entry.sourcePracticeId }
    var compare by rememberSaveable(entry.id) { mutableStateOf(false) }
    var showFeedback by rememberSaveable(entry.id) { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.practiceGoal.isNotBlank()) {
                Text("AI 原创针对练习 · 非官方题目", style = MaterialTheme.typography.titleMedium)
                Text("训练目标：${entry.practiceGoal}")
            }
            if (source == null) Text("来源练习暂不可用，当前内容仍保存在本机。")
            else {
                Text("来源练习：${source.title.ifBlank { "未命名练习" }}")
                TextButton(onClick = { compare = !compare }) { Text(if (compare) "收起来源作答" else "对照来源作答与建议") }
                if (compare) {
                    Text("来源作答（保留的历史版本）")
                    SelectionContainer { Text(source.answer.ifBlank { "尚未作答" }) }
                    Text("当前版本作答")
                    SelectionContainer { Text(entry.answer.ifBlank { "尚未作答" }) }
                    if (source.feedback.isNotBlank()) {
                        TextButton(onClick = { showFeedback = !showFeedback }) { Text(if (showFeedback) "收起来源 AI 建议" else "展开来源 AI 建议") }
                        if (showFeedback) MarkdownFeedback(source.feedback)
                    }
                }
            }
        }
    }
}
