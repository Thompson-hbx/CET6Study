package com.example.cet6.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cet6.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Binds [BackupPanel] to its ViewModel; the services come from the activity's [StudyViewModel]. */
@Composable
fun BackupCard(vm: StudyViewModel, standalone: Boolean = false) {
    val model = viewModel { BackupViewModel(vm.services) }
    val state by model.state.collectAsStateWithLifecycle()
    BackupPanel(state, model::onIntent, standalone)
}

@Composable
fun BackupPanel(state: BackupUiState, onIntent: (BackupIntent) -> Unit, standalone: Boolean = false) {
    val backup = state.backup
    var expanded by rememberSaveable(standalone) { mutableStateOf(standalone) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onIntent(BackupIntent.Export(uri))
    }
    val restore = rememberLauncherForActivityResult(StudyDocumentPicker()) { uri ->
        if (uri != null) onIntent(BackupIntent.Preview(uri))
    }
    if (!standalone) {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small
        ) {
            Icon(Icons.Outlined.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "收起数据备份" else "数据备份与恢复")
        }
    }
    if (expanded) {
        Group(header = "本机数据") {
            ValueRow("词库", "${state.wordCount} 词")
            GroupDivider()
            ValueRow("练习记录", "${state.practiceCount} 条")
            GroupDivider()
            ValueRow("个人题库", "${state.paperCount} 套")
        }
        Group(footer = "完整备份包含学习记录、草稿、批改、对话及个人题库，自动分段并校验完整性。不包含登录凭证、API Key 或原卷 PDF；原卷请单独保留。备份 ZIP 内为明文，请选择可信位置。") {
            ActionRow("导出完整备份", { export.launch("CET6Study-complete-${state.today}.zip") }, enabled = state.canExport)
        }
        Group(footer = "恢复支持完整 ZIP 和旧学习 JSON，先预览再合并；重复恢复不增加重复记录。") {
            ActionRow("恢复预览", { restore.launch(arrayOf("*/*")) }, enabled = state.canPreview)
        }
        if (backup.restorePending) {
            Group(header = "未完成的恢复",
                footer = "已保留本机记录与恢复内容；重试会继续合并，不重复增加历史。若仍失败，请保留原备份和本机数据后联系维护者。") {
                ActionRow("继续上次恢复", { onIntent(BackupIntent.Resume) }, enabled = state.canMerge)
            }
        }
        if (backup.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (backup.status.isNotBlank()) GroupFooter(backup.status, color = MaterialTheme.colorScheme.primary)
    }
    val preview = backup.preview
    if (preview != null) {
        var adoptPlans by remember(preview) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { onIntent(BackupIntent.CancelRestore) }, title = { Text("合并恢复预览") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("备份时间：${Instant.ofEpochMilli(preview.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                Text("${preview.words.size} 个词、${preview.reviews.size} 条学习日志、${preview.schedules.size} 条 FSRS 状态、${preview.plans.size} 个每日目标、${preview.reading.size} 条旧阅读、${preview.essays.size} 篇旧作文、${preview.coach.size} 条训练、${preview.turns.size} 条讲解与对话。")
                Text(backup.paperPreview?.let { "包含 ${it.size} 套个人题库；按版本合并，冲突将拒绝恢复。" }
                    ?: "此文件仅含学习记录，个人题库和原卷需要另行恢复。")
                Text("确认后先在本机保存恢复任务，再合并学习记录与题库；若中断，重启会继续，也可在此重试。恢复需要额外的本机存储空间，请仍保留原备份。")
                Text("保留现有词义与来源，按可比较的学习时间恢复更新的进度；无法比较的旧版进度保留本机。所有已有历史保留，冲突日志将拒绝整次恢复。")
                Row {
                    Checkbox(checked = adoptPlans, onCheckedChange = { adoptPlans = it }, enabled = !backup.busy)
                    Text("采用备份中的每日目标（会替换同日期本机目标）", Modifier.padding(top = 10.dp))
                }
                if (backup.status.isNotBlank()) Text(backup.status)
            }
        }, confirmButton = {
            TextButton(onClick = { onIntent(BackupIntent.Restore(adoptPlans)) }, enabled = state.canMerge) { Text("确认合并恢复") }
        }, dismissButton = {
            TextButton(onClick = { onIntent(BackupIntent.CancelRestore) }, enabled = !backup.busy) { Text("取消") }
        })
    }
}
