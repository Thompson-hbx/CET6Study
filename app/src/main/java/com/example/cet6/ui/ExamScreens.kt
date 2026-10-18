package com.example.cet6.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.theme.AccentWarning
import com.example.cet6.ui.theme.AccentWarningContainer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PaperRow(title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
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
            Text("→", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExamLibraryScreen(vm: StudyViewModel, onContinue: (CoachPractice) -> Unit,
    onStart: (ExamPaper, CoachKind, ExamPassage?) -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(2026) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var management by rememberSaveable { mutableStateOf(false) }
    val imported = vm.examPapers.associateBy { it.stableId }
    val selected = (ExamCatalog.papers().map { imported[it.stableId] ?: it } + vm.examPapers.filter { it.sample }).find { it.stableId == selectedId }
    BackHandler(selectedId != null || management) { selectedId = null; management = false }
    val uriHandler = LocalUriHandler.current
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewExam) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportExam) }

    // If paper detail view is open:
    if (selected != null) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextButton(onClick = { selectedId = null }) {
                Text("← 返回真题列表", style = MaterialTheme.typography.labelLarge)
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selected.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (selected.status) {
                                "已导入" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                "待校对" -> AccentWarningContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = selected.status,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = when (selected.status) {
                                    "已导入" -> MaterialTheme.colorScheme.onPrimaryContainer
                                    "待校对" -> AccentWarning
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Text(
                        "内容版本 ${selected.revision} · 来源：${selected.sourceName.ifBlank { "官方考次目录" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (selected.issues().isEmpty()) {
                // Section C: 仔细阅读
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("仔细阅读", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("篇章 1 与篇章 2 · 共 10 题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = { onStart(selected, CoachKind.READING, null) },
                                enabled = !vm.coach.working
                            ) {
                                Text("录入两篇答案")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        selected.passages.forEach { p ->
                            val done = vm.coach.history.count { ExamCodec.itemId(it) == "${selected.stableId}/READING/${p.code}" && it.submittedAt > 0 }
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !vm.coach.working) { onStart(selected, CoachKind.READING, p) }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("篇章 ${p.code} · ${p.title.ifBlank { "Section C" }}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("1—5 题 · 已练 $done 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("单篇练习 →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // Writing
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("短文写作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Writing · 1 题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = { onStart(selected, CoachKind.WRITING, null) },
                                enabled = !vm.coach.working
                            ) {
                                Text("开始写作")
                            }
                        }
                        if (selected.writingInstructions.isNotBlank()) {
                            Text(
                                selected.writingInstructions.take(120) + if (selected.writingInstructions.length > 120) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }

                // Translation
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("段落翻译", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Translation · 1 题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = { onStart(selected, CoachKind.TRANSLATION, null) },
                                enabled = !vm.coach.working
                            ) {
                                Text("开始翻译")
                            }
                        }
                        if (selected.chineseText.isNotBlank()) {
                            Text(
                                selected.chineseText.take(120) + if (selected.chineseText.length > 120) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AccentWarningContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, AccentWarning.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("资料尚未就绪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AccentWarning)
                        Text("从你合法持有的资料导入并完成校对后，即可在这里练习。", style = MaterialTheme.typography.bodyMedium)
                        selected.issues().forEach {
                            Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(
                            onClick = { selectedId = null; management = true },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text("打开题库管理")
                        }
                    }
                }
            }

            if (selected.sourceUrl.isNotBlank()) {
                Surface(
                    onClick = { runCatching { uriHandler.openUri(selected.sourceUrl) } },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("材料来源：${selected.sourceName.ifBlank { "外部考次网页" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("查看链接 ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        return
    }

    // Secondary Management View
    if (management) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("题库管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { management = false }) { Text("返回真题") }
            }
            Text("导入你有权使用的结构化 JSON 资料。PDF / Word 请先在电脑本地转换并校对；扫描件暂不自动识别。操作方法见项目导入说明。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !vm.examBusy) {
                    Text("导入 JSON 资料")
                }
                OutlinedButton(onClick = { export.launch("cet6-personal-exams.json") }, enabled = !vm.examBusy && vm.examPapers.isNotEmpty()) {
                    Text("导出个人题库")
                }
            }
            if (vm.examStatus.isNotBlank()) {
                Text(vm.examStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            vm.examPreview.forEach { p ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${p.label} · ${p.status}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(p.issues().joinToString("；").ifBlank { "结构完整，使用权与套卷一致性由导入者确认" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (vm.examPreview.isNotEmpty()) {
                Button(onClick = vm::confirmExamImport, enabled = !vm.examBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("确认保存个人题库")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { runCatching { uriHandler.openUri(ExamCatalog.SET_SOURCE) } }) {
                Text("查看套次目录来源 ↗", style = MaterialTheme.typography.labelMedium)
            }
        }
        return
    }

    // MAIN LIBRARY SCREEN: 3-tier hierarchy
    val years = listOf(2026, 2025, 2024, 2023)
    val availableSessions = ExamCatalog.sessions.filter { it.year == year }.sortedByDescending { it.date }
    var selectedSessionDate by rememberSaveable(year) { mutableStateOf(availableSessions.firstOrNull()?.date ?: "") }
    val currentSession = availableSessions.find { it.date == selectedSessionDate } ?: availableSessions.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Top row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("按考次练习", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { management = true }) {
                Text("题库管理", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Resume last practice if any
        val latest = vm.coach.history.firstOrNull { it.material.isNotBlank() }
        if (latest != null) {
            Surface(
                onClick = { onContinue(latest) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("继续上次练习", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(latest.title.ifBlank { CoachKind.valueOf(latest.kind).label }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Text("继续 →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Level 1: Year Selector (2026, 2025, 2024, 2023)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            years.forEach { y ->
                val isSelected = year == y
                Surface(
                    onClick = { year = y },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "$y",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontFeatureSettings = "tnum"
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Level 2: Month Selector for selected year
        if (availableSessions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableSessions.forEach { session ->
                    val isSelected = session.date == currentSession?.date
                    val label = "${session.month} 月" + if (session.note.isNotBlank()) " · 加考" else ""
                    Surface(
                        onClick = { selectedSessionDate = session.date },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Level 3: Paper List (3 sets for selected session)
        if (currentSession != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (1..3).forEach { set ->
                    val p = imported[ExamCatalog.paperId(currentSession.date, "$set")]
                        ?: ExamCatalog.papers().first { it.examDate == currentSession.date && it.setCode == "$set" }
                    val completed = vm.coach.history.filter { it.submittedAt > 0 }
                        .mapNotNull { ExamCodec.itemId(it) }
                        .filter { it.startsWith(p.stableId + "/") }.toSet()
                    val count = (if (completed.any { it.endsWith("/READING/all") }) 2 else completed.count { it.contains("/READING/") }) +
                        completed.count { !it.contains("/READING/") }

                    Surface(
                        onClick = { selectedId = p.stableId },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "第 $set 套",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                // Status badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = when (p.status) {
                                        "已导入" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                        "待校对" -> AccentWarningContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = p.status,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = when (p.status) {
                                            "已导入" -> MaterialTheme.colorScheme.onPrimaryContainer
                                            "待校对" -> AccentWarning
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            Text(
                                text = "仔细阅读 10 题 · 写作 1 题 · 翻译 1 题",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (count > 0) "已完成 $count / 4 项" else "未开始",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFeatureSettings = "tnum"
                                    )
                                )
                                Text(
                                    text = "进入试卷 →",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Functional sample papers at the bottom
        val samples = vm.examPapers.filter { it.sample }
        if (samples.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                samples.forEach { sample ->
                    Surface(
                        onClick = { selectedId = sample.stableId },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "功能验证样本 · 仔细阅读",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "体验 →",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamTrainingScreen(vm: StudyViewModel, kind: CoachKind, scroll: ScrollState) {
    val state = vm.coach
    val entry = state.entries[kind] ?: return
    val meta = remember(entry.questions) { ExamCodec.metadata(entry) } ?: return
    if (kind == CoachKind.READING) {
        PaperAnswerScreen(vm, entry, scroll)
        return
    }
    var panel by rememberSaveable(entry.id) { mutableIntStateOf(if (entry.submittedAt > 0) 2 else 0) }
    val submitted = entry.submittedAt > 0
    val enabled = !state.working && !submitted
    LaunchedEffect(panel, entry.id) { scroll.scrollTo(0) }

    Text(entry.title, style = MaterialTheme.typography.headlineSmall)
    val labels = listOf("作答", "对照", "复盘")
    SecondaryTabRow(panel, containerColor = MaterialTheme.colorScheme.background) {
        labels.forEachIndexed { i, label -> Tab(selected = panel == i, onClick = { panel = i }, text = { Text(label) }, enabled = i != 2 || submitted) }
    }

    if (panel == 2) {
        FeedbackAndReview(vm, kind, entry)
        return
    }
        if (panel == 0) {
            SelectionContainer { Text(entry.material, style = MaterialTheme.typography.bodyLarge, lineHeight = 30.sp) }
            WritingTimer(entry.id, enabled)
            OutlinedTextField(entry.answer, { text -> state.edit(kind) { it.copy(answer = text) } },
                modifier = Modifier.fillMaxWidth(), minLines = 8, readOnly = !enabled,
                label = { Text(if (kind == CoachKind.WRITING) "我的作文" else "我的译文") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 29.sp),
                supportingText = { Text("${Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(entry.answer).count()} 词 · ${entry.answer.length} 字符") })
            if (!submitted) Button(onClick = { state.submit(kind, false) }, enabled = enabled && entry.answer.isNotBlank()) { Text("提交") }
            else TextButton(onClick = { panel = 1 }) { Text("查看对照") }
        } else {
            Text("我的原稿", style = MaterialTheme.typography.titleLarge)
            SelectionContainer { Text(entry.answer.ifBlank { "尚未作答" }, lineHeight = 29.sp) }
            if (submitted) {
                HorizontalDivider()
                if (meta.optString("referenceTranslation").orEmpty().isNotBlank()) {
                    Text("有来源的参考译文", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(meta.optString("referenceTranslation"), lineHeight = 29.sp) }
                    Text(meta.optString("referenceSource"), style = MaterialTheme.typography.bodySmall)
                }
                Text("AI 修改建议", style = MaterialTheme.typography.titleLarge)
                if (entry.feedback.isNotBlank()) MarkdownFeedback(entry.feedback)
                else Text("保存原稿后，可在复盘页请求逐句批改。")
            }
        }
    Text(state.messages[kind].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!submitted) TextButton(onClick = { state.save(kind) }, enabled = !state.working) { Text("保存草稿") }
}

@Composable
private fun WritingTimer(id: String, enabled: Boolean) {
    var startedAt by rememberSaveable(id) { mutableLongStateOf(0) }
    var elapsed by rememberSaveable(id) { mutableLongStateOf(0) }
    LaunchedEffect(startedAt, enabled) {
        while (startedAt > 0 && enabled) { elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0) / 1000; delay(1000) }
    }
    TextButton(onClick = { startedAt = if (startedAt == 0L) System.currentTimeMillis() else 0; elapsed = 0 }, enabled = enabled) {
        Text(if (startedAt == 0L) "开始计时（可选）" else "${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')} · 停止")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackAndReview(vm: StudyViewModel, kind: CoachKind, entry: CoachPractice) {
    val state = vm.coach
    TextButton(onClick = { state.submit(kind, true) }, enabled = !state.working && !vm.busy) { Text(if (entry.feedback.isBlank()) "AI 批改" else "重新批改") }
    if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(state.messages[kind].orEmpty(), style = MaterialTheme.typography.bodySmall)
    if (entry.feedback.isNotBlank()) {
        MarkdownFeedback(entry.feedback)
        var followUp by rememberSaveable(entry.id) { mutableStateOf("") }
        OutlinedTextField(followUp, { followUp = it.take(4000) }, label = { Text("追问证据或修改建议") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { state.submit(kind, true, followUp) }, enabled = !state.working && followUp.isNotBlank()) { Text("发送追问") }
    }
    if (state.partialEntryId == entry.id && state.partial.isNotBlank()) {
        var show by rememberSaveable(entry.id) { mutableStateOf(false) }
        TextButton(onClick = { show = !show }) { Text("未完成的临时反馈（尚未保存）") }
        if (show) Text(state.partial)
    }
    if (kind == CoachKind.READING) ReadingEvidenceCard(state, entry)
    Text("这次卡在哪里？", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("词汇理解", "长难句", "逻辑推断", "选项陷阱", "语法", "表达").forEach { reason ->
            FilterChip(entry.errorReason.contains(reason), { state.edit(kind) { it.copy(errorReason = if (it.errorReason.contains(reason)) it.errorReason else listOf(it.errorReason, reason).filter(String::isNotBlank).joinToString("；")) } }, label = { Text(reason) }, enabled = !state.working)
        }
    }
    OutlinedTextField(entry.errorReason, { value -> state.edit(kind) { it.copy(errorReason = value) } }, label = { Text("记录自己的错因") }, modifier = Modifier.fillMaxWidth(), enabled = !state.working)
    TextButton(onClick = { state.open(kind, entry, retry = true) }, enabled = !state.working) { Text("再练") }
    TextButton(onClick = { state.generate(kind) }, enabled = !state.working && entry.errorReason.isNotBlank()) { Text("AI 针对练习") }
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
