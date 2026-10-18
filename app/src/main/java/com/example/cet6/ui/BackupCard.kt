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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackupCard(vm: StudyViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val restore = rememberLauncherForActivityResult(StudyDocumentPicker()) { uri ->
        if (uri != null) vm.previewBackup(uri)
    }
    OutlinedButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    ) {
        Icon(Icons.Outlined.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "收起数据备份" else "数据备份与恢复")
    }
    if (expanded) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("导出全部词汇、学习日志、复习计划、每日目标、旧版记录，以及阅读、作文、翻译训练、草稿和错因。文件不包含登录凭证。")
                Text("备份是包含学习内容的明文 JSON，请选择自己信任的保存位置。恢复前先预览，重复恢复不会重复增加记录。")
                OutlinedButton(onClick = { export.launch("CET6Study-${vm.today}.json") }, enabled = !vm.backupBusy) { Text("导出备份") }
                OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = !vm.backupBusy) { Text("恢复预览") }
                if (vm.backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.backupStatus.isNotBlank()) Text(vm.backupStatus)
            }
        }
    }
    val preview = vm.backupPreview
    if (preview != null) {
        var adoptPlans by remember(preview) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = vm::cancelBackupRestore, title = { Text("合并恢复预览") }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("备份时间：${Instant.ofEpochMilli(preview.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                Text("${preview.words.size} 个词、${preview.reviews.size} 条学习日志、${preview.schedules.size} 条 FSRS 状态、${preview.plans.size} 个每日目标、${preview.reading.size} 条旧阅读、${preview.essays.size} 篇旧作文、${preview.coach.size} 条 G3 训练。")
                Text("保留现有词义与来源，按可比较的学习时间恢复更新的进度；无法比较的旧版进度保留本机。所有已有历史保留，冲突日志将拒绝整次恢复。")
                Row {
                    Checkbox(checked = adoptPlans, onCheckedChange = { adoptPlans = it }, enabled = !vm.backupBusy)
                    Text("采用备份中的每日目标（会替换同日期本机目标）", Modifier.padding(top = 10.dp))
                }
                if (vm.backupStatus.isNotBlank()) Text(vm.backupStatus)
            }
        }, confirmButton = {
            TextButton(onClick = { vm.restoreBackup(adoptPlans) }, enabled = !vm.backupBusy && !vm.savingStudy) { Text("确认合并恢复") }
        }, dismissButton = {
            TextButton(onClick = vm::cancelBackupRestore, enabled = !vm.backupBusy) { Text("取消") }
        })
    }
}
