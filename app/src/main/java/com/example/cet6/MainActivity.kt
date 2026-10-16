package com.example.cet6

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.cet6.ui.StudyViewModel
import com.example.cet6.ui.VocabularyImportCard

class MainActivity : ComponentActivity() {
    private val vm: StudyViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { MainContent(vm) }
        }
    }
}

@Composable
private fun MainContent(vm: StudyViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("今日", "单词", "阅读", "作文", "连接")
    Scaffold(
        topBar = { Surface(Modifier.fillMaxWidth().statusBarsPadding(), tonalElevation = 2.dp) {
            Text("六级研习室 · CET-6", Modifier.padding(18.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        } },
        bottomBar = { NavigationBar {
            tabs.forEachIndexed { index, name ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Text(listOf("日", "A", "阅", "✎", "◉")[index]) }, label = { Text(name) })
            }
        } }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(key(tab) { rememberScrollState() })
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (tab) {
                0 -> TodayScreen(vm, onWords = { tab = 1 }, onReading = { tab = 2 }, onWriting = { tab = 3 })
                1 -> WordScreen(vm)
                2 -> ReadingScreen(vm)
                3 -> WritingScreen(vm)
                else -> ConnectionScreen(vm)
            }
        }
    }
}

@Composable
private fun TodayScreen(vm: StudyViewModel, onWords: () -> Unit, onReading: () -> Unit, onWriting: () -> Unit) {
    SectionTitle("今天，从一小步开始", "${vm.today} · 学习数据保存在本机，单词与阅读可离线使用。")
    val progress = vm.todayProgress
    val plan = vm.plan
    val newCount = progress?.newCount ?: 0
    val reviewCount = progress?.reviewCount ?: 0
    val newAvailable = vm.words.count { it.dueAt == 0L }
    val due = vm.words.count { it.dueAt in 1..vm.now }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日单词计划", style = MaterialTheme.typography.titleMedium)
            Text("新词 $newCount / ${plan?.newGoal ?: "…"} · 复习 $reviewCount / ${plan?.reviewGoal ?: "…"}")
            Text("词库可学新词 $newAvailable 个，当前到期 $due 个")
            if (plan != null) {
                val availableNewGoal = minOf(plan.newGoal, newCount + newAvailable)
                val availableReviewGoal = minOf(plan.reviewGoal, reviewCount + due)
                val total = availableNewGoal + availableReviewGoal
                val done = minOf(newCount, availableNewGoal) + minOf(reviewCount, availableReviewGoal)
                LinearProgressIndicator(progress = { if (total == 0) 1f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth())
                Text(if (done >= total) "当前可执行的单词任务已完成。" else "优先复习到期词，再学习新词。")
                if (availableNewGoal < plan.newGoal || availableReviewGoal < plan.reviewGoal)
                    Text("词量不足或无到期词时，按实际可学数量计算进度。", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onWords, modifier = Modifier.fillMaxWidth()) { Text("开始单词学习") }
        }
    }
    var newGoal by rememberSaveable(plan?.date, plan?.newGoal) { mutableStateOf(plan?.newGoal?.toString().orEmpty()) }
    var reviewGoal by rememberSaveable(plan?.date, plan?.reviewGoal) { mutableStateOf(plan?.reviewGoal?.toString().orEmpty()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = newGoal, onValueChange = { newGoal = it.take(3) }, label = { Text("每日新词") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(value = reviewGoal, onValueChange = { reviewGoal = it.take(3) }, label = { Text("每日复习") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
    }
    OutlinedButton(onClick = { vm.savePlan(newGoal.toInt(), reviewGoal.toInt()) },
        enabled = plan != null && !vm.savingStudy && newGoal.toIntOrNull() in 1..100 && reviewGoal.toIntOrNull() in 1..200) {
        Text("保存学习目标")
    }
    if (vm.studyStatus.isNotBlank()) Text(vm.studyStatus)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onReading) { Text("阅读练习") }
        OutlinedButton(onClick = onWriting) { Text("作文训练") }
    }
    Text("单词学习记录", style = MaterialTheme.typography.titleMedium)
    Text("累计学习 ${vm.dailyProgress.size} 天 · 累计作答 ${vm.dailyProgress.sumOf { it.totalCount }} 次")
    val total = vm.dailyProgress.sumOf { it.totalCount }
    if (total > 0) Text("自评记住率 ${vm.dailyProgress.sumOf { it.rememberedCount } * 100L / total}%（不是考试正确率）")
    vm.dailyProgress.take(7).forEach { day ->
        Text("${day.date}：新词 ${day.newCount}，复习 ${day.reviewCount}，记住 ${day.rememberedCount}/${day.totalCount}")
    }
    if (total == 0) Text("完成第一张单词卡后，这里会显示真实学习记录。旧版未保存逐次日志，历史不补算。")
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConnectionScreen(vm: StudyViewModel) {
    var message by rememberSaveable { mutableStateOf("请只回答：六级研习室 API 测试成功。") }
    SectionTitle("连接 ChatGPT", "第一阶段：OAuth 登录 → 模型列表 → 流式 API 调用")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (vm.sessionEmail.isBlank()) "未连接" else "已登录：${vm.sessionEmail}")
            Text(if (vm.hasPlan) "套餐 scope：已授予（实际调用待验证）" else "套餐 scope：未授予")
            Button(onClick = vm::signIn, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with ChatGPT")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::refreshModels, enabled = vm.hasPlan && !vm.busy) {
                    Text("刷新模型")
                }
                OutlinedButton(onClick = vm::signOut, enabled = !vm.busy) { Text("退出连接") }
            }
        }
    }
    ModelSelector(vm)
    OutlinedTextField(value = message, onValueChange = { message = it },
        label = { Text("测试消息") }, minLines = 3, enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth())
    Button(onClick = { vm.ask(message) },
        enabled = vm.hasPlan && vm.chosenModel.isNotBlank() && message.isNotBlank() && !vm.busy,
        modifier = Modifier.fillMaxWidth()) {
        Text("发送一条测试请求")
    }
    StatusCard(vm)
    Text("授权过程由系统浏览器完成。此应用不收集密码、不使用 ChatGPT Cookie。",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ModelSelector(vm: StudyViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("推理模型", fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = vm.models.isNotEmpty() && !vm.busy) {
                Text(vm.models.find { it.slug == vm.chosenModel }?.displayName ?: "请先获取模型")
                Text(" ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                vm.models.forEach { model ->
                    DropdownMenuItem(text = { Text(model.displayName) }, onClick = {
                        vm.selectModel(model.slug); expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun StatusCard(vm: StudyViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("状态：${vm.status}", style = MaterialTheme.typography.bodyMedium)
            if (vm.result.isNotBlank()) {
                HorizontalDivider()
                Text(vm.result, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun WordScreen(vm: StudyViewModel) {
    SectionTitle("六级词汇", "离线间隔复习。内置原创学术词汇练习，可导入有权使用的词库；不是完整六级大纲词库。")
    val due = vm.words.filter { it.dueAt <= vm.now }.sortedWith(compareBy({ it.dueAt == 0L }, { it.dueAt }, { it.word }))
    val progress = vm.todayProgress
    var extra by rememberSaveable(vm.today) { mutableStateOf(false) }
    val planned = due.filter { word -> extra || vm.plan?.let {
        if (word.dueAt == 0L) (progress?.newCount ?: 0) < it.newGoal else (progress?.reviewCount ?: 0) < it.reviewGoal
    } == true }
    Text("当前到期 ${due.count { it.dueAt > 0 }} · 未学 ${due.count { it.dueAt == 0L }} · 词库 ${vm.words.size}")
    if (planned.isEmpty()) {
        Text(if (due.isEmpty()) "当前没有到期单词，可浏览词库或稍后再来。" else "今日计划已完成。")
        if (due.isNotEmpty()) OutlinedButton(onClick = { extra = true }) { Text("继续额外学习") }
    }
    planned.take(1).forEach { word ->
        var reveal by remember(word.word) { mutableStateOf(false) }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(word.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (!reveal) {
                    OutlinedButton(onClick = { reveal = true }) { Text("显示释义") }
                } else {
                    Text(word.meaning)
                    Text(word.example, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.review(word, false) }, enabled = !vm.savingStudy) { Text("忘记了") }
                        Button(onClick = { vm.review(word, true) }, enabled = !vm.savingStudy) { Text("记住了") }
                    }
                }
            }
        }
    }
    if (vm.studyStatus.isNotBlank()) Text(vm.studyStatus)
    VocabularyImportCard(vm)
    Text("本地学习历史（展示最近 10 次）", style = MaterialTheme.typography.titleMedium)
    vm.wordReviews.take(10).forEach { review ->
        Text("${review.word} · ${if (review.remembered) "记住了" else "忘记了"} · ${review.studiedOn}")
    }
    var search by rememberSaveable { mutableStateOf("") }
    var browse by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { browse = !browse }) { Text(if (browse) "收起词库" else "浏览与搜索词库") }
    if (browse) {
    OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("搜索全部词库") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    vm.words.filter { it.word.contains(search.trim(), true) || it.meaning.contains(search.trim()) }.take(50).forEach { word ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(word.word, fontWeight = FontWeight.Bold)
                Text(word.meaning)
                Text(word.example)
                Text("来源：${word.source}", style = MaterialTheme.typography.bodySmall)
                Text(if (word.dueAt == 0L) "尚未学习" else "下次复习：${Instant.ofEpochMilli(word.dueAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    }
}

private const val READING_TEXT = """University libraries are changing as students increasingly use digital resources. However, a recent campus survey found that quiet study spaces remain essential. Students valued online access for convenience, but many reported that a physical library helped them concentrate. The survey suggests that universities should balance digital investment with well-designed study areas rather than replacing one with the other."""
private const val READING_QUESTION = "What does the passage suggest universities should do?"
private val READING_OPTIONS = listOf(
    "A. Replace all physical libraries with digital services.",
    "B. Maintain both online resources and physical study spaces.",
    "C. Reduce spending on online resources immediately.",
    "D. Ask students to stop studying in libraries."
)

@Composable
private fun ReadingScreen(vm: StudyViewModel) {
    SectionTitle("阅读理解", "原创示例题。先选答案，再获取本地判题和 AI 解析。")
    Text(READING_TEXT, style = MaterialTheme.typography.bodyLarge)
    HorizontalDivider()
    Text(READING_QUESTION, fontWeight = FontWeight.SemiBold)
    var choice by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    READING_OPTIONS.forEachIndexed { index, option ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = choice == "ABCD"[index].toString(),
                onClick = { if (!submitted) choice = "ABCD"[index].toString() }, enabled = !submitted)
            Text(option, modifier = Modifier.padding(top = 12.dp))
        }
    }
    Button(onClick = { vm.recordReading(choice); submitted = true },
        enabled = choice.isNotEmpty() && !submitted) { Text("提交答案") }
    if (submitted) {
        Text(if (choice == "B") "回答正确：B" else "回答有误，正确答案：B")
        OutlinedButton(onClick = {
            vm.ask("阅读材料：\n$READING_TEXT\n\n题目：$READING_QUESTION\n选项：\n${READING_OPTIONS.joinToString("\n")}\n我的答案：$choice；参考答案：B。请标明原文依据，逐项解析，并给一条阅读技巧。")
        }, enabled = vm.hasPlan && vm.chosenModel.isNotBlank() && !vm.busy) { Text("AI 解析") }
    }
    Text("已保存答题记录：${vm.readingAttempts.size} 条（最多显示最近 30 条）")
    StatusCard(vm)
}

@Composable
private fun WritingScreen(vm: StudyViewModel) {
    SectionTitle("作文批改", "提交作文后获取建议；AI 给出的评分不等于官方六级阅卷分数。")
    var topic by remember { mutableStateOf("The Importance of Critical Thinking") }
    var essay by remember { mutableStateOf("") }
    OutlinedTextField(value = topic, onValueChange = { topic = it },
        label = { Text("写作题目") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = essay, onValueChange = { essay = it },
        label = { Text("英文作文") }, modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp),
        minLines = 7)
    Text("当前约 ${essay.trim().split(Regex("\\s+")).count { it.isNotEmpty() }} 词")
    Button(onClick = {
        vm.ask("六级作文题目：$topic\n学生作文：\n$essay\n\n请按切题、结构、词汇和语法四项分段点评；逐句指出最重要的3处问题并给修改理由；给一段改写示范。不要声称自己是官方阅卷员，也不要编造官方分数。",
            saveEssay = topic to essay)
    }, enabled = essay.isNotBlank() && vm.hasPlan && vm.chosenModel.isNotBlank() && !vm.busy,
        modifier = Modifier.fillMaxWidth()) { Text("提交 AI 批改") }
    StatusCard(vm)
    if (vm.essays.isNotEmpty()) {
        Text("本地批改历史", style = MaterialTheme.typography.titleMedium)
        vm.essays.take(3).forEach { saved ->
            var expanded by remember(saved.id) { mutableStateOf(false) }
            ElevatedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(saved.topic, fontWeight = FontWeight.Medium)
                    Text("点击${if (expanded) "收起" else "查看"}批改记录")
                    if (expanded) Text(saved.feedback)
                }
            }
        }
    }
}
