package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.data.CoachKind
import com.example.cet6.data.CoachPractice
import com.example.cet6.data.WeaknessReview
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeaknessReviewCard(history: List<CoachPractice>, today: String, enabled: Boolean, onReview: (CoachPractice) -> Unit) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val all = WeaknessReview.records(history)
    val records = WeaknessReview.records(history, category)
    val periods = WeaknessReview.periods(records, LocalDate.parse(today), ZoneId.systemDefault())
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("我的薄弱项回顾", style = MaterialTheme.typography.titleMedium)
            Text("按自己确认的错因筛选。记录数不是考试正确率，也不代表能力变化。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(category == null, { category = null }, label = { Text("全部 ${all.size}") })
                WeaknessReview.categories.forEach { label ->
                    val count = all.count { label in WeaknessReview.categoriesOf(it) }
                    if (count > 0) FilterChip(category == label, { category = label }, label = { Text("$label $count") })
                }
            }
            Text("${category ?: "全部错因"} · 近7天 ${periods.recent} 条 · 前7天 ${periods.previous} 条")
            Text("按作答日期分组，使用当前笔记；同一练习可属于多个类别。", style = MaterialTheme.typography.bodySmall)
            if (records.isEmpty()) Text("暂未记录错因。完成练习后，可在「错因难句」中记录自己的问题。")
            records.take(5).forEach { entry ->
                HorizontalDivider()
                Text(entry.title.ifBlank { "未命名练习" }, fontWeight = FontWeight.Medium)
                Text("${CoachKind.valueOf(entry.kind).label} · " + Instant.ofEpochMilli(entry.submittedAt)
                    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                Text(entry.errorReason)
                OutlinedButton(onClick = { onReview(entry) }, enabled = enabled) { Text("回看错因与针对练习") }
            }
            if (records.size > 5) Text("此处显示最近5条，其余记录可在对应训练页的历史中检索。")
        }
    }
}
