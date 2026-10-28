package com.example.cet6.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.example.cet6.data.*
import com.example.cet6.ui.components.*

private enum class HomeTab(val key: String, val label: String) {
    WRITING("writing", "写作"), TRANSLATION("translation", "翻译"), READING("reading", "阅读"), ME("me", "我的")
}

/**
 * Four skill-first tabs; tasks open as full screens on a small back stack. Routes are plain strings
 * so the stack survives configuration changes and process death through the saved state bundle.
 */
@Composable
fun AppRoot(vm: StudyViewModel, legacy: LegacyScreens) {
    var stack by rememberSaveable { mutableStateOf("") }
    val routes = stack.split('|').filter(String::isNotBlank)
    val top = routes.lastOrNull()
    var tab by rememberSaveable { mutableStateOf(HomeTab.entries.firstOrNull { it.key == vm.lastTab } ?: HomeTab.WRITING) }
    val snackbar = remember { SnackbarHostState() }
    fun push(route: String) { if (route != stack.split('|').lastOrNull()) stack = (stack.split('|').filter(String::isNotBlank) + route).joinToString("|") }
    fun pop() { stack = stack.split('|').filter(String::isNotBlank).dropLast(1).joinToString("|") }
    fun routeFor(entry: CoachPractice): String {
        val kind = CoachKind.valueOf(entry.kind)
        return if (kind == CoachKind.READING && ExamCodec.metadata(entry) == null) "custom/READING" else "work/${kind.name}"
    }
    fun open(entry: CoachPractice) { vm.coach.open(CoachKind.valueOf(entry.kind), entry) { push(routeFor(it)) } }
    fun start(paper: ExamPaper, kind: CoachKind) {
        vm.coach.startExam(ExamCodec.practice(paper, kind)) { push("work/${kind.name}") }
    }
    if (vm.backupRecoveryRunning) {
        Scaffold { padding ->
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("正在完成备份恢复")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("恢复内容已保存在本机，请稍候。若操作中断，下次启动会继续合并。")
            }
        }
        return
    }
    BackHandler(enabled = top != null) { pop() }
    LaunchedEffect(vm.coach.importedReadingId) {
        vm.coach.importedReadingId?.let { vm.coach.consumeReadingImport(it); push("custom/READING") }
    }
    LaunchedEffect(vm.coach.shareStatus) { if (vm.coach.shareStatus.isNotBlank()) snackbar.showSnackbar(vm.coach.shareStatus) }

    when {
        top == null -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    Hairline()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                        HomeTab.entries.forEach { item ->
                            val selected = tab == item
                            NavigationBarItem(selected = selected, onClick = { tab = item; vm.lastTab = item.key },
                                icon = { Icon(when (item) {
                                    HomeTab.WRITING -> if (selected) Icons.Filled.EditNote else Icons.Outlined.EditNote
                                    HomeTab.TRANSLATION -> if (selected) Icons.Filled.Translate else Icons.Outlined.Translate
                                    HomeTab.READING -> if (selected) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Outlined.MenuBook
                                    HomeTab.ME -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
                                }, contentDescription = null) },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                        }
                    }
                }
            }
        ) { padding ->
            when (tab) {
                HomeTab.WRITING -> TaskHome(vm, CoachKind.WRITING, padding, ::open, { start(it, CoachKind.WRITING) }) {
                    vm.coach.startCustom(CoachKind.WRITING, "自拟题目", "") { push("work/WRITING") }
                }
                HomeTab.TRANSLATION -> TaskHome(vm, CoachKind.TRANSLATION, padding, ::open, { start(it, CoachKind.TRANSLATION) }) {
                    vm.coach.startCustom(CoachKind.TRANSLATION, "自选段落", "") { push("work/TRANSLATION") }
                }
                HomeTab.READING -> ReadingHome(vm, padding, ::open, { start(it, CoachKind.READING) },
                    onShared = { id -> vm.examPapers.firstOrNull { it.stableId == id }?.let { start(it, CoachKind.READING) } },
                    onCustom = { vm.coach.open(CoachKind.READING) { push("custom/READING") } })
                HomeTab.ME -> MeHome(vm, padding, ::push)
            }
        }
        top == "work/WRITING" -> WritingWorkspace(vm, ::pop)
        top == "work/TRANSLATION" -> TranslationWorkspace(vm, ::pop)
        top == "work/READING" -> ReadingWorkspace(vm, ::pop)
        top == "custom/READING" -> SimpleScreen(
            title = "自己的文章",
            onBack = ::pop,
            actions = { vm.coach.entries[CoachKind.READING]?.let { WorkspaceMenu(vm, it) } }
        ) { scroll -> CoachScreen(vm, CoachKind.READING, scroll) }
        top == "me/connection" -> SimpleScreen("AI 连接", ::pop) { legacy.connection() }
        top == "me/review" -> SimpleScreen("错因回顾", ::pop) {
            WeaknessReviewCard(vm.coach.history, vm.today, !vm.coach.working, standalone = true, onReview = ::open)
        }
        top == "me/trends" -> SimpleScreen("学习趋势", ::pop) { LearningTrendsScreen(vm) }
        top == "me/records" -> SimpleScreen("练习记录", ::pop) { ExamHistory(vm, onOpen = ::open) }
        top == "me/backup" -> SimpleScreen("备份与恢复", ::pop) { BackupCard(vm, standalone = true) }
        top == "me/exams" -> SimpleScreen("题库管理", ::pop) { ExamManagement(vm) }
        top == "me/legacy" -> SimpleScreen("单词与旧版功能", ::pop) {
            legacy.today({ push("me/words") }, { vm.coach.open(CoachKind.READING) { push("custom/READING") } },
                { tab = HomeTab.WRITING; pop() }, ::open)
        }
        top == "me/words" -> SimpleScreen("六级词汇", ::pop) { legacy.words() }
        top == "me/essays" -> SimpleScreen("旧版作文记录", ::pop) { LegacyEssays(vm) }
        else -> LaunchedEffect(top) { pop() }
    }
}

