package com.example.cet6.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PaperRow(title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("→", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExamLibraryScreen(vm: StudyViewModel, onContinue: (CoachPractice) -> Unit,
    onStart: (ExamPaper, CoachKind, ExamPassage?) -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(2026) }
    var kind by rememberSaveable { mutableStateOf(CoachKind.READING) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var management by rememberSaveable { mutableStateOf(false) }
    val imported = vm.examPapers.associateBy { it.stableId }
    val selected = (ExamCatalog.papers().map { imported[it.stableId] ?: it } + vm.examPapers.filter { it.sample }).find { it.stableId == selectedId }
    BackHandler(selectedId != null || management) { selectedId = null; management = false }
    val uriHandler = LocalUriHandler.current
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewExam) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportExam) }

    if (selected != null) {
        TextButton(onClick = { selectedId = null }) { Text("← 全部试卷") }
        Text(selected.label, style = MaterialTheme.typography.headlineMedium)
        Text(selected.status + " · 内容版本 ${selected.revision}", style = MaterialTheme.typography.bodySmall)
        if (selected.issues(kind).isEmpty()) {
            when (kind) {
                CoachKind.READING -> {
                    Text("先在纸上独立完成试卷，再录入答案核对；解析会逐层提示你回到原题寻找证据。", lineHeight = 27.sp)
                    Text("先核对原卷：不同资料的套次编号可能不同。", style = MaterialTheme.typography.labelLarge)
                    selected.passages.forEach { p ->
                        p.questions.firstOrNull()?.let { Text("篇章 ${p.code} · 第 1 题：${it.stem}", style = MaterialTheme.typography.bodyMedium) }
                    }
                    Button(onClick = { onStart(selected, kind, null) }, enabled = !vm.coach.working) { Text("录入两篇答案 · 每篇 1—5") }
                    Text("也可分篇录入", style = MaterialTheme.typography.labelLarge)
                    selected.passages.forEach { p ->
                    val done = vm.coach.history.count { ExamCodec.itemId(it) == "${selected.stableId}/READING/${p.code}" && it.submittedAt > 0 }
                    PaperRow("篇章 ${p.code} · ${p.title.ifBlank { "Section C" }}", "1—5 题 · 已完成 $done 次",
                        { onStart(selected, kind, p) }, !vm.coach.working)
                    }
                }
                else -> {
                    SelectionContainer { Text(if (kind == CoachKind.WRITING) "${selected.writingPrompt}\n\n${selected.writingInstructions}" else selected.chineseText,
                        style = MaterialTheme.typography.bodyLarge, lineHeight = 30.sp) }
                    Button(onClick = { onStart(selected, kind, null) }, enabled = !vm.coach.working) { Text("开始${kind.label}") }
                }
            }
        } else {
            Text("资料尚未就绪", style = MaterialTheme.typography.titleLarge)
            Text("从你合法持有的资料导入并完成校对后，即可在这里练习。", style = MaterialTheme.typography.bodyLarge)
            selected.issues(kind).forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(onClick = { selectedId = null; management = true }) { Text("管理本地题库") }
        }
        PaperRow("材料来源", selected.sourceName, { runCatching { uriHandler.openUri(selected.sourceUrl) } })
        return
    }

    Text("一份试卷，\n一段专注的时间。", style = MaterialTheme.typography.headlineLarge, lineHeight = 39.sp)
    Text("CET–6  /  2023—2026", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    val latest = vm.coach.history.firstOrNull { it.material.isNotBlank() }
    if (latest != null) {
        PaperRow("继续上次练习", latest.title.ifBlank { CoachKind.valueOf(latest.kind).label }, { onContinue(latest) }, !vm.coach.working)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CoachKind.entries.forEach { k -> TextButton(onClick = { kind = k }) {
            Text(if (k == CoachKind.READING) "阅读核对" else k.label, fontWeight = if (kind == k) FontWeight.Bold else FontWeight.Normal,
                color = if (kind == k) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("历年试卷", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { management = !management }) { Text(if (management) "收起导入" else "导入资料") }
    }
    if (management) {
        Text("导入你有权使用的结构化 JSON 资料。PDF / Word 请先在电脑本地转换并校对；扫描件暂不自动识别。操作方法见项目导入说明。", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !vm.examBusy) { Text("选择 JSON") }
            TextButton(onClick = { export.launch("cet6-personal-exams.json") }, enabled = !vm.examBusy && vm.examPapers.isNotEmpty()) { Text("导出个人题库") }
        }
        if (vm.examStatus.isNotBlank()) Text(vm.examStatus, style = MaterialTheme.typography.bodySmall)
        vm.examPreview.forEach { p ->
            Text("${p.label} · ${p.status}", fontWeight = FontWeight.SemiBold)
            Text(p.issues().joinToString("；").ifBlank { "结构完整，使用权与套卷一致性由导入者确认" }, style = MaterialTheme.typography.bodySmall)
        }
        if (vm.examPreview.isNotEmpty()) Button(onClick = vm::confirmExamImport, enabled = !vm.examBusy) { Text("确认保存个人题库") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (2026 downTo 2023).forEach { y -> FilterChip(year == y, { year = y }, label = { Text("$y") }) }
    }
    ExamCatalog.sessions.filter { it.year == year }.sortedByDescending { it.date }.forEach { session ->
        Text("${session.month} 月 · ${session.date}" + if (session.note.isNotBlank()) " · 加考" else "", style = MaterialTheme.typography.titleMedium)
        if (session.note.isNotBlank()) Text(session.note, style = MaterialTheme.typography.bodySmall)
        (1..3).forEach { set ->
            val p = imported[ExamCatalog.paperId(session.date, "$set")] ?: ExamCatalog.papers().first { it.examDate == session.date && it.setCode == "$set" }
            val completed = vm.coach.history.filter { it.submittedAt > 0 }.mapNotNull { ExamCodec.itemId(it) }.filter { it.startsWith(p.stableId + "/") }.toSet()
            val count = (if (completed.any { it.endsWith("/READING/all") }) 2 else completed.count { it.contains("/READING/") }) + completed.count { !it.contains("/READING/") }
            PaperRow("第 $set 套", "${p.status} · 已完成 $count / 4 项", { selectedId = p.stableId })
        }
    }
    Text("套次为资料整理编号，原卷对应关系需导入时核对。2026 年 12 月尚未举行，未列入真题。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { runCatching { uriHandler.openUri(ExamCatalog.SET_SOURCE) } }) { Text("查看套次目录来源") }
    vm.examPapers.filter { it.sample }.forEach { p -> PaperRow(p.label, "仅验证功能，不是真题 · ${p.status}", { selectedId = p.stableId }) }
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

    Text(entry.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val labels = listOf("写作台", "对照", "复盘")
    SecondaryTabRow(panel, containerColor = MaterialTheme.colorScheme.background) {
        labels.forEachIndexed { i, label -> Tab(selected = panel == i, onClick = { panel = i }, text = { Text(label) }, enabled = i != 2 || submitted) }
    }

    if (panel == 2) {
        Text("回到证据，再想一步", style = MaterialTheme.typography.headlineMedium)
        FeedbackAndReview(vm, kind, entry)
        return
    }
        if (panel == 0) {
            Text(if (kind == CoachKind.WRITING) "Writing" else "Translation", fontFamily = FontFamily.Serif, style = MaterialTheme.typography.headlineLarge)
            SelectionContainer { Text(entry.material, style = MaterialTheme.typography.bodyLarge, lineHeight = 30.sp) }
            WritingTimer(entry.id, enabled)
            OutlinedTextField(entry.answer, { text -> state.edit(kind) { it.copy(answer = text) } },
                modifier = Modifier.fillMaxWidth(), minLines = 12, readOnly = !enabled,
                label = { Text(if (kind == CoachKind.WRITING) "在这里写下你的文章" else "写下你的英文翻译") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 29.sp),
                supportingText = { Text("${Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(entry.answer).count()} 词 · ${entry.answer.length} 字符") })
            if (!submitted) Button(onClick = { state.submit(kind, false) }, enabled = enabled && entry.answer.isNotBlank()) { Text("完成并保存") }
            else TextButton(onClick = { panel = 1 }) { Text("查看作答与修改对照") }
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
    TextButton(onClick = { state.submit(kind, true) }, enabled = !state.working && !vm.busy) { Text(if (entry.feedback.isBlank()) "请求 AI 逐句讲解" else "重新请求讲解") }
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
    TextButton(onClick = { state.open(kind, entry, retry = true) }, enabled = !state.working) { Text("再练一版 · 保留本次记录") }
    TextButton(onClick = { state.generate(kind) }, enabled = !state.working && entry.errorReason.isNotBlank()) { Text("按错因生成原创针对练习") }
    Text("AI 反馈仅供学习参考；请求会发送当前练习相关材料。", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ExamHistory(vm: StudyViewModel, onOpen: (CoachPractice) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(query) { mutableIntStateOf(20) }
    Text("练习档案", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(query, { query = it.take(200) }, label = { Text("搜索标题与错因") }, modifier = Modifier.fillMaxWidth())
    val records = vm.coach.history.filter { it.material.isNotBlank() && (query.isBlank() || it.title.contains(query, true) || it.errorReason.contains(query, true)) }
    if (records.isEmpty()) Text("还没有练习记录。保存一次草稿，就会出现在这里。")
    records.take(limit).forEach { e ->
        val date = Instant.ofEpochMilli(e.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        PaperRow(e.title.ifBlank { "自定义${CoachKind.valueOf(e.kind).label}" }, "$date · " + if (e.submittedAt > 0) "已作答" else "草稿", { onOpen(e) }, !vm.coach.working)
    }
    if (records.size > limit) TextButton(onClick = { limit += 20 }) { Text("更多记录") }
}
