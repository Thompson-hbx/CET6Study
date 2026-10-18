package com.example.cet6.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cet6.data.*

/** Paper-first: record completed answers, mark against sourced keys, then disclose one hint at a time. */
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
    var showIndividual by rememberSaveable(entry.id) { mutableStateOf(false) }
    val labels = listOf("录入答案", "逐题提示", "对照原文")
    val answers = questions.associate { it.number to ReadingQuestions.selected(entry.answer, "${it.number}").orEmpty() }
    val answered = answers.count { it.value.isNotBlank() }
    LaunchedEffect(panel, selectedNumber, entry.id) { scroll.scrollTo(0) }
    LaunchedEffect(submitted) { if (submitted) {
        panel = 1
        selectedNumber = questions.firstOrNull { ReadingGuidance.verdict(it, answers[it.number].orEmpty()) == "需要回看" }?.number ?: questions.first().number
    } }
    Text(entry.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SecondaryTabRow(panel, containerColor = MaterialTheme.colorScheme.background) {
        labels.forEachIndexed { i, label -> Tab(panel == i, { panel = i }, enabled = i != 1 || submitted, text = { Text(label) }) }
    }

    when (panel) {
        0 -> {
            Text("把纸上的答案带进来", style = MaterialTheme.typography.headlineMedium)
            Text("每篇都按 1、2、3、4、5 的顺序填写。直接输入五个选项即可，例如 BCADB。", lineHeight = 27.sp)
            Text("已录入 $answered / ${questions.size} 题", color = MaterialTheme.colorScheme.primary)
            passages.forEach { p -> key(p.code) {
                var typed by rememberSaveable(entry.id, p.code, inputVersion) { mutableStateOf<String?>(null) }
                var error by rememberSaveable(entry.id, p.code, inputVersion) { mutableStateOf("") }
                Text("篇章 ${p.code} · 1—5 题", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(typed ?: SequentialAnswers.sequence(entry.answer, p.questions), { raw ->
                    typed = raw
                    val result = runCatching { SequentialAnswers.apply(entry.answer, raw, p.questions) }
                    error = result.exceptionOrNull()?.message.orEmpty()
                    invalidPassages = if (result.isFailure) (invalidPassages + p.code).toSet().joinToString("") else invalidPassages.replace(p.code, "")
                    result.getOrNull()?.let { next ->
                        if (state.edit(CoachKind.READING) { it.copy(answer = next) }) typed = SequentialAnswers.normalize(raw)
                    }
                }, label = { Text("按 1—5 顺序输入答案") }, supportingText = { Text(error.ifBlank { "例如 BCADB；空格可分隔，? 表示未答。" }) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    singleLine = true, readOnly = submitted, enabled = !state.working, isError = error.isNotBlank(), modifier = Modifier.fillMaxWidth())
            } }
            TextButton(onClick = { showIndividual = !showIndividual }) { Text(if (showIndividual) "收起逐题录入" else "逐题检查 / 修改") }
            if (showIndividual || submitted) passages.forEach { p ->
                Text("篇章 ${p.code}", style = MaterialTheme.typography.titleMedium)
                p.questions.sortedBy { it.number }.forEachIndexed { index, q ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${index + 1}", Modifier.padding(top = 16.dp), fontWeight = FontWeight.Medium)
                        "ABCD".forEach { c -> FilterChip(answers[q.number] == "$c", {
                            inputVersion++; invalidPassages = ""
                            state.edit(CoachKind.READING) { it.copy(answer = ReadingQuestions.choose(it.answer, "${q.number}", "$c")) }
                        }, enabled = !submitted && !state.working, label = { Text("$c") }) }
                    }
                }
            }
            if (!submitted) {
                if (answered < questions.size) Text("还有 ${questions.size - answered} 题未录入；核对时只比较已录入的答案。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { state.submit(CoachKind.READING, false) }, enabled = !state.working && answered > 0 && invalidPassages.isBlank()) { Text("核对已录入的答案") }
            }
            else {
                Text("本次录入已保存。修改时会保留这一份记录。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { state.open(CoachKind.READING, entry, retry = true) }, enabled = !state.working) { Text("重新录入一版") }
            }
            Text("核对依据为导入资料中的有来源参考答案；未提供答案的题目不会被判为对错。", style = MaterialTheme.typography.bodySmall)
        }
        1 -> {
            val known = questions.filter { it.referenceAnswer.isNotBlank() && it.answerSource.isNotBlank() && answers[it.number].orEmpty().isNotBlank() }
            val matched = known.count { answers[it.number] == it.referenceAnswer }
            Text("先看结果，再找依据", style = MaterialTheme.typography.headlineMedium)
            Text("${known.size} 题可核对 · $matched 题与参考一致 · ${known.size - matched} 题需要回看", style = MaterialTheme.typography.bodyMedium)
            val missingKey = questions.count { answers[it.number].orEmpty().isNotBlank() && (it.referenceAnswer.isBlank() || it.answerSource.isBlank()) }
            if (answered < questions.size || missingKey > 0) Text("${questions.size - answered} 题未录入 · $missingKey 题暂无有来源参考答案", style = MaterialTheme.typography.bodySmall)
            Text("先不展开正确选项。选一题，带着提示回到手中的原卷。", style = MaterialTheme.typography.bodySmall)
            passages.forEach { p ->
                Text("篇章 ${p.code} · 1—5 题", style = MaterialTheme.typography.titleMedium)
                val row = p.questions.sortedBy { it.number }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { index, q -> FilterChip(selectedNumber == q.number, { selectedNumber = q.number }, modifier = Modifier.weight(1f), label = {
                    val status = ReadingGuidance.verdict(q, answers[q.number].orEmpty())
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${index + 1}")
                        Text(when (status) { "与参考一致" -> "✓"; "需要回看" -> "!"; else -> "?" }, style = MaterialTheme.typography.labelSmall)
                    }
                }) }
                }
            }
            Text("✓ 参考一致　! 需要回看　? 待核对", style = MaterialTheme.typography.bodySmall)
            val q = questions.firstOrNull { it.number == selectedNumber } ?: questions.first()
            val passage = passages.first { p -> p.questions.any { it.number == q.number } }
            val level = levels.split(';').mapNotNull { token -> token.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1].toIntOrNull() } }.toMap()["${q.number}"] ?: 0
            val ordinal = passage.questions.sortedBy { it.number }.indexOfFirst { it.number == q.number } + 1
            Text("篇章 ${passage.code} · 第 $ordinal 题\n${q.stem}", style = MaterialTheme.typography.titleLarge, lineHeight = 29.sp)
            Text("我的答案 ${answers[q.number].orEmpty().ifBlank { "未答" }} · ${ReadingGuidance.verdict(q, answers[q.number].orEmpty())}", color = MaterialTheme.colorScheme.primary)
            if (level == 0) Text("先把题干译成自己的话：它究竟在问事实、原因、态度，还是推断？")
            if (level >= 1) {
                Text("01 / 圈出题干线索", style = MaterialTheme.typography.titleMedium)
                Text("在纸上圈出题干里的主体、时间和限定词。用一句中文写下“我需要找什么信息”，暂时不要只凭印象挑选项。", lineHeight = 27.sp)
            }
            if (level >= 2) {
                Text("02 / 回到原文定位", style = MaterialTheme.typography.titleMedium)
                val paragraph = ReadingGuidance.paragraph(passage, q)
                Text(if (paragraph != null) "资料中的证据位于篇章 ${passage.code} 第 $paragraph 自然段。先在原卷找到这一段，寻找题干词的同义表达。"
                    else "这份资料未提供可核对的证据位置。请从题干关键词出发逐段寻找同义表达；不能把 AI 猜测当作确定位置。", lineHeight = 27.sp)
            }
            if (level >= 3) {
                Text("03 / 读懂证据关系", style = MaterialTheme.typography.titleMedium)
                if (q.evidence.isNotBlank() && passage.content.contains(q.evidence)) {
                    SelectionContainer { Text("“${q.evidence}”", fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 30.sp) }
                    Text("把这句话放回上下文：主语做了什么？转折、因果或范围有没有改变？先自己写一句解释，再对照选项。", lineHeight = 27.sp)
                } else Text("尚无经过原文匹配的证据句。可以请求本层 AI 提示，再自己在原卷核实；不要补造引用。")
            }
            if (level >= 4) {
                Text("04 / 选项与参考对照", style = MaterialTheme.typography.titleMedium)
                q.options.forEach { Text("${it.label}. ${it.text}", lineHeight = 27.sp) }
                Text("参考答案：${q.referenceAnswer.ifBlank { "暂无可核实答案" }}", fontWeight = FontWeight.Bold)
                if (q.answerSource.isNotBlank()) Text("来源：${q.answerSource}", style = MaterialTheme.typography.bodySmall)
                Text("正确选项如何改写了证据？你原先的选项是偷换主体、扩大范围，还是把推测写成事实？")
            }
            if (level < 4) OutlinedButton(onClick = {
                levels = (levels.split(';').filter { it.isNotBlank() && !it.startsWith("${q.number}:") } + "${q.number}:${level + 1}").joinToString(";")
            }) { Text(when (level) { 0 -> "从第 1 层提示开始"; 1 -> "下一层：定位原文"; 2 -> "下一层：查看证据句"; else -> "最后一步：揭示参考与选项" }) }
            if (level > 0) TextButton(onClick = { state.readingHint(q.number, level) }, enabled = !state.working && !vm.busy) { Text("让 AI 补充本层提示") }
            if (level > 0) {
                var asking by rememberSaveable(entry.id, q.number, level) { mutableStateOf(false) }
                var followUp by rememberSaveable(entry.id, q.number, level) { mutableStateOf("") }
                TextButton(onClick = { asking = !asking }) { Text("对这一层继续追问") }
                if (asking) {
                    OutlinedTextField(followUp, { followUp = it.take(2000) }, label = { Text("哪一步还不明白？") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { state.readingHint(q.number, level, followUp) }, enabled = !state.working && !vm.busy && followUp.isNotBlank()) { Text("发送本层追问") }
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
            HorizontalDivider()
            Text("记录这道题的思路", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("词汇理解", "长难句", "逻辑推断", "选项陷阱", "录入更正").forEach { reason ->
                    SuggestionChip(onClick = { state.edit(CoachKind.READING) {
                        val note = "$reason：篇章 ${passage.code} 第 $ordinal 题"
                        it.copy(errorReason = if (it.errorReason.split('；').contains(note)) it.errorReason else listOf(it.errorReason, note).filter(String::isNotBlank).joinToString("；"))
                    } }, label = { Text(reason) }, enabled = !state.working)
                }
            }
            OutlinedTextField(entry.errorReason, { value -> state.edit(CoachKind.READING) { it.copy(errorReason = value) } }, label = { Text("我的错因与定位笔记") }, enabled = !state.working, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { state.open(CoachKind.READING, entry, retry = true) }, enabled = !state.working) { Text("回到纸上再练 · 新建录入记录") }
            TextButton(onClick = { state.generate(CoachKind.READING) }, enabled = !state.working && entry.errorReason.isNotBlank()) { Text("根据错因生成原创针对练习") }
            Text("AI 提示只发送当前这一题及所属篇章，可能有误，请对照原卷核实。", style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            Text("原文只用于对照", style = MaterialTheme.typography.headlineMedium)
            Text("以你手中的原卷为准；导入版本如有差异，请回到题库校对。", style = MaterialTheme.typography.bodySmall)
            passages.forEach { p ->
                Text("篇章 ${p.code} · ${p.title}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                SelectionContainer { Text(p.content, fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 32.sp) }
            }
        }
    }
    Text(state.messages[CoachKind.READING].orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!submitted) TextButton(onClick = { state.save(CoachKind.READING) }, enabled = !state.working) { Text("保存录入草稿") }
}
