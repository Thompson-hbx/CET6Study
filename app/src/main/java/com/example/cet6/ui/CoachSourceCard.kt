package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.data.CoachPractice

@Composable
fun CoachSourceCard(state: CoachState, entry: CoachPractice) {
    if (entry.sourcePracticeId.isBlank()) return
    val source = state.history.firstOrNull { it.id == entry.sourcePracticeId }
    var compare by rememberSaveable(entry.id) { mutableStateOf(false) }
    var showFeedback by rememberSaveable(entry.id) { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.practiceGoal.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("AI 原创针对练习 · 非官方题目", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Text(
                        "训练目标：${entry.practiceGoal}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (source == null) {
                Text(
                    "来源练习暂不可用，当前内容仍保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "来源练习：${source.title.ifBlank { "未命名练习" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                TextButton(onClick = { compare = !compare }) {
                    Text(if (compare) "收起来源作答" else "对照来源作答与建议")
                }
                if (compare) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("来源作答（保留的历史版本）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer {
                                Text(source.answer.ifBlank { "尚未作答" }, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text("当前版本作答", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            SelectionContainer {
                                Text(entry.answer.ifBlank { "尚未作答" }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (source.feedback.isNotBlank()) {
                        TextButton(onClick = { showFeedback = !showFeedback }) {
                            Text(if (showFeedback) "收起来源 AI 建议" else "展开来源 AI 建议")
                        }
                        if (showFeedback) MarkdownFeedback(source.feedback)
                    }
                }
            }
        }
    }
}
