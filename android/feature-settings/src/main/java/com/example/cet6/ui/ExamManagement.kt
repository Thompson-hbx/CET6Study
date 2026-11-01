package com.example.cet6.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cet6.data.*
import com.example.cet6.domain.ExamLibraryState
import com.example.cet6.ui.components.*

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
