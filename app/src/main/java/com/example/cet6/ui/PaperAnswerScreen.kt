package com.example.cet6.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*
import com.example.cet6.ui.components.InteractiveStepPill
import com.example.cet6.ui.theme.AccentSuccess
import com.example.cet6.ui.theme.AccentSuccessContainer
import com.example.cet6.ui.theme.AccentWarning
import com.example.cet6.ui.theme.AccentWarningContainer

/** Paper-first: record completed answers, mark against sourced keys, then disclose one hint at a time. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnswerCardSlot(
    number: String,
    value: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusIcon: String? = null,
    statusColor: Color? = null,
    containerColor: Color? = null
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(56.dp).height(68.dp),
        shape = RoundedCornerShape(10.dp),
        color = containerColor ?: if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
               else if (value.isNotBlank()) MaterialTheme.colorScheme.surfaceVariant
               else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else if (value.isNotBlank()) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (value.isNotBlank()) {
                    statusColor ?: MaterialTheme.colorScheme.onSurface
                } else MaterialTheme.colorScheme.outlineVariant
            )
            if (statusIcon != null) {
                Text(
                    text = statusIcon,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PaperAnswerScreen(vm: StudyViewModel, entry: CoachPractice, scroll: ScrollState) {
    val state = vm.coach
    val passages = remember(entry.questions) { ExamCodec.passages(entry) }
    val questions = remember(passages) { passages.flatMap { it.questions }.sortedBy { it.number } }
    if (questions.isEmpty()) { Text("题目快照无法读取，请保留记录并重新核对内容包。"); return }
    val submitted = entry.submittedAt > 0
    var panel by rememberSaveable(entry.id) { mutableIntStateOf(if (submitted) 1 else 0) }
    var selectedNumber by rememberSaveable(entry.id) { mutableIntStateOf(questions.first().number) }
    var levels by rememberSaveable(entry.id) { mutableStateOf("") }
    var invalidPassages by rememberSaveable(entry.id) { mutableStateOf("") }
    var inputVersion by rememberSaveable(entry.id) { mutableIntStateOf(0) }
    val labels = listOf("录入答案", "核对", "查看原文")
    val answers = questions.associate { it.number to ReadingQuestions.selected(entry.answer, "${it.number}").orEmpty() }
    val answered = answers.count { it.value.isNotBlank() }
    LaunchedEffect(panel, selectedNumber, entry.id) { scroll.scrollTo(0) }
    LaunchedEffect(submitted) { if (submitted) {
        panel = 1
        selectedNumber = questions.firstOrNull { ReadingGuidance.verdict(it, answers[it.number].orEmpty()) == "需要回看" }?.number ?: questions.first().number
    } }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Exam Title and Header
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("仔细阅读 · Section C", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SecondaryTabRow(panel, containerColor = MaterialTheme.colorScheme.background) {
            labels.forEachIndexed { i, label ->
                Tab(selected = panel == i, onClick = { panel = i }, enabled = i != 1 || submitted, text = {
                    Text(label, fontWeight = if (panel == i) FontWeight.Bold else FontWeight.Normal)
                })
            }
        }

        when (panel) {
            0 -> {
                // PANEL 0: 答案录入页 (答题卡)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("答题卡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "已录入 $answered / ${questions.size} 题",
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    passages.forEach { p -> key(p.code) {
                        var typed by rememberSaveable(entry.id, p.code, inputVersion) { mutableStateOf<String?>(null) }
                        var error by rememberSaveable(entry.id, p.code, inputVersion) { mutableStateOf("") }
                        var selectedSlotIndex by rememberSaveable(entry.id, p.code) { mutableIntStateOf(-1) }
                        val passageQuestions = p.questions.sortedBy { it.number }
                        val passageAnswered = passageQuestions.count { answers[it.number].orEmpty().isNotBlank() }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Header of passage
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "篇章 ${p.code}" + if (p.title.isNotBlank()) " · ${p.title}" else "",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "第 ${passageQuestions.first().number}—${passageQuestions.last().number} 题",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (passageAnswered == 5) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            "$passageAnswered / 5",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (passageAnswered == 5) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // 5 Answer Slots (01—05)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    passageQuestions.forEachIndexed { index, q ->
                                        val opt = answers[q.number].orEmpty()
                                        val isSelected = selectedSlotIndex == index
                                        AnswerCardSlot(
                                            number = (index + 1).toString().padStart(2, '0'),
                                            value = opt,
                                            isSelected = isSelected,
                                            onClick = {
                                                selectedSlotIndex = if (selectedSlotIndex == index) -1 else index
                                            }
                                        )
                                    }
                                }

                                // Slot Quick Selector (if a slot is selected)
                                if (selectedSlotIndex in 0..4) {
                                    val targetQ = passageQuestions[selectedSlotIndex]
                                    val currentOpt = answers[targetQ.number].orEmpty()
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "第 0${selectedSlotIndex + 1} 题修改：",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                listOf("A", "B", "C", "D").forEach { c ->
                                                    FilterChip(
                                                        selected = currentOpt == c,
                                                        onClick = {
                                                            inputVersion++
                                                            invalidPassages = ""
                                                            state.edit(CoachKind.READING) {
                                                                it.copy(answer = ReadingQuestions.choose(it.answer, "${targetQ.number}", c))
                                                            }
                                                            typed = null
                                                        },
                                                        enabled = !submitted && !state.working,
                                                        label = { Text(c, fontWeight = FontWeight.Bold) }
                                                    )
                                                }
                                                if (currentOpt.isNotBlank()) {
                                                    TextButton(
                                                        onClick = {
                                                            inputVersion++
                                                            invalidPassages = ""
                                                            state.edit(CoachKind.READING) {
                                                                it.copy(answer = ReadingQuestions.choose(it.answer, "${targetQ.number}", ""))
                                                            }
                                                            typed = null
                                                        },
                                                        enabled = !submitted && !state.working
                                                    ) {
                                                        Text("清除", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Quick Sequential Input Capsule
                                val seqValue = typed ?: SequentialAnswers.sequence(entry.answer, p.questions)
                                OutlinedTextField(
                                    value = seqValue,
                                    onValueChange = { raw ->
                                        typed = raw
                                        val result = runCatching { SequentialAnswers.apply(entry.answer, raw, p.questions) }
                                        error = result.exceptionOrNull()?.message.orEmpty()
                                        invalidPassages = if (result.isFailure) (invalidPassages + p.code).toSet().joinToString("") else invalidPassages.replace(p.code, "")
                                        result.getOrNull()?.let { next ->
                                            if (state.edit(CoachKind.READING) { it.copy(answer = next) }) typed = SequentialAnswers.normalize(raw)
                                        }
                                    },
                                    label = { Text("5 字母快速连续输入") },
                                    placeholder = { Text("如 CADCB，未答填 ?") },
                                    supportingText = if (error.isNotBlank()) ({ Text(error, color = MaterialTheme.colorScheme.error) })
                                        else ({ Text("支持 CADCB 连续录入，上方答题卡实时显示") }),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 3.sp),
                                    singleLine = true,
                                    readOnly = submitted,
                                    enabled = !state.working,
                                    isError = error.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } }

                    // Bottom Action Section
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已录入 $answered / ${questions.size} 题",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("已保存到本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!submitted) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { state.submit(CoachKind.READING, false) },
                                        enabled = !state.working && answered > 0 && invalidPassages.isBlank(),
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Text("核对答案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { state.save(CoachKind.READING) },
                                        enabled = !state.working,
                                        modifier = Modifier.height(48.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Text("保存录入草稿")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { state.open(CoachKind.READING, entry, retry = true) },
                                    enabled = !state.working,
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("再练一次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            val missingKey = questions.count { it.referenceAnswer.isBlank() || it.answerSource.isBlank() }
                            if (missingKey > 0) {
                                Text("$missingKey 题缺少答案或来源，待核对", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            1 -> {
                // PANEL 1: 核对结果页
                val known = questions.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() && answers[it.number].orEmpty().isNotBlank() }
                val matched = known.count { answers[it.number] == it.referenceAnswer }
                val needReview = known.size - matched
                val unanswered = questions.size - answered
                val missingKey = questions.count { answers[it.number].orEmpty().isNotBlank() && (it.referenceAnswer.isBlank() || it.answerSource.isBlank()) }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Top Metric Card: 优先显示有来源且已作答题目的实际核对结果
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("实际核对结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("以有来源答案为准", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "$matched",
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = AccentSuccess,
                                        fontFeatureSettings = "tnum"
                                    )
                                )
                                Text(
                                    "/ ${known.size} 题与参考一致",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = AccentSuccessContainer.copy(alpha = 0.6f)
                                ) {
                                    Text(
                                        "✓ $matched 题一致",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = AccentSuccess
                                    )
                                }
                                if (needReview > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = AccentWarningContainer
                                    ) {
                                        Text(
                                            "! $needReview 题需回看",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = AccentWarning
                                        )
                                    }
                                }
                                if (unanswered > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surface
                                    ) {
                                        Text(
                                            "? $unanswered 题未录入",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (missingKey > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surface
                                    ) {
                                        Text(
                                            "? $missingKey 题缺少来源",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Answer Sheet in Check Mode
                    passages.forEach { p ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val row = p.questions.sortedBy { it.number }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("篇章 ${p.code}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("点击题号查看独立解析", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    row.forEachIndexed { index, q ->
                                        val status = ReadingGuidance.verdict(q, answers[q.number].orEmpty())
                                        val isConsistent = status == "与参考一致"
                                        val isNeedReview = status == "需要回看"
                                        val icon = when {
                                            isConsistent -> "✓"
                                            isNeedReview -> "!"
                                            else -> "?"
                                        }
                                        val iconColor = when {
                                            isConsistent -> AccentSuccess
                                            isNeedReview -> AccentWarning
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        val containerColor = when {
                                            isConsistent -> AccentSuccessContainer.copy(alpha = 0.35f)
                                            isNeedReview -> AccentWarningContainer.copy(alpha = 0.45f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        }
                                        AnswerCardSlot(
                                            number = (index + 1).toString().padStart(2, '0'),
                                            value = answers[q.number].orEmpty(),
                                            isSelected = selectedNumber == q.number,
                                            onClick = { selectedNumber = q.number },
                                            statusIcon = icon,
                                            statusColor = iconColor,
                                            containerColor = containerColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text("图例：✓ 与参考一致　! 需要回看　? 待核对", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // Focused Question Review Card (独立题目解析视图)
                    val q = questions.firstOrNull { it.number == selectedNumber } ?: questions.first()
                    val passage = passages.first { p -> p.questions.any { it.number == q.number } }
                    val level = levels.split(';').mapNotNull { token -> token.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1].toIntOrNull() } }.toMap()["${q.number}"] ?: 0
                    val ordinal = passage.questions.sortedBy { it.number }.indexOfFirst { it.number == q.number } + 1
                    val verdict = ReadingGuidance.verdict(q, answers[q.number].orEmpty())
                    val status = when (verdict) {
                        "未录入" -> "待核对 · 未录入"
                        "暂无可核实答案" -> "待核对 · 缺少答案或来源"
                        else -> verdict
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.5.dp, if (verdict == "与参考一致") AccentSuccess.copy(alpha = 0.5f) else AccentWarning.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Header of Question
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("篇章 ${passage.code} · 第 $ordinal 题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = when (verdict) {
                                        "与参考一致" -> AccentSuccessContainer
                                        "需要回看" -> AccentWarningContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = status,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = when (verdict) {
                                            "与参考一致" -> AccentSuccess
                                            "需要回看" -> AccentWarning
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            // Question Stem
                            Text(
                                text = q.stem,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                lineHeight = 26.sp
                            )

                            // My Answer (未进入最终揭示步骤，不应直接显示正确选项)
                            Text(
                                text = "我的答案：${answers[q.number].orEmpty().ifBlank { "未答" }}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            fun setQuestionLevel(targetLevel: Int) {
                                levels = (levels.split(';').filter { it.isNotBlank() && !it.startsWith("${q.number}:") } +
                                    "${q.number}:${targetLevel.coerceIn(0, 4)}").joinToString(";")
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                maxItemsInEachRow = 4
                            ) {
                                listOf("01" to "线索", "02" to "定位", "03" to "证据", "04" to "选项").forEachIndexed { i, (number, label) ->
                                    InteractiveStepPill(number, label, level == i + 1, level > i + 1,
                                        onClick = { setQuestionLevel(i + 1) }, modifier = Modifier.widthIn(min = 64.dp))
                                }
                            }

                            // Step content
                            if (level == 0) {
                                Text("遇到疑问？点击“查看提示”按四个步骤自主分析，最终层才对照参考答案与来源。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (level == 1) {
                                Text("01 / 圈出题干线索", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("圈出题干的主体、时间和限定词，明确要找的信息。", lineHeight = 24.sp, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (level == 2) {
                                Text("02 / 回到原文定位", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                val paragraph = ReadingGuidance.paragraph(passage, q)
                                Text(
                                    if (paragraph != null) "篇章 ${passage.code} · 第 $paragraph 自然段，寻找题干的同义表达。"
                                    else "这份资料未提供可核对的证据位置。请从题干关键词出发逐段寻找同义表达；不能把 AI 猜测当作确定位置。",
                                    lineHeight = 24.sp, style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (level == 3) {
                                Text("03 / 读懂证据关系", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                if (q.evidence.isNotBlank() && passage.content.contains(q.evidence)) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        SelectionContainer {
                                            Text("“${q.evidence}”", fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.padding(12.dp))
                                        }
                                    }
                                    Text("结合上下文，核对主语、转折、因果和范围。", lineHeight = 24.sp, style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    Text("尚无经过原文匹配的证据句。可以请求本层 AI 提示，再自己在原卷核实；不要补造引用。", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (level == 4) {
                                Text("04 / 选项与参考对照", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                q.options.forEach {
                                    Text("${it.label}. ${it.text}", lineHeight = 24.sp, style = MaterialTheme.typography.bodyMedium)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = AccentSuccessContainer.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, AccentSuccess.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("参考答案：${q.referenceAnswer.ifBlank { "暂无可核实答案" }}", fontWeight = FontWeight.Bold, color = AccentSuccess)
                                        if (q.answerSource.isNotBlank()) Text("来源：${q.answerSource}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("正确选项如何改写了证据？你原先的选项是偷换主体、扩大范围，还是把推测写成事实？", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            // Step Buttons
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (level > 0) {
                                    OutlinedButton(onClick = { setQuestionLevel(level - 1) }, modifier = Modifier.height(42.dp)) { Text("上一步") }
                                }
                                if (level < 4) {
                                    Button(onClick = { setQuestionLevel(level + 1) }, modifier = Modifier.height(42.dp)) { Text(if (level == 0) "查看提示" else "下一步") }
                                }
                                if (level > 0) {
                                    TextButton(onClick = { state.readingHint(q.number, level) }, enabled = !state.working && !vm.busy, modifier = Modifier.height(42.dp)) {
                                        Text("AI 提示")
                                    }
                                }
                            }

                            // AI follow-up
                            if (level > 0) {
                                var asking by rememberSaveable(entry.id, q.number, level) { mutableStateOf(false) }
                                var followUp by rememberSaveable(entry.id, q.number, level) { mutableStateOf("") }
                                TextButton(onClick = { asking = !asking }) { Text(if (asking) "收起追问" else "向 AI 追问") }
                                if (asking) {
                                    OutlinedTextField(
                                        followUp,
                                        { followUp = it.take(2000) },
                                        label = { Text("哪一步还不明白？") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = { state.readingHint(q.number, level, followUp) },
                                        enabled = !state.working && !vm.busy && followUp.isNotBlank(),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) { Text("发送追问") }
                                }
                            }

                            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())

                            if (entry.feedback.isNotBlank()) {
                                val heading = "## 第 ${q.number} 题 · 第 $level 层提示"
                                val hint = entry.feedback.substringAfterLast(heading, "").substringBefore("\n\n## 第 ")
                                if (level > 0 && hint.isNotBlank()) MarkdownFeedback(hint)
                                var showSaved by rememberSaveable(entry.id) { mutableStateOf(false) }
                                TextButton(onClick = { showSaved = !showSaved }) { Text("${if (showSaved) "收起" else "查看"}此前保存的讲解") }
                                if (showSaved) MarkdownFeedback(entry.feedback)
                            }

                            if (state.partialEntryId == entry.id && state.partial.isNotBlank()) {
                                Text("本层响应尚未完成，未计为已保存提示。", style = MaterialTheme.typography.bodySmall)
                                var partial by rememberSaveable(entry.id) { mutableStateOf(false) }
                                TextButton(onClick = { partial = !partial }) { Text("查看未完成的临时文本") }
                                if (partial) Text(state.partial)
                            }

                            // Level 4 Reflection & Error reason
                            if (level == 4) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Text("错因归因（选填）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("词汇理解", "长难句", "逻辑推断", "选项陷阱", "录入更正").forEach { reason ->
                                        SuggestionChip(onClick = { state.edit(CoachKind.READING) {
                                            val note = "$reason：篇章 ${passage.code} 第 $ordinal 题"
                                            it.copy(errorReason = if (it.errorReason.split('；').contains(note)) it.errorReason else listOf(it.errorReason, note).filter(String::isNotBlank).joinToString("；"))
                                        } }, label = { Text(reason) }, enabled = !state.working)
                                    }
                                }
                                OutlinedTextField(
                                    entry.errorReason,
                                    { value -> state.edit(CoachKind.READING) { it.copy(errorReason = value) } },
                                    label = { Text("我的错因与定位笔记") },
                                    enabled = !state.working,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { state.open(CoachKind.READING, entry, retry = true) }, enabled = !state.working) { Text("再练一次") }
                                    if (entry.errorReason.isNotBlank()) {
                                        OutlinedButton(onClick = { state.generate(CoachKind.READING) }, enabled = !state.working) { Text("AI 针对练习") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                // PANEL 2: 查看原文
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    passages.forEach { p ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("篇章 ${p.code} · ${p.title.ifBlank { "Section C" }}", modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                        val words = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(p.content).count()
                                        Text("约 $words 词", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                SelectionContainer {
                                    Text(p.content, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 28.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.messages[CoachKind.READING].orEmpty().isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    state.messages[CoachKind.READING].orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
