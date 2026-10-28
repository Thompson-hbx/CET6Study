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
import com.example.cet6.ui.components.Note
import com.example.cet6.ui.components.StatusPill
import com.example.cet6.ui.components.Tag
import com.example.cet6.ui.components.Tone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PaperRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    skillLabel: String? = null,
    statusLabel: String? = null,
    statusTone: Tone = Tone.Muted,
    note: String? = null
) {
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (skillLabel != null) Tag(skillLabel, Tone.Ink)
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!note.isNullOrBlank()) Note(note, tone = Tone.Better)
            }
            if (!statusLabel.isNullOrBlank()) {
                StatusPill(statusLabel, statusTone)
            } else {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
                    Text("进入", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExamHistory(vm: StudyViewModel, onOpen: (CoachPractice) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var statusFilter by rememberSaveable { mutableIntStateOf(0) } // 0 = all, 1 = submitted, 2 = draft
    var limit by rememberSaveable(query, kindFilter, statusFilter) { mutableIntStateOf(20) }
    val bands = remember(vm.reviewTurns) { latestBands(vm.reviewTurns) }
    val allValid = remember(vm.coach.history) {
        vm.coach.history.filter { it.material.isNotBlank() || it.answer.isNotBlank() }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(200) },
        label = { Text("搜索标题、目标与错因") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = kindFilter == null,
            onClick = { kindFilter = null },
            label = { Text("全部题型 ${allValid.size}") }
        )
        CoachKind.entries.forEach { kind ->
            val count = allValid.count { it.kind == kind.name }
            if (count > 0) {
                FilterChip(
                    selected = kindFilter == kind.name,
                    onClick = { kindFilter = if (kindFilter == kind.name) null else kind.name },
                    label = { Text("${kind.label} $count") }
                )
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "全部状态", 1 to "已提交", 2 to "草稿").forEach { (code, label) ->
            FilterChip(
                selected = statusFilter == code,
                onClick = { statusFilter = code },
                label = { Text(label) }
            )
        }
    }
    val records = allValid.filter { e ->
        (kindFilter == null || e.kind == kindFilter) &&
            (statusFilter == 0 || (statusFilter == 1 && e.submittedAt > 0) || (statusFilter == 2 && e.submittedAt == 0L)) &&
            (query.isBlank() || e.title.contains(query, true) || e.errorReason.contains(query, true) || e.practiceGoal.contains(query, true))
    }
    if (records.isEmpty()) Note("暂无符合筛选条件的练习记录。")
    records.take(limit).forEach { e ->
        val kind = CoachKind.valueOf(e.kind)
        val date = Instant.ofEpochMilli(if (e.submittedAt > 0) e.submittedAt else e.updatedAt)
            .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        val detail = when (kind) {
            CoachKind.READING -> {
                val questions = ExamCodec.passages(e).flatMap { it.questions }
                if (questions.isNotEmpty()) {
                    val answered = questions.count { ReadingQuestions.selected(e.answer, "${it.number}").orEmpty().isNotBlank() }
                    "已录 $answered/${questions.size} 题"
                } else {
                    val customQs = ReadingQuestions.parse(e.questions)
                    if (customQs.isNotEmpty()) "自建 ${customQs.size} 题" else "自选阅读"
                }
            }
            else -> "${WritingCoach.words(e.answer)} 词"
        }
        val (statusLabel, statusTone) = when {
            e.submittedAt == 0L -> "草稿" to Tone.Better
            bands[e.id] != null -> "${bands[e.id]} 分档" to Tone.Ink
            kind == CoachKind.READING -> {
                val known = ExamCodec.passages(e).flatMap { it.questions }.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() }
                if (known.isNotEmpty()) {
                    val right = known.count { ReadingQuestions.selected(e.answer, "${it.number}") == it.referenceAnswer }
                    "$right/${known.size}" to Tone.Ink
                } else "已核对" to Tone.Good
            }
            else -> "已提交" to Tone.Good
        }
        val note = listOfNotNull(
            e.practiceGoal.takeIf(String::isNotBlank)?.let { "训练目标：$it" },
            e.errorReason.takeIf(String::isNotBlank)?.let { "错因：$it" }
        ).joinToString(" · ").takeIf(String::isNotBlank)
        PaperRow(
            title = e.title.ifBlank { "自定义${kind.label}" },
            subtitle = "$date · $detail",
            onClick = { onOpen(e) },
            enabled = !vm.coach.working,
            skillLabel = kind.label,
            statusLabel = statusLabel,
            statusTone = statusTone,
            note = note
        )
    }
    if (records.size > limit) TextButton(onClick = { limit += 20 }) { Text("更多记录（剩余 ${records.size - limit} 条）") }
}
