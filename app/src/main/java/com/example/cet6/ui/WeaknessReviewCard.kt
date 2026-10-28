package com.example.cet6.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
fun WeaknessReviewCard(
    history: List<CoachPractice>,
    today: String,
    enabled: Boolean,
    standalone: Boolean = false,
    onReview: (CoachPractice) -> Unit
) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var limit by rememberSaveable(category, kindFilter, standalone) { mutableIntStateOf(if (standalone) 20 else 5) }
    val all = WeaknessReview.records(history)
    val byKind = if (kindFilter == null) all else all.filter { it.kind == kindFilter }
    val records = WeaknessReview.records(byKind, category)
    val periods = WeaknessReview.periods(records, LocalDate.parse(today), ZoneId.systemDefault())
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (standalone) "按题型与错因筛选" else "错因回顾",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        "${all.size} 条记录",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (standalone && all.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = kindFilter == null,
                        onClick = { kindFilter = null },
                        label = { Text("全部题型") },
                        shape = MaterialTheme.shapes.small
                    )
                    CoachKind.entries.forEach { kind ->
                        val count = all.count { it.kind == kind.name }
                        if (count > 0) {
                            FilterChip(
                                selected = kindFilter == kind.name,
                                onClick = { kindFilter = if (kindFilter == kind.name) null else kind.name },
                                label = { Text("${kind.label} $count") },
                                shape = MaterialTheme.shapes.small
                            )
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text("全部 ${byKind.size}") },
                    shape = MaterialTheme.shapes.small
                )
                WeaknessReview.categories.forEach { label ->
                    val count = byKind.count { label in WeaknessReview.categoriesOf(it) }
                    if (count > 0) {
                        FilterChip(
                            selected = category == label,
                            onClick = { category = label },
                            label = { Text("$label $count") },
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "${category ?: "全部错因"} · 近7天 ${periods.recent} 条 · 前7天 ${periods.previous} 条",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (records.isEmpty()) {
                Text(
                    "暂未记录错因。完成练习后，可在「错因笔记」或「错因难句」中记录自己的问题。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            records.take(limit).forEach { entry ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                entry.title.ifBlank { "未命名练习" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    CoachKind.valueOf(entry.kind).label,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Text(
                            Instant.ofEpochMilli(entry.submittedAt)
                                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            entry.errorReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedButton(
                            onClick = { onReview(entry) },
                            enabled = enabled,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("回看练习")
                        }
                    }
                }
            }
            if (records.size > limit) {
                if (standalone) {
                    TextButton(onClick = { limit += 20 }) {
                        Text("更多记录（剩余 ${records.size - limit} 条）")
                    }
                } else {
                    Text(
                        "此处显示最近5条，其余记录可在对应训练页的历史中检索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
