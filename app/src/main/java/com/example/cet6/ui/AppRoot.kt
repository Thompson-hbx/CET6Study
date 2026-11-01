package com.example.cet6.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cet6.data.*
import com.example.cet6.domain.ExamLibraryState
import com.example.cet6.ui.components.*
import com.example.cet6.ui.theme.LocalMarks
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

private enum class HomeTab(val key: String, val label: String) {
    TODAY("today", "今日"),
    PRACTICE("practice", "练习"),
    REVIEW("review", "复盘"),
    ME("me", "我的"),
}

/** Saved tab keys: the four tabs, or a skill key, which opens 练习 on that skill (also what older versions stored). */
private val practiceKeys = mapOf("writing" to CoachKind.WRITING, "translation" to CoachKind.TRANSLATION, "reading" to CoachKind.READING)

/** Titles of pushed screens, keyed by their navigation route. */
private val routeTitles = mapOf(
    "work/WRITING" to "写作", "work/TRANSLATION" to "翻译", "work/READING" to "阅读", "custom/READING" to "自己的文章",
    "me/style" to "界面风格", "me/connection" to "AI 连接", "me/review" to "错因回顾", "me/trends" to "学习趋势", "me/records" to "练习记录",
    "me/backup" to "备份与恢复", "me/exams" to "题库管理", "me/legacy" to "单词与旧版功能", "me/words" to "六级词汇",
    "me/essays" to "旧版作文记录", "me/sync" to "设备同步",
)

/** The tab roots share one destination; every other screen is pushed on top of it. */
private const val HOME = "home"

/**
 * Today, practice, review and me; tasks open as full screens through Navigation Compose, which keeps the back stack
 * across configuration changes and process death and handles system back.
 */
