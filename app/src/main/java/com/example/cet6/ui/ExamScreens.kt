package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PaperRow(title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
                Text("进入", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ExamHistory(vm: StudyViewModel, onOpen: (CoachPractice) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(query) { mutableIntStateOf(20) }
    Text("练习档案", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(query, { query = it.take(200) }, label = { Text("搜索标题与错因") }, modifier = Modifier.fillMaxWidth())
    val records = vm.coach.history.filter { it.material.isNotBlank() && (query.isBlank() || it.title.contains(query, true) || it.errorReason.contains(query, true)) }
    if (records.isEmpty()) Text("暂无练习记录")
    records.take(limit).forEach { e ->
        val date = Instant.ofEpochMilli(e.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        PaperRow(e.title.ifBlank { "自定义${CoachKind.valueOf(e.kind).label}" }, "$date · " + if (e.submittedAt > 0) "已作答" else "草稿", { onOpen(e) }, !vm.coach.working)
    }
    if (records.size > limit) TextButton(onClick = { limit += 20 }) { Text("更多记录") }
}