/** Screens that still live in MainActivity; passed in so their code stays where it is. */
class LegacyScreens(
    val connection: @Composable () -> Unit,
    val words: @Composable () -> Unit,
    val today: @Composable (onWords: () -> Unit, onReading: () -> Unit, onWriting: () -> Unit, onReview: (CoachPractice) -> Unit) -> Unit
)

@Composable
fun SimpleScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(androidx.compose.foundation.ScrollState) -> Unit
) {
    val scroll = rememberScrollState()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { ScreenTopBar(title, onBack = onBack, actions = actions) }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(scroll)
            .padding(horizontal = PageGutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content(scroll)
        }
    }
}

@Composable
private fun LegacyEssays(vm: StudyViewModel) {
    if (vm.essays.isEmpty()) Note("没有早期版本保存的作文。")
    vm.essays.forEach { saved ->
        var expanded by remember(saved.id) { mutableStateOf(false) }
        ListRow(saved.topic.ifBlank { "历史作文" }, subtitle = shortTime(saved.createdAt), onClick = { expanded = !expanded })
        if (expanded) SelectionContainer {
            Text("原文：\n${saved.content}\n\n批改：\n${saved.feedback}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Import and export of the personal question bank; unchanged behaviour, now its own screen. */
@Composable
fun ExamManagement(vm: StudyViewModel) {
    val uriHandler = LocalUriHandler.current
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewExam) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportExam) }
    val catalogCount = remember { ExamCatalog.papers().size }
    val importedCount = vm.examPapers.count { !it.sample }
    val sampleCount = vm.examPapers.count { it.sample }
    SectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CardTitle("当前个人题库")
            StatusPill(
                text = "已导入 $importedCount / $catalogCount 套",
                tone = if (importedCount > 0) Tone.Good else Tone.Better
            )
        }
        Note(
            listOfNotNull(
                "真题目录共 $catalogCount 套（2023.06–2025.12）",
                if (sampleCount > 0) "原创样本 $sampleCount 套" else null
            ).joinToString(" · ")
        )
        Text("导入你有权使用的结构化 JSON 资料。PDF / Word 请先在电脑本地转换并校对；扫描件暂不自动识别。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !vm.examBusy) {
                Text("导入 JSON 资料")
            }
            OutlinedButton(onClick = { export.launch("cet6-personal-exams.json") }, enabled = !vm.examBusy && vm.examPapers.isNotEmpty()) {
                Text("导出个人题库")
            }
        }
        if (vm.examStatus.isNotBlank()) Text(vm.examStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (vm.examPreview.isNotEmpty()) {
        SectionCard {
            CardTitle("导入预览（${vm.examPreview.size} 套）")
            vm.examPreview.forEachIndexed { index, p ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${p.label} · ${p.status}", style = MaterialTheme.typography.bodyLarge)
                    Note(p.issues().joinToString("；").ifBlank { "结构完整，使用权与套卷一致性由导入者确认" })
                }
                if (index < vm.examPreview.lastIndex) Hairline()
            }
            Button(onClick = vm::confirmExamImport, enabled = !vm.examBusy, modifier = Modifier.fillMaxWidth()) {
                Text("确认保存个人题库")
            }
        }
    }
    Note("「备份与恢复」可一次导出学习记录与个人题库。此处的题库 JSON 仅包含资料。")
    TextButton(onClick = { runCatching { uriHandler.openUri(ExamCatalog.SET_SOURCE) } }) { Text("查看套次目录来源") }
}
