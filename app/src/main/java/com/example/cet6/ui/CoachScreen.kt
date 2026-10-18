package com.example.cet6.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val englishWords = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CoachScreen(vm: StudyViewModel, kind: CoachKind, scrollState: ScrollState? = null, reviewId: String? = null) {
    val state = vm.coach
    val entry = state.entries[kind]
    Text("AI ${kind.label}教练", style = MaterialTheme.typography.headlineSmall)
    Text("材料与作答保存在本机。只有点击 AI 按钮才发送本次内容；AI 建议不是官方答案或成绩。")
    if (entry == null) {
        Text(state.messages[kind] ?: "正在恢复本地草稿……")
        return
    }
    val editable = !state.working && entry.submittedAt == 0L
    var panel by rememberSaveable(entry.id, entry.feedback.isNotBlank()) {
        mutableIntStateOf(if (entry.feedback.isNotBlank()) 1 else 0)
    }
    LaunchedEffect(entry.id, reviewId) { if (entry.id == reviewId && entry.submittedAt > 0) panel = 2 }
    LaunchedEffect(entry.id, panel) { scrollState?.scrollTo(0) }
    Text(state.messages[kind] ?: "输入后自动保存草稿，也可手动保存。", style = MaterialTheme.typography.bodySmall)
    if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("资料作答", "AI 讲解", "错因难句", "历史").forEachIndexed { index, label ->
            FilterChip(selected = panel == index, onClick = { panel = index },
                enabled = index != 2 || entry.submittedAt > 0, label = { Text(label) })
        }
    }
    TextButton(onClick = { state.open(kind) }, enabled = !state.working) { Text("新建${kind.label}练习（保留当前内容）") }
    if (panel != 3) CoachSourceCard(state, entry)
    if (panel == 0) {
        val materialWords = remember(entry.material) { englishWords.findAll(entry.material).count() }
        val answerWords = remember(entry.answer) { englishWords.findAll(entry.answer).count() }
        OutlinedTextField(entry.title, { value -> state.edit(kind) { it.copy(title = value) } },
            label = { Text("${kind.label}题目 / 标题") }, readOnly = !editable, enabled = !state.working, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(entry.material, { value -> state.edit(kind) { it.copy(material = value) } },
            label = { Text(when (kind) {
                CoachKind.READING -> "粘贴英文阅读文章"
                CoachKind.WRITING -> "作文题目要求"
                CoachKind.TRANSLATION -> "待翻译的中文原文"
            }) }, supportingText = { Text((if (kind == CoachKind.READING) "英文约 $materialWords 词 · " else "") + "${entry.material.length}/60000 字符") },
            readOnly = !editable, enabled = !state.working, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth())
        if (kind == CoachKind.READING) {
            var question by rememberSaveable(entry.id) { mutableStateOf("") }
            OutlinedTextField(question, { question = it }, label = { Text("新题目（含选项；可附参考答案）") },
                enabled = editable, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                if (state.addReadingQuestion(question)) question = ""
            }, enabled = editable && question.isNotBlank() && entry.questions.length + question.length + 2 <= 20_000) { Text("添加题目") }
            OutlinedTextField(entry.questions, { value -> state.edit(kind) { it.copy(questions = value) } },
                label = { Text("已添加题目（可编辑、编号）") }, readOnly = !editable, enabled = !state.working, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        }
        if (kind == CoachKind.READING) ReadingChoices(entry, editable) { value -> state.edit(kind) { it.copy(answer = value) } }
        OutlinedTextField(entry.answer, { value -> state.edit(kind) { it.copy(answer = value) } },
            label = { Text(if (kind == CoachKind.READING) "独立作答（按题号填写）" else "我的英文${kind.label}") },
            supportingText = { Text((if (kind != CoachKind.READING) "英文约 $answerWords 词 · " else "") + "${entry.answer.length}/30000 字符") },
            readOnly = !editable, enabled = !state.working, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth())
        Text(if (state.history.any { it == entry }) "本次内容已保存到本机" else "本次内容尚未确认保存，可点击「保存到本机」", style = MaterialTheme.typography.bodySmall)
        if (entry.submittedAt > 0) Text("本次原作答已保留。需要修改时，到「错因难句」选择「重新练习 / 修改一版」。", style = MaterialTheme.typography.bodySmall)
    }
    if (panel == 0 || panel == 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { state.save(kind) }, enabled = !state.working) { Text("保存到本机") }
            if (editable) OutlinedButton(onClick = { state.submit(kind, false) },
                enabled = entry.material.isNotBlank() && entry.answer.isNotBlank()) { Text("提交作答") }
        }
        Button(onClick = { panel = 1; state.submit(kind, true) }, enabled = !state.working && !vm.busy &&
            entry.material.isNotBlank() && entry.answer.isNotBlank() && (kind != CoachKind.READING || entry.questions.isNotBlank()),
            modifier = Modifier.fillMaxWidth()) {
            Text(if (entry.feedback.isBlank()) "保存作答并请求 AI ${if (kind == CoachKind.READING) "解析" else "批改"}" else "重新请求 AI")
        }
        if (!vm.hasPlan || vm.chosenModel.isBlank()) Text("AI 需要网络：到「连接」页完成登录并加载模型；本地保存不受影响。")
    }
    if (panel == 1 && entry.feedback.isBlank()) Text("尚无完整 AI 反馈。可在资料作答页填写内容，或重试已保存的作答。")
    if (panel == 1 && state.partial.isNotBlank() && state.partialEntryId == entry.id) {
        val receiving = state.activeRequest == entry.id
        var showPartial by rememberSaveable(entry.id, receiving) { mutableStateOf(receiving) }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (receiving) "正在接收临时响应" else "未完成的临时响应", fontWeight = FontWeight.Bold)
                Text("尚未保存为完整反馈；重新请求会重新生成，已有作答和完整反馈仍保留。")
                TextButton(onClick = { showPartial = !showPartial }) { Text(if (showPartial) "收起临时文本" else "查看临时文本") }
                if (showPartial) SelectionContainer { Text(state.partial) }
            }
        }
    }
    if (panel == 1 && entry.feedback.isNotBlank()) {
        Text("已保存 AI 反馈 · ${entry.model}", fontWeight = FontWeight.Bold)
        var followUp by rememberSaveable(entry.id) { mutableStateOf("") }
        OutlinedTextField(followUp, { followUp = it.take(4000) }, label = { Text("继续追问（可要求定位证据或拆解难句）") },
            enabled = !state.working, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { state.submit(kind, true, followUp) }, enabled = !state.working && !vm.busy && followUp.isNotBlank()) { Text("发送追问并保存") }
        MarkdownFeedback(entry.feedback)
    }
    if (panel == 2 && entry.submittedAt > 0) {
        if (kind == CoachKind.READING) ReadingEvidenceCard(state, entry)
        Text("回看原文与上方作答，核对 AI 建议；保存自己的错因以便复习。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("词汇理解", "长难句", "逻辑推断", "选项陷阱", "语法", "表达").forEach { category ->
                SuggestionChip(onClick = { state.edit(kind) {
                    it.copy(errorReason = if (it.errorReason.isBlank()) category else "${it.errorReason}；$category")
                } }, label = { Text(category) }, enabled = !state.working)
            }
        }
        OutlinedTextField(entry.errorReason, { value -> state.edit(kind) { it.copy(errorReason = value) } },
            label = { Text("我的错因 / 修改笔记（可纠正 AI 归因）") }, enabled = !state.working,
            minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("按自己确认的错因生成新练习；只发送当前练习的相关内容，生成失败仍保留原题。")
        Button(onClick = { state.generate(kind) }, enabled = !state.working && !vm.busy && entry.errorReason.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("AI 生成针对练习") }
        OutlinedButton(onClick = { state.open(kind, entry, retry = true) }, enabled = !state.working) { Text("重新练习 / 修改一版（保留本次记录）") }
    }
    if (panel == 3) {
        HorizontalDivider()
        Text("本地${kind.label}历史", style = MaterialTheme.typography.titleMedium)
        var onlyMistakes by rememberSaveable(kind) { mutableStateOf(false) }
        var search by rememberSaveable(kind) { mutableStateOf("") }
        OutlinedTextField(search, { search = it.take(200) }, label = { Text("搜索标题、错因或训练目标") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(onlyMistakes, { onlyMistakes = it })
            Text("只看已记录错因的练习", Modifier.padding(top = 12.dp))
        }
        val records = state.history.filter { it.kind == kind.name && (it.material.isNotBlank() || it.answer.isNotBlank()) &&
            (!onlyMistakes || it.errorReason.isNotBlank()) &&
            (search.isBlank() || listOf(it.title, it.errorReason, it.practiceGoal).any { text -> text.contains(search.trim(), ignoreCase = true) }) }
        Text("找到 ${records.size} 条记录")
        if (records.isEmpty()) Text(if (search.isNotBlank() || onlyMistakes) "没有匹配记录，请调整关键词或筛选。" else "暂无记录。离线也能保存和回看练习。")
        var visibleCount by rememberSaveable(kind, onlyMistakes, search) { mutableIntStateOf(10) }
        records.take(visibleCount).forEach { saved ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(saved.title.ifBlank { "未命名${kind.label}练习" }, fontWeight = FontWeight.Medium)
                    if (saved.practiceGoal.isNotBlank()) Text("针对练习：${saved.practiceGoal}")
                    Text(Instant.ofEpochMilli(saved.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) +
                        " · " + when { saved.submittedAt == 0L -> "草稿"; saved.feedback.isBlank() -> "已作答 / 待 AI"; else -> "已保存解析" })
                    if (saved.errorReason.isNotBlank()) Text("错因：${saved.errorReason}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { state.open(kind, saved) }, enabled = !state.working) { Text("查看 / 继续") }
                        OutlinedButton(onClick = { state.open(kind, saved, retry = true) }, enabled = !state.working) { Text("再练") }
                    }
                }
            }
        }
        if (records.size > visibleCount) TextButton(onClick = { visibleCount += 10 }) { Text("更多记录") }
        if (kind == CoachKind.READING && vm.readingAttempts.isNotEmpty()) {
            var legacy by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { legacy = !legacy }) { Text(if (legacy) "收起旧版阅读记录" else "查看旧版阅读记录") }
            if (legacy) vm.readingAttempts.forEach { saved ->
                Text("${saved.questionId} · 作答 ${saved.answer} · ${if (saved.correct) "旧版本地判题正确" else "旧版本地判题有误"}")
            }
        }
    }
}

@Composable
fun WritingAndTranslationScreen(vm: StudyViewModel, scrollState: ScrollState? = null, reviewId: String? = null,
    reviewKind: CoachKind = CoachKind.WRITING) {
    var translation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reviewId) { if (reviewId != null) translation = reviewKind == CoachKind.TRANSLATION }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!translation, { translation = false }, label = { Text("作文") })
        FilterChip(translation, { translation = true }, label = { Text("中译英") })
    }
    CoachScreen(vm, if (translation) CoachKind.TRANSLATION else CoachKind.WRITING, scrollState, reviewId)
    if (!translation && vm.essays.isNotEmpty()) {
        Text("旧版作文批改记录（已保留）", style = MaterialTheme.typography.titleMedium)
        vm.essays.forEach { saved ->
            var expanded by remember(saved.id) { mutableStateOf(false) }
            OutlinedButton(onClick = { expanded = !expanded }) { Text(saved.topic.ifBlank { "历史作文" }) }
            if (expanded) SelectionContainer { Text("原文：\n${saved.content}\n\n批改：\n${saved.feedback}") }
        }
    }
}
