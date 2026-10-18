package com.example.cet6.ui

import com.example.cet6.ui.components.StatusBadge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val englishWords = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun CoachScreen(vm: StudyViewModel, kind: CoachKind, scrollState: ScrollState? = null, reviewId: String? = null) {
    val state = vm.coach
    val entry = state.entries[kind]
    // Structured snapshots contain reference answers. Every legacy route must use the gated exam UI.
    if (entry != null && ExamCodec.metadata(entry) != null) {
        ExamTrainingScreen(vm, kind, scrollState ?: androidx.compose.foundation.rememberScrollState())
        return
    }
    if (entry == null) {
        Text("AI ${kind.label}教练", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(state.messages[kind] ?: "正在恢复本地草稿……", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val editable = !state.working && entry.submittedAt == 0L

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("AI ${kind.label}教练", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        StatusBadge(
            text = if (editable) "可编辑" else if (entry.submittedAt > 0) "已作答" else "只读",
            isSuccess = editable
        )
    }

    Text(
        state.messages[kind] ?: "输入后自动保存草稿，也可手动保存。AI 批改不代表官方答案；请先核对再提交。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    var panel by rememberSaveable(entry.id, entry.feedback.isNotBlank()) {
        mutableIntStateOf(if (entry.feedback.isNotBlank()) 1 else 0)
    }
    LaunchedEffect(entry.id, reviewId) { if (entry.id == reviewId && entry.submittedAt > 0) panel = 2 }
    LaunchedEffect(entry.id, panel) { scrollState?.scrollTo(0) }

    if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())

    val panels = listOf("资料作答", "AI 讲解", "错因难句", "历史")
    SecondaryTabRow(
        selectedTabIndex = panel,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        panels.forEachIndexed { index, label ->
            val enabled = index != 2 || entry.submittedAt > 0
            Tab(
                selected = panel == index,
                onClick = { panel = index },
                enabled = enabled,
                text = {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (panel == index) FontWeight.Bold else FontWeight.Medium
                    )
                }
            )
        }
    }

    OutlinedButton(
        onClick = { state.open(kind) },
        enabled = !state.working,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("新建${kind.label}练习（保留当前内容）", fontWeight = FontWeight.SemiBold)
    }

    if (panel != 3) CoachSourceCard(state, entry)

    if (panel == 0) {
        val materialWords = remember(entry.material) { englishWords.findAll(entry.material).count() }
        val answerWords = remember(entry.answer) { englishWords.findAll(entry.answer).count() }

        Text("练习材料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            entry.title,
            { value -> state.edit(kind) { it.copy(title = value) } },
            label = { Text("${kind.label}题目 / 标题") },
            readOnly = !editable,
            enabled = !state.working,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            entry.material,
            { value -> state.edit(kind) { it.copy(material = value) } },
            label = { Text(when (kind) {
                CoachKind.READING -> "粘贴英文阅读文章"
                CoachKind.WRITING -> "作文题目要求"
                CoachKind.TRANSLATION -> "待翻译的中文原文"
            }) },
            supportingText = {
                Text((if (kind == CoachKind.READING) "英文约 $materialWords 词 · " else "") + "${entry.material.length}/60000 字符")
            },
            readOnly = !editable,
            enabled = !state.working,
            minLines = 4,
            maxLines = 10,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )

        if (kind == CoachKind.READING) {
            Text("题目与选项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            var question by rememberSaveable(entry.id) { mutableStateOf("") }
            OutlinedTextField(
                question,
                { question = it },
                label = { Text("新题目（含选项；可附参考答案）") },
                enabled = editable,
                minLines = 3,
                maxLines = 6,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { if (state.addReadingQuestion(question)) question = "" },
                enabled = editable && question.isNotBlank() && entry.questions.length + question.length + 2 <= 20_000,
                shape = MaterialTheme.shapes.small
            ) {
                Text("添加题目")
            }
            OutlinedTextField(
                entry.questions,
                { value -> state.edit(kind) { it.copy(questions = value) } },
                label = { Text("已添加题目（可编辑、编号）") },
                readOnly = !editable,
                enabled = !state.working,
                minLines = 3,
                maxLines = 8,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )
            ReadingChoices(entry, editable) { value -> state.edit(kind) { it.copy(answer = value) } }
        }

        Text("独立作答", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            entry.answer,
            { value -> state.edit(kind) { it.copy(answer = value) } },
            label = { Text(if (kind == CoachKind.READING) "独立作答（按题号填写）" else "我的英文${kind.label}") },
            supportingText = {
                Text((if (kind != CoachKind.READING) "英文约 $answerWords 词 · " else "") + "${entry.answer.length}/30000 字符")
            },
            readOnly = !editable,
            enabled = !state.working,
            minLines = 4,
            maxLines = 10,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )

        val isSaved = state.history.any { it == entry }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (isSaved) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                if (isSaved) "本次内容已保存到本机" else "本次内容尚未确认保存，可点击「保存到本机」",
                style = MaterialTheme.typography.bodySmall,
                color = if (isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
            )
        }

        if (entry.submittedAt > 0) {
            Text(
                "本次原作答已保留。需要修改时，到「错因难句」选择「重新练习 / 修改一版」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (panel == 0 || panel == 1) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { state.save(kind) },
                        enabled = !state.working,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存到本机")
                    }
                    if (editable) {
                        OutlinedButton(
                            onClick = { state.submit(kind, false) },
                            enabled = entry.material.isNotBlank() && entry.answer.isNotBlank(),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("提交作答")
                        }
                    }
                }

                Button(
                    onClick = { panel = 1; state.submit(kind, true) },
                    enabled = !state.working && !vm.busy &&
                        entry.material.isNotBlank() && entry.answer.isNotBlank() && (kind != CoachKind.READING || entry.questions.isNotBlank()),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (entry.feedback.isBlank()) "保存作答并请求 AI ${if (kind == CoachKind.READING) "解析" else "批改"}" else "重新请求 AI",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (!vm.hasPlan || vm.chosenModel.isBlank()) {
                    Text(
                        "AI 需要网络：到「连接」页完成登录并加载模型；本地保存不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (panel == 1 && entry.feedback.isBlank()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    "尚无完整 AI 反馈。可在资料作答页填写内容，或重试已保存的作答。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (panel == 1 && state.partial.isNotBlank() && state.partialEntryId == entry.id) {
        val receiving = state.activeRequest == entry.id
        var showPartial by rememberSaveable(entry.id, receiving) { mutableStateOf(receiving) }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (receiving) "正在接收临时响应" else "未完成的临时响应", fontWeight = FontWeight.Bold)
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            if (receiving) "流式传输中" else "部分内容",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Text("尚未保存为完整反馈；重新请求会重新生成，已有作答和完整反馈仍保留。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showPartial = !showPartial }) {
                    Text(if (showPartial) "收起临时文本" else "查看临时文本")
                }
                if (showPartial) SelectionContainer { Text(state.partial, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    if (panel == 1 && entry.feedback.isNotBlank()) {
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
                    Text("已保存 AI 反馈 · ${entry.model}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            "完整已保存",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                var followUp by rememberSaveable(entry.id) { mutableStateOf("") }
                OutlinedTextField(
                    followUp,
                    { followUp = it.take(4000) },
                    label = { Text("继续追问（可要求定位证据或拆解难句）") },
                    enabled = !state.working,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { state.submit(kind, true, followUp) },
                    enabled = !state.working && !vm.busy && followUp.isNotBlank(),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("发送追问并保存")
                }
                MarkdownFeedback(entry.feedback)
            }
        }
    }

    if (panel == 2 && entry.submittedAt > 0) {
        if (kind == CoachKind.READING) ReadingEvidenceCard(state, entry)

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("错因归档与复习", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "回看原文与上方作答，核对 AI 建议；保存自己的错因以便复习。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("词汇理解", "长难句", "逻辑推断", "选项陷阱", "语法", "表达").forEach { category ->
                        val selected = entry.errorReason.contains(category)
                        SuggestionChip(
                            onClick = { state.edit(kind) {
                                it.copy(errorReason = if (it.errorReason.isBlank()) category else "${it.errorReason}；$category")
                            } },
                            label = { Text(category) },
                            enabled = !state.working,
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }
                OutlinedTextField(
                    entry.errorReason,
                    { value -> state.edit(kind) { it.copy(errorReason = value) } },
                    label = { Text("我的错因 / 修改笔记（可纠正 AI 归因）") },
                    enabled = !state.working,
                    minLines = 2,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "按自己确认的错因生成新练习；只发送当前练习的相关内容，生成失败仍保留原题。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { state.generate(kind) },
                    enabled = !state.working && !vm.busy && entry.errorReason.isNotBlank(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("AI 生成针对练习")
                }
                OutlinedButton(
                    onClick = { state.open(kind, entry, retry = true) },
                    enabled = !state.working,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新练习 / 修改一版（保留本次记录）")
                }
            }
        }
    }

    if (panel == 3) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本地${kind.label}历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                var onlyMistakes by rememberSaveable(kind) { mutableStateOf(false) }
                var search by rememberSaveable(kind) { mutableStateOf("") }
                OutlinedTextField(
                    search,
                    { search = it.take(200) },
                    label = { Text("搜索标题、错因或训练目标") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onlyMistakes, { onlyMistakes = it })
                    Spacer(Modifier.width(4.dp))
                    Text("只看已记录错因的练习", style = MaterialTheme.typography.bodyMedium)
                }
                val records = state.history.filter { it.kind == kind.name && (it.material.isNotBlank() || it.answer.isNotBlank()) &&
                    (!onlyMistakes || it.errorReason.isNotBlank()) &&
                    (search.isBlank() || listOf(it.title, it.errorReason, it.practiceGoal).any { text -> text.contains(search.trim(), ignoreCase = true) }) }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "找到 ${records.size} 条记录",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (records.isEmpty()) {
                    Text(
                        if (search.isNotBlank() || onlyMistakes) "没有匹配记录，请调整关键词或筛选。" else "暂无记录。离线也能保存和回看练习。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var visibleCount by rememberSaveable(kind, onlyMistakes, search) { mutableIntStateOf(10) }
                records.take(visibleCount).forEach { saved ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    saved.title.ifBlank { "未命名${kind.label}练习" },
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                val statusText = when {
                                    saved.submittedAt == 0L -> "草稿"
                                    saved.feedback.isBlank() -> "已作答 / 待 AI"
                                    else -> "已保存解析"
                                }
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = when {
                                        saved.submittedAt == 0L -> MaterialTheme.colorScheme.surfaceVariant
                                        saved.feedback.isBlank() -> MaterialTheme.colorScheme.tertiaryContainer
                                        else -> MaterialTheme.colorScheme.secondaryContainer
                                    }
                                ) {
                                    Text(
                                        statusText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            if (saved.practiceGoal.isNotBlank()) {
                                Text(
                                    "针对练习：${saved.practiceGoal}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                Instant.ofEpochMilli(saved.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (saved.errorReason.isNotBlank()) {
                                Text(
                                    "错因：${saved.errorReason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { state.open(kind, saved) },
                                    enabled = !state.working,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("查看 / 继续")
                                }
                                OutlinedButton(
                                    onClick = { state.open(kind, saved, retry = true) },
                                    enabled = !state.working,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("再练")
                                }
                            }
                        }
                    }
                }
                if (records.size > visibleCount) {
                    TextButton(onClick = { visibleCount += 10 }, modifier = Modifier.fillMaxWidth()) {
                        Text("更多记录")
                    }
                }
                if (kind == CoachKind.READING && vm.readingAttempts.isNotEmpty()) {
                    var legacy by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { legacy = !legacy }) {
                        Text(if (legacy) "收起旧版阅读记录" else "查看旧版阅读记录")
                    }
                    if (legacy) vm.readingAttempts.forEach { saved ->
                        Text("${saved.questionId} · 作答 ${saved.answer} · ${if (saved.correct) "旧版本地判题正确" else "旧版本地判题有误"}")
                    }
                }
            }
        }
    }
}

@Composable
fun WritingAndTranslationScreen(
    vm: StudyViewModel,
    scrollState: ScrollState? = null,
    reviewId: String? = null,
    reviewKind: CoachKind = CoachKind.WRITING
) {
    var translation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reviewId) { if (reviewId != null) translation = reviewKind == CoachKind.TRANSLATION }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = !translation,
                onClick = { translation = false },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("作文") },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = translation,
                onClick = { translation = true },
                leadingIcon = { Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("中译英") },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f)
            )
        }
    }
    CoachScreen(vm, if (translation) CoachKind.TRANSLATION else CoachKind.WRITING, scrollState, reviewId)
    if (!translation && vm.essays.isNotEmpty()) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("旧版作文批改记录（已保留）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                vm.essays.forEach { saved ->
                    var expanded by remember(saved.id) { mutableStateOf(false) }
                    OutlinedButton(onClick = { expanded = !expanded }, shape = MaterialTheme.shapes.small) {
                        Text(saved.topic.ifBlank { "历史作文" })
                    }
                    if (expanded) SelectionContainer {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                        ) {
                            Text(
                                "原文：\n${saved.content}\n\n批改：\n${saved.feedback}",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