@Composable
fun AppRoot(vm: StudyViewModel, legacy: LegacyScreens) {
    val navController = rememberNavController()
    var tab by rememberSaveable {
        mutableStateOf(
            HomeTab.entries.firstOrNull { it.key == vm.lastTab }
                ?: if (vm.lastTab in practiceKeys) HomeTab.PRACTICE else HomeTab.TODAY,
        )
    }
    var practice by rememberSaveable { mutableStateOf(practiceKeys[vm.lastTab] ?: CoachKind.WRITING) }
    fun showPractice(kind: CoachKind) {
        tab = HomeTab.PRACTICE
        practice = kind
        vm.lastTab = practiceKeys.entries.first { it.value == kind }.key
    }
    val snackbar = remember { SnackbarHostState() }
    // One capture node serves all root tabs; switching content does not need a new blur state.
    val hazeState = remember { HazeState() }
    fun push(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }
    fun pop() {
        navController.popBackStack()
    }
    fun routeFor(entry: CoachPractice): String {
        val kind = CoachKind.valueOf(entry.kind)
        return if (kind == CoachKind.READING && ExamCodec.metadata(entry) == null) "custom/READING" else "work/${kind.name}"
    }
    fun open(entry: CoachPractice) {
        vm.coach.open(CoachKind.valueOf(entry.kind), entry) { push(routeFor(it)) }
    }
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
    LaunchedEffect(vm.coach.importedReadingId) {
        vm.coach.importedReadingId?.let {
            vm.coach.consumeReadingImport(it)
            push("custom/READING")
        }
    }
    LaunchedEffect(vm.coach.shareStatus) { if (vm.coach.shareStatus.isNotBlank()) snackbar.showSnackbar(vm.coach.shareStatus) }

    // A short fade keeps pushes calm; the library default is slower than the rest of the app's motion.
    NavHost(
        navController,
        startDestination = HOME,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) },
    ) {
        composable(HOME) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    val reviewsWaiting = remember(vm.coach.history) { WeaknessReview.records(vm.coach.history).isNotEmpty() }
                    HomeTabBar(tab, hazeState, reviewsWaiting) { item ->
                        if (item == HomeTab.PRACTICE) {
                            showPractice(practice)
                        } else {
                            tab = item
                            vm.lastTab = item.key
                        }
                    }
                },
            ) { padding ->
                val header: @Composable () -> Unit = { PracticeHeader(practice, ::showPractice) }
                Box(Modifier.fillMaxSize().haze(hazeState)) {
                    when (tab) {
                        HomeTab.TODAY -> TodayHome(vm, padding, ::open, ::start, ::push)
                        HomeTab.PRACTICE -> when (practice) {
                            CoachKind.WRITING -> TaskHome(
                                vm,
                                CoachKind.WRITING,
                                padding,
                                ::open,
                                { start(it, CoachKind.WRITING) },
                                onCustom = { vm.coach.startCustom(CoachKind.WRITING, "自拟题目", "") { push("work/WRITING") } },
                                header = header,
                            )
                            CoachKind.TRANSLATION -> TaskHome(
                                vm,
                                CoachKind.TRANSLATION,
                                padding,
                                ::open,
                                { start(it, CoachKind.TRANSLATION) },
                                onCustom = { vm.coach.startCustom(CoachKind.TRANSLATION, "自选段落", "") { push("work/TRANSLATION") } },
                                header = header,
                            )
                            CoachKind.READING -> ReadingHome(
                                vm,
                                padding,
                                ::open,
                                { start(it, CoachKind.READING) },
                                onShared = { id -> vm.examPapers.firstOrNull { it.stableId == id }?.let { start(it, CoachKind.READING) } },
                                onCustom = { vm.coach.open(CoachKind.READING) { push("custom/READING") } },
                                header = header,
                            )
                        }
                        HomeTab.REVIEW -> ReviewHome(vm, padding, ::push)
                        HomeTab.ME -> MeHome(vm, padding, ::push)
                    }
                }
            }
        }
        composable("work/WRITING") {
            WritingWorkspace(vm, ::pop)
        }
        composable("work/TRANSLATION") {
            TranslationWorkspace(vm, ::pop)
        }
        composable("work/READING") {
            ReadingWorkspace(vm, ::pop)
        }
        composable("custom/READING") {
            SimpleScreen(
                title = routeTitles.getValue("custom/READING"),
                onBack = ::pop,
                actions = { vm.coach.entries[CoachKind.READING]?.let { WorkspaceMenu(vm, it) } },
            ) { scroll -> CoachScreen(vm, CoachKind.READING, scroll) }
        }
        composable("me/style") {
            SimpleScreen(routeTitles.getValue("me/style"), ::pop) { UiStyleScreen(vm.uiStyle, vm::selectUiStyle) }
        }
        composable("me/connection") {
            SimpleScreen(routeTitles.getValue("me/connection"), ::pop) { ConnectionRoute(vm) }
        }
        composable("me/review") {
            SimpleScreen(routeTitles.getValue("me/review"), ::pop) {
                WeaknessReviewCard(vm.coach.history, vm.today, !vm.coach.working, standalone = true, onReview = ::open)
            }
        }
        composable("me/trends") {
            SimpleScreen(routeTitles.getValue("me/trends"), ::pop) { LearningTrendsScreen(vm) }
        }
        composable("me/records") {
            SimpleScreen(routeTitles.getValue("me/records"), ::pop) { ExamHistory(vm, onOpen = ::open) }
        }
        composable("me/backup") {
            SimpleScreen(routeTitles.getValue("me/backup"), ::pop) { BackupCard(vm, standalone = true) }
        }
        composable("me/sync") {
            SimpleScreen(routeTitles.getValue("me/sync"), ::pop) { DeviceSyncScreen(vm) }
        }
        composable("me/exams") {
            SimpleScreen(routeTitles.getValue("me/exams"), ::pop) { ExamManagementRoute(vm) }
        }
        composable("me/legacy") {
            SimpleScreen(routeTitles.getValue("me/legacy"), ::pop) {
                legacy.today(
                    { push("me/words") },
                    { vm.coach.open(CoachKind.READING) { push("custom/READING") } },
                    {
                        showPractice(CoachKind.WRITING)
                        pop()
                    },
                    ::open,
                )
            }
        }
        composable("me/words") {
            SimpleScreen(routeTitles.getValue("me/words"), ::pop) { legacy.words() }
        }
        composable("me/essays") {
            SimpleScreen(routeTitles.getValue("me/essays"), ::pop) { LegacyEssays(vm) }
        }
    }
}

/**
 * Floating tab bar: a frosted capsule hovering above the content, which keeps scrolling underneath it, with the
 * current tab on a soft inner pill and its icon's accent stroke in vermilion. A vermilion dot on 复盘 says
 * corrections are waiting. Each item is a selectable tab so screen readers and the UI scripts see which one is current.
 */
