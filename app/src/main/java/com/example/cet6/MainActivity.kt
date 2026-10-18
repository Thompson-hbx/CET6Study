package com.example.cet6

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
import com.example.cet6.ui.WordContentLicenses
import com.example.cet6.ui.BackupCard
import com.example.cet6.ui.CoachScreen
import com.example.cet6.ui.WritingAndTranslationScreen
import com.example.cet6.ui.theme.CET6Theme
import com.example.cet6.data.CoachKind
import com.example.cet6.data.WordRating
import com.example.cet6.data.WordScheduler
import com.example.cet6.data.SharedReading
import com.example.cet6.data.CoachPractice
import com.example.cet6.ui.WeaknessReviewCard
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: StudyViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CET6Theme { MainContent(vm) }
        }
        if (savedInstanceState == null) receiveShare(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }
    private fun receiveShare(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        try {
            val input = SharedReading.from(incoming.type, incoming.getCharSequenceExtra(Intent.EXTRA_TEXT),
                incoming.getCharSequenceExtra(Intent.EXTRA_SUBJECT))
            vm.coach.importReading(input)
        } catch (e: IllegalArgumentException) { vm.coach.shareFailed(e.message ?: "分享内容无法导入")
        } catch (_: Exception) { vm.coach.shareFailed("分享内容无法读取，请重新分享纯文本。")
        } finally {
            incoming.action = Intent.ACTION_MAIN
            incoming.removeExtra(Intent.EXTRA_TEXT)
            incoming.removeExtra(Intent.EXTRA_SUBJECT)
        }
    }
    override fun onResume() { super.onResume(); vm.refreshStudyClock() }
    override fun onStop() { vm.saveDrafts(); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(vm: StudyViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf(CoachKind.READING) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var mine by rememberSaveable { mutableStateOf("") }
    val tabs = listOf("题库", "训练", "复盘", "我的")
    val scroll = key(tab, kind, mine) { rememberScrollState() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun open(entry: CoachPractice) {
        kind = CoachKind.valueOf(entry.kind)
        vm.coach.open(kind, entry)
        custom = com.example.cet6.data.ExamCodec.metadata(entry) == null
        tab = 1
    }
    LaunchedEffect(vm.coach.importedReadingId) {
        vm.coach.importedReadingId?.let { kind = CoachKind.READING; custom = true; tab = 1; vm.coach.consumeReadingImport(it) }
    }
    LaunchedEffect(vm.coach.shareStatus) {
        if (vm.coach.shareStatus.isNotBlank()) snackbar.showSnackbar(vm.coach.shareStatus)
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == 1 && scroll.value > 0) SmallFloatingActionButton(onClick = { scope.launch { scroll.animateScrollTo(0) } }) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "返回训练导航")
            }
        },
        topBar = { if (tab != 1) TopAppBar(title = { Text(if (tab == 0) "历年真题" else tabs[tab],
            style = MaterialTheme.typography.titleLarge) }) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                tabs.forEachIndexed { index, name ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; mine = "" },
                        icon = { Icon(when(index) { 0 -> Icons.Outlined.MenuBook; 1 -> Icons.Outlined.EditNote;
                            2 -> Icons.Outlined.AutoStories; else -> Icons.Outlined.Hub }, null) }, label = { Text(name) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> com.example.cet6.ui.ExamLibraryScreen(vm, onContinue = { open(it) }, onStart = { paper, selected, passage ->
                    kind = selected; custom = false
                    vm.coach.startExam(com.example.cet6.data.ExamCodec.practice(paper, selected, passage)); tab = 1
                })
                1 -> {
                    val entry = vm.coach.entries[kind]
                    if (entry == null || com.example.cet6.data.ExamCodec.metadata(entry) == null || custom) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CoachKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
                        }
                    }
                    if (entry != null && com.example.cet6.data.ExamCodec.metadata(entry) != null && !custom) {
                        com.example.cet6.ui.ExamTrainingScreen(vm, kind, scroll)
                    } else {
                        CoachScreen(vm, kind, scroll)
                    }
                    TextButton(onClick = { tab = 0 }) { Text("返回题库") }
                }
                2 -> {
                    WeaknessReviewCard(vm.coach.history, vm.today, !vm.coach.working, onReview = { open(it) })
                    com.example.cet6.ui.ExamHistory(vm, onOpen = { open(it) })
                }
                else -> when (mine) {
                    "essays" -> { TextButton(onClick = { mine = "" }) { Text("返回我的") }; WritingAndTranslationScreen(vm, scroll) }
                    "connection" -> { TextButton(onClick = { mine = "" }) { Text("返回我的") }; ConnectionScreen(vm) }
                    "words" -> { TextButton(onClick = { mine = "" }) { Text("返回我的") }; WordScreen(vm) }
                    "legacy" -> { TextButton(onClick = { mine = "" }) { Text("返回我的") }
                        TodayScreen(vm, onWords = { mine = "words" }, onReading = { kind = CoachKind.READING; custom = true; tab = 1 },
                            onWriting = { kind = CoachKind.WRITING; custom = true; tab = 1 }, onReview = { open(it) }) }
                    else -> {
                        com.example.cet6.ui.PaperRow("ChatGPT 与模型", if (vm.hasPlan) "已连接 · 管理模型与授权" else "连接后可请求 AI 讲解", { mine = "connection" })
                        BackupCard(vm)
                        Text("题库内容与作答分开保存，请在题库页单独导出个人题库。", style = MaterialTheme.typography.bodySmall)
                        com.example.cet6.ui.PaperRow("历史功能", "单词记录、FSRS、旧版作文与学习计划", { mine = "legacy" })
                        com.example.cet6.ui.PaperRow("旧版写译与作文记录", "查看保留的旧版作文批改", { mine = "essays" })
                        com.example.cet6.ui.PaperRow("自定义练习", "手动粘贴阅读、作文或翻译材料", { vm.coach.open(kind); custom = true; tab = 1 })
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(vm: StudyViewModel, onWords: () -> Unit, onReading: () -> Unit, onWriting: () -> Unit,
    onReview: (CoachPractice) -> Unit) {
    SectionTitle("今天，从一篇阅读开始", "${vm.today} · 学习数据保存在本机，AI 解析需要网络。")
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("六级专项训练", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val records = vm.coach.history.filter { it.submittedAt > 0 }
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ) {
                    Text(
                        "${records.size} 次训练 · ${records.count { it.feedback.isNotBlank() }} 次反馈",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Button(
                onClick = onReading,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(Icons.Outlined.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("阅读训练与 AI 精讲", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onWriting,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("作文 / 中译英批改", fontWeight = FontWeight.SemiBold)
            }
            val records = vm.coach.history.filter { it.submittedAt > 0 }
            Text(
                "已作答 ${records.size} 次 · 已存 AI 反馈 ${records.count { it.feedback.isNotBlank() }} 次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    BackupCard(vm)
    WeaknessReviewCard(vm.coach.history, vm.today, !vm.coach.working && !vm.busy, onReview)
    var legacyWords by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { legacyWords = !legacyWords },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    ) {
        Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (legacyWords) "收起原有单词计划与记录" else "展开原有单词计划与记录")
    }
    if (legacyWords) {
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
        if (total > 0) Text("自评认识率 ${vm.dailyProgress.sumOf { it.rememberedCount } * 100L / total}%（不是考试正确率）")
        Text("计划按不同单词计数；短时重复作答会保存全部记录，不重复增加计划完成量。", style = MaterialTheme.typography.bodySmall)
        vm.dailyProgress.take(7).forEach { day ->
            Text("${day.date}：新词 ${day.newCount}，复习 ${day.reviewCount}，记住 ${day.rememberedCount}/${day.totalCount}")
        }
        if (total == 0) Text("完成第一张单词卡后，这里会显示真实学习记录。旧版未保存逐次日志，历史不补算。")
    }
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
    SectionTitle("连接 ChatGPT", "OAuth 登录 → 模型列表 → 流式 API 调用")
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (vm.sessionEmail.isBlank()) "未连接" else "已登录：${vm.sessionEmail}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (vm.sessionEmail.isNotBlank()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        if (vm.sessionEmail.isNotBlank()) "有效会话" else "离线",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (vm.sessionEmail.isNotBlank()) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                if (vm.hasPlan) "套餐 scope：已授予（实际调用待验证）" else "套餐 scope：未授予",
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.hasPlan) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
            Button(
                onClick = vm::signIn,
                enabled = !vm.busy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Continue with ChatGPT", fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::refreshModels,
                    enabled = vm.hasPlan && !vm.busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新模型")
                }
                OutlinedButton(
                    onClick = vm::signOut,
                    enabled = !vm.busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("退出连接")
                }
            }
        }
    }
    ModelSelector(vm)
    OutlinedTextField(
        value = message,
        onValueChange = { message = it },
        label = { Text("测试消息") },
        minLines = 3,
        enabled = !vm.busy,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { vm.ask(message) },
        enabled = vm.hasPlan && vm.chosenModel.isNotBlank() && message.isNotBlank() && !vm.busy,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("发送一条测试请求", fontWeight = FontWeight.SemiBold)
    }
    StatusCard(vm)
    Text(
        "授权过程由系统浏览器完成。此应用不收集密码、不使用 ChatGPT Cookie。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
    SectionTitle("六级词汇", "FSRS-6 离线复习 · 先看语境，再回忆词义。内置原创学术词汇练习，可导入有权使用的词库。")
    val due = vm.words.filter { it.dueAt <= vm.now }.sortedWith(compareBy({ it.dueAt == 0L }, { it.dueAt }, { it.word }))
    val progress = vm.todayProgress
    var extra by rememberSaveable(vm.today) { mutableStateOf(false) }
    val planned = due.filter { word -> extra || vm.schedules[word.word]?.phase?.let { it != WordScheduler.REVIEW } == true || vm.plan?.let {
        if (word.dueAt == 0L) (progress?.newCount ?: 0) < it.newGoal else (progress?.reviewCount ?: 0) < it.reviewGoal
    } == true }
    Text("当前到期 ${due.count { it.dueAt > 0 }} · 未学 ${due.count { it.dueAt == 0L }} · 词库 ${vm.words.size}")
    if (planned.isEmpty()) {
        Text(if (due.isEmpty()) "当前没有到期单词，可浏览词库或稍后再来。" else "今日计划已完成。")
        if (due.isNotEmpty()) OutlinedButton(onClick = { extra = true }) { Text("继续额外学习") }
        vm.words.filter { it.dueAt > vm.now }.minOfOrNull { it.dueAt }?.let {
            Text("下次到期：${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}")
        }
        OutlinedButton(onClick = vm::refreshStudyClock) { Text("刷新到期任务") }
    }
    planned.take(1).forEach { word ->
        var reveal by remember(word.word) { mutableStateOf(false) }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(word.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                vm.pronunciations[word.word]?.let { Text("美式参考音标：$it") }
                Text(word.example, style = MaterialTheme.typography.bodyMedium)
                if (!reveal) {
                    OutlinedButton(onClick = { reveal = true }) { Text("显示释义") }
                } else {
                    Text(word.meaning)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WordRating.entries.forEach { rating ->
                            val interval = runCatching {
                                WordScheduler.next(word, vm.schedules[word.word], rating, vm.now).card.dueAt - vm.now
                            }.getOrNull()
                            OutlinedButton(onClick = { vm.review(word, rating) }, enabled = !vm.savingStudy,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("${rating.label} · ${interval?.let(WordScheduler::intervalText) ?: "保存后安排"}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (vm.studyStatus.isNotBlank()) Text(vm.studyStatus)
    VocabularyImportCard(vm)
    WordContentLicenses()
    Text("本地学习历史（展示最近 10 次）", style = MaterialTheme.typography.titleMedium)
    vm.wordReviews.take(10).forEach { review ->
        val label = WordRating.entries.find { it.value == review.rating }?.label ?: if (review.remembered) "记住了（旧版）" else "忘记了（旧版）"
        Text("${review.word} · $label · ${review.studiedOn}")
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