@Composable
private fun HomeTabBar(current: HomeTab, hazeState: HazeState, reviewsWaiting: Boolean, onSelect: (HomeTab) -> Unit) {
    val capsule = RoundedCornerShape(percent = 50)
    val brand = LocalMarks.current.brand
    GlassSurface(
        state = hazeState,
        shape = capsule,
        modifier = Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 8.dp).fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(6.dp)) {
            HomeTab.entries.forEach { item ->
                val selected = item == current
                val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                val accent = if (selected) brand else tint
                val icon = remember(item, tint, accent) {
                    when (item) {
                        HomeTab.TODAY -> BrandIcons.today(tint, accent)
                        HomeTab.PRACTICE -> BrandIcons.practice(tint, accent)
                        HomeTab.REVIEW -> BrandIcons.review(tint, accent)
                        HomeTab.ME -> BrandIcons.me(tint)
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(capsule)
                        .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(icon, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp))
                        if (item == HomeTab.REVIEW && reviewsWaiting) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp)
                                    .size(8.dp).background(brand, RoundedCornerShape(50)),
                            )
                        }
                    }
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = tint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** Screens that still live in MainActivity; passed in so their code stays where it is. */
class LegacyScreens(
    val words: @Composable () -> Unit,
    val today: @Composable (onWords: () -> Unit, onReading: () -> Unit, onWriting: () -> Unit, onReview: (CoachPractice) -> Unit) -> Unit,
)

@Composable
fun SimpleScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(androidx.compose.foundation.ScrollState) -> Unit,
) {
    val scroll = rememberScrollState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(title, onBack = onBack, showDivider = scroll.canScrollBackward, actions = actions) },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(scroll)
                .padding(horizontal = PageGutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
        if (expanded) {
            SelectionContainer {
                Text("原文：\n${saved.content}\n\n批改：\n${saved.feedback}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Binds [ExamManagement] to its ViewModel; the library comes from the activity's [StudyViewModel]. */
@Composable
fun ExamManagementRoute(vm: StudyViewModel) {
    val model = viewModel { ExamLibraryViewModel(vm.services.exams) }
    val state by model.state.collectAsStateWithLifecycle()
    ExamManagement(state, model::onIntent)
}

/** Import and export of the personal question bank. */
@Composable
fun ExamManagement(state: ExamLibraryState, onIntent: (ExamLibraryIntent) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> onIntent(ExamLibraryIntent.Preview(uri)) } }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { it?.let { uri -> onIntent(ExamLibraryIntent.Export(uri)) } }
    val catalogCount = remember { ExamCatalog.papers().size }
    val importedCount = state.papers.count { !it.sample }
    val sampleCount = state.papers.count { it.sample }
    Group(
        header = "当前个人题库",
        footer = listOfNotNull(
            "真题目录共 $catalogCount 套（2023.06–2025.12）",
            if (sampleCount > 0) "原创样本 $sampleCount 套" else null,
        ).joinToString(" · "),
    ) {
        ValueRow("已导入", "$importedCount / $catalogCount 套", if (importedCount > 0) Tone.Good else Tone.Better)
    }
    Group(footer = "导入你有权使用的结构化 JSON 资料。PDF / Word 请先在电脑本地转换并校对；扫描件暂不自动识别。") {
        ActionRow("导入 JSON 资料", { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.busy)
        GroupDivider()
        ActionRow("导出个人题库", { export.launch("cet6-personal-exams.json") }, enabled = !state.busy && state.papers.isNotEmpty())
    }
    if (state.status.isNotBlank()) GroupFooter(state.status, color = MaterialTheme.colorScheme.primary)
    if (state.preview.isNotEmpty()) {
        Group(header = "导入预览（${state.preview.size} 套）") {
            state.preview.forEachIndexed { index, p ->
                Column(Modifier.fillMaxWidth().padding(horizontal = CardPadding, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${p.label} · ${p.status}", style = MaterialTheme.typography.bodyLarge)
                    Note(p.issues().joinToString("；").ifBlank { "结构完整，使用权与套卷一致性由导入者确认" })
                }
                GroupDivider()
            }
            ActionRow("确认保存个人题库", { onIntent(ExamLibraryIntent.ConfirmImport) }, enabled = !state.busy)
        }
    }
    Group(footer = "「备份与恢复」可一次导出学习记录与个人题库。此处的题库 JSON 仅包含资料。") {
        ActionRow("查看套次目录来源", { runCatching { uriHandler.openUri(ExamCatalog.SET_SOURCE) } })
    }
}
